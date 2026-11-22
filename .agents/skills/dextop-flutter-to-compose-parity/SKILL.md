---
name: dextop-flutter-to-compose-parity
description: Guarantees 1:1 feature parity while migrating Dextop's 54 Flutter/Dart screens and 166 MethodChannel RPCs to Kotlin/Jetpack Compose M3 Expressive. Use during M3 before deleting any lib/ file.
---

# Dextop Flutter → Compose Parity Guard

## Non-negotiable rules

1. **166-RPC checklist is the contract:** `SessionRepository` (16), `PrivilegeRepository` (11), `DisplayAndShellRepository` (33), `WorkspaceAndAppsRepository` (5), `DiagnosticsRepository` (11), `LinuxRuntimeRepository` (90 from `LinuxChannel.kt`). No RPC may be dropped silently.
2. **Screen parity before deletion.** A Dart screen may only be deleted after its Compose replacement passes widget/UI tests for the same flows (setup wizard, settings, Linux store, display topology, diagnostics, keyboard/plasma/Samsung settings).
3. **ARB → XML l10n migration:** 7 locales (`en`, `ja`, `ko`, `zh`, `ru`, `pt`, `pt_BR`) must keep key parity and placeholder parity. Remove `NativeStrings.kt` and the vendored `third_party/material_3_expressive` only after generation is verified in CI.
4. **Compose quality bar:** M3 Expressive (`material3:1.5.0-alpha18`), `StateFlow` + `collectAsStateWithLifecycle`, stable keys, no `GlobalScope`, no recomposition storms. Follow `@android/skills` jetpack-compose/adaptive and `hamen/material-3-skill`.
5. **Isolate Compose screens in a clean `ui/` package** so upstream engine commits cherry-pick cleanly. Keep `/android` core packages structurally aligned during M0–M3.
6. **Exit gate:** zero `libflutter.so`, APK shrinks >=15–25 MB, cold start >=40% faster, localization tests green.

## Review checklist

- [ ] RPC count matches (166).
- [ ] Every screen has a parity test.
- [ ] All 7 locales key+placeholder parity.
- [ ] No GlobalScope / unstable Compose state.
- [ ] `libflutter.so` absent from final APK.

## Regression shield

Lost settings, broken localization, upstream cherry-pick conflicts.
