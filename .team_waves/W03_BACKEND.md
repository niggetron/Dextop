# W3B (M4 look-ahead) — Wave 3 Backend E / Six-Backend Abstraction Report

## Scope inspected
- Rules: `DeviceProfiles.kt:59-96` (narrow `xiaomi_redmi_pad_pro_5g` :69-84, broad `xiaomi_hyperos` :85-87, Samsung/Pixel :9-28,:95); `DesktopEnvironment.kt:22-46` (`DeviceMatch` ANDs all fields), `:48-131` (`DesktopEnvironment` flags/strategy lists), `:162-178` (rule resolve = `firstOrNull`), `:191-207` (`xiaomiHyperos`), `:209-268` (`aospFreeform`/`samsungDex`).
- Backends: `DisplayMirrorBackend.kt:17-49` (attach SPI), `:59-65` (strategy map), `:142-205` (ordered fallback), `:319-387` (untrusted-first recording display), `:414-747` (`VirtualDisplayPlatform` flag ladders). `DesktopModeConfigurator.kt:55-77,84-101,157-190` (BUG-02 fix: IO scope + cancel). `PhoneRotationController.kt:16-46,125-135` (WAL-backed freeze/restore). `plasma/CompositedWindowShell.kt:62-82,995-1030,1276-1286,4007-4013` and `MirrorService.kt:3176-3194` (Book shell flag wiring).
- HyperOS branches in `MirrorService.kt`: `:178-181` profile ids, `:1066-1070` `isXiaomiProfile`+flags, `:4672-4689` touchpad viewport, `:12151-12214` direct Plasma create, `:12490`, `:12679-12695`, `:12703-12715`, `:13315-13322`, `:13360-13368`, `:13429-13434`.
- `XiaomiFeatureFlags.kt:11-41`; `SessionJournal.kt:77-116`; `PrivilegedAccess.kt:11-45`.
- Design refs: `docs/architecture/BACKEND_ABSTRACTION_DESIGN.md:4`, `CROSS_OEM_COMPATIBILITY_ARCHITECTURE.md:4`, `DEVICE_COMPATIBILITY_MATRIX.md:4`, `RISK_REGISTER.md:4` (E→D→F on stripped `ADD_TRUSTED_DISPLAY`), `.agents/skills/dextop-hyperos-oem-backends/SKILL.md:8-15`; sibling `W03_PROBE.md:38,313-321` defines `BackendId` as an enum — must be reconciled below.
- No builds, no source edits.

