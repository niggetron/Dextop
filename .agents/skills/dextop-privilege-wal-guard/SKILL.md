---
name: dextop-privilege-wal-guard
description: Enforces typed PrivilegeGate commands and atomic WAL snapshot+fsync before any Settings.Global or window-manager mutation in Dextop. Use when editing privilege, session journal, display settings, or Android 16 key restore code.
---

# Dextop Privilege + WAL Guard

## Non-negotiable rules

1. **Never concatenate raw shell strings.** Only typed commands (e.g. `ShellCommand.SetDisplayMode`, `ShellCommand.WriteGlobalSetting`) may pass through `PrivilegeGate`. Arguments are validated against an allowlist.
2. **Every global/system setting mutation is journaled first.** Before changing any `Settings.Global` key or `wm size`/`wm density`, commit an entry to the atomic JSON WAL and `fsync()` it. Missing WAL entry = the change must not happen.
3. **Restore every recorded key.** Never hardcode a subset (BUG-01: 4 of 7 Android 16 `modernKeys` leaked). A `null` original value means "delete this key on restore" — record it, do not skip it.
4. **No blocking IPC on the main looper.** `wm`, `settings`, `am`, `dumpsys` calls run on a session-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Every posted runnable is cancellable and cancelled on teardown (BUG-02).
5. **Watchdog confirmation.** If display settings were mutated, a 15-second detached watchdog rolls back unless the user confirms. `kill -9` must still restore every key.
6. **Least privilege.** Prefer embedded ADB/Shizuku with explicit consent; never assume root. `SO_PEERCRED` or equivalent authentication on guest sockets.

## Review checklist

- [ ] Typed command used, no raw shell string.
- [ ] WAL entry written + fsync before mutation.
- [ ] Restore iterates the dynamic map (including nulls).
- [ ] All coroutines/jobs cancelled in teardown.
- [ ] `kill -9` recovery tested.
- [ ] PII scrubbed from logs and reports.

## Regression shield

BUG-01 (key leak), BUG-02 (main-thread IPC). See `DesktopEnvironment.kt`, `DesktopModeConfigurator.kt`, `SessionJournal.kt`.
