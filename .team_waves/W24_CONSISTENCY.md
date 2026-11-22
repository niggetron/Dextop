# Consistency — Wave 24 Report

## Scope inspected
Actual tree only (`/root/projects/Dextop`); no builds, no source edits, no token. Five axes:
1. Version strings — `pubspec.yaml`, `.github/release-please/manifest.json`, `CHANGELOG.md`, `README*.md`, `.github/workflows/*.yml`.
2. Package names — `android/**/build.gradle.kts`, `AndroidManifest.xml`, workflows, README.
3. Feature-flag keys — `XiaomiFeatureFlags.kt`, Dart `lib/`, `README.md`, `docs/`.
4. Watchdog constants — `WatchdogClient.kt` / `WatchdogDaemon.kt` / `WatchdogProtocol.kt`.
5. WAL methods — `SessionJournal.kt` vs all call sites.

## Findings

### A. Version strings
- **P1 — README is one beta behind the tree.** `pubspec.yaml:4` = `2.0.0-beta.4+45`, `.github/release-please/manifest.json:2` = `2.0.0-beta.4`, `CHANGELOG.md:3` = `[2.0.0-beta.4]`, but `README.md:176` = `Dextop 2.0.0-beta.3` and `README.md:234` = `Dextop 2.0.0-beta.3 runs Debian 13…`. **Fix:** change both README lines to `2.0.0-beta.4`. (Nuance: `CHANGELOG.md:13-14` says beta.4 is not yet released; if README is meant to name the *last shipped* build, instead add "(next: beta.4)" to both lines so tree/release state is unambiguous.)
- **P3 — translated READMEs drift to a stable `2.0.0`.** `README.ja.md:164,183`, `README.ko.md:182`, `README.zh-CN.md:122` say `Dextop 2.0.0`. **Fix:** align to whichever README.md settles on (`beta.4`) or add an explicit "stable channel" qualifier.
- **P3 — stale example in workflow description.** `.github/workflows/manual-release.yml:11,54` uses `2.0.0-beta.3` as the example version; beta-release.yml's `2.1.0-beta.1` is a generic future example (leave it). **Fix:** bump both manual-release examples to `2.0.0-beta.4`.
- No mismatch in version-code plumbing: `build.gradle.kts:78-79`, `release-apk.yml:105-109`, `beta-release.yml:41-42`, `manual-release.yml:65-66`, `release.yml:87-88` all derive from `pubspec.yaml:+45`; all workflows pin `flutter-version: 3.47.7`, consistent with `pubspec.yaml:8` `>=3.47.0`.

### B. Package names — no mismatch
- `build.gradle.kts:55,74` `moe.n4tsu.dextop`; play flavor `:104` `moe.n4tsu.gpdextop`.
- Manifest uses hardcoded `moe.n4tsu.dextop.*` component/action names + `${applicationId}` for authorities/affinities (`AndroidManifest.xml:39-96,108-186,275,364`). Companion `queries` list both IDs (`cardex/.../AndroidManifest.xml:3-4`, `cardexdriving/.../AndroidManifest.xml:5-6`).
- Companion IDs `moe.n4tsu.dextop.cardex` / `.cardex.driving` (`cardex/build.gradle.kts:46`, `cardexdriving/build.gradle.kts:35`) match `README.md:242`. Play badge `README.md:13` = `moe.n4tsu.gpdextop` ✓. Suffixed CI package `release-apk.yml:128` = `moe.n4tsu.dextop.r<hash>` with suffix `r`+12 hex (`release-apk.yml:117`), matches `README.md:211,242` ✓.

### C. Feature-flag keys
- **P1 — two documented flags are dead.** `README.md:166-167` promises `xiaomi_tablet_touch_mode` and `xiaomi_topology_layout` escape hatches; `XiaomiFeatureFlags.kt:27-32` defines `touchMode`/`topologyLayout` reading `flutter.xiaomi_tablet_touch_mode` / `flutter.xiaomi_topology_layout`, but repo-wide grep finds **zero readers** (only `forceFreeform`, `pinMetrics`, `laptopDirectDisplay` are read at `MirrorService.kt:12176,12208,12211,12516,12712,12741,13387`). **Fix:** either wire the two values into the touch-injection / topology-parcel code paths, or delete `XiaomiFeatureFlags.kt:26-32,38-39` and the two README rows. (Also recorded in W22_TABLET.md:14.)
- **P2 — no Dart writer for any of the five flags.** `XiaomiFeatureFlags.kt:12` reads `FlutterSharedPreferences`, and `README.md:162-167` says the switches are read from there, but `lib/**/*.dart` has **zero** `setBool/setString('xiaomi_*')` calls (other Flutter keys such as `window_manager`, `experimental_gamepad` do have writers, so the `flutter.` prefix convention itself is correct). **Fix:** add settings UI writes for the three live flags (Kotlin keys stay `flutter.xiaomi_*`, Dart writes the unprefixed logical key), or state in README that these are adb/external-only overrides.
- `CHANGELOG.md:22` lists all five keys without the `flutter.` prefix — consistent with Flutter's SharedPreferences semantics; no fix.
- `docs/architecture/HYPEROS_3_COMPATIBILITY_ANALYSIS.md:4` mentions only `xiaomi_force_freeform` — not a mismatch, just partial.