## Findings
- **P0 — device-scoped workarounds are manufacturer-scoped in practice.** `MirrorService.kt:178-181,1066-1067`: `isXiaomiProfile` is true for both `xiaomi_hyperos` and `xiaomi_redmi_pad_pro_5g`. Therefore every Xiaomi/Redmi/POCO *phone* runs `applyXiaomiDesktopDensity` (:12490,:12686), forced freeform (:12186,:12714), live-resize pinning (:13315,:13360), direct Plasma in laptop mode (:12153) and the touchpad-range retry (:4672). Violates SKILL.md:11 (a phone-tuned workaround must never reach the broad rule) and creates WAL writes that differ from the diagnosed device. Fix: replace every `isXiaomiProfile` guard with `activeBackend.id == BackendId.scoped("xiaomi_redmi_pad_pro_5g")`; broad Xiaomi resolves to C, which uses the environment's settings but none of the pin/force branches.
- **P0 — no failover exists on HyperOS display-creation failure.** `MirrorService.kt:12151-12214`: a rejected direct `VirtualDisplay` ends in `completeStart(failure); stop()`. RISK_REGISTER expects HyperOS to strip `ADD_TRUSTED_DISPLAY`; the correct outcome is E→D (standalone Wayland Mode B, no DP Alt needed on this USB‑2.0 tablet) →F. Fix: `BackendSelector` + FSM `nextAfter()`; E teardown must finish WAL rollback before D initialize (no nested sessions).
- **P1 — `BackendId` shape conflicts with the probe report.** `W03_PROBE.md:38` declares an enum; `BACKEND_ABSTRACTION_DESIGN.md:4` requires a sealed interface with `ScopedDeviceOem(profileId)`. Fix: adopt the sealed interface (code below); `BackendId.E` stays valid, but `recommendedBackend.name` (`W03_PROBE.md:341`) becomes `.key`, and `selectBackend` (`W03_PROBE.md:315`) must return `BackendId.scoped(REDMI_PAD_PRO_5G)` for the narrow rule and `C` for broad `xiaomi_hyperos`.
- **P1 — flags are an unaudited side channel.** `XiaomiFeatureFlags.kt:14-24` reads legacy Flutter prefs with `true` defaults; they are not WAL-recorded and cannot be tied to a session config. Fix: read once into `BackendConfig.flags`, log effective values, never use them as match criteria; they are escape hatches, not capability proof.
- **P1 — teardown ordering can re-apply stale settings.** `DesktopModeConfigurator.kt:84-101` schedules 250/750 ms re-applies; if E's teardown calls `sessionJournal.restoreSystemSettings()` before those jobs are cancelled, a stale `wm` write lands after restore (same class as BUG-02). Fix: E release calls `desktopModeConfigurator.cancelPendingWork()` (:181-190) before any WAL restore.
- **P2 — shell flags are unconstrained at the backend boundary.** `MirrorService.kt:3176-3194` passes `DesktopEnvironment` flags verbatim. E must assert the narrow tuple: `fullscreenApplicationDisplays=true`, `passivePlasmaShellFocus=true`, `maxRateApplicationDisplays=true`, `privateApplicationDisplays=false`, `applicationOverlay=false` (sdk 36; `:3182/:3191`), `book`/`dextop_book` default WM, plus `pinPhysicalMouseToDesktop=true` and `directDeckKeyboardToApp=true` from `DeviceProfiles.kt:80-82`.
- **P2 — dead flags.** `XiaomiFeatureFlags.kt:26-32` `touchMode`/`topologyLayout` have no readers. Wire into E's logged `BackendConfig` or remove; today they give false confidence in diagnostics.
- **P2 — mirror strategy is inherited, not declared.** `DeviceProfiles.kt:78-83` copies `xiaomiHyperos` and never sets `mirrorStrategies`; E therefore uses default `virtual_display,window_manager,surface_control` plus untrusted-first (`DisplayMirrorBackend.kt:336-349`). Keep this order explicitly in E's config so a future profile edit cannot silently move WindowManager mirroring ahead of the recording display.
- **P3 — `PhoneRotationController` has no backend owner.** `MirrorService.kt:13395-13398` rotates on live resize for all profiles; D (Wayland) must not touch phone rotation. E alone owns `force()`/`restore(clear=true)`.

## Deliverable — exact design (sketches only)

### NEW `Backends.kt` — replaces `W03_PROBE.md:38` enum
```kotlin
package moe.n4tsu.dextop

import android.graphics.Rect

/** Six blueprint backends; E is only ever instantiated as a device-scoped variant. */
internal sealed interface BackendId {
    data object A : BackendId              // public Android 16 desktop
    data object B : BackendId              // native OEM desktop (DeX)
    data object C : BackendId              // composited virtual display + Book/Plasma
    data object D : BackendId              // standalone Wayland Linux Mode B
    data object E : BackendId              // reserved: never selected unscoped
    data object F : BackendId              // graceful compatibility (always available)
    data class ScopedDeviceOem(val profileId: String) : BackendId

    val key: String get() = when (this) {
        A -> "A"; B -> "B"; C -> "C"; D -> "D"; E -> "E"; F -> "F"
        is ScopedDeviceOem -> "E:$profileId"
    }

    companion object {
        const val REDMI_PAD_PRO_5G = "xiaomi_redmi_pad_pro_5g"
        fun scoped(profileId: String) = ScopedDeviceOem(profileId)
        /** Rule id -> backend; narrow pad is the ONLY E. Broad Xiaomi stays C. */
        fun fromRule(ruleId: String?): BackendId? = when (ruleId) {
            REDMI_PAD_PRO_5G -> scoped(REDMI_PAD_PRO_5G)
            "samsung_trifold", "samsung_carrier_model", "samsung_dex" -> B
            "xiaomi_hyperos" -> C
            "pixel_android" -> A
            else -> null
        }
    }
}

internal enum class PrivilegeRequirement { NONE, SHELL, SECURE_SETTINGS }
internal enum class BackendHealth { HEALTHY, DEGRADED, FAILED }

internal data class BackendFlags(
    val forceFreeform: Boolean, val pinMetrics: Boolean, val laptopDirectDisplay: Boolean,
    val touchMode: String, val topologyLayout: String,   // logged only; no match logic
)

internal data class BackendConfig(
    val identity: DeviceIdentity, val environment: DesktopEnvironment,
    val caps: CapabilitySnapshot, val width: Int, val height: Int,
    val density: Int, val flags: BackendFlags,
    val surface: android.view.Surface,   // host Surface for the direct display
)

internal data class BackendHandle(
    val id: BackendId,
    val displayId: Int?,
    val release: () -> Unit,     // idempotent; FSM may call twice on rollback
)

internal interface DesktopBackend {
    val id: BackendId
    val requiredPrivilege: PrivilegeRequirement
    /** Pure: reads [caps] only, no reflection, no settings writes, no display creation. */
    fun canActivate(environment: DesktopEnvironment, caps: CapabilitySnapshot): Probe
    fun initialize(config: BackendConfig, journal: SessionJournal): BackendHandle
    fun validateHealth(handle: BackendHandle): BackendHealth
    fun applyWindowBounds(handle: BackendHandle, taskId: Int, bounds: Rect, displayId: Int): Boolean
    fun teardown(handle: BackendHandle, reason: String)
}
```
`W03_PROBE.md` integration edits: `:341` `recommendedBackend.name` → `recommendedBackend.key`; `:315-316` → `BackendId.fromRule(rule)` (so narrow returns the scoped id and broad Xiaomi returns C).

