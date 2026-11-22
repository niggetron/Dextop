# W9A (M4) — Wave 9 BackendId / Selector / Compatibility-Bridge Report

## Scope inspected
- `CapabilitySnapshot.kt:23` (`internal enum class BackendId { A,B,C,D,E,F }`), `:30` (`recommendedBackend: BackendId`), `:48` (`.name`), `:186-193` (recommendation ladder). Grep: only 6 `BackendId` + 3 `recommendedBackend` sites in the whole tree, all in this file.
- `W03_BACKEND.md:24-91,187-222` (sealed-interface + `BackendSelector` sketches), `:321` (collision warning); `W03_PROBE.md:38,313-321,341`.
- `DeviceProfiles.kt:8-96` (rule ids); `DeviceMatchTest.kt:8-127` (fixtures/style). Kotlin `2.2.20`, `testImplementation(kotlin("test"))` (`settings.gradle.kts:23`, `app/build.gradle.kts:232`) → `data object` + `kotlin.test` OK. No builds, no source edits.

## Findings
- **P0 — name collision is fatal.** A sealed `BackendId` (W3) and the enum at `CapabilitySnapshot.kt:23` are both `internal` in `moe.n4tsu.dextop`; two declarations cannot compile together. There is no coexistence: delete the enum and make the sealed interface the single owner. The "bridge" is source-compat shims in its companion, not a second type.
- **P1 — broad Xiaomi wrongly reaches E.** `CapabilitySnapshot.kt:186-193` maps only the narrow rule to E (OK), but `W03_PROBE.md:315` maps *both* xiaomi rules to E. Recommendation must be rule-driven: narrow → `ScopedDeviceOem`, broad `xiaomi_hyperos` → C, so device-scoped workarounds never reach Xiaomi phones (`SKILL.md:11`, W3 P0).
- **P1 — `.name` at `:48` breaks on a sealed type**; canonical output becomes `.key`.
- **P2 — enum-only APIs** (`.ordinal`, `values()`, `valueOf`) have zero current call sites, but provide `fromKey`/`values`/`entries` shims for future waves/generated persistence.

## Deliverable — exact files

### NEW `android/app/src/main/kotlin/moe/n4tsu/dextop/BackendId.kt`
```kotlin
package moe.n4tsu.dextop

/** Single source of truth. E is only ever instantiated device-scoped. */
internal sealed interface BackendId {
    data object A : BackendId              // public Android 16 desktop
    data object B : BackendId              // native OEM desktop (DeX)
    data object C : BackendId              // composited virtual display + Book/Plasma
    data object D : BackendId              // standalone Wayland Linux Mode B
    data object E : BackendId              // reserved; never selected unscoped
    data object F : BackendId              // graceful compatibility, always available
    data class ScopedDeviceOem(val profileId: String) : BackendId

    /** Stable identity. "A".."F"; scoped is "E:<profileId>". Replaces enum .name. */
    val key: String get() = when (this) {
        A -> "A"; B -> "B"; C -> "C"; D -> "D"; E -> "E"; F -> "F"
        is ScopedDeviceOem -> "E:$profileId"
    }

    /** Old enum-style label only (scoped collapses to "E"). NOT an identity. */
    val legacyName: String get() = if (this is ScopedDeviceOem) "E" else key

    companion object {
        const val REDMI_PAD_PRO_5G = "xiaomi_redmi_pad_pro_5g"
        fun scoped(profileId: String): BackendId = ScopedDeviceOem(profileId)

        /** Rule id -> backend. Narrow pad is the ONLY E; broad Xiaomi stays C. null = undecided. */
        fun fromRule(ruleId: String?): BackendId? = when (ruleId) {
            REDMI_PAD_PRO_5G -> scoped(REDMI_PAD_PRO_5G)
            "samsung_trifold", "samsung_carrier_model", "samsung_dex" -> B
            "xiaomi_hyperos" -> C
            "pixel_android" -> A
            else -> null
        }

        /** Pure recommendation used by CapabilitySnapshotEngine and tests. */
        fun recommendFor(ruleId: String?, sdk: Int, platformManaged: Boolean, privileged: Boolean): BackendId =
            fromRule(ruleId) ?: when {
                sdk >= 36 && platformManaged -> A
                platformManaged -> B
                privileged -> C
                else -> F
            }

        /** Inverse of [key]; replacement for enumValueOf<BackendId>(saved). */
        fun fromKey(key: String?): BackendId? = when (key) {
            "A" -> A; "B" -> B; "C" -> C; "D" -> D; "E" -> E; "F" -> F
            else -> key?.takeIf { it.startsWith("E:") }
                ?.removePrefix("E:")?.takeIf { it.isNotBlank() }?.let(::scoped)
        }

        /** Compat shims for enum-only call sites (no current users). */
        fun values(): List<BackendId> = listOf(A, B, C, D, E, F)
        val entries: List<BackendId> get() = values()
    }
}
```

