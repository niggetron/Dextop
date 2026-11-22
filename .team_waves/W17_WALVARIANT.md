# W17A (variant analysis) — Wave 17 Report

## Scope inspected
`SessionJournal.kt` (443 L), `DesktopModeConfigurator.kt` (200 L), `MirrorService.kt` metric/restore/display paths, plus every call site found by grep: `AndroidAutoMirrorActivity.kt`, `CoverDisplaySessionController.kt`, `DisplayMirrorBackend.kt`, `DisplayTopologyController.kt`, `MainActivity.kt`, `PhoneRotationController.kt`, `InternalRefreshRateController.kt`, `plasma/CompositedWindowShell.kt`, `linux/LinuxCompat.kt`, `linux/LinuxPhantomProcesses.kt`, `privilege/EmbeddedPrivilegeKeeper.kt`, `CardexRelayService.kt`. No builds, no source edits.

## Findings

### P0 — BUG-01 recurrence: WAL session never marks itself open
- `SessionJournal.kt:72-76` + `:305-311`: the `begin` event writes no `phase` key, so `applyEvent("begin")` leaves `phase = "idle"` (from `blankState()` :261-262). Nothing else sets phase until `running()`/`paused()`.
  - `:52` `preparing()` idempotence guard never fires, so every `rememberGlobal`/`rememberSystem` (`:134-138`), `recordWm` (`:157`), `rememberPhoneRotation` (`:167`) re-enters `preparing()` → `append(..., reset = true)` at `:290` **truncates the WAL and discards every earlier mutation**. Concrete: `DesktopModeConfigurator.applyForCurrentDevice():62-76` loops keys on main while phase is idle → only the last key is journaled; same in `configureDisplayNow():107-116` and `AutoDisplaySession.start():587,625,770`. This is the exact "restores 3 of 7 keys" failure mode.
  - `:191-194` `restoreAll()` short-circuits on `phase == "idle"` → no restore on a crash that happens before `running()`; `:243-245` `snapshot()` always reports `recoverable/transactionOpen = false`.
  - **Fix:** add `.put("phase", "preparing")` to the `begin` payload at `:72-76` (or `state.put("phase","preparing")` in `applyEvent` "begin" *after* the key copy). Then `:52` becomes a real guard, WAL appends instead of resets, and recovery restores.

### P1 — Forced display metrics mutated without WAL (BUG-01 class)
- `MirrorService.kt:12709-12712` `applyXiaomiDesktopDensity()` → `setForcedDisplayDensityForUser`; called `:12195`, `:12501`. No `recordWm`. **Fix:** before `applyForcedMetric`, call `sessionJournal.recordWm(displayId, "density", "reset", dpi.toString())`.
- `MirrorService.kt:12356-12362` `applyAutoLogicalSize()` → `setForcedDisplaySize`; called `:12295`, `:12375`. No journal. **Fix:** `recordWm(targetDisplayId, "size", "reset", "${targetWidth}x${targetHeight}")` before invoke.
- `MirrorService.kt:13318-13333` `resizeActiveDisplay()` → `setForcedDisplaySize` + `setForcedDisplayDensityForUser` on live resize; no journal. **Fix:** `recordWm()` for each changed metric before mutation (first-writer-wins keeps the original `reset` baseline, which is correct for restore).
- `MirrorService.kt:12692-12693,13378` `clearForcedDisplaySize/...DensityForUser` clears inherited overrides with no record; `:12700-12703` records *after* the clear. **Fix:** record `reset` baseline before the first clear.
- `AndroidAutoMirrorActivity.kt:677-696` `AutoDisplaySession.resizeLogical()` → both forced size + density via reflection, no journal (caller `CardexRelayService.kt:290`). **Fix:** pass its `sessionJournal` (already field `:514`) and `recordWm()` both metrics before invoke.
- `DisplayTopologyController.kt:406-412,421-429` `setDensity()` (`wm density`) / `setRotation()` (`wm user-rotation lock`) mutate physical-display metrics with no journal; caller `MainActivity.kt:608-619` uses a background Thread so not main-blocking, but unrestored. **Fix:** inject `SessionJournal`, `recordWm(displayId,"density", before, dpi)` and add a `rotation` restore kind for user-rotation lock.

### P1 — Settings mutated without WAL; SECURE scope cannot be restored
- `MirrorService.kt:288-315`, `:1009-1019`, `:14057-14064` write `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` / `ACCESSIBILITY_ENABLED` with no journal. `SessionJournal` has no `rememberSecure`, and `writeSetting():402` / `readSetting():417` hard-fail for `JournalScope.SECURE`, so even a recorded secure key could not be restored. **Fix:** add `rememberSecure(key, before)`, implement SECURE via privileged `settings get/put/delete secure <key>` in `writeSetting`/`readSetting`, and journal before each of the three sites (null → delete).
- `AndroidAutoMirrorActivity.kt:579` sanitizes `overlay_display_devices` **before** `sessionJournal.preparing()` at `:587` snapshots it (`:53-55`), so the pre-session value is unrecoverable. Intentional cleanup, but WAL cannot undo it — **fix:** snapshot first, then sanitize, then record the sanitized value as a `mutate` overlay record.

