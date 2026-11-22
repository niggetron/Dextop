# Web Research — Wave 19 Report (Dextop watchdog correctness)

## Scope inspected
Web-only; no source edits, no token used. Researched 5 platform questions relevant to the 15 s detached watchdog / atomic-JSON-WAL session FSM on Redmi Pad Pro 5G, Android 16 (API 36), HyperOS 3. No local source inspected, so "file:line" below maps to BRIEF.md bug IDs / expected watchdog code sites, not audited lines.

## Findings

### P0 — watchdog shell app_process is NOT "detached" and cannot do UI/Settings on API 36
- A `shell app_process` runs as uid 2000 with **no ProcessRecord in AMS**; high-level APIs fail (`Given calling package android does not match caller's uid 2000` on Settings provider; `Toast`/broadcast/window all broken). OEM skins add non-AOSP framework calls (MIUI `Toast.checkGameHomeAllowList`) that blow up shell processes. Since **Android 14 QPR3 / Android 15 (hence 16), `addView` requires a valid Android app process** — a shell watchdog cannot render any overlay/recovery UI. Cite: https://gist.github.com/RikkaW/be3fe4178903702c54ec73b2fc1187fe
- Survival: plain `kill -9 <app_pid>` kills only that PID; the shell app_process (parent adbd/sh, uid 2000) survives **as long as the adb shell session lives**. On adb disconnect/sh exit, adbd tears down the child process group with **SIGHUP**, killing it; need `setsid`/`nohup` + reparent to init. Cites: https://android.googlesource.com/platform/system/core/+/aebebeb/adb/daemon/shell_service.cpp ("will send SIGHUP manually to the child process"), https://github.com/keymapperorg/KeyMapper/issues/2284, https://github.com/Genymobile/scrcpy/issues/483, https://unix.stackexchange.com/questions/169703/how-to-keep-running-a-process-in-android-when-disconnecting-adb-terminal-comman
- If the watchdog is `Runtime.exec`'d as a **child of the app**, `am force-stop` / cgroup cleanup kills it; only bare `kill -9` of the app PID is survivable (Linux has no default parent-death signal).
- Fix: launch detached via `setsid`, re-exec/init-reparent; treat watchdog as headless control-plane only (raw binder / shell commands), never UI or `Settings` content provider. Do not assume it survives adb or force-stop.

### P0 — `wm size/density reset` vs manual settings write (bootloop risk)
- AOSP `WindowManagerShellCommand`: `wm size WxH` -> `setForcedDisplaySize`; `wm size reset` -> `clearForcedDisplaySize` (w=h=-1). `wm density N` -> `setForcedDisplayDensityForUser(USER_CURRENT)`; `wm density reset` -> `clearForcedDisplayDensityForUser` (density=-1). Density must be >=72. Cite: https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/wm/WindowManagerShellCommand.java
- Forced size/density are persisted by DisplayManagerService in Settings.Secure keys `display_size_forced` / `display_density_forced`, so overrides survive reboot; `reset` removes the override. Cite: https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/display/DisplayManagerService.java
- Writing `display_density_forced -1` by hand via `settings put secure` can **corrupt boot** (Google issue tracker 283009661; MIUI bootloop reports). Cite: https://issuetracker.google.com/issues/283009661 , https://www.reddit.com/r/tasker/comments/ll2ad6/wm_density_with_root_make_my_phone_on_bootloop/
- Fix (relates BUG-02): watchdog recovery MUST call `wm size reset` **and** `wm density reset` (current user), off main looper in a session-scoped cancellable IO scope, then verify with `wm size` / `wm density` / `dumpsys display`. Never write the forced-* keys directly.

