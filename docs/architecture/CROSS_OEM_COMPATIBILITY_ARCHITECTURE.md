# CROSS_OEM_COMPATIBILITY_ARCHITECTURE

# Cross-OEM Compatibility Architecture
Six backends: A public Android 16 desktop; B native OEM desktop (DeX/Workstation); C composited virtual display + shell; D standalone Wayland Linux desktop (Mode B, fully cross-OEM); E scoped device/OEM backend (Redmi Pad Pro 5G HyperOS 3); F graceful compatibility. Deterministic selection from a read-only CapabilitySnapshot with ordered fallback and WAL rollback. Isolation tests must prove a narrow rule never matches another device.
