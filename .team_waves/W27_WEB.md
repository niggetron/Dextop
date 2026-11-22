# Web — Wave 27 Report

## Scope inspected
GitHub REST API (releases, tags, actions workflows/runs/jobs, commits, compare) and raw `main` files. No source edits, no token used. Checked 2026-11-22.

## Findings
- **beta.4 not published.** Only tag in repo: `v2.0.0-beta.3` → `efb6bd3`. Releases list has exactly one release: `v2.0.0-beta.3`, prerelease, published 2026-11-21T12:52:59Z by github-actions, asset `Dextop-2.0.0-beta.3-arm64.apk` (151,110,564 B, sha256 `c46a6d9f…`), 1 download. `GET /releases/tags/v2.0.0-beta.4` → 404.
  - https://api.github.com/repos/niggetron/Dextop/releases
  - https://api.github.com/repos/niggetron/Dextop/tags
  - https://github.com/niggetron/Dextop/releases/tag/v2.0.0-beta.3
- **`main` is ready but unreleased:** HEAD `d0194ff` ("docs: final version and feature-flag consistency fixes"), 18 commits ahead of beta.3; `pubspec.yaml` already says `version: 2.0.0-beta.4+45`.
  - https://api.github.com/repos/niggetron/Dextop/compare/v2.0.0-beta.3...main
  - https://raw.githubusercontent.com/niggetron/Dextop/main/pubspec.yaml
- **Last Debug APK run is green:** run #28 `45003446931`, head `d0194ff`, `status=completed`, `conclusion=success` (note: `run_attempt=2`; attempt 1 failed). Single job "Analyze, test, and build" all steps success incl. localization, analyze, Flutter/Linux/Android tests, `Build Debug APK`, companion APKs, upload artifact. Finished 2026-11-22T08:46:52Z. Prior run #27 also success.
  - https://github.com/niggetron/Dextop/actions/runs/45003446931
  - https://api.github.com/repos/niggetron/Dextop/actions/runs/45003446931/jobs
- **Release workflow state:** `Beta release` (`.github/workflows/beta-release.yml`) has **0 runs ever**. `Manual Release` (`.github/workflows/manual-release.yml`) has 2 runs: #1 failure, #2 **success** at `efb6bd3` — the run that created beta.3 + its APK. `Release APK` standalone has 0 runs (reusable only).
  - https://github.com/niggetron/Dextop/actions/workflows/manual-release.yml
  - https://github.com/niggetron/Dextop/actions/runs/44905522881
- **Watch item:** commit `c95c824` (2026-11-22T08:21Z) changed both release workflows' reusable call to `uses: $/.github/workflows/release-apk.yml` and added explicit `version_code = pubspec_build+1` (now 45+1=46). No release workflow has run since that change, so the `$/` call form is unexercised in a publish.
  - https://github.com/niggetron/Dextop/commit/c95c824bfef66ee65ec0c057506d582302e10766

## Deliverable — exact final action
1. Open https://github.com/niggetron/Dextop/actions/workflows/manual-release.yml → **Run workflow** → branch `main` → inputs: `version = 2.0.0-beta.4`, `prerelease = true` (default). This creates tag `v2.0.0-beta.4` at current `main` HEAD, generates deterministic notes, and triggers `release-apk.yml` to attach the arm64 debug APK (version_code 46). Prefer this workflow: it is the proven path for beta.3.
2. Confirm: `GET https://api.github.com/repos/niggetron/Dextop/releases/tags/v2.0.0-beta.4` → 200, `prerelease: true`, APK asset `Dextop-2.0.0-beta.4-arm64.apk`, and the workflow run conclusion `success`.
3. Fallback if the `uses: $/` call fails: dispatch `Beta release` at https://github.com/niggetron/Dextop/actions/workflows/beta-release.yml with `version = 2.0.0-beta.4` — but it uses the same `$/` call form, so a CI fix would then be required (outside this web-only task).

## Risks / device testing needed
- Both release workflows and Debug APK share concurrency group `android-gradle`: release will queue behind any Debug APK run; do not start them in parallel.
- Last Debug APK needed attempt 2; release build may flake similarly — rerun if needed.
- No on-device verification possible from web-only scope; Xiaomi Redmi Pad Pro 5G / Android 16 smoke test still required after install.
