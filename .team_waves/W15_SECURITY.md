# W15B (M8) — Security Audit Report

## Scope inspected
- `PrivilegedAccess.kt`, `privilege/*` (`DistributionPrivilegeRuntime`, `EmbeddedPrivilegeServer`, `EmbeddedPrivilegeProvider`, `EmbeddedPrivilegeKeeper`, `EmbeddedPrivilegeProtocol`, `PublisherKeys`, `DistributionPrivilegeBootstrap`, `EmbeddedServerPayload`, `LocalAdbEndpoint`, `AndroidKeystorePrivateKeyStore`)
- `SessionJournal.kt` (WAL), `WatchdogProtocol/Client/Daemon.kt`
- `DiagnosticReport.kt`, `DiagnosticExport.kt`, `OperationLog.kt`, `DeviceDiagnostics.kt`, `MainActivity.kt` export/share paths
- `AndroidManifest.xml`, `res/xml/file_paths.xml`, all 6 AIDL interfaces
- All `privileged.execute(...)` / `sh -c` call sites (81 grep hits), `LinuxPaths.container()` validation

## Findings

### P0-1 — Arbitrary privileged command execution: no command gate anywhere
`PrivilegedAccess.kt:25-41` `execute(vararg arguments: String)` accepts any argv; `EmbeddedPrivilegeServer.kt:152-160,179-188` forwards it verbatim to `ProcessBuilder(*command)` as shell user. `DistributionPrivilegeRuntime.kt:74-91` is an unfiltered Binder `executeCommand`. Any code path (or a compromised helper holding the runtime Binder) can run `pm`, `settings`, `sh -c`, etc. as shell. Raw `sh -c` strings are spread across `MirrorService.kt:7971,8195,8183-8185,13709`, `PlasmaSystemControls.kt:121`, `LinuxNetworkMonitor.kt:117`, `EmbeddedPrivilegeProvider.kt:102-103`, `LinuxShortcutInstaller.kt:340`, `LinuxChannel.kt:383`. On Android 16/HyperOS the shell identity is root-equivalent for settings/window/PM — a single string-injection or confused-deputy is full device compromise.
**Fix:** typed `PrivilegeGate` (deliverable below); route every call through it; keep `runCommand` in the daemon gated by the same `CommandAllowlist`.

### P0-2 — Watchdog token leaked via envp on Shizuku fallback
`PrivilegedAccess.kt:50-53`: `newProcess(arguments, arrayOf("DEXTOP_WD_TOKEN=$input"), null)`. The file's own comment admits argv leaks, but `envp` is equally world-readable: every shell process (including every Linux program under PRoot, which runs as the same shell uid) can read `/proc/<pid>/environ` of the watchdog daemon. The token authenticates `HELLO` on the abstract socket (`WatchdogDaemon.kt:61-62`).
**Fix:** fail closed — if `DistributionPrivilegeRuntime` is unavailable, skip `arm()` entirely (`SessionJournal.armWatchdog` → no-op); never place the token in envp. If a fallback is required, pass it through an fd added via `newProcess`'s `fds`, never a string.

### P1-3 — Watchdog receipt path traversal
`WatchdogDaemon.kt:145-148`: `receipt(sessionId)` = `File("/data/local/tmp/dextop/watchdog", "$sessionId.ack")`; `sessionId` comes from the authenticated `ARM` frame (`WatchdogDaemon.kt:73`) with no format check. A token-bearing client (or leaked token, see P0-2) can send `../../...` and write/overwrite arbitrary shell-owned files.
**Fix:** validate `sessionId` against `Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")` at `ARM` receipt and again in `receipt()`; reject non-matching before any filesystem use. Use `File(RECEIPT_DIR, "$sid.ack").canonicalFile` and assert `startsWith(File(RECEIPT_DIR).canonicalFile)`.

