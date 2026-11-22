# Final handoff message (ready to send to the user)

---

## Dextop rebuild — status & what to do next

**Implemented and shipped (M0–M2), verified by GitHub Actions only:**
- **M0:** atomic session WAL — all 7 Android 16 `/ 36` `modernKeys` + Secure settings restored, fsync-before-mutate, reverse-order verified restore; off-main cancellable `wm` IPC; native epoll-spin/FD-leak, AHB-broker stack-buffer/dlopen/auth and swipe-LRU race fixes.
- **M1:** read-only `CapabilitySnapshot` (backend recommendation) + `tool/hyperos_capability_capture.sh`.
- **M2:** deterministic `DesktopSessionFsm`, detached 15 s rollback watchdog, 3 JVM test classes.

**Design-only, NOT shipped (M3–M8):** Flutter is still the shipping UI. No Compose host, no Rust `libdextop_core.so`, no backends A–F source, no portals/terminal/tiling. Designs live in `.team_waves/` and `docs/architecture/`. **No on-device validation was possible here.**

**1 — Install the suffixed debug APK**
Download the arm64 debug asset from [this fork's Releases](https://github.com/niggetron/Dextop/releases/latest), then:
```sh
adb devices
adb install -r dextop-<...>-github-arm64-debug.apk
adb shell pm list packages | grep dextop      # confirm moe.n4tsu.dextop.r<hash>, NOT bare .dextop
```
It installs **side-by-side** with any stable build. Keep the debug build: `run-as` (below) only works on a debuggable APK.

**2 — Export logs**
In Dextop: **Settings → App information → Operation log → Export ZIP**. Consent to include system settings / `/proc` dumps / full session log. Saved to `/sdcard/Download/dextop-diagnostics-*.zip`; pull it:
```sh
adb pull /sdcard/Download/dextop-diagnostics-<exact-name>.zip ./
```

**3 — Run the ADB capability capture (read-only, no settings writes)**
```sh
bash tool/hyperos_capability_capture.sh
```
Produces `hyperos-capture-<timestamp>.txt`. Run on your workstation with **only** the Redmi Pad Pro 5G connected via ADB.

**4 — What to send back**
- The diagnostics ZIP (all entries: `manifest.txt`, `diagnostic-report.txt`, `session-log.txt`, `flutter-errors.txt`, `capability-snapshot.txt`).
- `hyperos-capture-*.txt`.
- From the snapshot, confirm `device: duchamp`, `matchedRuleId: xiaomi_redmi_pad_pro_5g`, `recommendedBackend: E` — a broad `xiaomi_hyperos` match invalidates tablet results.
- Any crash repro steps + `adb logcat -d -v threadtime -s DextopWatchdog:D DextopSessionJournal:D '*:E'` if testing the kill-9/WAL watchdog path (see `.team_waves/W23_DEVICE.md`).
- PII-scrub (emails, paths, session IDs) before sending.

**Caveats (important):**
- **Debug-signed** — no keystore needed, but not accepted by Google Play or trusted by the fixed-package Android Auto companions.
- **Unique package per build** (`moe.n4tsu.dextop.r<hash>`) — releases install as separate apps, not updates; settings do not carry over.
- **No device validation** — all claims above are static/CI-verified only; the Redmi Pad Pro 5G (Android 16 / HyperOS 3) run is the first real test.
- **Token rotation** — a GitHub PAT is embedded in `.git/config` remote URL. Do not share the `.git` directory as-is; strip the `@` from `remote.origin.url` (`git remote set-url origin https://github.com/niggetron/Dextop.git`) and rotate the PAT if the directory was ever exposed.
