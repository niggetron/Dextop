# CI Audit — Wave 21 Report

## Scope inspected

All 11 `.github/workflows/*.yml` (assign-author, beta-release, debug-apk, macos-compat, manual-release, pages, pr-checks, pr-labels, release-apk, release, safety), `.github/release-please/{config,manifest}.json`, `pubspec.yaml:4`, `android/app/build.gradle.kts:44-51,78-79`. Verified against the exact gate versions pinned in `safety.yml`: zizmor 1.32.0, pinact 5.0.2 (checksums of all three tool tarballs match). Ran the gate commands offline on copies; no builds, no source edits, no token.

Verified clean: all 9 distinct external action SHAs match their `# vX` comments (`git ls-remote`); `pinact run --check` exits 0 on the 10 files the gate checks; `actionlint` 1.7.12 exits 0; no `${{ }}` inside any `run:` block (all untrusted values go through `env:` → no template-injection); all reusable-workflow `with:` inputs are declared/string-typed; release-please config validates against the v5 schema and `tag_name`/`version` are valid root-component outputs; `release-apk.yml` has no `concurrency` so the shared `android-gradle` group does not self-deadlock.

## Findings

### P0 — `safety.yml` "Workflows" job fails on every PR (verified: `zizmor --offline` exit 14, 7 findings)

**P0-1 `excessive-permissions` (4× High)** — `beta-release.yml:14`, `manual-release.yml:21`, `release.yml:10-11`.
Why: 2 jobs share a workflow-level write permission and neither job re-declares it; zizmor 1.32.0 emits at Regular persona (not pedantic-gated), severity High for `contents`/`pull-requests`. This is a regression from the recent concurrency edits touching exactly these files. GitHub would also hand `contents: write` to the release-please job's third-party action unnecessarily.
Fix — set top-level `permissions: {}` and scope per job (the reusable `release-apk` job **must** get `contents: write` explicitly, otherwise its token is empty and `gh release upload` fails):

```yaml
# beta-release.yml / manual-release.yml
permissions: {}
...
  publish:
    permissions:
      contents: write
...
  release-apk:
    permissions:
      contents: write
```

```yaml
# release.yml
permissions: {}
...
  release-pr:
    permissions:
      contents: write
      pull-requests: write
...
  release-apk:
    permissions:
      contents: write
```

**P0-2 `self-repository` (3× Low)** — `beta-release.yml:70`, `manual-release.yml:78`, `release.yml:107`.
Why: zizmor ≥1.30 (gate uses 1.32.0) flags workspace-relative `uses: ./.github/workflows/release-apk.yml`; any earlier step could replace the file on disk. Any of the three findings makes the job fail.
Fix: `uses: $/.github/workflows/release-apk.yml` in all three (no `@ref`; resolves at the running commit). Verified: with P0-1 + P0-2 applied to a temp copy, zizmor → `No findings to report`, exit 0; pinact → exit 0.
Caveat: actionlint ≤1.7.12 rejects `$/` (`workflow-call`), but safety.yml never runs actionlint and GitHub-hosted runners (≥2.336.0) support it. If hardening policy requires SHA-pinned reusable calls instead, use `owner/repo/.github/workflows/release-apk.yml@<sha>` — but then every edit to that file needs a SHA bump; `$/` is preferable here.

### P1 — beta release APK gets a lower `versionCode` than every other release path

`beta-release.yml:71-73` passes only `tag` + `version_name`; `release-apk.yml:103-110` therefore falls back to `version_code = base` from pubspec at the tag. `manual-release.yml:64-65,82` passes `base + 1`, and `release.yml:86-97` writes `build + 1` into the release PR so its tag's pubspec also yields `base + 1`. Result: a beta cut from the same main as `2.0.0-beta.4+45` gets versionCode 45 while a manual/stable release gets 46 — Android refuses to install the beta as an update ("app not installed"). This matters for the tablet's side-by-side release assets.
Fix: make every caller pass an explicit `version_code = <pubspec build> + 1` and stop relying on the call-only fallback. In `beta-release.yml`, add a checkout (`persist-credentials: false`) to `publish`, output the code, and forward it:

```yaml
    outputs:
      tag: ${{ steps.publish.outputs.tag }}
      version: ${{ steps.publish.outputs.version }}
      version_code: ${{ steps.meta.outputs.version_code }}
...
      - name: Compute version code
        id: meta
        run: |
          base=$(awk -F'+' '/^version:/{print $2; exit}' pubspec.yaml)
          echo "version_code=$((base + 1))" >> "$GITHUB_OUTPUT"
...
      with:
        tag: ${{ needs.publish.outputs.tag }}
        version_name: ${{ needs.publish.outputs.version }}
        version_code: ${{ needs.publish.outputs.version_code }}
```

For `release.yml`, add `echo "version_code=$((build + 1))" >> "$GITHUB_OUTPUT"` to the "Fix the version line" step, expose it as a `release-pr` job output, and pass it as `version_code` to `release-apk.yml`.

### P2 — release-please release/APK attach no longer happens on the release-PR merge

`release.yml:3-7` is now `workflow_dispatch`-only (commit `5c1bf6c`) but `release.yml:104` still assumes "Release Please emits a tag only on the push that merges the release PR." With no `push` trigger, that push runs nothing; the `release-apk` job at `release.yml:101-110` is gated on `released_tag != ''` and is skipped. A release + APK now requires a manual re-dispatch *after* merging the PR, with nothing enforcing the order.
Fix: either restore `push: { branches: [main] }` alongside `workflow_dispatch`, or delete the `release-apk` job from `release.yml` and document `manual-release.yml` as the only supported publish path.

### P2 — macos-compat.yml is unpinned and hidden from the gate

`macos-compat.yml:31` `actions/checkout@v4` and `:129` `actions/upload-artifact@v4`; `safety.yml:80-84` excludes the file. Verified: including it makes zizmor exit 14 (`unpinned-uses`, 2× High) and pinact exit 1. Fix: pin both to the SHAs already used in the repo (`11d5960a… # v4.4.0`, `ea165f8d… # v4.6.2`) and drop `! -name macos-compat.yml` from the `find` in `safety.yml:83`.

### P3 — release-please config notes (no schema errors)

`.github/release-please/config.json` is schema-valid; `versioning: prerelease` + `prerelease-type: beta` + `prerelease: true` and `include-component-in-tag: false` are consistent with tags `v<version>` produced by the release workflows. Two fragilities: (a) `release.yml:84-90` hardcodes branch `release-please--branches--main--components--dextop` and a regex that requires `+<int>` in pubspec — it raises `AttributeError` on any version line without `+build`; rewrite with `re.search(...) or raise SystemExit('unexpected pubspec version')`. (b) `changelog-sections` `type: deps` never matches Dependabot's `chore(deps):` commits (they land under `chore`); harmless, but drop the dead entry or key it on `chore`+scope. Manifest `2.0.0-beta.4` has no local tag to cross-check, so verify tag `v2.0.0-beta.4` exists before the next release-please run.

### Non-findings (checked, no action)

Permissions on `assign-author.yml`, `pr-labels.yml`, `safety.yml` are already `{}` + job-scoped; `pages.yml`/`release-apk.yml` top-level writes are single-job/reusable-only → pedantic-suppressed. No `pull_request_target`/`workflow_run`/`toJSON(secrets)`/`GITHUB_ENV` sinks, no `contains()` on string lists, no `&&`/falsy-ternary patterns, all SHA comments correct, all tool checksums correct.

## Deliverable

Exact patch set = P0-1 (permissions blocks) + P0-2 (`$/` reusable calls) + P1 version_code plumbing + P2 trigger decision + macos pin/exclusion removal. Verification performed on `/tmp/opencode/wftest` copy:

```
zizmor --offline $(find .github/workflows -name '*.yml' ! -name macos-compat.yml | sort)   # exit 0 after fix
pinact run --check <same files>                                                            # exit 0 before and after
actionlint -color=false                                                                   # exit 0 on current repo
```

No source files were modified; no builds run.

## Risks / device testing needed

- `$/` requires Actions runner ≥2.336.0 (GitHub-hosted `ubuntu-latest` is fine; do not reintroduce self-hosted runners without checking).
- After the version_code fix, install beta.4 → stable → beta.5 in order on the Redmi Pad Pro 5G and confirm each is treated as an upgrade (the current beta APK can be a downgrade and will be rejected).
- Confirm the suffixed package (`moe.n4tsu.dextop.r<hash>`) still co-installs side by side and that `--clobber` does not drop notes from other releases.
- If `push: [main]` is restored in `release.yml`, re-check that it and `debug-apk.yml` serialize on `android-gradle` without a queued-run timeout.