### P1-4 — Diagnostic redaction is regex-key-based only and is bypassed on the direct report path
`OperationLog.kt:49-56` only redacts `key=value` tokens. `DiagnosticReport.kt` emits `name: value` (`line()` at :19) and `detail=` prose (`:84-87`); neither matches, so `path: /data/...`, `email: a@b`, `sessionId: ...`, capability `detail` PII, and `externalDisplayNames` (`:71`) survive. `DiagnosticExport.kt:35` sanitizes ZIP entries, but `MainActivity.kt:750-760` calls `DiagnosticReport(this).build()` directly for `diagnosticReport` and `shareDiagnosticReport` — **no sanitizeDocument**, so the share intent leaks raw data.
**Fix:** sanitize at the `DiagnosticReport.build()` boundary (return `OperationLog.sanitizeDocument(sb.toString())`), expand `SANITIZERS` to cover `: ` and JSON `"key": "value"` forms plus keys `sessionId|token|key|secret|password|nonce|serial|fingerprint|email|address|ssid|displayName|name`, and redact `externalDisplayNames`/`privilegeProviderName` to counts/enums.

### P1-5 — Exported service with no permission; custom permission defined but unused
`AndroidManifest.xml:38-40` defines `moe.n4tsu.dextop.permission.CARDEX_RELAY` at `protectionLevel="normal"` and never applies it. `AndroidManifest.xml:205-207` `<service .CardexRelayService android:exported="true" />` has no `android:permission`, so any app can bind and drive the privileged relay. `EmbeddedPrivilegeProvider` (`:77-81`) is exported with no manifest permission (code-level UID/key checks help, but no defense in depth); `LinuxHostedWindowActivity` (`:292`) and `LinuxLaunchActivity` (`:359`) are exported with no signature gate.
**Fix:** set `protectionLevel="signature"` and add `android:permission="moe.n4tsu.dextop.permission.CARDEX_RELAY"` to the service; add a signature-level permission to the provider and the two exported activities; keep the existing `Binder.getCallingUid()/PublisherKeys` checks as second layer.

### P1-6 — Unfiltered arbitrary-command AIDL entry points
`ILinuxContainerService.aidl`: `runCommand(containerId, command, timeoutSeconds)` (tx 10), `guestLog(name)`, `setGuestState(name, content)` (tx 46/47), `storageBreakdown(..., path, ...)` (tx 83), `openTerminalIn(..., directory)` (tx 89). App-side containerId/name are validated (`LinuxPaths.kt:21-26` `c[a-zA-Z0-9]+`; `LinuxContainerService.kt:711-730` regex-guarded names), so no direct traversal today, but `runCommand` is an unrestricted shell sink through the privileged service.
**Fix:** delete `runCommand` from production AIDL or gate with `PrivilegeGate.readOnlyDiagnostic`; add `verifyPath(root, relative)` helper (resolve + `canonicalFile.startsWith(root.canonicalFile)`) for every `name`/`path`/`directory` parameter. Keep `setKeyringSecret` (tx 64) and `onAccountPassword` (cb 7) out of all logs — confirm no `OperationLog` call includes their values.

### P2-7 — Failure log records full argv
`PrivilegedAccess.kt:40`: `Log.e(tag, "privileged command failed: ${arguments.joinToString(" ")}", it)`. Any future token/secret passed as argv lands in logcat and in exported reports.
**Fix:** log `argv[0]` + `PrivilegeGate.describe()` only, never joined args; sanitize with `OperationLog.sanitizeForReport`.

### P2-8 — Over-broad manifest permissions on target device
`AndroidManifest.xml:10` `MANAGE_EXTERNAL_STORAGE`, `:32` `QUERY_ALL_PACKAGES`, `:34-35` `REQUEST_INSTALL/DELETE_PACKAGES`. On HyperOS 3 these trigger special-access prompts and are needlessly broad for the common path.
**Fix:** justify in Play/HyperOS declaration or replace content access with SAF/FileProvider; self-grant `WRITE_SECURE_SETTINGS` via `pm grant` (`EmbeddedPrivilegeKeeper.kt:130`) is intentional but must be documented as a privilege escalation boundary.

