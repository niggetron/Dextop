# W17C (variant analysis) — Wave 17 Report

## Scope inspected
`CapabilitySnapshot.kt` (208L), `DeviceProfiles.kt` (102L), `XiaomiFeatureFlags.kt` (41L), all Xiaomi/HyperOS branches of `MirrorService.kt` (14.1kL); cross-checked `DesktopEnvironment.kt`, `DesktopModeConfigurator.kt`, `CapabilityProbe.kt`, `PrivilegedAccess.kt`, `DeviceDiagnostics.kt`.

## Findings

### P1 — Manufacturer-wide behavior that is not device-scoped
- `DeviceProfiles.kt:85` broad `xiaomi_hyperos` rule matches *all* Xiaomi/Redmi/POCO and applies the full verified-on-one-device workaround set (`directPlasmaDisplay`, `untrustedMirrorDisplay`, `fullscreenApplicationDisplays`, `passivePlasmaShellFocus`, `configureFreeformWindowing`, forced globals, `cmd_window` strategy) to every Xiaomi phone. Fix: narrow `manufacturers`/`devices`/`fingerprintPrefixes` to tested models (as the tablet rule at `:69-84` does); give untested Xiaomi a minimal AOSP-freeform fallback.
- `MirrorService.kt:1066-1067, 12193-12200, 12725`: `isXiaomiProfile` (both Xiaomi rule ids) drives `configureDisplay(forceFreeform = true)` → global `Settings.Global` writes on every Xiaomi. Fix: gate on `isScopedXiaomiTablet` or a real capability probe, never manufacturer alone; key `XiaomiFeatureFlags` (`:35-39`) by `environment.id` so a phone-side toggle cannot change tablet behavior.
- `MirrorService.kt:1406-1407`: `physicalInputRoutingSupported` disables physical-input routing for *all* Samsung devices by manufacturer string. Fix: scope to known affected models or probe routing capability.
- `MirrorService.kt:406-413`: `restoreSamsungBottomGestureState` writes `sem_bottom_gesture_restored` on every Samsung restore, even sessions that never set it. Fix: record the marker in `SessionJournal` and only restore when it was actually mutated.
- `CapabilitySnapshot.kt:133-135`: `windowing["scopedDeviceProfile"]` returns `supported` for any `xiaomi_*` rule, not the narrow scoped rule. Fix: `matchedRule == "xiaomi_redmi_pad_pro_5g"`.

### P1 — "Side-effect-free" probes that have effects
- `CapabilitySnapshot.kt:83, 85-87`: `Class.forName(name)` initializes the class (runs static initializers), contradicting the doc at `:65-70`. Fix: `Class.forName(name, false, CapabilitySnapshotEngine::class.java.classLoader)` in both helpers.
- `CapabilitySnapshot.kt:180-181`: inside the "read-only" engine, `PrivilegedAccess.isAvailable()` calls `Shizuku.getBinder()` and `DeviceDiagnostics(context).report()` calls `Shizuku.pingBinder()` (`DeviceDiagnostics.kt:74`), `getInstalledPackages`, `queryIntentActivities`, `ExternalDisplayDetector.snapshot()` — service binding + heavy IPC. Fix: use `context.checkSelfPermission(WRITE_SECURE_SETTINGS)` + precomputed provider state for the privilege probe; pass a prebuilt diagnostics map into the engine instead of calling `report()` there.
- `MirrorService.kt:406-413`: setting write in a "restore" probe path (see above).
- `MirrorService.kt:3380, 5751-5805`: `refreshFoldingApiState(force = true)` does synchronous `WindowInfoTracker` binder IPC on the UI looper. Fix: keep the 180 ms throttle and move forced calls to the IO scope.

### P2 — Stale-cache risks
- `CapabilitySnapshot.kt:72-81`: `cached` built once; `invalidate()` is never called anywhere in the tree. Dynamic fields (`displays` `:99`, `maxRefreshRate` `:114-117`, hot-plugged `inputDevices` `:137-141`, privilege state `:180-183`) go stale after display create/attach or mouse/keyboard plug, so recommendations and reports describe a device state that no longer exists. Fix: register `DisplayManager.DisplayListener` + `InputManager.registerInputDeviceListener` in the engine and call `invalidate()` on added/removed/changed; or key the cache by `(display ids, input device ids, privilege state, sdk)`.
- `XiaomiFeatureFlags.kt:12, 15-32`: `SharedPreferences` is process-cached; writes from another process / ADB / backup-restore are invisible for the session. `touchMode` `:27-28` and `topologyLayout` `:31-32` are never consumed anywhere — dead config that can hide stale state. Fix: register `OnSharedPreferenceChangeListener` (or re-read on session start); remove or wire the two unused flags.
- `MirrorService.kt:1057-1064`: `useLegacyPixelMirrorProfile`/`desktopEnvironment` are `lazy` for the whole Service lifetime, so a mid-session privilege-provider or device-identity change leaves the Xiaomi branch selection stale. Fix: recompute on session restart rather than process/scoped lazily.

### P1 — Backend recommendation errors (`CapabilitySnapshot.kt:186-193`)
- `:189-190` `platformManaged && SDK>=36 -> Backend A` recommends the *public Android desktop* for Samsung DeX (`platformManaged = true`, `startupMode = OEM_MANAGED`) on Android 16. Fix: test `environment.startupMode == DesktopStartupMode.OEM_MANAGED -> BackendId.B` before the SDK/A branch.
- `:186, 188` `isScopedXiaomi` only matches the literal `xiaomi_redmi_pad_pro_5g`, so broad Xiaomi HyperOS phones get C/F even though `MirrorService.kt:1066-1067, 12193, 12725` applies the direct-Plasma HyperOS OEM path to all Xiaomi profiles (C = composited virtual display, which is not what runs). Fix: recommend E for every `xiaomi_*` rule that sets `directPlasmaDisplay`, or scope the runtime Xiaomi workarounds to the narrow tablet so the recommendation is truthful. Also replace raw rule-id string equality with `environment.id`/capability flags.
- `:191` Backend C is chosen on `privileged.isAvailable()` alone, ignoring `display["virtualDisplay"]`/`trustedVirtualDisplay` and `windowing["cmdWindow"]`. Fix: require the virtual-display probe to be supported before recommending C, else F.
- `:121-123` `windowing["freeform"]` is reported supported whenever `environment.configureFreeformWindowing` is true (all Xiaomi), even if the hidden API is unavailable; fix: require the `cmdWindow` probe result instead of profile intent.

## Deliverable
Concrete edits listed above; no code written (report-only agent). Highest-leverage patch pair: narrow `DeviceProfiles.kt:85` + rewrite the `backend` `when` at `CapabilitySnapshot.kt:186-193` to key on `DesktopEnvironment` capability flags, and make `classExists`/`methodExists` non-initializing.

## Risks / device testing needed
- Confirm on Redmi Pad Pro 5G (duchamp, API 36) that removing manufacturer-wide Xiaomi freeform writes on phones does not regress app launch; verify backend E still selected.
- Verify Samsung DeX device on Android 16 still works when recommended backend changes A→B (reporting-only vs runtime path).
- Exercise display hot-plug / mouse plug during an active session to confirm snapshot invalidation fix does not thrash.
- Check `FlutterSharedPreferences` key ownership between Dart and Kotlin before adding per-device flag keys.