### P1 — Restore skips null-deletions / ignores failure
- `SessionJournal.kt:377-384` `restoreOne("overlay")` uses `Settings.Global.putString(key, null)` when original was absent; Android leaves a NULL-valued row instead of deleting it. `watchdogActions():115` correctly uses `settings delete global ...`. Same flaw in `writeSetting()` fallback `:398-404`. **Fix:** when `before == null` call privileged `settings delete global overlay_display_devices` (and `settings delete <scope> <key>` generally), otherwise `putString`.
- `DesktopModeConfigurator.kt:192-199` `restore()` discards the `Boolean` from `restoreSystemSettings()` but still clears `savedGlobals`, resets `applied`/`forcedFreeformGlobalsApplied`. On partial failure (journal kept for retry) the in-memory state is gone. **Fix:** only clear/reset when restore returns `true`.
- `SessionJournal.kt:218-227` `restoreSystemKeys()` silently skips missing records and `discardSystemKeys()` removes records without restoring; combined with P0 this drops keys. **Fix:** have `restoreSystemKeys` return unmet keys and never `discardSystemKeys` a mutated key.

### P2 — BUG-02 recurrence: blocking IPC / WAL fsync on main
- `MirrorService.kt:12039-12063` (Surface callbacks, main looper) → `createDisplay():12126` → `desktopModeConfigurator.applyForCurrentDevice():12139`; `DesktopModeConfigurator.kt:62-76` performs `Settings.Global` binder writes and `rememberGlobal()` → `SessionJournal.kt:290-293` `out.fd.sync()` **on the main thread**. **Fix:** move `applyForCurrentDevice()` onto the session IO scope (return a `Job` like `configureDisplay()` at `:84-102`) and call `remember*()` only from IO.
- `MirrorService.kt:12714-12765` `configureDisplay()` (its own, hidden `IWindowManager` reflection) invokes `setIgnoreOrientationRequest`, `setFixedToUserRotation`, `setShouldShowSystemDecors`, `setDisplayImePolicy`; `applyDisplayRotation():12793-12825` calls `freezeDisplayRotation`. Called from `attachDesktopDisplay():12502,13410` on main. **Fix:** dispatch to session-scoped IO with a generation guard; cancel on stop.
- `DesktopModeConfigurator.kt:182-190` `cancelPendingWork()` cancels the scope but does not wait; non-interruptible `commandRunner` calls (`:112,120,143,161,164`) can land after `restore():193`. **Fix:** defer `restore()` until cancelled jobs complete (`invokeOnCompletion`/join callback), or serialize commands through one cancellable executor.

### P2/P3 — Delayed callbacks not cancelled
- `MirrorService.kt:12383-12385` `postMainDelayed()` allocates a new `Handler` per call and never cancels. `waitForOverlay():12457-12478` retries via it at `:12465` with no `active/stopping` guard and can call `attachDesktopDisplay()` at `:12477` after stop. **Fix:** one retained main Handler + generation token, `removeCallbacksAndMessages(null)` in `stop()` (pattern already at `AndroidAutoMirrorActivity.kt:659`).
- `MirrorService.kt:3070-3073` `plasmaHost.privilegedExecute` spawns an untracked `Thread` per command (fire-and-forget, returns `true`); `CompositedWindowShell.kt:2896-2897` uses it for `wm density` overrides — no journal, no cancellation at teardown. **Fix:** route through session IO scope + `recordWm`.
- `MainActivity.kt:1429-1463` `writeOverlaySetting()` (currently no call sites) writes `overlay_display_devices` without WAL and does blocking Shizuku IPC at `:1453` on the MethodChannel (main) thread. **Fix:** delete dead code or route through `SessionJournal` + IO.

### P3
- `linux/LinuxCompat.kt:45`, `linux/LinuxPhantomProcesses.kt:35`, `privilege/EmbeddedPrivilegeKeeper.kt:122` persist `Settings.Global` values (`settings_enable_monitor_phantom_procs`, `adb_wifi_enabled`) with no before-value record/reversal; not session-scoped but leak across sessions. **Fix:** journal or document as intentional one-way bootstrap.

## Deliverable
Code-level fix map above; no source changed. Highest-leverage single change: put `"phase"` into the `begin` WAL event (`SessionJournal.kt:72-76`) — without it every other WAL fix is moot.

## Risks / device testing needed
- Verify on Redmi Pad Pro 5G / HyperOS 3: kill -9 mid-start, reboot, confirm all 7+ globals, overlay spec, wallpaper size, forced density/size and rotation restore.
- Assert `Settings.Global.getString(overlay_display_devices)` is `null` (row deleted, not NULL-valued) after restore when it was absent initially.
- StrictMode dropBox / Perfetto trace during `surfaceCreated` and `attachDesktopDisplay` to confirm no main-thread `fd.sync()` or WMS binder calls.
- Stop during Android Auto `waitForOverlay` retry and Plasma `wm density` command to confirm no late attach/override.
