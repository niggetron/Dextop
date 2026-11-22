# ANDROID_DESKTOP_RESEARCH

# Android Desktop Research
Android 16 introduced native Desktop Windowing and large-screen behavior changes (resizability/orientation restrictions ignored on >=600dp). Prefer public APIs: ActivityOptions, requestPointerCapture, ASurfaceControl, AHardwareBuffer, AChoreographer, ADPF. OEM skins (HyperOS MiuiFreeform, Samsung DeX, ColorOS) diverge sharply; capability probes must be read-only and authoritative. Sources: developer.android.com/about/versions/16, AOSP android-16.0.0_r1 Settings.java and WindowManagerService.java.
