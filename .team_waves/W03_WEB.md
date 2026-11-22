# Web Agent W3C — Wave 3 Report
## Scope inspected
Web-only research (no builds/source/token). Target: Redmi Pad Pro 5G (codename `ruan`), Android 16 / HyperOS 3. Sources: Xiaomi/GSMArena spec pages, HyperOS ROM trackers, AOSP/Google developer docs, scrcpy issues #5523/#6143, LocalDex technical notes.

## Findings

### P0 — Hardware facts (public, confirmable pre-device)
- SoC **Snapdragon 7s Gen 2 (SM7435-AB)**, 4nm, 4×Cortex-A78 2.4GHz + 4×A55 1.95GHz; GPU **Adreno 710**. [mi.com UK FAQ](https://www.mi.com/uk/support/faq/details/KA-243670/), [GSMArena full specs](https://m.gsmarena.com/xiaomi_redmi_pad_pro_5g-ampp-13071.php), [XiaomiToday](https://en.xiaomitoday.it/redmi-pad-pro-5g.html).
- Display **12.1" IPS LCD, 1600×2560, 16:10, 120Hz, 600 nits, Dolby Vision**, 249ppi; 10000mAh; LPDDR4X; UFS. [GSMArena](https://m.gsmarena.com/xiaomi_redmi_pad_pro_5g-ampp-13071.php), [Xiaomi MICROLESS listing](https://global.microless.com/product/xiaomi-redmi-pad-pro-5g-12-1-120hz-ips-lcd-display-qualcomm-sm7435-ab-snapdragon-7s-gen-2-chipset-6gb-ram-128gb-storage-adreno-710-gpu-10000mah-wi-fi-cellular-hyperos-graphite-gray-xrprggc/).
- **USB Type-C 2.0 ONLY** (Xiaomi official "Data transfer: USB 2.0"). [mi.com NG specs](https://mi.com/ng/product/redmi-pad-pro-5g/specs), [GSMArena](https://gsmarena.com/xiaomi_redmi_pad_pro_5g-opinions-13071.php).
  - **Why it matters:** USB 2.0 implies no DP Alt Mode → wired external-display/Workstation path likely unavailable; plan virtual-display (backend C) and wireless display as primary. Must confirm on-device.
  - Fix: probe `dumpsys display` / DP alt-mode state; don't assume HDMI-out.
- HyperOS 3 build IDs (Android 16), codename `ruan`, from ROM trackers (unofficial — capture exact on-device): EEA **OS3.0.303.0.WFSEUXM** (2026-09-21), Global Stable **OS3.0.303.0.WFSMIXM** (2026-08-20), Global Beta **OS3.0.304.0.WFSMIXM** (2026-10-29), India **OS3.0.306.0.WFSINXM** (2026-10-29), Russia **OS3.0.306.0.WFSRUXM** (2026-11-06), China **OS3.0.308.0.WFSCNXM** (2026-11-04). [xiaomiadvices](https://xiaomiadvices.com/hyperos/ruan/os3-0-303-0-wfseuxm), [hyperosupdate global](https://hyperosupdate.com/firmware/ruan_global), [xmfirmwareupdater IN](https://xmfirmwareupdater.com/hyperos/ruan/stable/OS3.0.306.0.WFSINXM), [xiaomifirmware](https://xiaomifirmware.com/firmware/ruan), [androidfirmwarefile](https://androidfirmwarefile.com/firmware/xiaomi-redmi-pad-pro-5g-ruan), [miuirom](https://miuirom.org/updates/hyperos-3).

### P0 — Trusted virtual display restrictions (Android 16)
- `VIRTUAL_DISPLAY_FLAG_TRUSTED` (`1 << 10`) requires **`android.permission.ADD_TRUSTED_DISPLAY`**, protectionLevel `signature`, minSdk 33 — unobtainable by third-party apps. Same for `CREATE_VIRTUAL_DEVICE`, `OWN_DISPLAY_GROUP`, `ALWAYS_UNLOCKED`. [AOSP roles.xml](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml), [go-xrkit flags](https://pkg.go.dev/github.com/go-xrkit/android@v0.19.2), [DisplayManagerService](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/display/DisplayManagerService.java).
- Trusted flag = display trusted to show system decorations and receive input without user touch. Non-system virtual displays: only activities with `allowEmbedded` permitted, caller needs `ACTIVITY_EMBEDDING`; `FLAG_PRIVATE` restricts all but owner. [AOSP recommended practices](https://source.android.com/docs/core/display/multi_display/recommended-practices).
- Shell UID lost these permissions in Android 15 QPR2 beta (security hardening), restored for Android 16 upstream — but **OEM builds vary**: Xiaomi MI 8 on A15 and LineageOS builds still fail `scrcpy --new-display` with `SecurityException: Requires ADD_TRUSTED_DISPLAY permission...`; `adb root` fixes it. Google issue [384752747](https://issuetracker.google.com/issues/384752747). [scrcpy #5523](https://github.com/Genymobile/scrcpy/issues/5523).
  - **Why it matters:** backend C cannot rely on app-created trusted virtual display via public SDK. Options: (a) ADB-shell-launched server only after runtime probe confirms shell grant; (b) untrusted virtual display + `allowEmbedded` activities only; (c) Shizuku/shell still subject to OEM grant; (d) root (out of scope).
  - Fix: capability-gate backend C on runtime `createVirtualDisplay(TRUSTED)` probe; fall back to F (graceful compatibility) or E (OEM) if denied.

### P0 — Android 16 desktop/freeform gotcha
- On Android 16 QPR1/QPR2+, app-created/shell-created virtual displays **do not auto-activate desktop mode/freeform** (no taskbar/titlebar, apps fullscreen); workaround `settings put global force_desktop_mode_on_external_displays 1`, but even that is flaky (1080p may fail while 4K works). [scrcpy #6143](https://github.com/Genymobile/scrcpy/issues/6143).
- Deterministic fix (verified on One UI 9, AOSP `DisplayWindowSettings.getWindowingModeLocked()`): persisted per-display windowing mode is checked before desktop heuristics → `adb shell wm set-display-windowing-mode -d <displayId> 5` (`5` = `WINDOWING_MODE_FREEFORM`). [LocalDex TECHNICAL.md](https://github.com/sam1am/localdex/blob/main/docs/TECHNICAL.md).
- Android 16 behavior change: **virtual device owners can override app orientation/aspect-ratio/resizability** on select virtual devices (large-screen projection); applies to trusted/privileged virtual-device owners. [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all).
- AOSP connected-display desktop windowing (taskbar, freeform/maximized) exists in Android 16 but is OEM/device gated. [Android Developers Blog](https://developer.android.com/blog/posts/android-devices-extend-seamlessly-to-connected-displays).

### P1 — HyperOS 3 / Xiaomi OEM desktop
- Workstation mode = Xiaomi tablet-side desktop (since HyperOS 2): external monitor + keyboard, resizable windows, drag-and-drop; described by Xiaomi Community. [Xiaomi Community](https://new.c.mi.com/pk/post/32592), [smartinggoods guide](https://smartinggoods.com/blog/xiaomi-pad-desktop-mode).
- **Unconfirmed / contradictory:** a Redmi Pad Pro owner reports HyperOS 3 did **not** bring desktop mode to this model despite Android 16. [Reddit r/RedmiPadPro](https://reddit.com/r/RedmiPadPro/comments/1qzvorz/hyperos_3_for_redmi_pad_pro). Treat backend E as "probe, don't assume".
- HyperOS 3 = Android 16 base; Xiaomi may also block global `enable_freeform_support` mutation or strip `wm set-display-windowing-mode`. [miuirom](https://miuirom.org/updates/hyperos-3).

### P1 — ADB probe commands (M1 capability capture)
```
# build / capability identity
adb shell getprop ro.build.version.incremental
adb shell getprop ro.build.version.release        # 16
adb shell getprop ro.system.build.version.incremental
adb shell getprop ro.product.device                # ruan
adb shell getprop ro.miui.ui.version.name          # HyperOS 3 string

# display topology / flags / windowing modes
adb shell dumpsys display
adb shell dumpsys window
adb shell wm help                                  # is set-display-windowing-mode present?

# freeform settings (read, do not mutate on prod)
adb shell settings list global | grep -iE 'freeform|desktop|resizable|display'
adb shell settings get global enable_freeform_support
adb shell settings get secure force_resizable_activities

# shell permission grant (grep grantedPermissions for com.android.shell)
adb shell dumpsys package com.android.shell | grep -iE 'ADD_TRUSTED_DISPLAY|CREATE_VIRTUAL_DEVICE|WRITE_SECURE_SETTINGS'

# trusted virtual display runtime probe (scrcpy server / app_process)
adb shell CLASSPATH=/data/local/tmp/scrcpy-server app_process / com.genymobile.scrcpy.Server 4.1 \
  new_display=1920x1080/240 tunnel_forward=true   # watch for SecurityException

# force freeform on a created display (id from New display log / dumpsys)
adb shell wm set-display-windowing-mode -d <N> 5

# optional fallback toggles (mutate only with user consent)
adb shell settings put global force_desktop_mode_on_external_displays 1
adb shell settings put global force_allow_on_external 1
adb shell settings put global enable_freeform_support 1
adb shell settings put secure force_resizable_activities 1
adb shell settings put global overlay_display_devices "1920x1080/240"   # 'none' to tear down

# launch probe
adb shell am start --display <N> --windowingMode 5 <pkg>/<activity>
```
Sources: [LocalDex](https://github.com/sam1am/localdex/blob/main/docs/TECHNICAL.md), [scrcpy #6143](https://github.com/Genymobile/scrcpy/issues/6143), [Android SE freeform thread](https://android.stackexchange.com/questions/261679/how-to-invoke-an-application-in-freeform-mode-via-adb), [dumpsys docs](https://developer.android.com/tools/dumpsys), [scrcpy #5523](https://github.com/Genymobile/scrcpy/issues/5523).

### P2 — requestPointerCapture / ASurfaceControl viability
- `View.requestPointerCapture()` is **public API since 26**, not hidden. Only works when the view hierarchy has **focus** → call in `onWindowFocusChanged`/after user interaction, not `onCreate`; relative-mode mouse events (`SOURCE_MOUSE`, ACTION_HOVER_MOVE/buttons/scroll) delivered to `OnCapturedPointerListener`; release via `releasePointerCapture()`. [Mouse input guide](https://developer.android.com/games/playgames/input-mouse), [View reference](https://developer.android.com/reference/android/view/View). Android 17 adds "absolute" capture mode — not available at API 36. [Android 17 Beta 2 post](https://developer.android.com/blog/posts/the-second-beta-of-android-17?hl=en).
  - Caveat: requires real mouse input device; finger touch won't produce SOURCE_MOUSE. On a virtual/embedded display, focus must be on that display — verify on-device. Waydroid notes games must explicitly request capture. [waydroid #482](https://github.com/waydroid/waydroid/issues/482).
- `ASurfaceControl` / `ASurfaceTransaction` / `AHardwareBuffer` are **stable public NDK** (`android/surface_control.h`, NDK "Native Activity" group): create from window/parent, setBuffer(AHardwareBuffer, acquire fence), setPosition/setVisibility/setCrop/setFrameRate, apply atomically; new NDK `AInputReceiver` registers input on an ASurfaceControl. [NDK reference](https://developer.android.com/ndk/reference/group/native-activity).
  - Caveat: you can only build a surface tree under **your own** window/virtual display. Capturing/embedding **other apps'** surfaces requires hidden SurfaceControl APIs (signature/privileged) — keep behind `dextop-hyperos-oem-backends` adapter. [Issue 320706287](https://issuetracker.google.com/issues/320706287), [AOSP multi-display guidance](https://source.android.com/docs/core/display/multi_display/recommended-practices).
- Android 16 hidden-API list tightened — audit against `hiddenapi-flags.csv` (SHA-256 `9102af02...38074`). [non-SDK changes](https://developer.android.com/about/versions/16/changes/non-sdk-16).
- Android 16 adds 16KB page-size compat mode: set `android:pageSizeCompat` or ship 16KB-aligned `libdextop_core.so`. [behavior changes](https://developer.android.com/about/versions/16/behavior-changes-all).

## Deliverable — confirmable without device vs must-capture
**Confirmable now:** hardware SoC/GPU/display/USB 2.0; HyperOS 3 ≈ Android 16; existence of HyperOS Workstation mode; ADD_TRUSTED_DISPLAY signature-only model; requestPointerCapture + ASurfaceControl public/stable; Android 16 virtual-device-owner override + 16KB compat; ADB command syntax; known Android 15/16 shell-permission and freeform-on-virtual-display defects.
**Must capture on-device (M1):** exact `ro.build.version.incremental`/region + HyperOS string; whether Workstation/desktop mode actually exists on this SKU and its toggle; shell grant of ADD_TRUSTED_DISPLAY/CREATE_VIRTUAL_DEVICE/WRITE_SECURE_SETTINGS; results of trusted `createVirtualDisplay` probe; presence/behaviour of `wm set-display-windowing-mode`; defaults for enable_freeform_support/force_resizable_activities/force_desktop_mode_on_external_displays; DP-alt-mode/external-display availability (USB 2.0 → likely none); display IDs/flags from `dumpsys display`; requestPointerCapture with real mouse and on secondary/virtual display; ASurfaceControl+AHardwareBuffer zero-copy on Adreno 710 (gralloc usage/fps); non-SDK audit result on this build.

## Concrete feature flags (recommend)
- `USE_PUBLIC_SURFACE_CONTROL=true` — ASurfaceControl+AHardwareBuffer; fallback SurfaceView/ImageReader.
- `POINTER_CAPTURE_ENABLED=true` — requestPointerCapture on focus; fallback hover/absolute coordinate injection.
- `TRUSTED_VIRTUAL_DISPLAY=false` — enable only if runtime ADD_TRUSTED_DISPLAY probe passes; fallback untrusted+allowEmbedded or backend F/E.
- `FORCE_FREEFORM_WM=false` — issue `wm set-display-windowing-mode -d N 5` post-display-create when QPR2+ heuristic fails.
- `FORCE_DESKTOP_MODE_SETTING=false` — `force_desktop_mode_on_external_displays` mutation, opt-in.
- `FREEFORM_SETTING_FALLBACK=false` — global enable_freeform_support / force_resizable_activities mutation, opt-in, reversible in WAL.
- `OVERLAY_DISPLAY_FALLBACK=false` — overlay_display_devices (visible preview), last resort only.
- `HYPEROS_WORKSTATION_BACKEND=false` — backend E, gate on confirmed on-device capability + build-number allowlist.
- `HIDDEN_API_ADAPTER_VERSION=1` — all SurfaceControl/WindowManager hidden calls isolated per brief; audit vs hiddenapi-flags.csv.
- `PAGE_SIZE_16K_COMPAT=true` — 16KB-aligned native libs / pageSizeCompat manifest.

## Risks / device testing needed
ROM-tracker build IDs are unofficial and region-split — never use as runtime gates without on-device readback. Xiaomi may remove/alter `wm` subcommands and desktop-mode settings; adversarial combination of HyperOS 3 + USB 2.0 (no DP Alt Mode) may make external-display backends A/B/E unusable, leaving only C/F. Do not mutate global settings in production without typed `PrivilegeGate` + WAL + 15s watchdog (per brief). All pointer/graphics claims are API-level facts, not device-validated; M1 capture is mandatory before M4 backend selection.
