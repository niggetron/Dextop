# Device Test — Wave 23 Report: beta.4 Redmi Pad Pro 5G Runbook

## Scope inspected
Suffixed package derivation (`build.gradle.kts:100`, `release-apk.yml:116-128`); privilege grant (`EmbeddedPrivilegeKeeper.kt:130`, `MainActivity.kt:1731-1741`); WAL path + 7 keys (`SessionJournal.kt:36-37`, `W15_QA.md:28`, `W05_WATCHDOG_TEST.md:33-38`); watchdog kill-9 stages (`W05_WATCHDOG_TEST.md:295-310`, `W04_WATCHDOG.md:72-81`); detachment caveat (`W05_WEB.md:11`); export path (`W22_TABLET.md:30`, `DiagnosticExport.kt:24-58`). No builds, no edits, no token.

## Deliverable — exact steps (copy/paste)

Set variables once (workstation shell). `PKG` is the **suffixed** package from the release asset, form `moe.n4tsu.dextop.r<12hex>`:
```sh
adb devices                                   # exactly one tablet
PKG=$(adb shell pm list packages | sed -n 's/^package:\(moe\.n4tsu\.dextop\.r[0-9a-f]*\)$/\1/p' | head -1)
echo "$PKG"                                   # confirm it ends in .r<hash>, not bare .dextop
K="override_desktop_experience_features override_desktop_mode_features enable_non_resizable_multi_window force_desktop_mode_on_secondary_displays force_desktop_mode_on_external_displays force_resizable_activities enable_freeform_support"
WAL=files/dextop_session/session_journal.wal   # relative to run-as cwd
```

**1 — Install suffixed debug APK (side-by-side):**
```sh
adb install -r dextop-<...>-github-arm64-debug.apk   # the beta.4 suffixed debug asset
adb shell pm path "$PKG"                             # must resolve to /data/app/.../base.apk
```
Keep the suffix: the debug APK is debuggable, so `run-as` works in step 7. Do not install over the stable `moe.n4tsu.dextop`.

**2 — Grant privileges:**
- Plain `adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS` is **usually rejected on stock HyperOS 3** (`W20_WEB.md:17`); try it, but expect failure.
- Authoritative path: open Dextop → activate the embedded privileged runtime (Stellar) or Shizuku → accept its permission prompt. The app then self-grants via `pm grant <pkg> WRITE_SECURE_SETTINGS` (`EmbeddedPrivilegeKeeper.kt:130`) and, on the `github` flavor, enables its accessibility service.
- Verify:
```sh
adb shell dumpsys package "$PKG" | grep -i WRITE_SECURE_SETTINGS   # granted=true
```
Also confirm diagnostic `secureSettings: supported` / `privilegeProviderName: Dextop|Shizuku` in the exported report (step 8).

**3 — Baseline BEFORE any session (save locally):**
```sh
adb shell "for k in $K; do echo \"\$k=\$(settings get global \$k)\"; done" > baseline-keys.txt
adb shell 'echo "size=$(wm size) density=$(wm density)"' > baseline-wm.txt
adb shell getprop ro.product.device ro.product.model ro.build.fingerprint ro.product.manufacturer ro.miui.ui.version.name > device-identity.txt
adb logcat -c
```

**4 — Start a desktop session (UI):** launch Dextop → start desktop mode so settings are mutated. **Do NOT tap the 15 s "confirm" button** — you want the watchdog armed. Verify ARM:
```sh
adb shell "run-as $PKG sh -c 'grep -q ARM $WAL && echo ARMED || echo NO-ARM'"
adb shell 'dumpsys activity processes | grep -i dextop_watchdog'   # exactly one detached app_process
```

**5 — Trigger the crash:** kill **only the app PID**. Do NOT use `am force-stop` / `am kill-all` — UID-scoped kills also take out the shell-UID watchdog (`W05_WEB.md:11`), so no rollback would fire.
```sh
APP=$(adb shell pidof "$PKG"); echo "appPid=$APP"
adb shell kill -9 "$APP"
sleep 1                          # rollback is sub-second; 15 s is the hard deadline
```

