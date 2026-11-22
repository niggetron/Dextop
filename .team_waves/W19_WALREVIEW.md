# W19 (WAL review) — Wave 19 Report

## Scope inspected
`SessionJournal.kt` (448 L, at commit 565d711 "open WAL phase on begin / delete absent overlay key"), cross-checked against `WatchdogProtocol.kt`, `WatchdogClient.kt`, `WatchdogDaemon.kt`, call sites in `MirrorService.kt`, `MainActivity.kt`, `AndroidAutoMirrorActivity.kt`, `DesktopModeConfigurator.kt`, `PhoneRotationController.kt`, `InternalRefreshRateController.kt`, and W01/W17 reports. No builds, no edits.

## Findings

### P0 — detached watchdog is never armed (watchdogActions breaks ARM)
- `SessionJournal.kt:124` always emits `RestoreAction("wallpaper", emptyList(), true)` (begin always records a wallpaper mutation at `:68-70`).
- `WatchdogDaemon.kt:76` validates **every** received action: `actions.forEach { WatchdogProtocol.validate(it.argv) }`; `WatchdogProtocol.kt:84` `check(argv.isNotEmpty())` throws on the empty wallpaper argv → caught at `WatchdogDaemon.kt:98` → the whole ARM frame is discarded (`armed` stays false, no snapshot, no deadline). The 15 s rollback never runs after a kill -9.
- Fix: `WatchdogDaemon.kt:76` → `actions.filter { !it.handledByApp && it.argv.isNotEmpty() }.forEach { WatchdogProtocol.validate(it.argv) }` (`fireAndExit():134` already skips these). Test: ARM with a wallpaper action must set `armed=true`.

### P0 — torn tail is skipped but never truncated; future appends corrupt it
- `recover():329-339`: bad JSON / bad CRC / seq gap all do `return@forEachLine` (skip-and-continue) and the file is left untouched. A torn final line (no trailing `\n`) + next `append()` at `:291` `FileOutputStream(walFile, !reset /*=true*/)` concatenates the new record onto the garbage tail → combined line fails `JSONObject(line)` → **every later record is lost** (BUG-01 recurrence).
- Fix: in `recover()` accumulate only verified lines' bytes (or byte offset), `break` on the first invalid/gapped record, then rewrite the WAL to the verified prefix (`FileOutputStream(walFile,false).write(validBytes); fd.sync()`) before any append; set `sequence` to last valid seq. Same guard needed in `writeTerminal():351-355` (truncate-then-write window).

### P1 — sequence/state are per-instance and `++` is outside the lock
- `:40-41` `state`/`sequence` are instance fields; `recover()` runs per instance at `:43-46`. `:285` `++sequence` is evaluated **before** `synchronized(this)` at `:290`, and the lock is per-instance. MainActivity creates transient instances (`:834, :1282, :1331, :1362`) alongside `MirrorService:1101` and `AndroidAutoMirrorActivity:514` → duplicate seq values and interleaved file writes; replay's `seq != sequence+1` (`:336`) then drops one of them.
- Fix: process-wide singleton + companion static lock; construct record (increment seq, build JSON) **inside** the static synchronized block; `recover()` exactly once; `armWatchdog`/`watchdogActions` (`:82,129,298-300`) build actions and read sessionId under that same lock (otherwise the action list can be stale vs WAL).

### P1 — recover advances seq before a successful applyEvent
- `:337-338` sets `sequence = seq` then swallows `runCatching { applyEvent(...) }`. A malformed-but-CRC-valid `raw` leaves state unapplied but seq advanced → next append writes `seq+1` over a hole; replay later drops it.
- Fix: parse `JSONObject(raw)` and apply first inside `runCatching`; advance `sequence` only on success; on failure `break` + truncate tail (as in P0 above).

### P1 — null-deletion still writes a NULL-valued row (generic setting)
- `writeSetting():403-409` fallback uses `Settings.Global/System.putString(..., null)`; that leaves a row with null value, not a delete; SECURE hard-fails (`:407`). `restoreOne("setting"):363-365` then "verifies" via `readSetting():418-424`, which returns null → `null == null` → false positive, so `restoreAll` commits with the row still present. (HyperOS can re-seed a default from that row.)
- Fix: when `value == null`, only issue privileged `settings delete <scope> <key>`; if privileged unavailable return `false` (journal kept for retry) — never `putString(null)`. Implement SECURE via `settings get/put/delete secure`. In `readSetting`, do not map a literal `"null"` value to absence; use shell exit/`Settings.getString` null only.

