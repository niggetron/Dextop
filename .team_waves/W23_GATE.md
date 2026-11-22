# Gate Agent (W23) — M3 Go/No-Go: Flutter retirement + Compose host

## Scope inspected
- `git log/status/tag/branch` at HEAD `4189856` ("honest implementation status: M0–M2 shipped, M3–M8 designed only"); no tags, one branch `main`, clean tree.
- Actual M3 footprint: `android/app/src/main/kotlin/.../ui/` **absent**, `repo*/repository` dirs **absent**, `MainActivity.kt:37` still `class MainActivity : FlutterActivity()`, `io.flutter.*` imports intact. 79 display + 95 Linux method-name literals + 11 diagnostics still inline in two `when` blocks (`MainActivity.kt:358-848`, `linux/LinuxChannel.kt:36-629`).
- M3 design reports only: W06_REPO1/2/3, W07_SCREENS/THEME/WEB, W08_RETIRE/SHELL; parity skill `.agents/skills/dextop-flutter-to-compose-parity/SKILL.md`.
- Release/CI state: W20_RELEASE, W21_CI, W21_COMPILE, W08_RETIRE P0s; `pubspec.yaml`, 11 workflows, `settings.gradle.kts`, `app/build.gradle.kts`.
- Device: `adb` not installed, no `hyperos-capture-*.txt` checked in (only `docs/architecture/HYPEROS_3_COMPATIBILITY_ANALYSIS.md`). No builds, no source edits, no token.

## Verdict: **NO-GO** for attempting M3 beyond non-breaking groundwork this session.
**GO only** for the fallback-anchor step (below). Every Flutter-deletion step (W08_RETIRE 5–9) is blocked by unmet prerequisites, a Flutter-bound release pipeline, and zero device verification.

## Findings (why)

- **P0 — M3 has zero implementation.** Repos are design sketches in W06, not code; no `ui/` host, no `RpcGolden`, no `NativeCallAdapter`. W06's `FlutterMethodChannelAdapter` (W06_REPO2:89-108) *calls out to Flutter* — unusable in a native host (W08_SHELL P0). Porting 166 RPCs / 54 screens by hand makes parity unverifiable.
- **P0 — no rollback anchor.** No `pre-flutter-retirement` tag, no `flutter-legacy` branch, no retained last-green Flutter APK. W08_RETIRE step 0 is not done; every later step is irreversible in practice.
- **P0 — settings loss.** ~80 native call sites still use `FlutterSharedPreferences.xml` + `flutter.` prefix (MirrorService ~35, W08_RETIRE:29). No `DextopPrefs` compat; deleting Flutter wipes user settings.
- **P0 — release pipeline is Flutter-bound at 4 points.** `flutter.*` supplies compile/target SDK + versionCode/Name (`app/build.gradle.kts:61-62,77-79`); `pubspec.yaml` is `awk`-parsed by 4 release paths and `release-please` is `"dart"`; outputs hardcoded to `build/app/outputs/flutter-apk/` (release-apk.yml:126,147,151-154; debug-apk.yml:74,83); `settings.gradle.kts:2-21` needs `flutter.sdk` + plugin loader. Any deletion before step 5 mirrors breaks all builds and releases.
- **P0 — l10n generators read `lib/l10n`** in `preBuild` for **both** app and cardex (`app/build.gradle.kts:169-190`, `android/build.gradle.kts:25-50`, W08_RETIRE:33). Cannot delete `lib/` before relocating catalogs.
- **P0 — CI gate already red.** W21_CI: `safety.yml` "Workflows" job fails every PR (zizmor exit 14: excessive-permissions ×4, self-repository ×3) plus versionCode skew. No M3 change can be trusted until this is green.
- **P1 — no device verification possible.** No ADB/capture in-session; 54-screen + 166-RPC parity, predictive back, 12.1" adaptive nav, TalkBack, 120 Hz jank (W07_THEME) and HyperOS 3 backend selection (W03_WEB requires M1 capture) are unverifiable here.
- **P1 — package/naming conflict** `…dextop.repo` (W06_REPO1/2) vs `…dextop.repository` (W06_REPO3) must be consolidated before any `ui/` import.
- **P1 — RPC count discrepancy.** Brief/skill say Linux = 90 → 166 total; W06_REPO3 enumerates **95** distinct literals (91 dispatch arms). Golden list must be generated from source, not the doc, or ~5 RPCs are silently dropped.
- **P1 — no native Compose theme/adaptive nav** (W07_THEME: no window-size-class/material3-adaptive, raw colors, 44dp targets); Flutter host flags must not be ported (API 36 ignores orientation locks ≥600dp; predictive back bypasses `onBackPressed` — W07_WEB).