**6 — Verify the detached watchdog restored settings:**
```sh
adb shell "for k in $K; do echo \"\$k=\$(settings get global \$k)\"; done" > post-keys.txt
diff baseline-keys.txt post-keys.txt            # expect NO differences
adb shell 'wm size; wm density; echo "overlay=$(settings get global overlay_display_devices)"'   # size/density reset, overlay cleared
adb shell 'ls -l /data/local/tmp/dextop/watchdog/*.ack' 2>/dev/null || adb root   # rollback receipt exists
adb logcat -d -s DextopWatchdog:D DextopSessionJournal:D | grep -i rollback    # ROLLBACK{reason} line
```
All 7 keys back to baseline + `wm` reset + receipt + `ROLLBACK` log = PASS. If a key stays mutated, HyperOS rejected the shell `settings put` — capture the `dumpsys display` diff and mark MANUAL app-side restore (`W04_WATCHDOG.md:84`).

**7 — Verify WAL restoration via ADB (exact commands):**
```sh
# durable WAL dump (debug APK => run-as works)
adb shell "run-as $PKG cat $WAL" > session_journal.wal
# expect: 7 x global:<modernKey> mutate records + 1 wm record + valid CRC; after ACK the journal is cleared/kept per FSM
grep -c 'mutate' session_journal.wal
adb shell "run-as $PKG sh -c 'ls -l $WAL; cat $WAL | tail -5'"
# key-by-key readback vs baseline (already post-keys.txt):
while read -r line; do k=${line%%=*}; echo "$k -> $(adb shell settings get global "$k")"; done < baseline-keys.txt
```
Also useful raw checks:
```sh
adb shell "run-as $PKG cat $WAL | grep -E 'ARM|wm|overlay|crc'"
adb logcat -d -v threadtime -s DextopWatchdog:D SessionJournal:D '*:E' > kill9-logcat.txt
```

**8 — Export the diagnostics ZIP:** in Dextop UI: Settings → App information → Operation log → Export ZIP (consent to include system settings / proc dumps / full session log if asked). Filename form `dextop-diagnostics-<PKG>-<version>-<yyyyMMdd-HHmm>.zip` saved under `/sdcard/Download`. Pull it:
```sh
adb shell ls /sdcard/Download/dextop-diagnostics-*.zip
adb pull /sdcard/Download/dextop-diagnostics-<exact-name>.zip ./
unzip -l dextop-diagnostics-*.zip   # must contain manifest.txt, diagnostic-report.txt, session-log.txt, flutter-errors.txt, capability-snapshot.txt
```
Check `capability-snapshot.txt` for `device: duchamp`, `matchedRuleId: xiaomi_redmi_pad_pro_5g`, `recommendedBackend: E` — a broad `xiaomi_hyperos` match invalidates tablet results (`W22_TABLET.md:8,31`).

## What to send back for further fixes
1. The exported diagnostics ZIP (all 5 entries; enable includeSystemSettings + includeFullSessionLog).
2. `baseline-keys.txt`, `post-keys.txt`, `diff` output, `baseline-wm.txt`, `session_journal.wal`.
3. `kill9-logcat.txt` (DextopWatchdog/SessionJournal tags), the `dumpsys activity processes | grep dextop_watchdog` output, and the `/data/local/tmp/dextop/watchdog/*.ack` listing (use `adb root` if denied).
4. `device-identity.txt` (getprops) + `adb shell dumpsys display` / `wm size` / `wm density` after restore.
5. One-line verdict per step: privilege grant succeeded Y/N; daemon count; all 7 keys restored Y/N (+ which failed); `wm` reset Y/N; rollback log present Y/N; ZIP exported Y/N.
PII-scrub (emails, paths, session IDs) before sending. Do not redo `am force-stop`; only `kill -9 <appPid>` reproduces a valid detached-watchdog test.

## Risks / device testing needed
- Plain `pm grant` likely rejected on stock HyperOS 3 → privileged-runtime UI path is required; record which was used.
- `run-as` works only for the debug APK; if using a release asset, swap to `adb root` / `cat /data/data/$PKG/...`.
- Shell `settings put` on hidden 36 keys may be rejected; `wm size/density` and `overlay_display_devices` are the strongest signals.
- Receipt dir `/data/local/tmp/dextop/watchdog` may need root to list; missing receipt ≠ failed rollback if keys/wm restored.
- This runbook covers the warm kill (S1) + WAL/export path; staggered 3/10/14.5 s kills (S2), ACK-negative (S3), daemon re-arm (S4), Doze (S6) and clean teardown (S7) remain follow-up stages (`W05_WATCHDOG_TEST.md:303-308`).