### D. Watchdog constants — no mismatch
- Deadline: `WatchdogClient.kt:91` `deadlineMs=15_000L`; `WatchdogDaemon.kt:19` `DEFAULT_DEADLINE_MS=15_000L` (fallback at `:88`). Heartbeat: `WatchdogDaemon.kt:20,96` `HEARTBEAT_WINDOW_MS=15_000L`; client beats every `5_000L` (`WatchdogClient.kt:99`) — 3 beats/window, intentional. Comments `WatchdogClient.kt:94-95` say 15 s ✓.
- Wire: `WatchdogProtocol.kt:14` `MAGIC="DW01"`, `:15` `MAX_FRAME=256*1024`; W10 design (`W10_RUSTCORE.md:50,101`) uses `WD_ARM_MS=15_000` and `"DW01"` ✓. Key names `deadlineMs`/`armSeq`/`sessionId`/`nonce` match between client (`:86-92,108-110,117`) and daemon (`:66-98`).
- P3 doc drift only: `W18_NATIVE.md:116` shows `System.currentTimeMillis() + HEARTBEAT_WINDOW_MS`; actual `WatchdogDaemon.kt:87,96,124` uses `SystemClock.elapsedRealtime()`. Fix that report snippet, not source.
- Test `WatchdogProtocolTest.kt:9-45` covers framing/CRC/argv; no constant assertions, so no drift risk there.

### E. WAL methods — no mismatch
- Every called token exists with a matching signature: `preparing(4 args)` (`SessionJournal.kt:51`; callers `AndroidAutoMirrorActivity.kt:587,897`, `MirrorService.kt:2993,12973`), `running(Int)` (`:186`; `MirrorService.kt:12344,12554`, `AAMirrorActivity.kt:777`), `paused()` (`:189`; `MirrorService.kt:8347`), `rememberGlobal` (`:135`; `DesktopModeConfigurator.kt:67,111`), `rememberSecure` (`:142`; `MirrorService.kt:14065`), `recordWm(4 args)` (`:161`; `MirrorService.kt:12373,12715,12716,12725`), `rememberPhoneRotation` (`:171`; `PhoneRotationController.kt:19,42`), `phoneRotationState()` (`:179`; `PhoneRotationController.kt:33`), `restoreSystemSettings()` (`:193`; `AndroidAutoMirrorActivity.kt:715,821`, `MirrorService.kt:2197,14038`, `MainActivity.kt:1288,1368`, `DesktopModeConfigurator.kt:194`), `restoreSystemKeys` (`:223`; `InternalRefreshRateController.kt:19`), `restoreWallpaperDimensions()` (`:235`; `MirrorService.kt:5607,8388,14022`), `snapshot()` (`:247`; 5 callers), `clear()` (`:262`; 5 callers). No call to a non-existent method.
- Dead API (not a mismatch, but cleanup candidates): `rememberSystem` (`:138`), `recordSetting` (`:145`), `restoreAll` (`:195`, only reached via `restoreSystemSettings`), `discardSystemKeys` (`:230`), `InternalRefreshRateController.restore()` (`:18`) have no external callers. `restoreSystemKeys(KEYS)` looks for ids `system:min_refresh_rate` / `system:peak_refresh_rate` (`:225`) that the WAL no longer records, so it is a no-op by design after M0.

## Deliverable
Mismatch list above with file:line and exact fix. Only true cross-file version drift (README beta.3 vs beta.4, translated READMEs, manual-release example) and the two dead/documented Xiaomi flags need action; package names, watchdog constants, and WAL method signatures are consistent across the actual tree.

## Risks / device testing needed
- Before changing README version text, confirm with release owner whether beta.4 has shipped: `CHANGELOG.md:13-14` says it has not, so the "current prerelease" wording may be deliberately last-released rather than tree version.
- If the two dead Xiaomi flags are wired rather than removed, device-test on the Redmi Pad Pro 5G: `xiaomi_tablet_touch_mode` against the multi-touch path and `xiaomi_topology_layout` against the SDK-36 display-topology parcel layout (HyperOS reorders it); if removed, confirm no external automation depends on the keys.
- No build/CI run was performed (per BRIEF); all findings are static greps against the checked-out tree.
