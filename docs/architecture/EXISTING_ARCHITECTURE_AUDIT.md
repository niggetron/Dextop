# EXISTING_ARCHITECTURE_AUDIT

# Existing Architecture Audit
Key components: MirrorService.kt (14k LOC God object), CompositedWindowShell.kt (4.8k), LinuxContainerService.kt (2.1k), MainActivity.kt + LinuxChannel.kt (166 MethodChannel RPCs), cpp/wayland (compositor.c, gpu_present.c, deck_direct.inc), native_input.cpp. Device profiles live in DeviceProfiles.kt / DesktopEnvironment.kt. This fork has already added HyperOS freeform restoration, process-wide hidden-API bypass, non-fatal metric pinning, and diagnostics log export. Remaining structural debt: MirrorService decomposition, Flutter host UI, fragmented input pipeline, PulseAudio binaries.