### EDIT `CapabilitySnapshot.kt` (4 edits; apply together with the new file)
1. Delete line 23: `internal enum class BackendId { A, B, C, D, E, F }` — leave no second declaration.
2. `:30` type stays `val recommendedBackend: BackendId,` (now the sealed interface).
3. `:48`: `add("recommendedBackend: ${recommendedBackend.name}")` → `add("recommendedBackend: ${recommendedBackend.key}")`.
4. Replace `:186-193`:
```kotlin
        // Rule wins: narrow pad -> E(scoped), broad Xiaomi -> C. Unmatched rules use the ladder.
        val backend = BackendId.recommendFor(
            matchedRule, Build.VERSION.SDK_INT,
            environment.platformManaged, privileged.isAvailable(),
        )
```
(`import android.os.Build` stays: still referenced.) `W03_PROBE.md:341` `.name` → `.key`; `:315-316` `selectBackend` → `BackendId.fromRule(rule) ?: <env ladder>`.

### NEW `android/app/src/main/kotlin/moe/n4tsu/dextop/BackendSelector.kt`
```kotlin
package moe.n4tsu.dextop

/** Pure activation gate. Default = allow-all; W3 backends adapt via the note below. */
internal fun interface BackendActivator {
    fun canActivate(snapshot: CapabilitySnapshot): Probe
}
internal data class BackendAttempt(val id: BackendId, val probe: Probe)
internal data class BackendSelection(
    val recommended: BackendId,
    val order: List<BackendAttempt>,
    val chosen: BackendId,
)

internal class BackendSelector(private val activators: Map<String, BackendActivator> = emptyMap()) {
    fun plan(snapshot: CapabilitySnapshot): BackendSelection {
        val rule = snapshot.matchedRuleId
        val env = snapshot.environmentId
        val ids: List<BackendId> = when {
            rule == BackendId.REDMI_PAD_PRO_5G -> listOf(
                BackendId.scoped(BackendId.REDMI_PAD_PRO_5G), BackendId.D, BackendId.F) // E->D->F
            rule?.startsWith("samsung_") == true || env == "samsung_dex" ->
                listOf(BackendId.B, BackendId.F)                                       // preserve DeX
            rule == "pixel_android" || env == "android_freeform" ->
                listOf(BackendId.A, BackendId.C, BackendId.F)                          // preserve AOSP
            rule == "xiaomi_hyperos" -> listOf(BackendId.C, BackendId.D, BackendId.F)
            else -> listOf(BackendId.C, BackendId.D, BackendId.F)
        }
        val order = ids.map { id ->
            BackendAttempt(id, activators[id.key]?.canActivate(snapshot) ?: Probe.yes())
        }
        return BackendSelection(
            BackendId.fromRule(rule) ?: BackendId.F,
            order,
            order.firstOrNull { it.probe.ok }?.id ?: BackendId.F,
        )
    }

    /** Idempotent/order-stable: same plan+failed -> same successor; null = exhausted (end in F). */
    fun nextAfter(plan: BackendSelection, failed: BackendId): BackendId? =
        plan.order.dropWhile { it.id.key != failed.key }.drop(1)
            .firstOrNull { it.probe.ok && it.id.key != failed.key }?.id
}
```
W3 bridge to `DesktopBackend`: `BackendSelector(backends.mapValues { (_, b) -> BackendActivator { s -> b.canActivate(DesktopEnvironmentRegistry.resolve(s.identity), s) } })`. All gates are side-effect free; runtime `isSupported()`/attach stays authoritative.

