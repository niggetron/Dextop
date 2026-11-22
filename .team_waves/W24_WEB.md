# Web Research — Wave 24 Report

## Scope inspected
Android 16 / API 36 official developer docs only (developer.android.com, AOSP SettingsProvider source). Target device: Redmi Pad Pro 5G, HyperOS 3, API 36, arm64-v8a, minSdk 33, sw600dp tablet. No source edits. Seven shipping-regression items below; each has an exact verify command or code check plus citation.

## Findings (regression checklist — P0 = blocks ship)

### 1. Predictive back — P0
Default on for target 36; `onBackPressed()` is NOT called and `KeyEvent.KEYCODE_BACK` is NOT dispatched. Any custom back intercept that still uses these silently breaks.
- Code check: `grep -R "onBackPressed\|KEYCODE_BACK\|enableOnBackInvokedCallback" app/src/main` → expect zero `onBackPressed`/`KEYCODE_BACK`; migration to `OnBackPressedCallback` (Activity 1.6.0+), Compose `PredictiveBackHandler`/`BackHandler` (activity-compose 1.8.0+), or `OnBackInvokedCallback`.
- Opt-out (temporary only): `<application android:enableOnBackInvokedCallback="false">` or per-`<activity>`. For logging without consuming back: `OnBackInvokedDispatcher.PRIORITY_SYSTEM_NAVIGATION_OBSERVER` (API 36+).
- Manual verify: back gesture from root shows back-to-home; 3-button long-press back shows predictive animation.
- Sources: https://developer.android.com/about/versions/16/behavior-changes-16 ; https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture ; https://developer.android.com/about/versions/16/behavior-changes-all

### 2. Edge-to-edge — P0
Opt-out `R.attr.windowOptOutEdgeToEdgeEnforcement` deprecated and DISABLED on API 36 device. `setDecorFitsSystemWindows` disabled. No black bars/pillarbox; content draws under bars unless insets applied.
- Code check: `grep -R "windowOptOutEdgeToEdgeEnforcement\|setDecorFitsSystemWindows\|setStatusBarColor\|setNavigationBarColor" app/src/main` → expect none.
- Code check: Compose `Scaffold` + `WindowInsets`; Views `ViewCompat.setOnApplyWindowInsetsListener` + `android:fitsSystemWindows="true"`; cutout `layoutInDisplayCutoutMode = ALWAYS`.
- Manual verify: status bar / gesture nav / cutout transparent, no occluded controls on Settings/sign-in/low-traffic screens; `Configuration.screenWidthDp` no longer excludes system bars.
- Sources: https://developer.android.com/about/versions/16/behavior-changes-16 ; https://developer.android.com/about/versions/15/behavior-changes-15#edge-to-edge

### 3. Foreground service (FGS) restrictions — P0
A desktop app's session/watchdog FGS must use a valid declared type + permission; background starts and while-in-use types are heavily constrained.
- Manifest/code check: every `<service>` has `android:foregroundServiceType` + `FOREGROUND_SERVICE` + type permission; `specialUse` requires `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="..."/>`; `mediaProjection` requires `createScreenCaptureIntent()` grant before `startForeground()`; `dataSync`/`mediaProcessing` require `Service.onTimeout()` → `stopSelf()`; `shortService` ~3 min timeout.
- Verify commands:
  - `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS <pkg>`
  - `adb shell device_config put activity_manager data_sync_fgs_timeout_duration <ms>` (and `media_processing_fgs_timeout_duration`)
  - `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS <pkg>` then `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED <pkg>`
  - `adb shell am compat enable FGS_SAW_RESTRICTIONS <pkg>` (SYSTEM_ALERT_WINDOW needs a VISIBLE overlay on API 35+)
  - `adb shell dumpsys activity services <pkg>`
  - `adb logcat` check for `ForegroundServiceStartNotAllowedException`, `A foreground service of type ... did not stop within its timeout`, `Foreground service started from background can not have location/camera/microphone access`.
- BOOT_COMPLETED may not start dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone FGS.
- Sources: https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed ; https://developer.android.com/develop/background-work/services/fgs/service-types ; https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

### 4. Non-SDK interface restrictions — P0 (critical for hidden-API app)
Every hidden API must be behind a versioned adapter; API 36 adds a fresh flags list. Blocklist/max-target-36 interfaces throw `NoSuchFieldError`/`NoSuchMethodError` (or JNI NULL) on a non-debuggable build; OEMs may ADD but not remove entries (HyperOS can be stricter than AOSP).
- Verify: download `hiddenapi-flags.csv` (API 36) and `sha256sum` → `9102af02fe6ab68b92464bdff5e5b09f3bd62c65d1130aaf85d3296f17d38074`; diff every hidden symbol used against `blocked`/`max-target-*` tags.
- Verify: run debuggable build on API 36, exercise all backends A–F, `adb logcat | grep "Accessing hidden"` → no hits from our package.
- Code check: `StrictMode.setVmPolicy(VmPolicy.Builder().detectNonSdkApiUsage().penaltyListener(...).build())` in debug; `./gradlew lint`.
- Test-only escape hatch (never ship): `adb shell settings put global hidden_api_policy 1` (warn+allow), reset `adb shell settings delete global hidden_api_policy`.
- Sources: https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces ; https://developer.android.com/about/versions/16/changes/non-sdk-16

