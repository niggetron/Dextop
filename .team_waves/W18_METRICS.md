# W18A — Wave 18 Report: forced-metric + Secure WAL journaling design

## Scope inspected
`SessionJournal.kt` (448 L, full), `WatchdogProtocol.kt:82-101`, `MirrorService.kt` (metric sites 12352-12375, 12644-12712, 13300-13380; Secure sites 280-319, 986-1021, 14045-14068; journal ownership 1054-1104, 14014-14025), `AndroidAutoMirrorActivity.kt:505-696`, `DisplayTopologyController.kt:19-438` + call sites `DisplayEnvironmentSettings.kt:111,115`, `MainActivity.kt:542-630`, `PhoneRotationController.kt:12-42`, `DesktopModeConfigurator.kt:36-194`, JVM tests `WatchdogProtocolTest.kt`, `SessionWalCodecTest.kt`. No builds, no source edits.

## Findings (carry-over from W17, confirmed against current source)
- **P1** Every forced `wm size`/`wm density` mutation is unjournaled: `MirrorService.kt:12709-12712` density, `:12356-12362` logical size, `:13318-13333` live resize, `:13371-13379` pin/clear, `:12690-12703` inherited-override clear (records only the *re-pin*, not the clear), `AndroidAutoMirrorActivity.kt:677-696`, `DisplayTopologyController.kt:405-429`.
- **P1** `SessionJournal` has no `rememberSecure`; `writeSetting():407` / `readSetting():422` fail closed for `JournalScope.SECURE` (privileged branch at `:395-402`/`:413-417` already does handle `secure`, so only the API + call sites are missing). Three unjournaled `Settings.Secure` writes: `MirrorService.kt:288-315`, `:1009-1019`, `:14045-14065`.
- **P1** `AndroidAutoMirrorActivity.kt:574-587` sanitizes `overlay_display_devices` **before** `sessionJournal.preparing()` at `:587`, so the pre-sanitize overlay is not the WAL baseline.
- **P0 already applied** — `SessionJournal.kt:74` now carries `.put("phase","preparing")`. Keep it; all patches below depend on it.
- **New P1** Two `SessionJournal` instances (`MirrorService.kt:1101`, `AndroidAutoMirrorActivity.kt:514`) share the same WAL path (`context.filesDir`) but keep independent in-memory `state`/`sequence`. Companion-side Secure writes therefore cannot safely reach the instance journal. Fix via one companion-owned journal (Patch B0) and note the Auto risk in Risks.
- **New P1** `WatchdogProtocol.validate():96` allowlists only `wm size|density`; a `wm user-rotation` restore record is silently dropped from the watchdog ARM frame. Must extend the allowlist (Patch A5).
- **Design nuance** `SessionJournal.kt:202` already skips `kind == "rotation"` (phone rotation, owned by `PhoneRotationController`). Physical-display rotation must use a **distinct** kind (`wm-rotation`) to avoid that skip.

## Deliverable — exact patches

### A. `SessionJournal.kt` — new record kinds + Secure API

**A1 — `rememberSecure`, after `rememberSystem` (`:138-140`):**
```kotlin
    fun rememberSystem(key: String, value: String?) =
        recordSetting(JournalScope.SYSTEM, key, JournalBefore(value != null, value), null)

+   /** MUST be called before a Settings.Secure write; first writer for a key wins. */
+   fun rememberSecure(key: String, value: String?) =
+       recordSetting(JournalScope.SECURE, key, JournalBefore(value != null, value), null)
```

**A2 — physical rotation record, after `recordWm` (`:157-165`):**
```kotlin
    fun recordWm(displayId: Int, command: String, before: String, after: String) { /* unchanged */ }

+   /** Capture a physical display's user-rotation lock before changing it. */
+   fun recordDisplayRotation(displayId: Int, before: String) {
+       if (state.optString("phase", "idle") == "idle") preparing(0, 0, 0, false)
+       val id = "wm-rotation:$displayId"
+       if (has(id)) return
+       append("mutate", JSONObject()
+           .put("id", id).put("kind", "wm-rotation")
+           .put("displayId", displayId).put("before", before))
+   }
```
`before ∈ {"unlock", "lock:<0..3>"}` — produced by the query helpers in Patch D.