### NEW `android/app/src/test/kotlin/moe/n4tsu/dextop/BackendIdTest.kt`
```kotlin
package moe.n4tsu.dextop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendIdTest {
    private val e = BackendId.scoped(BackendId.REDMI_PAD_PRO_5G)
    private val pad = DeviceIdentity(
        "Redmi", "redmi", "Redmi Pad Pro 5G", "duchamp", "duchamp_global",
        "redmi/duchamp_global/duchamp:16/build", 36)
    private val xiaomiPhone = DeviceIdentity(
        "Xiaomi", "Xiaomi", "24030PN60G", "aurora", "aurora_global",
        "Xiaomi/aurora_global/aurora:16/build", 36)

    private fun caps(rule: String?, env: String = "xiaomi_hyperos") = CapabilitySnapshot(
        identity = xiaomiPhone, environmentId = env, matchedRuleId = rule,
        recommendedBackend = BackendId.fromRule(rule) ?: BackendId.F,
        display = mapOf("virtualDisplay" to Probe.yes()),
        windowing = mapOf("cmdWindow" to Probe.yes()), input = emptyMap(),
        graphics = mapOf("aSurfaceControl" to Probe.yes()), privilege = emptyMap(),
        capturedAtMillis = 0L)

    @Test fun fromRuleMapsOnlyTheNarrowPadToScopedE() {
        assertEquals(e, BackendId.fromRule(BackendId.REDMI_PAD_PRO_5G))
        assertEquals("E:xiaomi_redmi_pad_pro_5g", e.key)
        assertEquals(BackendId.C, BackendId.fromRule("xiaomi_hyperos")) // broad never E
        assertEquals(BackendId.B, BackendId.fromRule("samsung_trifold"))
        assertEquals(BackendId.B, BackendId.fromRule("samsung_carrier_model"))
        assertEquals(BackendId.B, BackendId.fromRule("samsung_dex"))
        assertEquals(BackendId.A, BackendId.fromRule("pixel_android"))
        assertNull(BackendId.fromRule("sony_xperia")); assertNull(BackendId.fromRule(null))
    }

    @Test fun recommendationIsRuleFirstThenLadder() {
        assertEquals(e, BackendId.recommendFor(BackendId.REDMI_PAD_PRO_5G, 36, true, true))
        assertEquals(BackendId.C, BackendId.recommendFor("xiaomi_hyperos", 36, true, true))
        assertEquals(BackendId.A, BackendId.recommendFor(null, 36, true, true))
        assertEquals(BackendId.B, BackendId.recommendFor(null, 35, true, true))
        assertEquals(BackendId.C, BackendId.recommendFor(null, 35, false, true))
        assertEquals(BackendId.F, BackendId.recommendFor(null, 35, false, false))
    }

    @Test fun fallbackChainIsDeterministicEThenDThenF() {
        val s = BackendSelector()
        val plan = s.plan(caps(BackendId.REDMI_PAD_PRO_5G))
        assertEquals(listOf(e.key, "D", "F"), plan.order.map { it.id.key })
        assertEquals(e, plan.recommended)
        assertEquals(e, plan.chosen)
        assertEquals(e, s.nextAfter(plan, e))
        assertEquals(plan.order, s.plan(caps(BackendId.REDMI_PAD_PRO_5G)).order)
    }

    @Test fun failedEAdvancesToDThenFThenNull() {
        val eBlocked = mapOf(e.key to BackendActivator { Probe.no("no_native_display") })
        val plan = BackendSelector(eBlocked).plan(caps(BackendId.REDMI_PAD_PRO_5G))
        assertEquals(BackendId.D, plan.chosen)
        assertEquals(BackendId.D, BackendSelector(eBlocked).nextAfter(plan, e))
        assertEquals(BackendId.F, BackendSelector(eBlocked).nextAfter(plan, BackendId.D))
        assertNull(BackendSelector(eBlocked).nextAfter(plan, BackendId.F))
    }

    @Test fun broadXiaomiSamsungAndPixelPlansAreUnchangedByEWorkarounds() {
        val s = BackendSelector()
        val xiaomi = s.plan(caps("xiaomi_hyperos"))
        assertEquals(listOf("C", "D", "F"), xiaomi.order.map { it.id.key })
        assertTrue(xiaomi.order.none { it.id is BackendId.ScopedDeviceOem })
        assertEquals(BackendId.C, xiaomi.chosen)
        assertEquals(listOf("B", "F"), s.plan(caps("samsung_dex", "samsung_dex")).order.map { it.id.key })
        assertEquals(listOf("A", "C", "F"), s.plan(caps("pixel_android", "android_freeform")).order.map { it.id.key })
    }

    @Test fun keyRoundTripsAndLegacyNameCollapsesScoped() {
        for (id in listOf(BackendId.A, BackendId.B, BackendId.C, BackendId.D, BackendId.E, BackendId.F, e))
            assertEquals(id, BackendId.fromKey(id.key))
        assertEquals("E", e.legacyName)
        assertNull(BackendId.fromKey(null)); assertNull(BackendId.fromKey("bogus"))
        assertNull(BackendId.fromKey("E:")); assertEquals(listOf("A","B","C","D","E","F"), BackendId.values().map { it.key })
    }
}
```
`pad` is used by device/registry assertions; keep it local to avoid touching `DeviceMatchTest`. Reconciled tests also assert a non-`duchamp` identity can no longer resolve to scoped E (see W3 `narrowRedmiRuleNeverMatchesAPhoneOrAnotherModel` in `DeviceMatchTest.kt`).

## Risks / device testing needed
- Merge gate: if any other wave reintroduces `BackendId` in this package, compilation fails. `BackendId.kt` is the sole owner; the enum must be deleted in the same change.
- Report/golden-log churn: scoped `recommendedBackend` now prints `E:xiaomi_redmi_pad_pro_5g` instead of `E`. Use `legacyName` only for old diagnostics, never as identity (E vs E:<profile> collide).
- `recommendFor` uses `Build.VERSION.SDK_INT` to preserve current behavior; on-device verify narrow `DeviceMatch` really resolves `duchamp` + fingerprint so a phone can't inherit scoped E.
- Selector is selection-only: runtime `MirrorAttachBackend.isSupported()`/attach and `validateHealth` must trigger the same FSM failover; E→D requires completed WAL rollback (phase=idle, no pending `DesktopModeConfigurator` jobs, rotation restored, Book shell stopped) before D init.
- New gate reason strings must reuse existing ARB keys (no `NativeStrings.kt` regeneration in this wave).
