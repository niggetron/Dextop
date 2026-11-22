package moe.n4tsu.dextop.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable configuration captured when a session starts. */
internal data class SessionConfig(
    val width: Int,
    val height: Int,
    val density: Int,
    val secure: Boolean = false,
    val decorations: Boolean = false,
    val forceFreeform: Boolean = false,
)

/** Session context. `generation` is carried by every effect and callback so
 * stale vendor callbacks on HyperOS 3 cannot re-enter a newer session. */
internal data class SessionCtx(
    val config: SessionConfig,
    val generation: Long,
    val displayId: Int = -1,
)

internal sealed interface FsmState {
    data object Idle : FsmState
    data class Starting(val ctx: SessionCtx) : FsmState
    data class Preparing(val ctx: SessionCtx) : FsmState
    data class CreatingDisplay(val ctx: SessionCtx) : FsmState
    data class Running(val ctx: SessionCtx) : FsmState
    data class PausedForAndroid(val ctx: SessionCtx) : FsmState
    data class SuspendedForLockScreen(val ctx: SessionCtx) : FsmState
    data class Stopping(val ctx: SessionCtx) : FsmState
    data class Recovering(val generation: Long) : FsmState
    data class Failed(val ctx: SessionCtx?, val reason: String) : FsmState
}

internal sealed interface FsmEvent {
    data class StartRequested(val config: SessionConfig) : FsmEvent
    data object ServiceConnected : FsmEvent
    data object SurfaceReady : FsmEvent
    data class DisplayAttached(val displayId: Int, val generation: Long) : FsmEvent
    data object PauseRequested : FsmEvent
    data object ResumeRequested : FsmEvent
    data object ScreenOff : FsmEvent
    data object UserPresent : FsmEvent
    data object StopRequested : FsmEvent
    data class RestoreCompleted(val generation: Long) : FsmEvent
    data class Failed(val reason: String) : FsmEvent
}

/** Side effects requested by the reducer; MirrorService executes them. */
internal sealed interface FsmEffect {
    data class OpenJournal(val ctx: SessionCtx) : FsmEffect
    data class CreateDisplay(val ctx: SessionCtx) : FsmEffect
    data class MarkRunning(val ctx: SessionCtx) : FsmEffect
    data object MarkPaused : FsmEffect
    data class ConfigureDisplay(val displayId: Int, val generation: Long) : FsmEffect
    data class AwaitDisplayRemoval(val ctx: SessionCtx) : FsmEffect
    data class RestoreJournal(val generation: Long) : FsmEffect
    data class ClearJournal(val generation: Long) : FsmEffect
    data class CancelPending(val generation: Long) : FsmEffect
    data class Log(val message: String) : FsmEffect
}

internal data class Transition(val state: FsmState, val effects: List<FsmEffect> = emptyList())

private fun generationOf(state: FsmState): Long = when (state) {
    is FsmState.Starting -> state.ctx.generation
    is FsmState.Preparing -> state.ctx.generation
    is FsmState.CreatingDisplay -> state.ctx.generation
    is FsmState.Running -> state.ctx.generation
    is FsmState.PausedForAndroid -> state.ctx.generation
    is FsmState.SuspendedForLockScreen -> state.ctx.generation
    is FsmState.Stopping -> state.ctx.generation
    is FsmState.Recovering -> state.generation
    is FsmState.Failed -> state.ctx?.generation ?: -1L
    FsmState.Idle -> -1L
}

/**
 * Deterministic desktop-session state machine.
 *
 * The reducer is pure Kotlin with no Android dependencies, so it is JVM-testable.
 * Every accepted event carries the current generation; stale vendor callbacks
 * (reordered on HyperOS 3) are dropped instead of mutating a newer session.
 * MirrorService will dispatch the returned effects in a later M2 step; today the
 * FSM is additive and does not change the running service.
 */
internal class DesktopSessionFsm(initial: FsmState = FsmState.Idle) {
    private var nextGeneration = 0L
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<FsmState> = _state.asStateFlow()

    val generation: Long get() = generationOf(_state.value)

    /** Returns the effects the caller must execute, in order. */
    fun send(event: FsmEvent): List<FsmEffect> {
        val transition = reduce(_state.value, event)
        _state.value = transition.state
        return transition.effects
    }