**A3 — `watchdogActions()` `when` (`:100-112`), add branch before `"wm"`:**
```kotlin
+               "wm-rotation" -> {
+                   val argv = rotationRestoreArgv(
+                       record.optInt("displayId"), record.optString("before", "unlock"))
+                   if (runCatching { WatchdogProtocol.validate(argv) }.isSuccess) {
+                       add(WatchdogProtocol.RestoreAction("wm-rotation", argv))
+                   }
+               }
                "wm" -> {
                    val displayId = record.optInt("displayId")
                    ...
```

**A4 — `restoreOne()` `when` (`:359-392`), add branch before `"wm"`:**
```kotlin
+       "wm-rotation" -> privileged.execute(
+           *rotationRestoreArgv(record.optInt("displayId"), record.optString("before", "unlock"))
+       ).succeeded
        "wm" -> {
            val value = record.optString("value", "reset")
            ...
```

**A5 — pure argv helper, before `SessionWalCodec` (`:426-428`) and `WatchdogProtocol.kt:94-98`:**
```kotlin
// SessionJournal.kt
+internal fun rotationRestoreArgv(displayId: Int, before: String): List<String> =
+    if (before == "unlock" || before.isBlank()) {
+        listOf("wm", "user-rotation", "-d", displayId.toString(), "unlock")
+    } else {
+        listOf("wm", "user-rotation", "-d", displayId.toString(), "lock",
+            before.removePrefix("lock:"))
+    }
```
```kotlin
// WatchdogProtocol.kt:94-98 — replace the "wm" branch
             "wm" -> {
-                check(argv.size in 2..5)
-                check(argv[1] in setOf("size", "density"))
-                check(argv.drop(1).all { it == "reset" || it == "-d" || SAFE_VALUE.matches(it) })
+                check(argv.size in 2..6)
+                check(argv.drop(1).all {
+                    it == "reset" || it == "-d" || it == "lock" || it == "unlock" ||
+                        SAFE_VALUE.matches(it)
+                })
+                when (argv[1]) {
+                    "size", "density" -> check(argv.size in 2..5)
+                    "user-rotation" -> {
+                        check(argv[2] == "-d" && argv[3].toIntOrNull() != null)
+                        check((argv[4] == "unlock" && argv.size == 5) ||
+                            (argv[4] == "lock" && argv.size == 6 && argv[5].toIntOrNull() in 0..3))
+                    }
+                    else -> error("wm command=${argv[1]}")
+                }
             }
```

**A6 — Secure restore path:** no code change needed in `writeSetting():394-410` / `readSetting():412-425`: the privileged branch (`settings put|delete|get secure <key>`) already covers `JournalScope.SECURE`. Keep `SECURE -> false` / `-> null` in the unprivileged fallback so restore **fails closed** and the WAL is retained (`:206-210`). Add a comment to both lines: `// secure requires shell; fall through to failure, never report success`.

**A7 — addendum (same file, W17 P1):** `restoreOne("overlay")` at `:378-389` — when `before == null`, replace `writeSetting(GLOBAL, ..., null)` with `privileged.execute("settings","delete","global", DisplayMirrorBackend.DISPLAY_SPECIFICATION).succeeded` so the row is deleted, not left NULL-valued.

### B. `MirrorService.kt` — one journal, then journal every metric/Secure write

**B0 — single companion-owned journal (prerequisite for the 288/1009 Secure sites):**
```kotlin
// MirrorService.kt:148 companion object, add fields + accessor
+        private var boundAppContext: Context? = null
+        private val journalLock = Any()
+        private var journalInstance: SessionJournal? = null
+        internal fun bindJournal(context: Context) { boundAppContext = context.applicationContext }
+        internal fun journal(context: Context): SessionJournal = synchronized(journalLock) {
+            journalInstance ?: SessionJournal(boundAppContext ?: context.applicationContext)
+                .also { journalInstance = it }
+        }
```
```kotlin
// MirrorService.kt:1101
-    private val sessionJournal by lazy { SessionJournal(this) }
+    private val sessionJournal get() = journal(this)
```
Call `bindJournal(applicationContext)` from `attachBaseContext`/`onCreate`. All existing `sessionJournal.*` call sites are unchanged.

