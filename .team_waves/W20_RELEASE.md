# Release Audit — Wave 20 Report

## Scope inspected
`.github/workflows/{release-apk,release,manual-release,debug-apk,beta-release,safety}.yml`, `.github/release-please/{config,manifest}.json`, `README.md`, `CHANGELOG.md`, `docs/architecture/*`, `android/app/build.gradle.kts`, `pubspec.yaml`, `tool/generate_release_notes.py`, `tool/hyperos_capability_capture.sh`, git status/tags (local + remote). No builds, no source edits, no token exposed.

## Findings

**P0 — version mismatch will break/misdirect the next release**
- `.github/release-please/manifest.json:2` = `2.0.0-beta.2`, but `CHANGELOG.md:3` already has a `2.0.0-beta.3` section and remote tag `v2.0.0-beta.3` exists (at `efb6bd3`), while `pubspec.yaml:4` = `2.0.0-beta.2+43` and local HEAD `32b9f5a` is 12 commits ahead with no tags fetched. Next release-please run derives `beta.3` from the manifest and collides with the existing tag/release → duplicate or skipped release. Re-running `release-apk.yml` on `v2.0.0-beta.3` builds stale code that does **not** contain the M0/M1/M2 fixes its own changelog entry lists (WAL, watchdog FSM, capability snapshot). Fix: pick one canonical version; bump manifest+pubspec to `beta.3` and move/retag `v2.0.0-beta.3` at HEAD, or bump to `beta.4` everywhere and add a `beta.4` changelog entry; regenerate notes from `generate_release_notes.py`.
- `README.md:176,221` claim “Current release: Dextop 2.0.0” / “Dextop 2.0.0 runs Debian 13”; repo is a prerelease (`beta.2`/`beta.3`). Fix: say “Current prerelease 2.0.0-beta.3 (beta.2 published)”.

**P1 — links and signing claims mislead fork users**
- `README.md:12,82,187,217,251` point to upstream `github.com/NarYuki/Dextop` while remote is `niggetron/Dextop`; “Get it on GitHub” and nightly links send users to the wrong repo. Only `:189,205` use the fork. Fix: repoint all release/actions/clone/wiki links to `niggetron/Dextop`.
- `README.md:187` says “latest **signed** APK”; fork assets are debug-signed per `release-apk.yml:5-9,145` and `README.md:192-196`. Fix: change to “debug-signed arm64 APK (fork notes below)”.
- `README.md:229` says Dextop verifies companion package + signing cert before Android Auto relay, but the github flavor package is `moe.n4tsu.dextop.r<hash>` (`build.gradle.kts:100`, `release-apk.yml:119`) while companions are fixed `moe.n4tsu.dextop.cardex(.driving)` (`README.md:229`) — companion trust/self-update cannot work for any fork asset. Workflow header admits this, README quick start does not. Fix: add explicit caveat at `README.md:227-231` that fork debug APKs cannot pair with Android Auto companions.
- `README.md:198` claims versionCode/versionName are “also bumped”; `release-apk.yml:104-110` only bumps on `workflow_dispatch`, so `beta-release.yml:70-72` and `release.yml:107-108` pass no `version_code` and reuse pubspec base (43). Fix: always bump in reusable workflow or drop the claim.

**P2 — workflow robustness / undocumented setup**
- `release-apk.yml:57-58` comment says callers serialize on `android-gradle`, but `release.yml:14` groups on `release-${{ github.ref }}` and `beta-release.yml:17` on `beta-release`; a release APK build can overlap `debug-apk.yml:15`/`manual-release.yml:24`. Fix: add `android-gradle` concurrency guard to those callers or correct the comment.
- `release.yml:4-6,27-37` needs repo setting “Allow Actions to create/approve PRs” and optional `DEXTOP_RELEASE_APP_ID`/`KEY`; `manual-release.yml` is the fallback but neither is documented in README. Fix: add a short release-process section.
- Blueprint mismatch: BRIEF mandates Kotlin+Compose + Rust `libdextop_core.so` and retiring Flutter; `build.gradle.kts:8`/`pubspec.yaml`/all workflows still build Flutter, and no `Cargo.toml` or `libdextop_core.so` exists. `CHANGELOG.md:3` calling this a “rebuild” is stale. Fix: align docs/release framing with what actually ships, or land the rebuild before labeling a release.

**P3 — missing M1 device capture**
- `tool/hyperos_capability_capture.sh` exists, but no `hyperos-capture-*.txt` output is checked in anywhere; `.team_waves/W03_WEB.md:84,99` says M1 ADB capture is mandatory before M4 backend selection and `docs/architecture/DEVICE_COMPATIBILITY_MATRIX.md:4` says Redmi Pad Pro 5G backend E+D is “unverified on HW”. `README.md:150-155` lists only POCO devices, not the target Redmi Pad Pro 5G, yet `:72,148` claim tested HyperOS 3 behavior. Fix: run the capture on the target tablet (Android 16 / HyperOS 3), commit a redacted capture under `docs/device-capture/`, and update README/matrix before claiming device-validated support.

## Fix list (ordered by importance)
1. Resolve version skew: manifest ↔ pubspec ↔ CHANGELOG ↔ remote tag; retag or bump to beta.4 and regenerate notes.
2. Correct README version claims, upstream links, and “signed” → “debug-signed”.
3. Add Android Auto companion caveat for suffixed/debug builds; fix the version-bump claim.
4. Fix concurrency grouping in release/beta workflows and document release-please vs manual-release setup.
5. Run/check in M1 capture for Redmi Pad Pro 5G; update device matrix and README Xiaomi table.
6. Reconcile Flutter-vs-Compose/Rust rebuild framing in CHANGELOG/README with shipped code.

## Deliverable
Audit only; no files outside this report were modified.

## Risks / device testing needed
- Publishing before step 1 risks a duplicate/wrong GitHub Release and an APK whose notes describe code it lacks.
- Fork users following README get upstream links and may expect a signed, updatable, Android Auto-compatible app; none hold for suffixed debug assets.
- All HyperOS 3 / Redmi Pad Pro 5G behavior claims remain unverified until M1 capture is performed on-device.
