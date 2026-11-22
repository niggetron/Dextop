# Web Agent W7C — Wave 7 Report

## Scope inspected

Web-only. No local source, no builds, no token. Target: Redmi Pad Pro 5G / Android 16 (API 36) / HyperOS 3 / 12.1" 120 Hz / arm64-v8a / minSdk 33.

Sources (all fetched this wave):
- Flutter add-to-app: https://docs.flutter.dev/add-to-app/android/project-setup · https://docs.flutter.dev/add-to-app/android/add-flutter-screen · https://docs.flutter.dev/platform-integration/android/compose-activity
- Flutter size: https://docs.flutter.dev/perf/app-size
- Manifest: https://developer.android.com/guide/topics/manifest/activity-alias-element
- Android 16: https://developer.android.com/about/versions/16/behavior-changes-16
- Prefs: https://pub.dev/packages/shared_preferences · https://raw.githubusercontent.com/flutter-team-archive/plugins/main/packages/shared_preferences/shared_preferences_android/android/src/main/java/io/flutter/plugins/sharedpreferences/MethodCallHandlerImpl.java · https://developer.android.com/topic/libraries/architecture/datastore
- Skills: https://github.com/android/skills · https://raw.githubusercontent.com/android/skills/main/jetpack-compose/adaptive/SKILL.md · https://developer.android.com/tools/agents/android-skills · https://github.com/hamen/material-3-skill · https://docs.flutter.dev/flutter-for/compose-devs

## Findings

### P0-1 — Flutter prefs are NOT plain native SharedPreferences; naive port silently drops settings
Flutter legacy `shared_preferences` writes to a **named** file `getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)` (→ `shared_prefs/FlutterSharedPreferences.xml`) and every key is prefixed `flutter.`. Worse, three types are encoded strings, not native types: `double` = base64 `"This is the prefix for a Double."` + value; `BigInteger` = base64 prefix + base-36 digits; `List<String>` = base64 prefix + Java-serialized object (`ObjectInputStream`). `bool`→`putBoolean`, `int`→`putLong`, `String`→`putString`.
Why it matters: BUG-01 settings/WAL restoration in M3 would read zero keys and "restore all" would pass with defaults — silent user-data loss on HyperOS device.
Concrete fix: migrate **while Dart still exists** (one release before code deletion). Kotlin reader: open legacy file, filter `flutter.`, strip prefix, decode the three marker prefixes, write canonical keys into Preferences DataStore behind a `flutterPrefsMigratedV1` flag; keep legacy file read-only for one release for rollback. Never delete the legacy file in the same release as the reader change. Plugin docs confirm legacy API is deprecated (v2.3+) and new APIs default to DataStore on Android; DataStore is the platform recommendation.