**B1 — density override (`:12709-12712`):**
```kotlin
     private fun applyXiaomiDesktopDensity(displayId: Int, dpi: Int) {
+        sessionJournal.recordWm(displayId, "density", "reset", dpi.toString())
         applyForcedMetric("setForcedDisplayDensityForUser", displayId, dpi, safeUserId())
         verifyDisplayMetrics(displayId, "density=$dpi")
     }
```

**B2 — Android Auto logical size (`:12356-12362`):**
```kotlin
     private fun applyAutoLogicalSize() {
         val service = systemService("window", "android.view.IWindowManager")
         val type = Class.forName("android.view.IWindowManager")
+        sessionJournal.recordWm(targetDisplayId, "size", "reset", "${targetWidth}x$targetHeight")
         type.getMethod("setForcedDisplaySize", ...).invoke(service, targetDisplayId, targetWidth, targetHeight)
     }
```

**B3 — live resize (`:13318-13333`, `:13375`):** first-writer-wins keeps the original `reset` baseline recorded at creation — exactly what restore needs.
```kotlin
             if (sizeChanged && !(autoOnlySession && autoOwnedDisplay != null) && phoneOwnedDisplay == null) {
+                sessionJournal.recordWm(targetDisplayId, "size", "reset", "${next.width}x${next.height}")
                 type.getMethod("setForcedDisplaySize", ...).invoke(service, targetDisplayId, next.width, next.height)
             }
             if (densityChanged || isScopedXiaomiTablet) {
+                sessionJournal.recordWm(targetDisplayId, "density", "reset", next.density.toString())
                 type.getMethod("setForcedDisplayDensityForUser", ...)
                     .invoke(service, targetDisplayId, next.density, userId)
             }
```
```kotlin
             if (isScopedXiaomiTablet && xiaomiFlags.pinMetrics) {
+                sessionJournal.recordWm(targetDisplayId, "size", "reset", "${targetWidth}x$targetHeight")
                 applyForcedMetric("setForcedDisplaySize", targetDisplayId, targetWidth, targetHeight)
```

**B4 — inherited-override clear (`:12690-12693`): record the clear itself before mutating.**
```kotlin
     private fun clearInheritedDisplayOverrides(displayId: Int) {
         val userId = safeUserId()
+        sessionJournal.recordWm(displayId, "size", "reset", "reset")
+        sessionJournal.recordWm(displayId, "density", "reset", "reset")
         applyForcedMetric("clearForcedDisplaySize", displayId)
         applyForcedMetric("clearForcedDisplayDensityForUser", displayId, userId)
```

**B5 — Secure accessibility writes, all three sites (before each mutation).** Site `:286-292` (companion, `instance == null`):
```kotlin
                 if (instance == null && enabled) {
                     val withoutDextop = services.filterNot { it == component }
+                    val j = journal(context)
+                    j.rememberSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
+                        services.joinToString(":").ifBlank { null })
+                    j.rememberSecure(Settings.Secure.ACCESSIBILITY_ENABLED,
+                        Settings.Secure.getInt(context.contentResolver,
+                            Settings.Secure.ACCESSIBILITY_ENABLED, 0).toString())
                     Settings.Secure.putString(context.contentResolver,
                         Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                         withoutDextop.joinToString(":"))
```
Site `:1006-1019` (`showOverlayDemo`, snapshot pre-add value):
```kotlin
             val beforeEnabled = services.joinToString(":").ifBlank { null }   // BEFORE services.add
             runCatching {
                 enabledForDemo = true
                 if (services.add(component)) {
+                    journal(context).rememberSecure(
+                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, beforeEnabled)
                     Settings.Secure.putString(..., services.joinToString(":"))
                 }
+                journal(context).rememberSecure(Settings.Secure.ACCESSIBILITY_ENABLED,
+                    Settings.Secure.getInt(context.contentResolver,
+                        Settings.Secure.ACCESSIBILITY_ENABLED, 0).toString())
                 Settings.Secure.putInt(..., Settings.Secure.ACCESSIBILITY_ENABLED, 1)
```
Site `:14045-14065` (`disableDextopAccessibilityService`, currently **unreferenced** — if re-enabled, call it before `restoreSystemSettings()` at `:14022`, otherwise a post-restore record poisons the WAL):
```kotlin
             val before = Settings.Secure.getString(contentResolver,
                 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
+            sessionJournal.rememberSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
+                before?.ifBlank { null })
+            sessionJournal.rememberSecure(Settings.Secure.ACCESSIBILITY_ENABLED,
+                Settings.Secure.getInt(contentResolver,
+                    Settings.Secure.ACCESSIBILITY_ENABLED, 0).toString())
             val remaining = before.orEmpty().split(':').filter { ... }
```
Null `before` → `rememberSecure(key, null)` → `watchdogActions()` emits `settings delete secure <key>` (`:91-95`), correct for an absent row.