### P2-9 — Predictable watchdog socket name / no fake-daemon defense
`WatchdogClient.kt:43` / `WatchdogDaemon.kt:38`: `@dextop.wd.$appUid` in the ABSTRACT namespace is guessable by any local process. Auth is token + peer uid (good), but a rogue shell process could bind first when no daemon exists and harvest harmless ARM frames.
**Fix:** derive the socket name from a keyed hash (`"@dextop.wd." + shortHash(token)`), and require the daemon to prove possession of a server nonce in `HELLO_ACK` (client nonce is already checked at `WatchdogClient.kt:51`).

### P3-10 — FileProvider config is correctly narrow (no finding)
`file_paths.xml` exposes only `<cache-path path="diag/" />`; export writes to `cacheDir/diag` and grants a read URI via ClipData (`MainActivity.kt:807-818`). Keep as-is; add a lint assertion that no `root-path`/`external-path` is ever added.

## Deliverable — exact code changes

### 1. New `PrivilegeGate.kt` (app/src/main/kotlin/.../privilege/PrivilegeGate.kt)
```kotlin
internal object CommandAllowlist {
    private val KEY = Regex("[a-z0-9_]+")
    private val SAFE = Regex("[A-Za-z0-9_.:/+=,@ \\-]+")
    private val PKG = Regex("[A-Za-z][A-Za-z0-9_.]+")
    fun settings(verb: String, scope: String, key: String, value: String?): List<String> {
        check(verb in setOf("get", "put", "delete"))
        check(scope in setOf("global", "system", "secure") && KEY.matches(key))
        if (value != null) check(SAFE.matches(value))
        return listOfNotNull("settings", verb, scope, key, value)
    }
    fun wm(sub: String, value: String?, displayId: Int): List<String> {
        check(sub in setOf("size", "density", "user-rotation"))
        check(displayId in 0..63)
        val v = value?.also { check(it == "reset" || SAFE.matches(it)) }
        return listOfNotNull("wm", sub, v, "-d", displayId.toString())
    }
    fun pmGrant(pkg: String, perm: String): List<String> {
        check(PKG.matches(pkg) && PKG.matches(perm))
        return listOf("pm", "grant", pkg, perm)
    }
    fun immutable(vararg args: String): List<String> = args.toList() // only fixed, literal templates
}

internal sealed class PrivilegeGate {
    data class SettingsGet(val scope: String, val key: String) : PrivilegeGate()
    data class SettingsPut(val scope: String, val key: String, val value: String) : PrivilegeGate()
    data class SettingsDelete(val scope: String, val key: String) : PrivilegeGate()
    data class Wm(val sub: String, val value: String?, val displayId: Int) : PrivilegeGate()
    data class PmGrant(val pkg: String, val perm: String) : PrivilegeGate()
    data class ReadOnlyDiagnostic(val template: Template) : PrivilegeGate() {
        enum class Template { DUMPSYS_ACTIVITIES, TELEPHONY_REGISTRY }
    }
    fun argv(): List<String> = when (this) {
        is SettingsGet -> CommandAllowlist.settings("get", scope, key, null)
        is SettingsPut -> CommandAllowlist.settings("put", scope, key, value)
        is SettingsDelete -> CommandAllowlist.settings("delete", scope, key, null)
        is Wm -> CommandAllowlist.wm(sub, value, displayId)
        is PmGrant -> CommandAllowlist.pmGrant(pkg, perm)
        is ReadOnlyDiagnostic -> when (template) {
            Template.DUMPSYS_ACTIVITIES -> listOf("dumpsys", "activity", "activities")
            Template.TELEPHONY_REGISTRY -> listOf("dumpsys", "telephony", "registry")
        }
    }
    fun describe(): String = javaClass.simpleName
}
```
Replace `PrivilegedAccess.execute(vararg String)` with `execute(gate: PrivilegeGate) = ... gate.argv() ...`; delete `executeWithInput` and the Shizuku envp branch entirely. Migrate `SessionJournal.kt:358-405`, `DisplayEnvironmentSettings.kt:64,84,129`, `SamsungDesktopSettings.kt:94-139`, `DisplayTopologyController.kt:408-424`, `EmbeddedPrivilegeKeeper.kt:130`, `MirrorService.kt:7971,13709` to gate types. `WatchdogProtocol.validate()` becomes a thin delegate to `CommandAllowlist` so both paths share one allowlist. In `EmbeddedPrivilegeServer.RuntimeBinder.onTransact`, reject any `executeCommand` argv that does not equal `PrivilegeGate.argv()` for a locally reconstructed gate (defense in depth).