### P0-2 — Launcher/deep-link breakage when swapping the launch Activity
`<activity-alias>` is the safe seam: its own intent filters (not the target's) decide activation; `targetActivity` must be declared **before** the alias in the manifest; both `<application>` and alias `android:enabled` must be true; `exported` defaults true when a filter exists. `android:name` is an arbitrary alias string, not a class.
Why it matters: removing `FlutterActivity` from the manifest while leaving stale `MAIN`/`LAUNCHER` filters causes duplicate icons or no launchable entry; OEM desktop/HyperOS deep links with no action or explicit component intents can break.
Concrete fix: register native `ComponentActivity` as target, move `MAIN`/`LAUNCHER` + deep-link filters to a stable alias, keep alias name unchanged across the cutover so launcher shortcuts/pinned widgets survive; toggle aliases with `PackageManager.setComponentEnabledSetting()` for staged rollout/rollback. On API 36 set `android:intentMatchingFlags="enforceIntentFilter"` only after auditing callers; use `allowNullAction` (or `none`) on aliases receiving no-action explicit intents — Safer Intents is opt-in in 16 but is the default roadmap.

### P1-3 — FlutterActivity lifecycle/back/config behavior does not transfer to a native Activity
Flutter's manifest registration uses a large `android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"`, `hardwareAccelerated`, `windowSoftInputMode="adjustResize"`, and Dart-side back handling via `onBackPressed`.
Why it matters on API 36 12.1" tablet: (a) predictive back is **on by default** — `onBackPressed` is not called and `KEYCODE_BACK` is not dispatched; migrate to `OnBackInvokedCallback`/Navigation 3 back. (b) orientation/resizability/aspect-ratio restrictions are **ignored** on displays ≥600dp — `screenOrientation`, `resizeableActivity`, `min/maxAspectRatio`, `setRequestedOrientation()` are no-ops; the old `configChanges` blanket is both unnecessary and harmful. (c) edge-to-edge is mandatory — `windowOptOutEdgeToEdgeEnforcement` is disabled; use `enableEdgeToEdge()` + insets. Temporary opt-out property `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` exists but dies at API 37.
Concrete fix: native Activity with no `configChanges`/`screenOrientation`, `enableEdgeToEdge()`, `rememberSaveable`/ViewModels for state, adaptive `WindowSizeClass` shell. This aligns with the official `jetpack-compose/adaptive` skill (last updated 2026-11-14): it explicitly says **never** add `configChanges`/`screenOrientation` to suppress recreation.

### P1-4 — "Remove Gradle dep" ≠ "APK shrunk"; verify binary evidence
Flutter is integrated via one of: `implementation(project(":flutter"))` + `apply from: .../include_flutter.groovy` in `settings.gradle`, or AAR deps (`flutter_release/debug/profile`) + `maven("https://storage.googleapis.com/download.flutter.io")`; plus `GeneratedPluginRegistrant`, `FlutterEngineCache` prewarm in `Application`, `FlutterActivity/Fragment/View`, `@style/LaunchTheme`, and `abiFilters` added to avoid missing-`libflutter.so` crashes.
Why it matters: leaving any AAR/repo/plugin registration keeps `libflutter.so`, `libapp.so`, `icudtl.dat`, `assets/flutter_assets` in the APK; device is arm64-v8a so an orphaned second ABI is pure dead weight.
Concrete fix: reverse every integration point above, delete the module, then prove it: baseline/final `flutter build apk --analyze-size` (or `apkanalyzer`/zip listing) must show zero `libflutter.so`/`libapp.so`/`flutter_assets`; keep only `libdextop_core.so` + C PRoot/preload libs. Set `ndk.abiFilters += "arm64-v8a"` only if no other ABI is needed. Compare the two `*-code-size-analysis_*.json` files in DevTools.

### P2-5 — Skills to adopt (both are live and Compose-first)
- `android/skills` (Google LLC, Apache-2.0): `jetpack-compose/adaptive` exists (also `jetpack-compose/migration`, `jetpack-compose-performance`, `migration`). Install via Android CLI `android skills add adaptive --project=.`; covers MediaQuery, Navigation 3 `SceneStrategy` multi-pane (`ListDetailSceneStrategy`, `SupportingPaneSceneStrategy`), `NavigationSuiteScaffold`, `GridCells.Adaptive`, screenshot previews for Phone/Foldable/Tablet/Desktop. Directly applicable to the 12.1" tablet shell.
- `hamen/material-3-skill` (MIT, v1.1.1 2026-06-29): Compose-primary MD3 skill, 30+ components, tokens, theming, responsive layout, 10-category MD3 compliance audit. Install `npx --yes skills add hamen/material-3-skill --skill material-3 -y`. README self-declares a "best-effort distillation" — prefer official Android docs for exact BOM/API signatures.
- Flutter→Compose concept mapping (Scaffold, Row/Column, LazyColumn, MaterialTheme, remember) is documented officially: https://docs.flutter.dev/flutter-for/compose-devs — use it to drive the parity checklist for the repo-local `dextop-flutter-to-compose-parity` skill.

## Deliverable — concrete safe M3 cutover order

1. **Inventory + freeze**: list all Flutter touchpoints (Gradle, manifest, `FlutterEngineCache`, MethodChannels, `GeneratedPluginRegistrant`, Dart→native channels, shared_prefs keys, deep links, launcher aliases).
2. **Prefs migration first** (P0-1): ship a Kotlin `FlutterPrefsMigrator` + DataStore, migrate-and-verify, flag `flutterPrefsMigratedV1`, keep legacy file one release. Do not touch WAL/journal semantics here (BUG-01 is separate).
3. **Native shell + alias** (P0-2/P1-3): add `ComponentActivity` + Compose, register `<activity-alias>` with existing launcher/deep-link filters, API-36-safe flags, predictive back, edge-to-edge. Ship behind alias toggle; verify launcher icon count and deep links.
4. **Feature parity**: port screens screen-by-screen using `jetpack-compose/adaptive` + `material-3-skill` + parity checklist; keep Flutter screens as fallback until parity signed off.
5. **Delete Flutter** only after 2–3: reverse `implementation(project(":flutter"))`/AAR deps, `include_flutter.groovy`, flutter maven repo, plugin registrations, `FlutterActivity` manifest block, `LaunchTheme`, prewarm code, module directory, `abiFilters` rollback if appropriate.
6. **Prove shrink** (P1-4): compare size-analysis JSON pre/post; assert no `libflutter.so`/`libapp.so`/`flutter_assets` in the arm64 APK.
7. **Cleanup pass**: remove legacy prefs file in a later release after telemetry confirms zero readers.

## Risks / device testing needed

- On-device Xiaomi Redmi Pad Pro 5G / HyperOS 3: launcher icon count + pinned shortcut, all deep links (with and without action), explicit intents from OEM desktop shell, predictive-back gesture, rotation/resize (API 36 ignores orientation locks), edge-to-edge insets with 120 Hz surface.
- Prefs: install an old build with known Flutter-written values (bool/int/double/String/List<String>), upgrade, assert all values survive; verify no data loss if migration flag is set but legacy file absent.
- Rollback drill: alias toggle back to old activity in one release window; confirm no duplicate icons and DataStore↔legacy coexistence.
- APK verification on CI artifact only (no local builds per brief).