### NEW `BackendRuntime.kt` — thin façade over the existing MirrorService collaborators (no God-object split in this wave)
```kotlin
package moe.n4tsu.dextop

/** One method per existing MirrorService helper; implementations delegate to today's private methods. */
internal interface BackendRuntime {
    val configurator: DesktopModeConfigurator
    val rotation: PhoneRotationController
    val mirror: DisplayMirrorBackend
    fun applyForCurrentDevice()
    fun clearOverlayDisplayRequestTwice(reason: String)                       // :12171
    fun createDirectPhoneDisplay(surface: android.view.Surface, w: Int, h: Int,
                                 beforePublish: (Int) -> Unit): OwnedVirtualDisplay // :12163-12199
    fun startBookShell(displayId: Int, config: BackendConfig)                 // :3186-3197
    fun stopPlasmaShell(restoreWindows: Boolean)                              // :3241
    fun clearInheritedDisplayOverrides(displayId: Int, pin: Boolean)          // :12679
    fun applyXiaomiDesktopDensity(displayId: Int, dpi: Int)                   // :12698
    fun configureDisplay(forceFreeform: Boolean)                              // :12703
    fun verifyDisplayMetrics(displayId: Int, expected: String)                 // WAL check
    fun moveTaskWindowingMode(taskId: Int, mode: Int, displayId: Int): Boolean // bridge at :2675
    fun directDisplayAlive(id: Int): Boolean
    fun configurationStable(id: Int): Boolean
}
```