    private fun reduce(state: FsmState, event: FsmEvent): Transition = when (event) {
        is FsmEvent.StartRequested -> {
            if (state !is FsmState.Idle) {
                Transition(state, listOf(FsmEffect.Log("start ignored in $state")))
            } else {
                val ctx = SessionCtx(event.config, ++nextGeneration)
                Transition(
                    FsmState.Starting(ctx),
                    listOf(FsmEffect.OpenJournal(ctx), FsmEffect.Log("session ${ctx.generation} starting")),
                )
            }
        }
        FsmEvent.ServiceConnected -> when (state) {
            is FsmState.Idle -> Transition(FsmState.Recovering(++nextGeneration),
                listOf(FsmEffect.RestoreJournal(nextGeneration)))
            else -> Transition(state)
        }
        FsmEvent.SurfaceReady -> when (state) {
            is FsmState.Starting -> Transition(FsmState.Preparing(state.ctx))
            else -> Transition(state)
        }
        is FsmEvent.DisplayAttached -> when (state) {
            is FsmState.Preparing, is FsmState.CreatingDisplay -> if (event.generation == generationOf(state)) {
                val ctx = when (state) {
                    is FsmState.Preparing -> state.ctx
                    is FsmState.CreatingDisplay -> state.ctx
                    else -> error("unreachable")
                }.copy(displayId = event.displayId)
                Transition(FsmState.Running(ctx), listOf(
                    FsmEffect.ConfigureDisplay(event.displayId, ctx.generation),
                    FsmEffect.MarkRunning(ctx),
                ))
            } else Transition(state, listOf(FsmEffect.Log("stale display attached: ${event.generation}")))
            else -> Transition(state)
        }
        FsmEvent.PauseRequested -> when (state) {
            is FsmState.Running -> Transition(FsmState.PausedForAndroid(state.ctx), listOf(FsmEffect.MarkPaused))
            else -> Transition(state)
        }
        FsmEvent.ResumeRequested -> when (state) {
            is FsmState.PausedForAndroid -> Transition(FsmState.Running(state.ctx))
            else -> Transition(state)
        }
        FsmEvent.ScreenOff -> when (state) {
            is FsmState.Running -> Transition(FsmState.SuspendedForLockScreen(state.ctx))
            else -> Transition(state)
        }
        FsmEvent.UserPresent -> when (state) {
            is FsmState.SuspendedForLockScreen -> Transition(FsmState.Running(state.ctx))
            else -> Transition(state)
        }
        FsmEvent.StopRequested -> when (state) {
            is FsmState.Idle, is FsmState.Failed -> Transition(state)
            else -> {
                val ctx = when (state) {
                    is FsmState.Starting -> state.ctx
                    is FsmState.Preparing -> state.ctx
                    is FsmState.CreatingDisplay -> state.ctx
                    is FsmState.Running -> state.ctx
                    is FsmState.PausedForAndroid -> state.ctx
                    is FsmState.SuspendedForLockScreen -> state.ctx
                    else -> null
                }
                if (ctx == null) Transition(state) else Transition(
                    FsmState.Stopping(ctx),
                    listOf(
                        FsmEffect.CancelPending(ctx.generation),
                        FsmEffect.AwaitDisplayRemoval(ctx),
                        FsmEffect.RestoreJournal(ctx.generation),
                    ),
                )
            }
        }
        is FsmEvent.RestoreCompleted -> when (state) {
            is FsmState.Stopping -> if (event.generation == state.ctx.generation) {
                Transition(FsmState.Idle, listOf(FsmEffect.ClearJournal(event.generation)))
            } else Transition(state)
            is FsmState.Recovering -> Transition(FsmState.Idle)
            else -> Transition(state)
        }
        is FsmEvent.Failed -> Transition(FsmState.Failed(
            ctx = when (state) {
                is FsmState.Starting -> state.ctx
                is FsmState.Preparing -> state.ctx
                is FsmState.CreatingDisplay -> state.ctx
                is FsmState.Running -> state.ctx
                else -> null
            },
            reason = event.reason,
        ), listOf(FsmEffect.Log("session failed: ${event.reason}")))
    }
}
