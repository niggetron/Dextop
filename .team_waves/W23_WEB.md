# Web Agent — Wave 23 Report

## Scope inspected
Verified exact ADB command syntax for Settings.Global reads, per-display `wm` size/density, process kill, `/proc/<pid>/stat`, and app-private file pull against official docs + current AOSP sources. Target: Android 16 (API 36) / HyperOS 3 tablet.

## Findings / citations

1. **Settings.Global read** — `adb shell settings get global <key>` (equiv. `adb shell cmd settings get global <key>`). `settings.sh` is literally `cmd settings "$@"` [A]. AOSP `SettingsService.MyShellCommand` help states `get [--user <USER_ID>|current] NAMESPACE KEY` and `NAMESPACE is one of {system, secure, global}, case-insensitive` [B]. Key string names (e.g. `adb_enabled`) are defined in `Settings.Global` [C]. Works from `shell` uid regardless of any app's debuggable flag.

2. **wm size/density per display** — `adb shell wm size -d <displayId>` and `adb shell wm density -d <displayId>`; density also accepts `-u <uniqueId>` [D]. AOSP `WindowManagerShellCommand` dispatches `size`/`density`, `getDisplayId()` parses `-d`, `runDisplayDensity()` parses `-d`/`-u`; with no value argument it only prints `Physical size/Override size` / `Physical density/Override density` [D]. Default display id = 0; enumerate others via `dumpsys display`. Do NOT use `am display-size` / `am display-density` for reads — those are override commands [E]. Works regardless of debuggable.

3. **Kill process** — `adb shell am force-stop <package>` (force-stop everything for package) and `adb shell am kill <package>` (only processes safe to kill; `--user` supported) [E]. By PID: `adb shell kill -9 <pid>` (toybox `kill`; shell provides the usual Unix tools [E]). Works regardless of debuggable.

4. **Read /proc/<pid>/stat** — `adb shell cat /proc/<pid>/stat`. Field 1 = pid, 2 = `comm` in parentheses (truncated to 16 chars), 3 = state, 22 = starttime (clock ticks), 23 = vsize (bytes), 24 = rss (pages); fields marked `[PT]` read as 0 without ptrace access [F]. Get PID via `pidof`/`ps -A`. Works regardless of debuggable.

5. **Pull app-private files** — `adb pull /data/data/<pkg>/...` (or `/data/user/0/<pkg>/...`) fails from `shell` uid on a production user build (no root; `adb root` only on userdebug/eng). `adb shell run-as <pkg> ls files` runs as the app uid, but AOSP explicitly rejects non-debuggable packages: `if (!info.debuggable) error(1, 0, "package not debuggable: %s", pkgname);` [G]. Also requires caller shell/root, `ro.boot.disable_runas` unset, well-formed data dir [G]. `android:debuggable` defaults to `false` [H].

### Debuggable=false suffixed APK — flag
- **FAILS:** `run-as <pkg> ...` and any `adb pull` wrapped in `run-as`. The filename suffix (`-release.apk`, a literal `debuggable=false` suffix, etc.) is irrelevant: only the manifest `android:debuggable` is checked [G][H].
- **DOES NOT HELP:** `adb shell am set-debug-app <pkg>` — affects debugger attachment, not the FLAG_DEBUGGABLE bit `run-as` reads [E][G].
- **WORKS regardless:** `settings get global`, `wm size/density`, `am force-stop/kill`, `kill -9`, `cat /proc/<pid>/stat`. Only app-private file pull is blocked.

## Deliverable (web citations)
[A] https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/cmds/settings/settings.sh
[B] https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsProvider/src/com/android/providers/settings/SettingsService.java?format=TEXT
[C] https://developer.android.com/reference/android/provider/Settings.Global
[D] https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/services/core/java/com/android/server/wm/WindowManagerShellCommand.java
[E] https://developer.android.com/tools/adb
[F] https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html
[G] https://raw.githubusercontent.com/aosp-mirror/platform_system_core/master/run-as/run-as.cpp
[H] https://developer.android.com/guide/topics/manifest/application-element#debug

## Risks / device testing needed
- AOSP main/master reflects upstream; HyperOS 3 may restrict shell `wm`, `settings`, or `/proc` visibility. Verify during M1 capability capture on the Redmi Pad Pro 5G.
- `wm` overrides are not persistent across reboot; pair every `wm size/density ... <value>` with `... reset` in WAL teardown (relevant to BUG-01/BUG-02).
- Release APK: do not rely on `run-as` in QA; provide an in-app debug export or use a userdebug lab device for app-private pulls.