## Exact prerequisites (all green before ANY Flutter deletion)
1. CI gate fixed: W21_CI P0-1/P0-2 + versionCode plumbing; `safety.yml`, `pr-checks`, debug/release APK green on Actions.
2. W08_RETIRE step 0 anchor: `pre-flutter-retirement` tag + `flutter-legacy` branch + pinned Flutter 3.47.7/Dart 3.13 + last-green APK asset.
3. Parity gate (W08_RETIRE step 1): generated 166/171-name `RpcGolden.kt`, `NativeCallAdapter` with direct native delegates (MirrorService, DesktopSessionFsm, LinuxContainerClient, BookStore), 54-screen test matrix + 7-locale key/placeholder parity; `ui/DextopActivity` + `.NativeLauncherAlias` toggle green with Flutter alias still enabled.
4. Data decoupling (step 2): `DextopPrefs` preserving file/prefix + migration test; l10n catalogs relocated to `tool/l10n/`, generators repointed, app+cardex builds green.
5. Native bridges (step 3): `DextopShortcuts` callback, plain `LinuxContainerClient.Listener`, Shizuku lifecycle moved to `UiHost.attach/detach`.
6. Repo packages consolidated; `kotlinx-coroutines-android/-test` declared explicitly (W06_REPO1 P2).
7. On-device sign-off on Redmi Pad Pro 5G / API 36 / HyperOS 3: capability capture committed, all 54 screens + host cutover (step 4) verified.

## Smallest viable first step (does NOT break the release pipeline)
**W08_RETIRE step 0 only — anchor the fallback.** `git tag pre-flutter-retirement`; branch `flutter-legacy`; record pinned Flutter 3.47.7 / Dart 3.13 in `docs/retirement.md`; attach the last green Flutter debug APK as a Release asset. Zero source/Gradle/workflow edits → pipeline untouched; revert = nothing. This is the *only* M3-adjacent step safe to take this session. Do not proceed to steps 1–9 in-session: step 1 onward requires Actions builds (token) and a device.

## Recommendation
Do **not** start M3 implementation. Take step 0, then queue W06/W07/W08 designs and the ordered W08_RETIRE plan for a session that has a green CI gate, GitHub Actions runs, and the target tablet. If pressured to produce M3 code, cap it at steps 0–2 groundwork that keeps Flutter shipping; **never** steps 5–9 (Gradle de-Flutter, manifest/CI cleanup, artifact deletion) without the checklist above green. Stacking a Flutter removal on a red gate and an unverified 171-RPC surface risks a release that cannot build, loses all user settings, and drops RPCs.

## Risks / device testing needed
- Steps 5–7 reverted together is the only escape hatch; after step 8 deletion, settings/l10n/RPC loss is not recoverable from a single revert.
- On target device: launch/deep links/tiles/shortcuts/binder death after cutover; predictive back + insets; extended rail + list-detail at ~1600dp; TalkBack order; 120 Hz springs; dynamic color/high contrast over wallpaper; external-display size-class transitions; beta→stable→beta versionCode upgrade order (W21_CI P1).
- `adb`-based capability capture must be committed before any backend E/F decision (M4 dependency).
