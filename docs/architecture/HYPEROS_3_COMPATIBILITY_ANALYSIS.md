# HYPEROS_3_COMPATIBILITY_ANALYSIS

# HyperOS 3 Compatibility Analysis
Redmi Pad Pro 5G: 12.1" 120 Hz, USB 2.0 (no DP Alt Mode). HyperOS replaces AOSP DesktopModeController with MiuiWindowManagerService/MiuiFreeFormManagerService; SecondaryDisplayLauncher attaches to recording displays; portrait-bound override; restricted VIRTUAL_DISPLAY_FLAG_TRUSTED task routing; aggressive background killing. This fork keeps a scoped xiaomi_redmi_pad_pro_5g profile with forced freeform (xiaomi_force_freeform), pinned metrics, direct Plasma display, and feature flags. Ground-truth ADB capture (M1) still required on the device.
