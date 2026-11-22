# Secrets / Scratch-Artifact Sweep — Wave 24 Report

## Scope inspected
Full tracked tree (2,467 files, 313 commits), working tree incl. untracked/ignored, `.git/config`, `.gitignore`, `.gitleaks.toml`, `.env.example`, `.github/workflows/*` (11), `.team_waves/*` (70), `.agents/skills/*`, `docs/*`, `tool/*`, git history (`git log -S` for `ghp_`, `github_pat_`, `AKIA`, private-key headers, `api_key`, `client_secret`).

## Findings

- **P1 — Credential embedded in git remote.** `.git/config` → `remote.origin.url = https://<PAT>@github.com/niggetron/Dextop.git`. Not a tracked file, but a wholesale copy/fork of the working dir (including `.git`) leaks a live GitHub token. Value intentionally not printed/stored here. Fix: `git remote set-url origin https://github.com/niggetron/Dextop.git`, switch to `gh auth`/credential helper, rotate the PAT if the directory was ever shared; add a pre-share check (`git config --get remote.origin.url | grep '@'`).

- **P1 — Internal agent scratch artifacts tracked.** `.team_waves/` (70 files: `BRIEF.md`, W01–W23 reports) is committed and **not** gitignored. Contains internal process rules, codenames, absolute build paths (`/root/projects/Dextop/...`), and design deliberation not meant for a public fork. Fix: `git rm -r --cached .team_waves/`, add `.team_waves/` to `.gitignore`; if any report is worth keeping, promote the few into `docs/architecture/` first. Same treatment for `.agents/skills/` (13 repo-local skill files) if those are harness-private rather than intended public contributor skills.

- **P2 — `.gitignore` gaps.** Lines 1–20 correctly cover `.env`, `.env.*` (with `!.env.example`), `*.jks/*.keystore/*.p12/*.pfx/*.key/*.pem`, `key.properties`, `service-account*.json`, `play-credentials*.json`, `google-services.json`, `firebase*`. Missing: `.team_waves/`, generic `credentials*.json`, `.netrc`, `*.cer`, `*.der`, `*.gpg`, `secring.*`, and `*_capture*.txt` (device-capture dumps, cf. `hyperos-capture-*.txt`). Fix: append these patterns.

- **P3 — Personal data in git history metadata.** 13 distinct commit-author emails include personal addresses (e.g. Gmail/Protonmail) in addition to project/noreply addresses. Already public upstream; only actionable if rewriting history for a clean fork. Fix: `git filter-repo --mailmap` if a de-identified fork is required.

- **P3 — Runtime secret-handling defects (not leaked values).** `.team_waves/W15_SECURITY.md:16-25,37`, `W18_NATIVE.md:14-15`, `W19_WDREVIEW.md:37-38`: watchdog token passed via envp/argv and logged; diagnostics share path bypasses `sanitizeDocument`. Cross-reference only; no credential material in the repo itself.

## Clean
No private-key blocks, token literals, credential URLs, or high-entropy secret blobs in tracked files outside `third_party/`. `CardCompanionCallerVerifier.kt:16-24` are public certificate SHA-256 pins (not secrets). `.env.example` holds placeholders only. Workflows use `${{ secrets.* }}`/`github.token`, SHA-pinned actions, `persist-credentials: false`; no hardcoded secrets. `.gitleaks.toml` correctly allowlists vendored minified xterm.js. No `.env`, keystore, or `local.properties` present on disk. Tracked `.so`/`.exe`/`.gz` files are intended packaged assets.

## Risks / device testing needed
None for this sweep. Rotate PAT and remove `.team_waves/`/`.agents/` before publishing a fork; re-run `gitleaks git --config .gitleaks.toml` on the full history after cleanup.
