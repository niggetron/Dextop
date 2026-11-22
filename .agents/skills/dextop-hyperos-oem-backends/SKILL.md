---
name: dextop-hyperos-oem-backends
description: Read-only capability probing and strictly scoped OEM/device backends for Dextop, especially Redmi Pad Pro 5G on Android 16 / HyperOS 3. Use when touching DeviceProfiles, CapabilityProbe, display backends, or OEM workarounds.
---

# Dextop HyperOS / OEM Backend Guard

## Non-negotiable rules

1. **Probes are read-only.** `CapabilityProbe` never changes settings, never starts displays, never writes files. It reports facts and reasons.
2. **Narrow scoping.** A device rule must be keyed by `device` + `apiLevel` + `oemVersion` (and fingerprint where stable). Never apply a phone-tuned workaround to every Xiaomi/Redmi/POCO device. Keep the broad rule only as a fallback.
3. **Redmi Pad Pro 5G reality:** 12.1" 120 Hz landscape tablet, USB 2.0 (no DP Alt Mode). Prefer standalone Mode B (native Wayland inside our own `ASurfaceControl`) and the scoped HyperOS backend. Do not assume external-monitor desktop handoff.
4. **HyperOS constraints to respect:** `SecondaryDisplayLauncher` on recording displays, portrait-bound override, `MiuiFreeform` policy, restricted `VIRTUAL_DISPLAY_FLAG_TRUSTED` task routing, aggressive background killing. Prefer `directPlasmaDisplay` + Book shell + privileged task hosting with graceful fallback.
5. **Prefer public APIs first:** `ASurfaceControl`, `AHardwareBuffer`, `AAudio`, `AChoreographer`, `View.requestPointerCapture`, Android 16 Desktop APIs. Every hidden API is behind a versioned adapter with a logged capability signal.
6. **Isolation tests mandatory.** `DeviceMatchTest.kt` must prove a narrow rule cannot match a phone or another model.

## Review checklist

- [ ] Probe has no side effects.
- [ ] Rule is device/api/oem scoped with a safe fallback.
- [ ] No manufacturer-wide hack introduced.
- [ ] Isolation test added.
- [ ] Missing capability surfaced to the user honestly.

## Regression shield

BUG-01/02 settings collision on SDK 36; coarse `xiaomi_hyperos` profile leaking to phones.