### C. `AndroidAutoMirrorActivity.kt` — snapshot before sanitize; journal resize

**C1 — move `preparing()` above the sanitize block (`:563` / `:587`):**
```kotlin
         requestedSpec = spec
+        // Open the transaction before ANY mutation: the WAL overlay baseline must
+        // be the pre-sanitize value, and geometry must be the real request.
+        sessionJournal.preparing(width, height, density, effectiveDecorations)
         runCatching {
             val before = Settings.Global.getString(...).orEmpty()
             ...
                 if (sanitized != before) {
                     check(Settings.Global.putString(... sanitized)) { ... }
                 }
-            sessionJournal.preparing(width, height, density, effectiveDecorations)  // DELETE
```
One reorder is sufficient: `preparing()`'s `initial` array (`:63-70`) already snapshots the overlay key. Do **not** add a second `rememberGlobal` for the same key — duplicate ids with different values would make restore order ambiguous. `rememberGlobal` / `recordSetting` auto-open an implicit session with zeroed geometry (`:142-145`), which is why the reorder is required rather than journaling first.

**C2 — `resizeLogical()` (`:677-688`):**
```kotlin
     fun resizeLogical(width: Int, height: Int, density: Int) {
         if (!isActive || displayId < 0) return
+        sessionJournal.recordWm(displayId, "size", "reset", "${width}x$height")
+        sessionJournal.recordWm(displayId, "density", "reset", density.toString())
         val service = privilegedAccess.service("window", "android.view.IWindowManager")
```

### D. `DisplayTopologyController.kt` — inject journal, record density + rotation

**D1 — constructor + journal (`:19-23`); default arg keeps all other call sites unchanged:**
```kotlin
-class DisplayTopologyController(private val context: Context) {
+class DisplayTopologyController(
+    private val context: Context,
+    private val sessionJournal: SessionJournal? = null,
+) {
     private val privilegedAccess = PrivilegedAccess("DextopTopology")
+    private val journal by lazy { sessionJournal ?: SessionJournal(context.applicationContext) }
```
`MainActivity.kt:611` → `DisplayTopologyController(this, MirrorService.journal(this))` so physical-display restores share the phone-session WAL.

**D2 — `setDensity()` (`:405-412`) + query helper:**
```kotlin
     fun setDensity(displayId: Int, dpi: Int): Map<String, Any> {
         checkPhysical(displayId)
+        journal.recordWm(displayId, "density", currentDensityArg(displayId), dpi.toString())
         val result = privilegedAccess.execute("wm", "density",
             if (dpi > 0) dpi.toString() else "reset", "-d", displayId.toString())
```
```kotlin
+   /** Current override dpi, or "reset" when the panel default is in effect. */
+   private fun currentDensityArg(displayId: Int): String =
+       Regex("Override density: (\\d+)")
+           .find(privilegedAccess.execute("wm", "density", "-d", displayId.toString()).output)
+           ?.groupValues?.get(1) ?: "reset"
```

**D3 — `setRotation()` (`:420-429`) + query helper:**
```kotlin
     fun setRotation(displayId: Int, rotation: Int): Map<String, Any> {
         checkPhysical(displayId)
         require(rotation in 0..3)
+        journal.recordDisplayRotation(displayId, currentRotationArg(displayId))
         val result = privilegedAccess.execute("wm", "user-rotation", "-d",
             displayId.toString(), "lock", rotation.toString())
```
```kotlin
+   private fun currentRotationArg(displayId: Int): String {
+       val output = privilegedAccess.execute("wm", "user-rotation", "-d", displayId.toString()).output
+       val locked = Regex("Rotation lock state:\\s*locked(?:,\\s*rotation:\\s*(\\d+))?").find(output)
+       return locked?.let { "lock:${it.groupValues[1].ifBlank { "0" }}" } ?: "unlock"
+   }
```
`MainActivity.kt:608-619` already runs these on a `Thread`, so the extra `wm` read-back adds no main-thread IPC (W17 BUG-02 concern avoided).