### P0/P1 — `/proc/<pid>/stat` starttime parsing (PID-reuse misdetect)
- `starttime` is **field 22** (1-based), clock ticks since boot (since 2.6). Cite: https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html
- Field 2 `comm` is parenthesized and may contain spaces/`)`; the widespread `awk '{print $22}'` idiom is wrong for such names, yielding a wrong token → watchdog mis-pairs PID with starttime and can misclassify a recycled PID as the live session (or vice versa). Cites for the (wrong) idiom: https://www.baeldung.com/linux/total-process-cpu-usage , https://lists.kernelnewbies.org/archives/list/kernelnewbies@kernelnewbies.org/message/4FVNV25SX6H3JU6J5HF33DW65YVCYGQN/
- Fix: parse from the **last** `')'` in the line; token index = 22-3 = 19 (0-based) after it. Store `(pid, starttime)` in WAL; treat any mismatch as process death and trigger recovery. Convert ticks with `_SC_CLK_TCK` (typically 100 on Android); remember value resets on reboot.

### P1 — LocalServerSocket abstract namespace lifetime / auth
- `LocalServerSocket(String)` binds in the Linux **abstract namespace**, no filesystem node. Cite: https://developer.android.com/reference/android/net/LocalServerSocket
- Abstract sockets "automatically disappear when all open references are closed" (unix(7)): on `kill -9` the kernel closes all fds, so the name is released immediately — no stale file, no unlink/TIME_WAIT needed. But an immediate rebind by a new instance can hit **EADDRINUSE** if the old listener is still alive (brief overlap) → watchdog heartbeat namespace is racy.
- Abstract sockets have **no permissions** (umask/fchmod irrelevant) — any app on device can connect or impersonate. Cite: https://man7.org/linux/man-pages/man7/unix.7.html
- Fix: use a per-session randomized name plus a shared secret token in the WAL, and require `SO_PEERCRED` uid == shell/system on accepted sockets; treat EADDRINUSE as "old instance alive", not "socket leaked".

### P1 — Settings.Global persistence across reboot / OEM (BUG-01)
- Since Android 6, settings live in `/data/system/users/<user>/settings_global.xml` (not a DB); SettingsProvider synchronously persists designated global/secure keys. AOSP writes therefore **survive reboot**. Cites: https://stackoverflow.com/questions/50144579/automatically-configure-android-settings , https://android.googlesource.com/platform/frameworks/base/+/97e5130234bcdc1c4a705be4e548302934499bd4/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java
- HyperOS/MIUI adds its own global/secure keys and may re-apply defaults at boot; OEM skins also rewrite framework classes, so assume persistence but not stability. Cites: https://sites.google.com/site/tweakradje/xiaomi-hyperos-3-tweaking
- Watchdog cannot use `Settings` from shell app_process (P0 above); must shell out to `settings` or raw binder.
- Fix (BUG-01): atomic JSON WAL records **all 7** modernKeys; on boot/session end re-apply every mutated key from WAL, including OEM keys; keep forced display keys out of direct writes (use `wm ... reset`).

## Deliverable (web citations summary)
- app_process limits/survival: gist RikkaW, adb shell_service.cpp, KeyMapper #2284, scrcpy #483, unix.stackexchange 169703
- abstract socket lifetime/auth: developer.android.com LocalServerSocket, unix(7) man page
- /proc starttime field 22 + parse pitfall: proc_pid_stat(5) man page, Baeldung, kernelnewbies list
- Settings persistence: SettingsProvider.java source, SO 50144579, HyperOS tweaking guide
- wm reset semantics: WindowManagerShellCommand.java + DisplayManagerService.java sources, Google issue 283009661

## Risks / device testing needed
1. On Redmi Pad Pro 5G: launch watchdog with `setsid`, pull USB, verify it survives 30 s; verify `am force-stop <pkg>` behavior.
2. Confirm on HyperOS 3 whether `display_size_forced`/`display_density_forced` survive reboot and whether OEM desktop-mode entry rewrites them.
3. Test abstract-socket rebind immediately after watchdog kill; implement SO_PEERCRED check.
4. Feed a `/proc/<pid>/stat` line with a spaced comm (e.g. `kworker/u8:1-events`) into starttime parser; assert PID-reuse detection after reboot.
5. Never issue `settings put secure display_density_forced -1` on device (bootloop risk) — only `wm ... reset`.
