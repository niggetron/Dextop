# Git-State Audit — Wave 26 (Final)

## Scope inspected
`git status/log/diff/show-ref`, `efb6bd3..HEAD` (v2.0.0-beta.3 tag boundary), `.gitignore`, filesystem scan for secrets/build artifacts, master log `/root/.oc-opencode2/data/opencode/log/opencode.log` (31 MB, outside repo). No builds, no source edits, no token handled.

## Baseline / range
- No local tags fetched. Remote `v2.0.0-beta.3` = `efb6bd314e76ced1abbe22cb7af6f9d8db2aa91b` (per W25_NOTES; not re-queried — no token use). Changelog commit `4af285f` records the beta.3 rebuild but is itself inside the range.
- `HEAD = origin/main = d0194ff` (d0194ff873ec4fe3d597d69a885b08e769aa2693). Clean, fully pushed.
- Range `efb6bd3..HEAD` = **18 commits** (release-notes generator boundary; beta.3 excluded).

## Commits since v2.0.0-beta.3 (oldest → newest)
| Commit | Subject | Milestone |
|---|---|---|
| 4af285f | docs: record the 2.0.0-beta.3 rebuild in the changelog [skip ci] | docs |
| b743936 | fix(m0): crash-safe WAL journal and off-main-thread windowing IPC | M0 |
| 68dfb6b | fix(m0): native epoll spin, AHB broker and swipe LRU races | M0 |
| 1c93e28 | feat(m1): read-only capability snapshot with backend recommendation | M1 |
| 725533d | feat(m2): deterministic desktop session FSM and scoped Xiaomi workarounds | M2 |
| d0463d3 | fix: resolve CI compile and lint errors in M1/M2 additions | M1/M2 fix |
| f98e943 | fix: FSM test lifecycle sequence | M2 fix |
| 478f047 | feat(m2): detached 15s rollback watchdog with WAL arming | M2 |
| 34d212d | fix: watchdog protocol error key accepted by localization lint | M2 fix |
| 886c9c6 | fix: watchdog token via env for Shizuku path | M2 fix |
| 78088f9 | fix: allow 5-arg wm watchdog commands | M2 fix |
| 565d711 | fix: open WAL phase on begin and delete absent overlay key on restore | M0/M2 fix |
| 32b9f5a | fix: watchdog ARM over persistent socket with heartbeat and fail-open liveness | M2 fix |
| 8e47213 | fix: journal forced metrics and Secure accessibility writes; fix release version skew | M0 + release |
| f60fce3 | fix: watchdog test uses a still-forbidden metacharacter | M2 fix |
| 4189856 | docs: honest implementation status (M0-M2 shipped, M3-M8 designed only) | docs |
| c95c824 | ci: fix zizmor findings and beta versionCode skew | CI |
| d0194ff | docs: final version and feature-flag consistency fixes | docs |

## Changed files (efb6bd3..HEAD): 134 files, +16,803 / −259
Grouped exact list under "What ships" below. Breakdown: 19 source/test/tool files, 7 release/docs/CI files, 22 `docs/architecture/*.md`, 13 `.agents/skills/*`, 73 `.team_waves/*.md`.

## Working-tree audit
- **Staged changes:** none. **Unstaged tracked edits:** none. → **No source edits left uncommitted.**
- **Untracked (all reports only, no source):** `.team_waves/W25_APK.md`, `W25_NOTES.md`, `W25_WEB.md`, `W26_HANDOFF.md`, `W26_WEB.md`. (Wave agents write reports into `.team_waves/`; these are report artifacts, not source.)
- **Ignored:** only `tool/__pycache__/` (Python bytecode from `generate_release_notes.py`). Not a build artifact of the app; harmless.
- **Secrets/local config:** `local.properties`, `android/local.properties`, `key.properties`, `.env`, `google-services.json`, `firebase_options.dart` — all absent on disk and none tracked. No `*.jks/*.keystore/*.p12/*.pfx/*.pem/*.key` tracked or present. `.gitignore` covers all of these.
- **Build artifacts:** no `build/`, `.gradle/`, `.cxx/`, `.apk`, `.aab`, `*.class`, `*.o`, or crash dumps. The 12 checked-in `*.so` under `android/app/src/main/jniLibs/arm64-v8a/` and `assets/deck-audio/modules/` are **tracked prebuilt bundled libs** (committed before this range), not locally generated artifacts.
- **Token hygiene:** PAT lives only in `.git/config` remote URL (never printed/stored). W26_HANDOFF documents stripping/rotating it before sharing the directory.
- **Authoritative progress record:** the master OpenCode log at `/root/.oc-opencode2/data/opencode/log/opencode.log` (outside the repo) is the authoritative progress record; 393 wave/Dextop progress markers. The git tree carries only committed source plus wave reports — it is not the progress record, and its cleanliness is consistent with that.