### P1 — overlay restore: phase fix is incomplete
- `restoreOne("overlay"):378-388`: null branch now calls `writeSetting(GLOBAL, …, null)` (good on privileged path, still wrong on fallback per above), but the non-null branch (`:383-386`) bypasses `writeSetting` and uses app `Settings.Global.putString` even when privileged shell is available (rejected/cached on HyperOS per W01) and has **no readback verification** (unlike `"setting"` at `:365`).
- Fix: replace the whole branch with `val written = writeSetting(JournalScope.GLOBAL, DisplayMirrorBackend.DISPLAY_SPECIFICATION, before); written && readSetting(...) == before`. `watchdogActions():113-123` already builds correct `settings put/delete global …` argv.

### P1 — watchdog restore plan is silently incomplete
- `watchdogActions():96,109,120` drop any action whose `validate()` fails with no log → daemon rolls back a subset of keys. No `"rotation"` branch at all (`:86-125`), so rotation frozen/forced by Dextop is never restored by the detached watchdog.
- Fix: log every dropped action and make `armWatchdog():129-132` fail/abort if any record was dropped; add rotation handling (extend `WatchdogProtocol.validate():94-98` to a shell-safe `wm user-rotation` action or mark handledByApp and guarantee app-side startup restore).

### P2 — restoreAll: reverse order correct, but wallpaper/rotation outside transaction and method unsynchronized
- `:200-204` LIFO is correct. But `restoreOne("wallpaper"):390` is a no-op, so callers of `restoreSystemSettings()` that don't separately call `restoreWallpaperDimensions()` (`MainActivity:1288,1368`; `AndroidAutoMirrorActivity:715,821`) never restore wallpaper size. Rotation skipped at `:202` and restored outside the transaction (`MainActivity:1286` before `:1288`), so a rotation-restore failure still lets `:212` commit/clear the journal.
- Fix: invoke `restoreWallpaperDimensions()` from the `"wallpaper"` case in the reverse loop; fold rotation into `onRestoreExtras` so its Boolean participates in `ok`; synchronize the whole method on the static lock used by `append` and reject appends while `phase == "restoring"`.

### P2 — wm argv construction unverified
- `restoreOne("wm"):367-376` and `watchdogActions():100-108` take `command` verbatim from WAL (no size/density allowlist in-app), default `displayId` to `0` via `optInt`, and never read back the metric after `privileged.execute(...).succeeded` → a no-op/failed `wm` still counts as restored. `-d <id>` placed after the value is unverified on API 36 (W01 risk item).
- Fix: restrict `command` to `size|density`; require explicit `displayId` (fail otherwise); verify with `wm size/density -d <id>` output == `before`; confirm arg order on the Redmi Pad Pro before release.

### P2 — snapshot consistency
- `snapshot():244-246` reports `recovered` and `transactionOpen` as the same expression `phase != idle`; there is no real "recovery happened" flag, and a failed restore (`:196` leaves phase `"restoring"`) is reported identically to a live session. Fields read without the lock (race with append/commit).
- Fix: set `private var wasRecovered = true` in `recover()` when any valid record replayed, expose it as `recovered`; compute `recoverable` as `phase != idle`; capture map under synchronized.

### P2 — legacy WAL loses phase after replay
- `applyEvent("begin"):306-312` now relies on the new `phase` key in `data` (`:74`). A pre-fix WAL begin record (or any begin without `phase`) leaves `phase = idle`, so `preparing():52` guard fails → next `recordSetting` calls `preparing(reset=true)` and truncates the WAL; `restoreAll():192` also short-circuits.
- Fix: after copying keys, `if (!data.has("phase")) state.put("phase", "preparing")`.

## Deliverable
Line-by-line fix map above; no source changed. Highest-leverage: (1) skip `handledByApp` in daemon ARM validation, (2) truncate WAL to verified prefix in `recover()`, (3) single static lock + singleton for seq/state, (4) true delete for null values and single privileged `writeSetting` call for overlay.

## Risks / device testing needed
- kill -9 mid-append (torn tail) then relaunch: assert all 7 modern keys, overlay row absent (not NULL), wallpaper dims, forced size/density and rotation restored.
- Verify detached daemon actually fires after 15 s with a wallpaper action present (receipt file written); verify `wm size <v> -d <id>` on API 36.
- StrictMode/Perfetto: no `fd.sync()` on main; concurrent MainActivity snapshot + MirrorService append produces no duplicate seq.