### NEW `ScopedHyperOsBackend.kt` — Backend E
```kotlin
package moe.n4tsu.dextop

internal class ScopedHyperOsBackend(private val runtime: BackendRuntime) : DesktopBackend {
    override val id = BackendId.scoped(BackendId.REDMI_PAD_PRO_5G)
    override val requiredPrivilege = PrivilegeRequirement.SECURE_SETTINGS

    override fun canActivate(env: DesktopEnvironment, caps: CapabilitySnapshot): Probe = when {
        caps.compatibility.matchedRuleId != BackendId.REDMI_PAD_PRO_5G ->
            Probe.no("nativeBackendNotScoped")
        caps.identity.sdk < 36 -> Probe.no("nativeBackendSdkUnsupported")
        !caps.privilege.binderAlive.ok || !caps.privilege.writeSecureSettings.ok ->
            Probe.no("nativeProbePrivilegeUnavailable")
        !caps.display.virtualDisplayConfig.ok || !caps.windowing.cmdWindow.ok ->
            Probe.no("nativeProbeVirtualDisplayUnavailable")
        "directPlasmaDisplay" !in caps.compatibility.activeWorkarounds ->
            Probe.no("nativeBackendNotScoped")
        else -> Probe.yes("scoped Redmi Pad Pro 5G / HyperOS 3")
    }

    override fun initialize(config: BackendConfig, journal: SessionJournal): BackendHandle {
        val f = config.flags
        runtime.applyForCurrentDevice()                 // WAL: xiaomiHyperos globals (:191-207)
        runtime.clearOverlayDisplayRequestTwice("backend_e_create")
        val owned = runtime.createDirectPhoneDisplay(config.surface, config.width, config.height) { id ->
            if (f.pinMetrics) runtime.applyXiaomiDesktopDensity(id, config.density)   // :12185
            if (f.forceFreeform) runtime.configurator.configureDisplay(id, forceFreeform = true) // :12187
        }
        // :12686 — HyperOS reports phone metrics for new virtual displays; pin explicitly.
        runtime.clearInheritedDisplayOverrides(owned.displayId, pin = f.pinMetrics)
        runtime.configureDisplay(forceFreeform = f.forceFreeform)                    // :12714
        runtime.rotation.force(portrait = config.width < config.height)             // WAL: rememberPhoneRotation
        check(config.environment.displayName.isNotBlank() &&
            config.environment.fullscreenApplicationDisplays &&
            config.environment.passivePlasmaShellFocus &&
            config.environment.maxRateApplicationDisplays &&
            !config.environment.privateApplicationDisplays &&
            !config.environment.plasmaApplicationOverlay &&
            config.environment.defaultWindowManager == "dextop_book" &&
            config.environment.pinPhysicalMouseToDesktop &&
            config.environment.directDeckKeyboardToApp &&
            config.environment.mirrorStrategies.first() == "virtual_display") {
            "Backend E requires the narrow xiaomi_redmi_pad_pro_5g profile"
        }
        runtime.startBookShell(owned.displayId, config)   // BookShell (:3186), maxRate (:1276)
        return BackendHandle(id, owned.displayId) {
            runtime.configurator.cancelPendingWork()      // BUG-02: cancel 250/750 ms re-applies FIRST
            runtime.stopPlasmaShell(restoreWindows = true)
            runtime.rotation.restore(clear = true)        // WAL-backed exact pre-session rotation
            owned.attachment.release()
            journal.restoreSystemSettings()               // reverse-order WAL restore last
        }
    }

    override fun validateHealth(h: BackendHandle) =
        if (runtime.directDisplayAlive(h.displayId ?: -1) &&
            runtime.configurationStable(h.displayId ?: -1)) BackendHealth.HEALTHY
        else BackendHealth.FAILED

    // Existing bridge (:2675-2687): setWindowingMode(FULLSCREEN=5) then setBounds via TaskBridge.
    override fun applyWindowBounds(h: BackendHandle, taskId: Int, bounds: Rect, displayId: Int): Boolean =
        runtime.moveTaskWindowingMode(taskId, 5, displayId)

    override fun teardown(handle: BackendHandle, reason: String) = handle.release()
}
```
E keeps every current device-specific branch gated on `activeBackend.id == id` instead of `isXiaomiProfile`: `MirrorService.kt:4672,12153,12490,12686,12714,13315,13360,13429`. Live resize stays identical (:13300-13434) but pin checks use `f.pinMetrics` from the session config, not a fresh pref read.

### NEW `BackendSelector.kt` — pure, deterministic E→D→F
```kotlin
package moe.n4tsu.dextop

internal data class BackendAttempt(val id: BackendId, val probe: Probe)
internal data class BackendSelection(val recommended: BackendId,
                                     val order: List<BackendAttempt>, val chosen: BackendId)

internal class BackendSelector(private val backends: Map<String, DesktopBackend>) {
    fun plan(caps: CapabilitySnapshot): BackendSelection {
        val rule = caps.compatibility.matchedRuleId
        val envId = caps.compatibility.environmentId
        val ids: List<BackendId> = when {
            rule == BackendId.REDMI_PAD_PRO_5G -> listOf(
                BackendId.scoped(BackendId.REDMI_PAD_PRO_5G), BackendId.D, BackendId.F) // E->D->F
            envId == "samsung_dex" -> listOf(BackendId.B, BackendId.F)                 // preserve DeX
            rule == "pixel_android" || envId == "android_freeform" ->
                listOf(BackendId.A, BackendId.C, BackendId.F)                          // preserve AOSP
            rule == "xiaomi_hyperos" -> listOf(BackendId.C, BackendId.D, BackendId.F)
            else -> listOf(BackendId.C, BackendId.D, BackendId.F)
        }
        val resolvedEnv = DesktopEnvironmentRegistry.resolve(caps.identity) // pure, no I/O
        val order = ids.map { id ->
            BackendAttempt(id, backends.getValue(id.key).canActivate(resolvedEnv, caps))
        }
        return BackendSelection(BackendId.fromRule(rule) ?: BackendId.F, order,
            order.firstOrNull { it.probe.ok }?.id ?: BackendId.F)
    }

    /** FSM calls this after teardown+WAL rollback of [failed]; null ends in F. */
    fun nextAfter(plan: BackendSelection, failed: BackendId): BackendId? =
        plan.order.dropWhile { it.id.key != failed.key }.drop(1)
            .firstOrNull { it.probe.ok && it.id.key != failed.key }?.id
}
```
`plan()` and `nextAfter()` are side-effect free and order-stable; the FSM may call `nextAfter()` repeatedly and always gets the same sequence. F is last and always `Probe.yes()`. D (`LinuxModeBackend`) requires `graphics.ahbZeroCopy.ok && graphics.asurfaceControl.ok` plus `LinuxModeContainers` Mode B availability and performs **no** Settings writes and **no** phone rotation changes; F (`GracefulBackend`) publishes a read-only compat surface + user-visible reason, never requests privileges.