### 2. Redaction (`OperationLog.kt:49-56,118-124`)
```kotlin
private val SANITIZERS = listOf(
    Regex("""(?i)\b(package|descriptor|serial|email|account|path|uri|url|host|endpoint|receiver|friendlyName|ssid|label|classpath|pid|sessionId|token|key|secret|password|nonce|fingerprint|displayName|name)\b\s*[:=]\s*[^,}\s]+""") to "$1=<redacted>",
    Regex("""(?i)"(token|secret|password|sessionId|key)"\s*:\s*"[^"]*"""") to "\"$1\":\"<redacted>\"",
    // keep existing email/uri/path/component/coordinate rules
)
```
`DiagnosticReport.build()`: `return OperationLog.sanitizeDocument(sb.toString())` (line 133). Redact `externalDisplayNames`/`privilegeProviderName` to `"<n> displays"` / enum. Wrap `shareDiagnosticReport`/`diagnosticReport` results with `OperationLog.sanitizeDocument`.

### 3. Watchdog hardening
- `WatchdogDaemon.kt:145`: add UUID guard + canonical-file containment (P1-3).
- `WatchdogClient.kt:28-38,40-54`: remove `executeWithInput`; if runtime unavailable, `arm()` logs and returns; hash socket name; verify server nonce.
- `WatchdogProtocol.kt:83-101`: delegate to `CommandAllowlist`; add `RestoreAction.fromJson` validation at parse time.

### 4. Manifest (`AndroidManifest.xml`)
`protectionLevel="signature"`; add `android:permission` to `CardexRelayService`, `EmbeddedPrivilegeProvider`, `LinuxHostedWindowActivity`, `LinuxLaunchActivity`.

### Tests (new/modified, JVM unit)
- `PrivilegeGateTest.kt`: accepts valid settings/wm/pm/ime; rejects `;`, `$`, backtick, newline, `../`, unknown verb, `wm size 1>/etc/x`.
- `WatchdogProtocolTest.kt`: extend — `validate` rejects injected value; `RestoreAction.fromJson` rejects bad argv; `sessionId "../evil"` rejected by receipt guard.
- `DiagnosticRedactionTest.kt`: seeded text with `sessionId: abc`, `"token":"xyz"`, `path: /data/data/x` → all `<redacted>`; `DiagnosticReport.build()` output contains no seeded values.
- `AndroidManifestTest.kt`: parse manifest, assert CARDEX_RELAY signature + applied to all exported sinks; `file_paths.xml` contains no `root-path`.
- `EmbeddedServerPayloadTest`/`LocalAdbEndpointTest`: extend with gate/allowlist assertions.

## Risks / device testing needed
- Gating every call site is a large mechanical change: run the full M3/M7 unit suite; verify all backends A–F still arm/restore the watchdog and that the 15 s detached rollback still fires on force-stop.
- Verify watchdog token no longer appears in any `/proc/<pid>/environ` or logcat on the Redmi Pad Pro (HyperOS 3); test both embedded-runtime and Shizuku-only privilege providers.
- Confirm signature-level permission does not break the generated Linux launcher APKs (they must be signed with the app key) and that Cardex relay still binds from the companion app.
- Export both ZIP and direct text on device; grep output for device fingerprint, display name, session UUID, `/data/` paths.
- Confirm `pm grant WRITE_SECURE_SETTINGS` self-grant still works post-upgrade on Android 16 (may require re-pairing).
