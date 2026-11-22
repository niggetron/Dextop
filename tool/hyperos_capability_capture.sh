#!/usr/bin/env bash
# Read-only HyperOS 3 / Redmi Pad Pro 5G capability capture (blueprint Part B.4).
# Run on your workstation with the tablet connected via ADB. This script NEVER
# writes settings and NEVER installs anything; it only dumps ground truth.
# Output: hyperos-capture-<timestamp>.txt in the current directory.
set -uo pipefail

out="hyperos-capture-$(date +%Y%m%d-%H%M%S).txt"
{
  echo "=== 1. DEVICE & OS IDENTITY ==="
  for prop in \
    ro.product.manufacturer ro.product.model ro.product.device ro.product.name \
    ro.build.version.sdk ro.build.version.release ro.build.version.security_patch \
    ro.mi.os.version.name ro.hardware.chipname ro.hardware.egl; do
    printf '%s=' "$prop"; adb shell getprop "$prop" 2>/dev/null | tr -d '\r'
  done

  echo "=== 2. DISPLAY, WINDOWING & DESKTOP FLAGS ==="
  adb shell dumpsys display 2>/dev/null | grep -E 'DisplayDeviceInfo|DisplayInfo|mDisplayId|supportedModes|flags=' || true
  echo "--- window displays ---"
  adb shell dumpsys window displays 2>/dev/null | head -n 120 || true
  echo "--- activity displays ---"
  adb shell dumpsys activity displays 2>/dev/null | head -n 120 || true
  echo "--- SurfaceFlinger displays ---"
  adb shell dumpsys SurfaceFlinger --display-id 2>/dev/null || true
  echo "--- global desktop flags ---"
  adb shell settings list global 2>/dev/null | grep -iE 'desktop|freeform|multi_window|resizable|secondary|force_' || true
  echo "--- system flags ---"
  adb shell settings list system 2>/dev/null | grep -iE 'workstation|freeform|miui_dkt|pad' || true
  echo "--- miui.home service ---"
  adb shell dumpsys activity service com.miui.home 2>/dev/null | head -n 60 || true

  echo "=== 3. PRIVILEGE & FREEFORM CAPABILITY ==="
  adb shell pm list features 2>/dev/null | grep -iE 'freeform|activities_on_secondary_displays|vulkan|opengles' || true
  adb shell cmd window get-fix-to-user-rotation 2>/dev/null || true
  adb shell wm size 2>/dev/null || true
  adb shell wm density 2>/dev/null || true
  echo "=== END ==="
} | tee "$out"

echo "Saved read-only capture to $out"