### Integration edits in `MirrorService.kt` (sketches; applied by the implementing agent)
- Replace `isXiaomiProfile` (:1067) with `private val activeBackend: BackendId` owned by `DesktopSessionFsm`; all eight guards above use `activeBackend == BackendId.REDMI_PAD_PRO_5G.scoped()` semantics.
- Replace the direct-display failure branch (:12206-12214) with `fsm.fail(BackendId.E... , reason)` → `selector.nextAfter(...)` → D/F; keep `stop()` only when the plan is exhausted.
- Keep `XiaomiFeatureFlags` reads at session start only, materialized into `BackendFlags`; do not re-read during live resize.
- `SessionJournal` :49-79 + W03_PROBE `CapabilitySnapshotStore.reset()` belong in the single teardown path shared by all three backends.

### NEW tests appended to `android/app/src/test/kotlin/moe/n4tsu/dextop/DeviceMatchTest.kt`
```kotlin
private val redmiPadPro5g = DeviceIdentity(
    "Redmi", "redmi", "Redmi Pad Pro 5G", "duchamp", "duchamp_global",
    "redmi/duchamp_global/duchamp:16/build", 36,
)

@Test fun narrowRedmiRuleNeverMatchesAPhoneOrAnotherModel() {
    assertEquals("xiaomi_redmi_pad_pro_5g",
        DesktopEnvironmentRegistry.resolve(redmiPadPro5g).id)
    // Phone: same OEM, same SDK, different device/fingerprint.
    assertEquals("xiaomi_hyperos", DesktopEnvironmentRegistry.resolve(xiaomiAurora).id)
    assertEquals("xiaomi_hyperos", DesktopEnvironmentRegistry.resolve(
        redmiPadPro5g.copy(fingerprint = "redmi/some_other_pad:16/build")).id)
    assertEquals("xiaomi_hyperos", DesktopEnvironmentRegistry.resolve(
        redmiPadPro5g.copy(sdk = 35)).id)
    assertEquals("xiaomi_hyperos", DesktopEnvironmentRegistry.resolve(
        redmiPadPro5g.copy(manufacturer = "POCO")).id)
}

@Test fun backendIdIsScopedAndBroadXiaomiIsNotE() {
    assertEquals(BackendId.scoped("xiaomi_redmi_pad_pro_5g"),
        BackendId.fromRule("xiaomi_redmi_pad_pro_5g"))
    assertEquals(BackendId.C, BackendId.fromRule("xiaomi_hyperos"))
    assertEquals(BackendId.E, BackendId.E) // reserved id still addressable
    assertEquals("E:xiaomi_redmi_pad_pro_5g",
        BackendId.scoped("xiaomi_redmi_pad_pro_5g").key)
}

private fun caps(identity: DeviceIdentity, rule: String?,
                 cmdWindow: Boolean = true, vd: Boolean = true) = CapabilitySnapshot(
    identity, PlatformCapabilities(identity.sdk, "16", "", "", 0,
        listOf("arm64-v8a"), true, Probe.yes(), Probe.yes(), Probe.yes()),
    DisplayCapabilities(Probe.yes(), Probe.yes(), 1, 120f,
        if (vd) Probe.yes() else Probe.no("x"), Probe.yes(), Probe.yes(),
        Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes()),
    WindowingCapabilities(Probe.yes(), Probe.yes(), Probe.yes(),
        if (cmdWindow) Probe.yes() else Probe.no("x"), Probe.yes(), Probe.yes()),
    InputCapabilities(Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes(),
        Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes()),
    GraphicsCapabilities(emptyList(), Probe.yes(), Probe.yes(), Probe.yes(),
        Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes(), Probe.yes()),
    PrivilegeCapabilities("shell", Probe.yes(), Probe.yes(), Probe.yes(),
        Probe.yes(), Probe.yes()),
    CompatibilityProfile(DesktopEnvironmentRegistry.resolve(identity).id, "",
        rule, BackendId.fromRule(rule) ?: BackendId.F, emptyList(),
        listOf("virtual_display"), emptyList(),
        setOfNotNull("directPlasmaDisplay".takeIf { rule == BackendId.REDMI_PAD_PRO_5G })), 0L)

private fun selector() = BackendSelector(listOf(
    ScopedHyperOsBackend(FakeRuntime()), LinuxModeBackend(FakeRuntime()),
    GracefulBackend()
).associateBy { it.id.key })

@Test fun fallbackIsDeterministicEThenDThenF() {
    val plan = selector().plan(caps(redmiPadPro5g, "xiaomi_redmi_pad_pro_5g"))
    assertEquals(listOf("E:xiaomi_redmi_pad_pro_5g", "D", "F"),
        plan.order.map { it.id.key })
    val s = selector()
    val e = BackendId.scoped("xiaomi_redmi_pad_pro_5g")
    assertEquals(BackendId.D, s.nextAfter(plan, e))   // E's successor, independent of runtime health
    assertEquals(BackendId.F, s.nextAfter(plan, BackendId.D))
    assertEquals(null, s.nextAfter(plan, BackendId.F))
    assertEquals(plan.order, s.plan(caps(redmiPadPro5g, "xiaomi_redmi_pad_pro_5g")).order)
}

@Test fun eFailsClosedWithoutNarrowRuleOrPrivilege() {
    val plan = selector().plan(caps(xiaomiAurora, "xiaomi_hyperos"))
    assertTrue(plan.order.none { it.id is BackendId.ScopedDeviceOem })
    assertEquals(BackendId.C, plan.chosen)
    val blocked = selector().plan(caps(redmiPadPro5g, "xiaomi_redmi_pad_pro_5g",
        cmdWindow = false))
    assertEquals(BackendId.D, blocked.chosen)
}

@Test fun samsungAndPixelPlansAreUnchangedByEWorkarounds() {
    val s = selector()
    val galaxy = DeviceIdentity("samsung", "samsung", "SM-S942Z", "m1q", "m1q",
        "samsung/m1q/build", 37)
    assertEquals(listOf("B", "F"), s.plan(caps(galaxy, "samsung_dex")).order.map { it.id.key })
    assertEquals(listOf("A", "C", "F"), s.plan(caps(pixel, "pixel_android")).order.map { it.id.key })
}
```
(`FakeRuntime` is a no-op `BackendRuntime`; `LinuxModeBackend`/`GracefulBackend` are the D/F stubs whose `canActivate` reads only the snapshot — no container start, no binder, no settings.)

