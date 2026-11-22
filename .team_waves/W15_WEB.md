# Web Agent W15C — Wave 15 Report

## Scope inspected
Web-only research (no builds/source/token) on Android performance/QA tooling and CI workflow supply-chain safety, scoped to Dextop M8 (Xiaomi Redmi Pad Pro 5G, Android 16/API 36, HyperOS 3, 12.1" 120 Hz, arm64-v8a, minSdk 33). Topics: Perfetto capture, FrameTimeline/JankStats, Simpleperf, Macrobenchmark, gfxinfo/SurfaceFlinger latency, batterystats, zizmor/pinact.

## Findings (P0/P1/P2/P3 — tooling/QA gaps for M8)

- **P0 — 120 Hz frame budget mis-set.** Default 60 Hz heuristics (16.6 ms) are wrong for this tablet: at 120 Hz the VSync period is ~8.33 ms (8333333 ns). Any jank gate must derive the budget from the actual refresh/VSync (and tolerate variable refresh), not hardcode 16.6 ms. FrameTimeline's Expected Timeline already uses the scheduler's predicted present time; use it as ground truth. (C1,C2,C7)

- **P0 — HyperOS invalidates the legacy FPS probe.** `dumpsys SurfaceFlinger --latency <layer>` (127-frame ring, 3 columns: desiredPresent/actualPresent/frameReady) is reported to stop emitting frames on Android 14+/HyperOS builds. A QA pipeline that relies on it silently returns nothing. Use `SurfaceFlinger --timestats` (enable/dump/clear) as the working per-layer source on HyperOS 3, or FrameTimeline/JankData. (C7,C8)

- **P1 — Surface-backed desktop path is invisible to FrameTimeline.** Perfetto FrameTimeline explicitly does not support `SurfaceView`. Dextop backends C/D (composited virtual display / Wayland) and the AHB zero-copy path use independent surfaces/layers; per-frame jank there needs `SurfaceControl.OnJankDataListener` (API 36) correlated by VSync ID and manual `Trace`/`AChoreographer` annotations — not just the view-level FrameTimeline UI. Also register `rendering_topology` so content layers get their own data. (C2,C3,C4)

- **P1 — API-36 jank attribution available but unwired.** `SurfaceControl.JankData`/`OnJankDataListener` (API 36) gives batched, async composer classifications (`JANK_APPLICATION`/`JANK_COMPOSER`/`JANK_OTHER`) with `getVsyncId()` to join against `FrameMetrics.FRAME_TIMELINE_VSYNC_ID` and `getScheduledAppFrameTimeNanos()`. `AppJankStats` (API 36) gives per-widget janky/total frame counts. These are the correct on-device signals for the target; nothing in M8's plan consumes them. (C3,C4)

- **P1 — native data plane unprofilable in CI.** `libdextop_core.so` (Rust) and the retained C fastpath/PRoot/preload need native CPU profiling. Simpleperf on arm64 works best with frame-pointer (`--call-graph fp`) call graphs; DWARF (`-g`) is default/reliable but costlier. Stripped release libs yield unknown symbols — CI must keep an unstripped `binary_cache`/`--symfs`. Nothing yet captures flamegraphs for the native hot paths (BUG-04 epoll spin, BUG-05 broker). (C9,C10,C11)

- **P1 — no reproducible perf regression gate.** Macrobenchmark is the only lab tool with stable JSON + per-iteration Perfetto traces for CI thresholding; results are "fuzzy" (min/median/max, p50/p90/p95/p99, `frameOverrunMs` on API 31+). Emulator results are invalid; need a real, consistent device and a threshold parser (`jq`/script) that fails the job. No macrobenchmark module or gating exists in the M8 plan. (C12,C13,C14)

- **P1 — detached-watchdog power cost unmeasured.** The 15 s detached watchdog plus mirror session can hold wakelocks/keep CPU and display awake. No batterystats baseline: need `dumpsys batterystats --reset` → on-battery run → `--enable full-wake-history` → `adb bugreport` to catch a lock held after screen-off. (C15,C16,C17)

- **P2 — workflow supply-chain risk.** GitHub Actions workflows (build/test/release for M8 sign-off) must be zizmor-scanned and all `uses:` pinned to SHAs. Unpinned tags/branch refs, excessive permissions, template injection, and impostor commits are the top findings; `permissions: {}` + per-job least privilege required. (C18,C19,C20,C21)

- **P2 — CI trace collection fragility.** Perfetto config path rules differ by version: on Android 12+ place configs in `/data/misc/perfetto-configs`; for long/CI traces use `PID=$(perfetto --background)` then `kill $PID` (Ctrl+C is not propagated over `adb shell`). Machine-to-machine configs should be binary protobuf, not `--txt`. (C1,C5,C6)

- **P3 — Battery Historian maintenance.** google/battery-historian archived Dec 2022; the `stable:3.1` container can fail to pull/run on modern hosts. Prefer `dumpsys batterystats` text/checkin + Perfetto `android.power` data source over a hosted/untrusted Historian; bugreports contain sensitive data. (C16,C17)

## Deliverable — web citations (grouped by topic)

**Perfetto capture (Android 16)**
- C1 Perfetto system tracing / record_android_trace: https://perfetto.dev/docs/getting-started/system-tracing
- C5 perfetto CLI (light/normal mode, `-t/-b/-s/-a`, `-c/--txt`, background `-D/--background`): https://perfetto.dev/docs/reference/perfetto-cli
- C6 Trace config + Android caveats (`--txt` only Q+, `/data/misc/perfetto-configs` on 12+, `PID=$(perfetto --background)`; don't use `--txt` for CI): https://perfetto.dev/docs/concepts/config
- C6b Advanced Android tracing (traced enabled R+, exclusive mode, full config): https://perfetto.dev/docs/learning-more/android
- C6c On-device System Tracing app / pull `/data/local/traces/`: https://developer.android.com/topic/performance/tracing/on-device
- C6d Android overview of tracing options: https://developer.android.com/topic/performance/systrace

**FrameTimeline / JankStats**
- C2 Perfetto FrameTimeline (Android 12+; Expected/Actual timelines, jank types, `actual_frame_timeline_slice` SQL, SurfaceView unsupported): https://perfetto.dev/docs/data-sources/frametimeline
- C3 `SurfaceControl.JankData` / `OnJankDataListener` (API 36; VSync ID join to FrameMetrics): https://developer.android.com/reference/android/view/SurfaceControl.JankData
- C3b `AppJankStats` (API 36): https://developer.android.com/reference/android/app/jank/AppJankStats
- C4 JankStats library + heuristics/state/listener; metrics-performance 1.0.0; more accurate from API 31: https://developer.android.com/topic/performance/jankstats ; https://developer.android.com/reference/androidx/metrics/performance/JankStats ; https://developer.android.com/jetpack/androidx/releases/metrics
- C4b Compose performance Expected vs Actual timeline colors (green/red/light-green/yellow) and variable refresh: https://developer.android.com/codelabs/jetpack-compose-performance
- C4c Field frame-monitoring checklist (JankStats default entry, bounded queues, VSync-ID join, rendering_topology; API-36 semantic buckets): https://wiki.androidperformance.com/part5-app/ch22-rendering-practice/10-frame-monitoring.html

**Simpleperf**
- C9 Simpleperf tips (report --sort dso/symbol, `report -g`, ARM64 notes): https://developer.android.com/ndk/guides/simpleperf.html
- C10 AOSP app profiling (`app_profiler.py -p <pkg> -a .Activity`, `-r "-g"`, `--call-graph fp`, `report_html.py`, `inferno.sh -sc`): https://android.googlesource.com/platform/system/extras/+/main/simpleperf/doc/android_application_profiling.md
- C11 Scripts reference (`app_profiler.py -np surfaceflinger`, binary_cache/symfs, report_html): https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/scripts_reference.md
- C11b Simpleperf doc index (ARM64 fp callgraph preferred, missing-symbol/--symfs fix): https://android.googlesource.com/platform/system/extras/+/main/simpleperf/doc

**Macrobenchmark**
- C12 Write a Macrobenchmark (`MacrobenchmarkRule.measureRepeated`, `./gradlew :macrobenchmark:connectedCheck`, UI Automator, discouraged on emulator): https://developer.android.com/macrobenchmark
- C13 FrameTimingMetric (`frameDurationCpuMs` p50/90/95/99; `frameOverrunMs` on API 31+), StartupTimingMetric TTID/TTFD; real-device requirement: https://developer.android.com/codelabs/android-macrobenchmark-inspect
- C14 Benchmark in CI (fuzzy results, real devices, build phases, `lockClocks` only for micro, JSON + trace collection via `adb pull`): https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci
- C14b Baseline Profile benchmarking with Macrobenchmark (`CompilationMode`, TTID/TTFD, JSON for CI): https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
- C14c FTL + GitHub Actions sample setup: https://github.com/android/performance-samples/blob/main/MacrobenchmarkSample/ftl/README.md
- C14d Macrobenchmark uses `dumpsys gfxinfo` bracketed around a user journey: https://developer.android.com/topic/performance/measuring-performance

**gfxinfo / SurfaceFlinger latency**
- C7 `dumpsys` gfxinfo / framestats / batterystats reference: https://developer.android.com/tools/dumpsys
- C8 `SurfaceFlinger --list`, `--latency-clear`, `--latency` (127 rows, 3 ns columns; 120 Hz ≈ 8333333 ns; INT64_MAX = pending): https://digitash.com/google/android/use-dumpsys-surfaceflinger-command-adb-measure-frame-latency
- C8b HyperOS/Android 14+ caveat + working `--timestats` fallback + gfxinfo fallback (FpsMonitor): https://raw.githubusercontent.com/fortifying/DroidPerf/main/app/src/main/java/com/droidperf/monitoring/fps/FpsMonitor.kt
- C8c `gfxinfo framestats` CSV format / FLAGS / `INTENDED_VSYNC`→`FRAME_COMPLETED`: https://android.googlesource.com/platform/frameworks/base/+/562ae3a/docs/html/training/testing/performance.jd

**batterystats**
- C15 Battery Historian setup (`batterystats --reset`, `--enable full-wake-history`, `adb bugreport`, unplug device): https://developer.android.com/topic/performance/power/setup-battery-historian
- C16 `dumpsys batterystats --checkin`/`-c` CSV, per-UID stats; note `-c` vs `--checkin` semantics: https://developer.android.com/tools/dumpsys
- C17 Battery Historian archived Dec 2022 / prefer text + Perfetto `android.power`, bugreport sensitivity: https://wiki.androidperformance.com/part3-tools/ch15-other-tools/05-battery-historian.html ; repo https://github.com/google/battery-historian
- C17b OEM `power_profile.xml` caveat (estimates, not measured current): https://source.android.com/docs/core/power

**zizmor / pinact workflow safety**
- C18 zizmor (SAST for GitHub Actions): https://zizmor.sh/ ; quickstart https://docs.zizmor.sh/quickstart
- C19 Audits (`unpinned-uses`, `impostor-commit`, `stale-action-refs`, `unpinned-tools`, `superfluous-actions`, `excessive-permissions`): https://docs.zizmor.sh/audits
- C20 Integrations / `zizmorcore/zizmor-action`, SARIF, `permissions: {}`, pinned SHAs: https://docs.zizmor.sh/integrations ; https://github.com/zizmorcore/zizmor-action
- C21 pinact + zizmor in the pinning-tool ecosystem (also frizbee/ratchet/scorecard/Dependabot/Renovate): https://www.cncf.io/blog/2026/05/04/securing-github-actions-ci-dependencies-recipe-card
- C22 OWASP GitHub Actions Security Cheat Sheet (pin all actions to commit hashes, verify impostor commits, least privilege, CodeQL `language: actions` + zizmor, block merge on high/critical): https://cheatsheetseries.owasp.org/cheatsheets/GitHub_Actions_Security_Cheat_Sheet.html

## Risks / device testing needed — recommended CI + device QA plan for M8

**CI (GitHub Actions only; no local builds; never expose the token):**
1. Add `.github/workflows/perf-ci.yml` with `permissions: {}` at top, per-job least privilege; run on PR + nightly. All `uses:` pinned to SHAs via `pinact`; add `zizmorcore/zizmor-action` (pinned) with `--format=sarif` → code scanning, block merge on high/critical; enable CodeQL `language: actions`. (C18–C22)
2. Build release + benchmark variants on Linux runners only (per brief). Do NOT pass repo URL/token into steps; use `${{ secrets.GITHUB_TOKEN }}` only where needed and never echo it.
3. Device-lab job on a physical, consistent Redmi Pad Pro 5G (ADB over Wi-Fi to avoid USB charging during power tests). Same device/fingerprint/API 36 across runs; record fingerprint + SDK in every artifact (Macrobenchmark JSON already does). (C14)
4. Gate on Macrobenchmark JSON: parse `startup` (TTID/TTFD), `FrameTimingMetric` (`frameDurationCpuMs` p90/p99, `frameOverrunMs`) with `jq`, fail on threshold regression vs a stored baseline; archive per-iteration Perfetto traces as artifacts. Use baseline profiles with `CompilationMode.Partial(Require)` vs `None`. Lock clocks only if using microbenchmarks on a rooted/userdebug device. (C12–C14)

**Device QA matrix (on the Redmi Pad Pro 5G):**
5. Frame/jank: capture Perfetto with FrameTimeline + atrace `gfx/view/input/wm` + `sched/freq` during startup, scroll, screen transition, and desktop-session attach. For surface-backed backends C/D and AHB path, attach `OnJankDataListener` + VSync-ID join and manual `Trace` sections; verify jank attribution app vs composer. Judge against 8.33 ms budget, not 16.6 ms. (C1–C4,C7)
6. Cross-check on-device: `SurfaceFlinger --timestats` (HyperOS working source) + `dumpsys gfxinfo <pkg> framestats`; treat `--latency` as best-effort only. (C7,C8)
7. Native hot paths: Simpleperf `app_profiler.py -p <dextop.pkg> -r "-e task-clock:u -f 1000 --duration 10 --call-graph fp"` with unstripped `libdextop_core.so`/C libs via `-lib`/`--symfs`; `report_html.py`/`inferno` flamegraphs for BUG-04/05 and PRoot/fastpath. Profile `surfaceflinger` with `-np` for composition cost. (C9–C11)
8. Battery: `batterystats --reset` → `--enable full-wake-history` → unplug, run 15 s detached-watchdog + mirror scenario → `adb bugreport bugreport.zip`; inspect wakelocks/top_app/last-charge; keep parser keyed on build fingerprint. Prefer local text/Perfetto `android.power` over hosted Historian. (C15–C17)
9. Functional/security sign-off: run zizmor locally pre-merge (`zizmor --collect=all .`, plus `zizmor <remote>@<ref>` with GH_TOKEN) so workflow findings never reach the protected branch. (C18–C22)

**Residual risks:** HyperOS may throttle or alter SurfaceFlinger/timestats interfaces between builds — pin a device firmware/fingerprint and re-baseline on update. FrameTimeline excludes SurfaceView; native Wayland layers require manual instrumentation to be observable. OEM `power_profile.xml` gives estimated (not measured) power, so use rail/instrument data for short-window power claims. Perf trace artifacts and bugreports can contain sensitive data — store in trusted, access-controlled CI artifacts only.