### 5. 16 KB page size — P0 (native `libdextop_core.so`, PRoot/fastpath)
Google Play blocks target-35+ updates lacking 16 KB support from 2027-02-01. ARM64 `.so` must be 16 KB ELF-aligned with RELRO end 16 KB-aligned; no hard-coded 4096.
- Verify commands:
  - `adb shell getconf PAGE_SIZE` → `16384`
  - `check_elf_alignment.sh app.apk` → `ALIGNED` for all arm64-v8a
  - `llvm-objdump -p libdextop_core.so | grep LOAD` → every `align 2**14` (never `2**13`/`2**12`)
  - `llvm-readelf -Wl libdextop_core.so | grep 'LOAD\|RELRO\|Type'` → `(GNU_RELRO.VirtAddr + MemSiz) % 0x4000 == 0` or no writable data after RELRO in same LOAD
  - `zipalign -c -P 16 -v 4 app.apk` → `Verification successful`
  - `bundletool dump config --bundle=app.aab | grep alignment` → `PAGE_ALIGNMENT_16K`
- Code check: `grep -R "PAGE_SIZE\|4096" cpp/ rust/`; replace with `getpagesize()`/`sysconf(_SC_PAGESIZE)`, align mmap/custom allocators to runtime page size; ARM64 stack buffers (BUG-05 64 KB buffer) must be page-safe.
- Build check: AGP >= 8.5.1, NDK r28 (16 KB default) or NDK r27 flags `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`.
- Force-fail test: `adb shell setprop bionic.linker.16kb.app_compat.enabled fatal; adb shell setprop pm.16kb.app_compat.disabled true` (API 17+; on API 36 use compat-mode dialog check / `android:pageSizeCompat`).
- Sources: https://developer.android.com/guide/practices/page-sizes ; https://developer.android.com/about/versions/16/behavior-changes-all

### 6. Settings.Global limits — P0 (desktop config restore, BUG-01)
`Settings.Global` is signature-protected: writes require `WRITE_SECURE_SETTINGS`; ordinary third-party app writes throw `SecurityException`. User restrictions can still block specific keys even with the permission; per-package custom-setting byte caps apply. All 7 `modernKeys` must be restored via WAL — do NOT rely on hidden Settings.Global writes that now fail or get silently reverted by HyperOS.
- Code check: `Context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)` == GRANTED before any `Settings.Global.put*`; assert `getString` round-trip for all `modernKeys`; verify WAL JSON contains every mutated key.
- Verify commands: `adb shell settings get global <key>` before/after session restore; `adb shell settings get global hidden_api_policy` (known global key, test-only). Restricted keys: `ADB_ENABLED`, `PACKAGE_VERIFIER_ENABLE`, `PREFERRED_NETWORK_MODE` (see AOSP `isGlobalOrSecureSettingRestrictedForUser`).
- Note: hidden-API policy itself is a Settings.Global key — non-SDK relaxation via `settings put global` only works from shell/root, not from the app.
- Sources: https://developer.android.com/reference/android/provider/Settings.Global ; https://android.googlesource.com/platform/frameworks/base/+/5514fb7aba781d8eabbbfc27a5d27a6b3a447b40/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java

### 7. Accessibility — P1
`announceForAccessibility()` and `TYPE_ANNOUNCEMENT` deprecated on API 36; TalkBack experience regresses if desktop shell uses them for state changes. Custom text rendering bypassing `android.text.Layout` must honor new outline-text mode.
- Code check: `grep -R "announceForAccessibility\|TYPE_ANNOUNCEMENT" app/src/main` → zero; replace with `Activity.setTitle()`, `setAccessibilityPaneTitle()`/Compose `paneTitle`, `setAccessibilityLiveRegion()`/`Modifier.semantics{liveRegion=...}` (sparingly), `CONTENT_CHANGE_TYPE_ERROR` + `setError()`/`TextView.setError()`.
- Code check / new APIs to adopt on API 36: `AccessibilityManager.isHighContrastTextEnabled()` + `addHighContrastTextStateChangeListener`, `TtsSpan.TYPE_DURATION`, `AccessibilityNodeInfo.addLabeledBy`/`getLabeledByList`, `setExpandedState` + `CONTENT_CHANGE_TYPE_EXPANDED`, `RANGE_TYPE_INDETERMINATE`, tri-state `setChecked(int)`, `View.setSupplementalDescription`, `AccessibilityNodeInfo.setFieldRequired`.
- Verify: `adb shell settings get secure accessibility_enabled`; `adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS`; TalkBack sweep + `adb shell uiautomator dump` on shell/desktop surfaces; Accessibility Scanner.
- Sources: https://developer.android.com/about/versions/16/behavior-changes-all ; https://developer.android.com/about/versions/16/features

## Deliverable (web citations)
Primary: behavior-changes-16, behavior-changes-all, behavior-changes-15, predictive-back-gesture, page-sizes, restrictions-non-sdk-interfaces, non-sdk-16, fgs/service-types, fgs/restrictions-bg-start, features, Settings.Global API ref, AOSP SettingsProvider.java. Full URLs inline per item above.

## Risks / device testing needed
- HyperOS 3 may add hidden-API blocklist entries and stricter Settings.Global policy than AOSP — test all backends A–F on the real Redmi Pad Pro 5G, not just emulator.
- Tablet form factor (sw>=600dp): API 36 ignores `screenOrientation`/`resizableActivity`/aspect-ratio unless temporary `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` opt-out; expected extra Activity recreation — verify WAL/session survival.
- Non-SDK escape hatches (`hidden_api_policy`) are test-only and will not be available on user builds.
- 16 KB backcompat mode can mask alignment defects; validate on a 16 KB image with `getconf PAGE_SIZE=16384` before sign-off.