## Risks / device testing needed
- Reflection success ≠ capability (W03_PROBE.md:351). `canActivate` is a *selection* gate only; runtime `MirrorAttachBackend.isSupported()`/attach and `validateHealth` stay authoritative and must trigger the same FSM failover.
- On Redmi Pad Pro 5G (HyperOS 3, Android 16) verify: narrow `DeviceMatch` actually matches `Build.DEVICE=duchamp` + fingerprint; `createVirtualDisplay` public/untrusted flag ladder; `cmd window set-display-windowing-mode 5` success; `setForcedDisplaySize/Density` stick after clear; 120 Hz on application displays; Book overlay token on sdk36; touchpad `AXIS_*` max == desktop bounds.
- E→D rollback must be observably complete before D init: assert WAL `phase=idle`, no pending `DesktopModeConfigurator` jobs, rotation restored, Book shell stopped. Missing any one leaves forced globals or a stale rotation under the Wayland desktop.
- D depends on packaged Turnip/VirGL + `libdextop_core.so` Mode B on arm64 and `/dev/uinput`-free input path; if absent, F must present a truthful reason string (ARB key, not literal — `NativeStrings.kt` is generated).
- USB 2.0 tablet has no DP Alt Mode: never add an external-monitor handoff path to E; D is standalone.
- Reconcile `BackendId` with the other wave's `CapabilitySnapshot.kt` before merging; two `BackendId` declarations in the same package will not compile.
