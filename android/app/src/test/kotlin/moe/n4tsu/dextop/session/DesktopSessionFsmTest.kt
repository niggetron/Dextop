package moe.n4tsu.dextop.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSessionFsmTest {
    private val config = SessionConfig(1920, 1200, 240)

    @Test
    fun full_start_and_stop_lifecycle() {
        val fsm = DesktopSessionFsm()
        assertTrue(fsm.state.value is FsmState.Idle)
        fsm.send(FsmEvent.StartRequested(config))
        assertTrue(fsm.state.value is FsmState.Starting)
        fsm.send(FsmEvent.SurfaceReady)
        assertTrue(fsm.state.value is FsmState.Preparing)
        // The reducer does not itself emit a display-create effect in this
        // additive skeleton; inject the attachment with the current generation.
        val effects = fsm.send(FsmEvent.DisplayAttached(7, fsm.generation))
        assertTrue(fsm.state.value is FsmState.Running)
        assertTrue(effects.any { it is FsmEffect.MarkRunning })
        fsm.send(FsmEvent.StopRequested)
        assertTrue(fsm.state.value is FsmState.Stopping)
        fsm.send(FsmEvent.RestoreCompleted(fsm.generation))
        assertTrue(fsm.state.value is FsmState.Idle)
    }

    @Test
    fun stale_display_attachment_is_dropped() {
        val fsm = DesktopSessionFsm()
        fsm.send(FsmEvent.StartRequested(config))
        fsm.send(FsmEvent.SurfaceReady)
        // Force into CreatingDisplay by attaching with the live generation.
        fsm.send(FsmEvent.DisplayAttached(5, fsm.generation))
        val stateBefore = fsm.state.value
        val effects = fsm.send(FsmEvent.DisplayAttached(9, fsm.generation - 1))
        assertEquals(stateBefore, fsm.state.value)
        assertTrue(effects.none { it is FsmEffect.ConfigureDisplay })
    }

    @Test
    fun pause_resume_and_lock_screen() {
        val fsm = DesktopSessionFsm()
        fsm.send(FsmEvent.StartRequested(config))
        fsm.send(FsmEvent.DisplayAttached(1, fsm.generation))
        fsm.send(FsmEvent.PauseRequested)
        assertTrue(fsm.state.value is FsmState.PausedForAndroid)
        fsm.send(FsmEvent.ResumeRequested)
        assertTrue(fsm.state.value is FsmState.Running)
        fsm.send(FsmEvent.ScreenOff)
        assertTrue(fsm.state.value is FsmState.SuspendedForLockScreen)
        fsm.send(FsmEvent.UserPresent)
        assertTrue(fsm.state.value is FsmState.Running)
    }

    @Test
    fun second_start_ignored_while_active() {
        val fsm = DesktopSessionFsm()
        fsm.send(FsmEvent.StartRequested(config))
        val generation = fsm.generation
        fsm.send(FsmEvent.StartRequested(config))
        assertEquals(generation, fsm.generation)
    }
}