## What ships (grouped by milestone)
**M0 — BUG-01..06 (source, shipped):**
- `android/app/src/main/kotlin/moe/n4tsu/dextop/SessionJournal.kt` (M) — atomic WAL, all 7 SDK-36 `modernKeys`, fsync-before-mutate, reverse-order verified restore; forced metrics + Secure writes
- `android/app/src/main/kotlin/moe/n4tsu/dextop/DesktopModeConfigurator.kt` (M) — off-main cancellable `wm` IPC
- `android/app/src/main/kotlin/moe/n4tsu/dextop/MirrorService.kt` (M) — decomposition/tethering for WAL/watchdog
- `android/app/src/main/kotlin/moe/n4tsu/dextop/input/LaptopSwipeDecoder.kt` (M) — LRU synchronization
- `android/app/src/main/cpp/wayland/deck_direct.inc` (M) — epoll spin / FD-leak fix
- `android/app/src/main/cpp/wayland/deck_ahb_broker.inc` (M) — stack-buffer / dlopen / auth fix
- `android/app/proguard-rules.pro` (M)
- `android/app/src/test/kotlin/moe/n4tsu/dextop/SessionWalCodecTest.kt` (A)

**M1 — capability capture (source, shipped):**
- `android/app/src/main/kotlin/moe/n4tsu/dextop/CapabilitySnapshot.kt` (A)
- `tool/hyperos_capability_capture.sh` (A)
- `android/app/src/main/kotlin/moe/n4tsu/dextop/DiagnosticExport.kt` (M)
- `android/app/src/main/kotlin/moe/n4tsu/dextop/DiagnosticReport.kt` (M)

**M2 — FSM + watchdog (source, shipped):**
- `android/app/src/main/kotlin/moe/n4tsu/dextop/session/DesktopSessionFsm.kt` (A)
- `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogClient.kt` (A)
- `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogDaemon.kt` (A)
- `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogProtocol.kt` (A)
- `android/app/src/main/kotlin/moe/n4tsu/dextop/PrivilegedAccess.kt` (M) — token via env for Shizuku
- `android/app/src/test/kotlin/moe/n4tsu/dextop/WatchdogProtocolTest.kt` (A)
- `android/app/src/test/kotlin/moe/n4tsu/dextop/session/DesktopSessionFsmTest.kt` (A)

**M3–M8 — DESIGN ONLY (no source shipped; Flutter remains the shipping UI):**
- `docs/architecture/*.md` (22 files, e.g. `HIGH_LEVEL_DESIGN.md`, `BACKEND_ABSTRACTION_DESIGN.md`, `GRAPHICS_AND_RENDERING_ARCHITECTURE.md`, `IMPLEMENTATION_ROADMAP.md`, `SECURITY_AND_PRIVILEGE_MODEL.md`, …)
- `.agents/skills/dextop-{flutter-to-compose-parity,hyperos-oem-backends,privilege-wal-guard,proot-c-boundary,wayland-ahb-zero-copy}/SKILL.md` (5) + `.agents/skills/material-3/` (8: `SKILL.md`, `.claude-plugin/plugin.json`, 6 `references/*.md`)
- `.team_waves/*.md` — 73 wave reports (W01–W24 + BRIEF; W25/W26 pending commit). No Compose host, no `libdextop_core.so`, no backends A–F source, no portals/terminal/tiling.

**Release / CI / docs:** `CHANGELOG.md`, `README.md`, `pubspec.yaml` (2.0.0-beta.4+45), `.github/release-please/manifest.json`, `.github/workflows/{beta-release,manual-release,release}.yml`.

## Findings
- **PASS** — 18 commits since beta.3; all pushed; HEAD == origin/main.
- **PASS** — zero uncommitted/staged source edits; zero build artifacts; zero local.properties/keystores/secrets tracked or present.
- **PASS** — untracked items are report-only `.team_waves/*.md`; sole ignored item is `tool/__pycache__/`.
- **PASS** — master log outside repo is the authoritative progress record.
- **P2 (carry-over, not a git-state defect)** — beta.4 CHANGELOG bullets are admittedly carried over from beta.3 (W25_NOTES); 3 `docs` commits are dropped by the release-notes generator; `manual-release.yml:81` `uses: $/...` should be `./...`. See W25_NOTES/W24_CONSISTENCY. Out of scope for this audit.

## Risks / device testing needed
None from git state. No on-device validation was possible; Redmi Pad Pro 5G (Android 16 / HyperOS 3) run remains the first real test (see W26_HANDOFF).
