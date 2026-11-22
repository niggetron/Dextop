# Release-notes bucketing verification — Wave 25

## Scope inspected
`tool/generate_release_notes.py`, `.github/release-please/config.json`, `CHANGELOG.md` (beta.4 header), `.github/workflows/release-apk.yml`, `.github/workflows/manual-release.yml`, `git log`.

## Range resolution
- No local tags exist; remote tag `v2.0.0-beta.3` → `efb6bd314e76ced1abbe22cb7af6f9d8db2aa91b` (`git ls-remote --tags`).
- `previous = git describe --tags --abbrev=0 "<TARGET>^"` (manual-release.yml:62, release-apk.yml:134) resolves to `v2.0.0-beta.3` for HEAD `d0194ff` (no newer tags). Generator range = `efb6bd3..HEAD` = **18 commits**.
- Generator mapping is byte-equivalent to `changelog-sections` in config.json; no cross-config drift.

## Exact commits and sections (generator output, `python3 tool/generate_release_notes.py efb6bd3 HEAD`)
| Commit | Subject | Section |
|---|---|---|
| 4af285f | docs: record the 2.0.0-beta.3 rebuild in the changelog [skip ci] | **DROPPED** (`docs` unmapped) |
| b743936 | fix(m0): crash-safe WAL journal and off-main-thread windowing IPC | Fixed |
| 68dfb6b | fix(m0): native epoll spin, AHB broker and swipe LRU races | Fixed |
| 1c93e28 | feat(m1): read-only capability snapshot with backend recommendation | Added |
| 725533d | feat(m2): deterministic desktop session FSM and scoped Xiaomi workarounds | Added |
| d0463d3 | fix: resolve CI compile and lint errors in M1/M2 additions | Fixed |
| f98e943 | fix: FSM test lifecycle sequence | Fixed |
| 478f047 | feat(m2): detached 15s rollback watchdog with WAL arming | Added |
| 34d212d | fix: watchdog protocol error key accepted by localization lint | Fixed |
| 886c9c6 | fix: watchdog token via env for Shizuku path | Fixed |
| 78088f9 | fix: allow 5-arg wm watchdog commands | Fixed |
| 565d711 | fix: open WAL phase on begin and delete absent overlay key on restore | Fixed |
| 32b9f5a | fix: watchdog ARM over persistent socket with heartbeat and fail-open liveness | Fixed |
| 8e47213 | fix: journal forced metrics and Secure accessibility writes; fix release version skew | Fixed |
| f60fce3 | fix: watchdog test uses a still-forbidden metacharacter | Fixed |
| 4189856 | docs: honest implementation status (M0-M2 shipped, M3-M8 designed only) | **DROPPED** (`docs` unmapped) |
| c95c824 | ci: fix zizmor findings and beta versionCode skew | Upgraded |
| d0194ff | docs: final version and feature-flag consistency fixes | **DROPPED** (`docs` unmapped) |

Result: Added 3, Fixed 11, Upgraded 1 = 15 emitted; **Removed 0, Enhanced 0**.

## Findings
- **P1 — broken reusable-workflow reference:** `manual-release.yml:81` `uses: $/.github/workflows/release-apk.yml`. Local reusable workflows must start `./`; `$/...` is parsed as a remote ref and will not resolve. The `publish` job still creates the Release and notes, but the `release-apk` job fails → no APK attached. Fix: `uses: ./.github/workflows/release-apk.yml`.
- **P2 — only 3 of 5 requested sections appear:** generator skips empty buckets (lines 77–78), so `Removed` and `Enhanced` are absent. No `revert`/`refactor`/`perf` commit exists in range. If all five headers are required, fix by emitting them unconditionally (or add explicit mapping commits); otherwise this is correct-by-data.
- **P2 — 3 commits dropped:** `docs` is absent from `TYPE_TO_SECTION` and config `changelog-sections`; 4af285f, 4189856, d0194ff vanish entirely. Fix: add `docs` mapping (e.g. → Enhanced or a Documentation section) or re-type those commits, if "no drops" is a hard requirement. (They are doc-only; consistent with release-please default hidden `docs`.)
- **P2 — CHANGELOG beta.4 block is misleading:** `CHANGELOG.md:3–55` lists Added/Fixed/Removed/Enhanced/Upgraded bullets but its own note (lines 5–14) admits they were carried over from beta.3. Only the 15 commits above are actually in the new range; the beta.4 `Removed`/`Enhanced` bullets describe pre-tag work, so they will not be in the generated notes.
- **P3 — semantic (not config) mis-bucket:** `c95c824 ci: fix zizmor findings…` lands in Upgraded because `ci → Upgraded`; text reads like a fix but mapping is config-consistent, so leave as-is unless taxonomy intent differs.

## Correctness checks that passed
- `SUBJECT` regex parses all `fix(m0)`, `feat(m1)`, `feat(m2)` scopes correctly; `SKIP` (`release|merge`) drops nothing in range; no duplicate entries; reverse-chronological order matches generator; `git describe` prefix stripping and tag boundary (beta.3 commit excluded) are correct; version validation regex accepts `2.0.0-beta.4`.
