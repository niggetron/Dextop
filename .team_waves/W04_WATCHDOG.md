# W4B (M2) — Wave 2 Watchdog Report

## Scope inspected
- `SessionJournal.kt` (WAL: `append()` :222-238, `recover()` :261-280, `restoreAll()` :134-156, `restoreOne()` :296-325, `writeTerminal()` :282-294)
- `PrivilegedAccess.kt` :11-46; `privilege/DistributionPrivilegeRuntime.kt` :74-91; `privilege/EmbeddedServerPayload.kt` :16-22; `privilege/EmbeddedPrivilegeProtocol.kt`
- `linux/LinuxServiceHost.kt` :18-57,87-94 (proven detached `app_process` pattern)
- `AndroidManifest.xml` (FGS `:linux_runtime`, `LinuxBootReceiver`, no watchdog component); `proguard-rules.pro:1-10`; `.agents/skills/dextop-privilege-wal-guard/SKILL.md:8-15`; `W01_BUG01.md`, `W03_BACKEND.md:224-228`

## Findings
- **P0 — no external rollback exists today.** `SessionJournal` is in-process (`context.filesDir` :36). `kill -9` / OEM reclaim on HyperOS leaves 7 mutated SDK-36 keys (`override_desktop_experience_features`, …) and `wm size/density` set for every user until next launch. Skill rule 5 is unimplemented. Fix: detached shell watchdog below.
- **P0 — watchdog cannot read the app-private WAL.** `/data/user/0/<pkg>/files/dextop_session/session_journal.wal` is UID-0700; shell watchdog cannot open it, and `run-as` fails on release. Fix: push a self-contained `RestoreAction[]` snapshot over IPC at every `append()`; no shared WAL file.
- **P1 — plain Android service is not viable.** Any `<service>`/FGS dies with `kill -9` and is reclaimed by HyperOS battery policy. Must be a shell `app_process` detached at spawn by `nohup setsid` (pattern already shipped in `EmbeddedServerPayload.kt:17-21`).
- **P1 — `PrivilegedAccess.execute()` has no stdin path** (`:25-41` calls `DistributionPrivilegeRuntime.execute(arguments)` only), but the launch command needs `IFS= read -r key` (embedded-server convention). Fix: add `executeWithInput()` delegating to the existing `executeCommandWithInput` transact (`EmbeddedPrivilegeProtocol.kt:10`).
- **P1 — `PR_SET_PDEATHSIG` is wrong direction** (signals child when *its* parent dies; watchdog's parent is init after `setsid`, not the app). Fix: poll `/proc/<pid>/stat` starttime; optional `pidfd_open`+`ppoll` JNI fast path.
- **P2 — tunnel/token leakage.** Token in argv is visible in `/proc`; generate with `PublisherKeys.issue()` and feed via stdin, plus verify `LocalSocket.getPeerCredentials().uid == appUid` (SO_PEERCRED) and a random HELLO nonce. Typed-argv allowlist reused from skill rule 1.
- **P2 — double rollback.** Idempotent for settings/wm, but persist an ACK receipt under `/data/local/tmp/dextop/watchdog/` so a watchdog restart after ACK does not re-fire.
- **P3 — wallpaper dimensions** are restored via `WallpaperManager` (`SessionJournal.kt:171-181`), unavailable to shell. Mark `handledByApp=true`; app performs it in teardown; note as accepted gap.

## Deliverable

### Process choice (decision)
| Option | Verdict |
|---|---|
| **Detached shell `app_process` via `DistributionPrivilegeRuntime`** | **M2 choice.** Already proven, shell UID has `WRITE_SECURE_SETTINGS`, runs same `settings`/`wm` argv as `restoreOne()`, Java-only (zero new deps), survives app kill. |
| Rust `libdextop_core.so` daemon | Defer to M5. Needs new exec/JNI bridge, `prost`/`tokio`, cross-compile; no privilege advantage. Keep Rust as data plane only. |
| Android FGS / `<service>` | Reject: dies with app, HyperOS reclaim. No manifest entry added. |

### IPC (dependency-light, no protobuf in M2)
Unix **abstract** `LocalServerSocket("@dextop.wd.<appUid>")` (no stale socket file), framed `[4-byte LE length][UTF-8 JSON][CRC32 of payload]`, max 256 KiB, magic `"DW01"`. JSON reuses `org.json` + `SessionWalCodec.canonical/crc32`; protobuf (`prost`) is an M5 drop-in behind `WatchdogProtocol`.
Messages: `HELLO{nonce,token,uid}` → `ARM{sessionId,armSeq,pid,pidStartTicks,deadlineMs=15000,actions[]}` → `HEARTBEAT{armSeq}` → `ACK{sessionId}` / `ROLLBACK{reason}`. `ARM` re-arms and replaces the snapshot (highest `armSeq` wins); `ACK` stops timer and exits after receipt write. Fire rollback on deadline expiry **or** `/proc/<pid>` death.

### New files (exact paths)
1. `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogProtocol.kt` — `RestoreAction(kind, argv, handledByApp)`, frame encode/decode, CRC, nonce, `validate(argv)`: `argv[0] ∈ {settings,wm}`, `argv[1] ∈ {put,delete,get,size,density}`, key regex `[a-z0-9_]+`, no shell metachars. Pure JVM-testable.
2. `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogDaemon.kt` — `@Keep object`, `main(args)`: read token from stdin, `LocalServerSocket(ABSTRACT)`, `Looper`-free worker thread, `ProcessBuilder("/system/bin/"+argv[0], *argv)` for rollback (reverse order), 250 ms death-poll thread (`/proc/<pid>/stat` field 22), `SIGKILL` self-exit after ACK/rollback. Never imports Android `Context`.
3. `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogClient.kt` — app side: `start()` builds `nohup /system/bin/setsid /system/bin/app_process /system/bin --nice-name=<pkg>:dextop_watchdog moe.n4tsu.dextop.WatchdogDaemon <pkg>` via `PrivilegedAccess.executeWithInput(..., token)`; `arm(actions)`, `heartbeat()` (every 5 s on the FSM IO scope), `ack(sessionId)`, `close()`.
4. `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogDaemonDeath.kt` — JNI facade `external fun waitForDeathOrTimeout(pid, startTicks, timeoutMs): Int`; Java fallback = `File("/proc/$pid/stat")` poll.
5. `android/app/src/main/cpp/watchdog_death_jni.c` (optional fast path) — `pidfd_open(pid,0)`, `ppoll(...,250ms)`, verify starttime via `/proc`; returns `DEAD|ALIVE|ERR`. **No `PR_SET_PDEATHSIG`.**
6. Tests: `android/app/src/test/kotlin/moe/n4tsu/dextop/WatchdogProtocolTest.kt` (frame/CRC/allowlist/reverse-order); `android/app/src/androidTest/kotlin/moe/n4tsu/dextop/WatchdogKillTest.kt` (below).

### Integration points (existing files)
- `SessionJournal.kt:38-45` add `private val watchdog = WatchdogClient(context, PrivilegedAccess("DextopWatchdog"))`; init starts daemon lazily.
- `SessionJournal.kt:70-75` after `append("begin", …, reset=true)` → `watchdog.arm(sessionId, actionsFrom(state))`.
- `SessionJournal.kt:222-238` at end of `append()` (after `applyEvent`) → if `op` is `"mutate"`/`"phase"` call `watchdog.arm(...)` with rebuilt `actions[]`; map `"setting"`→`settings put|delete <scope> <key> <value>`, `"wm"`→`wm <command> reset -d <displayId>` (or value form), `"overlay"`→`settings put global DISPLAY_SPECIFICATION …`, `"wallpaper"`→`handledByApp=true`.
- `SessionJournal.kt:154` immediately after successful `append("commit", …)` → `watchdog.ack(sessionId)`; `:198 clear()` → `watchdog.ack()` as well.
- `PrivilegedAccess.kt:25` add `executeWithInput(args, input)` → `DistributionPrivilegeRuntime.execute(args, input)` (already at `DistributionPrivilegeRuntime.kt:74-83`); Shizuku path uses `newProcess(args, in, out)`.
- `proguard-rules.pro:3` add `-keep class moe.n4tsu.dextop.WatchdogDaemon { *; }`, `-keep class moe.n4tsu.dextop.WatchdogDaemonDeath { *; }`.
- `android/app/src/main/cpp/CMakeLists.txt` add `watchdog_death_jni` target only when the optional JNI is enabled.
- `DesktopModeConfigurator.kt:62-76`, `MirrorService.kt:1092`, `MainActivity.kt:1282,1362`: **no change** — arming/ACK is owned by `SessionJournal`, so W01/W03 integration stays intact.
- `AndroidManifest.xml`: **no change** (no Android component; detached process is shell-spawned).

### Sketch (core)
```kotlin
// WatchdogDaemon.kt
fun main(argv: Array<String>) {
  val token = System.`in`.bufferedReader().readLine() ?: return
  val server = LocalServerSocket("@dextop.wd.${argv[0].hashCode()}")
  var snap: Snapshot? = null
  while (true) {
    val c = server.accept()
    val msg = WatchdogProtocol.read(c.inputStream)
    when (msg.type) {
      "HELLO"   -> if (msg.token == token && c.peerCredentials.uid == appUid) reply("OK")
      "ARM"     -> { snap = msg.snapshot; deadline = now + 15_000; startDeathPoll(msg.pid, msg.startTicks) }
      "HEARTBEAT" -> deadline = now + 15_000
      "ACK"     -> { writeReceipt(msg.sessionId); reply("ACKED"); exitProcess(0) }
    }
    if (now > deadline || deathPoll.dead) { rollback(snap!!.actions.asReversed()); exitProcess(0) }
  }
}
```

### kill-9 test plan (device: Redmi Pad Pro 5G / API 36 / HyperOS 3)
1. `adb shell am start …` → enter desktop session; confirm WAL has 7 global keys + `wm` record via `adb shell "run-as <pkg> cat files/dextop_session/session_journal.wal"`.
2. `adb shell ps -A | grep dextop_watchdog` → exactly one detached daemon; `adb shell pidof <app>` record `appPid`.
3. `adb shell kill -9 <appPid>`; wait ≤1 s. Assert all 7 keys equal pre-session values (`settings get global …`), `wm size`/`density` reset, overlay key restored.
4. Repeat with `kill -9` at 3 s / 10 s / 14.5 s after ARM; assert rollback every time.
5. Negative: send ACK, then `kill -9`; assert **no** rollback and receipt exists.
6. Timeout path: `kill -9` the daemon, do NOT ack; app re-arms → new daemon takes over (single-winner lock in abstract socket name).
7. PID reuse: kill app, spawn any process until PID reused; assert starttime check prevents false "alive".
8. HyperOS battery: lock screen 10 min mid-session, kill app; assert rollback still fires (validates detached shell UID).
9. Clean teardown: user confirms → ACK → daemon exits, no lingering `dextop_watchdog` process, no stray `/data/local/tmp` receipts.

## Risks / device testing needed
- Shell-process `settings put` for hidden keys may be rejected on HyperOS 3 → fallback to app-side restore at next launch; capture `dumpsys` diffs in report.
- Abstract-socket names are namespace-scoped per user; multi-user needs UID in name (done).
- `%` of duplicate daemons after APK update: add APK-exists check like `LinuxServiceHost.kt:50-55`.
- JNI `pidfd_open` needs API 33+ (minSdk 33 ok); Java poll fallback keeps M2 buildable without NDK changes.
- No local builds performed (GitHub Actions only); all sketches unverified by compiler.