## JVM test plan (no device, no Android framework)

1. **`SessionRestorePlanTest.kt`** (new; pure `org.json` + `kotlin.test`) — extract the argv construction out of `watchdogActions()` into `internal object SessionRestorePlan { fun argv(record: JSONObject): List<String>? }` and test:
   - `{"kind":"wm","command":"size","value":"reset","displayId":3}` → `["wm","size","reset","-d","3"]`
   - `{"kind":"wm","command":"density","value":"320","displayId":3}` → `["wm","density","320","-d","3"]`
   - `{"kind":"wm-rotation","displayId":3,"before":"unlock"}` → `["wm","user-rotation","-d","3","unlock"]`
   - `before="lock:1"` → `["wm","user-rotation","-d","3","lock","1"]`
   - `{"kind":"setting","namespace":"secure","key":"enabled_accessibility_services","value":null}` → `["settings","delete","secure","enabled_accessibility_services"]`
   - unknown kind → `null`; every produced argv passes `WatchdogProtocol.validate`.
2. **`WatchdogProtocolTest.kt`** (extend): `validate` accepts `wm user-rotation -d 3 unlock` and `... -d 3 lock 2`; rejects `lock 4`, `unlock 2`, `-d x`, missing `-d`, and `wm user-rotation;reboot`. `actionsFromJson(actionsToJson(...))` round-trips a `RestoreAction("wm-rotation", ...)`.
3. **`SessionWalCodecTest.kt`** (extend): canonical JSON of a `wm-rotation` record with `before="lock:1"` is byte-stable; CRC32 detects a single-byte flip; `JSONObject.NULL` canonicalises to `null` (covers null Secure baseline).
4. **`ForcedMetricJournalContractTest.kt`** (new, source-text lint like the repo's other contract tests): read the four Kotlin sources and assert, per mutation regex, that a `recordWm(...)`/`rememberSecure(...)`/`recordDisplayRotation(...)` occurs within the preceding 6 lines — covering `setForcedDisplaySize`, `setForcedDisplayDensityForUser`, `clearForcedDisplay*`, `wm user-rotation ... lock`, and `Settings.Secure.putString|putInt`. Assert `AndroidAutoMirrorActivity.kt` contains `sessionJournal.preparing(` **before** the `overlay_display_devices` sanitize `putString`, and that `SessionJournal.kt` still has `.put("phase", "preparing")` in `begin`, `rememberSecure`, `recordDisplayRotation`, `rotationRestoreArgv`, and the `"wm-rotation"` branches in both `watchdogActions()` and `restoreOne()`.
5. `SessionJournal.restoreAll()` itself stays **device-only** (needs Context, WallpaperManager, privileged shell); do not try to Robolectric it — assertions 1-4 cover the pure logic and the ordering contract.

## Risks / device testing needed
- Redmi Pad Pro 5G / HyperOS 3, API 36: `wm user-rotation -d <id>` output format must match the regex in D3; if HyperOS omits the rotation value, `lock:0` is a safe fallback but verify with `dumpsys display`.
- `wm density -d <id>` "Override density:" label is AOSP text; confirm on HyperOS 3, else `currentDensityArg` returns `reset` and restore clears an existing override. Pin behind a versioned adapter.
- Two `SessionJournal` instances still point at the same WAL path (`MirrorService` ↔ `AndroidAutoMirrorActivity`): B0 fixes only the MirrorService side. Either route Auto through `MirrorService.journal(context)` as well or give `SessionJournal` a process-wide singleton with re-read of `sequence` before append; otherwise interleaved sessions can collide on `seq`.
- Verify kill -9 mid-resize and mid-Auto-session, reboot, then: all forced size/density overrides reset, physical rotation unlocked (or restored to captured lock), and `Secure.ENABLED_ACCESSIBILITY_SERVICES` row deleted when originally absent (not NULL-valued).
- StrictMode trace: `rememberSecure` reads `Settings.Secure.getInt/getString` plus a `fd.sync()` — confirm these run on the companion start path or IO thread, not inside a Surface callback (W17 BUG-02). `DisplayTopologyController` queries stay on `MainActivity`'s `Thread`.
- Do not journal `disableDextopAccessibilityService()` if it runs after restore; re-enabling Dextop's own accessibility component during teardown would immediately re-arm the service.
