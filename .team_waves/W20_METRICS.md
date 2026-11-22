# W20A (metrics/Secure minimal patch) — Wave 20 Report

## Scope inspected
`SessionJournal.kt:18-20,51-79,134-165,394-425`, `WatchdogProtocol.kt:17-20,85-103`, `MirrorService.kt:205,265-315,1006-1019,1101,12356-12362,12709-12712,14045-14065`, JVM tests `WatchdogProtocolTest.kt`, `SessionWalCodecTest.kt`. No builds, no source edits, no token.

## Findings
- **P1 (in scope)** 5 unjournaled mutations: `applyXiaomiDesktopDensity` (`MirrorService.kt:12709-12712`), `applyAutoLogicalSize` (`:12356-12362`), and three `Settings.Secure` accessibility writes (`:286-315`, `:1006-1019`, `:14045-14065`). BUG-01 recurrence: kill/reboot leaves forced density/size and accessibility service list unrestored.
- **P1 (enabler)** `SessionJournal` has no `rememberSecure`; the two companion Secure sites cannot reach the instance journal (`:1101`). `writeSetting`/`readSetting` privileged branch already handles `secure` via `scope.shellName` (`:397,399,414`); only the API + call sites are missing.
- No P0. `SessionJournal.kt:74` `.put("phase","preparing")` is present — all patches depend on it; leave untouched.
- Deliberately **excluded** (out of scope, keep W18 for later): live-resize/pin/clear records, `wm-rotation`, `DisplayTopologyController`, `AndroidAutoMirrorActivity` overlay reorder. `WatchdogProtocol` needs no change: `"settings"` branch already allowlists `delete|put ... secure <key>` and `"wm"` branch allowlists `size|density <v> -d <id>`.

## Deliverable — exact patches (surgical, CI-safe)

### P0. `SessionJournal.kt` — add `rememberSecure` after `rememberSystem` (`:138-139`)
```kotlin
     fun rememberSystem(key: String, value: String?) =
         recordSetting(JournalScope.SYSTEM, key, JournalBefore(value != null, value), null)
 
+    /** MUST be called before a Settings.Secure write; first writer for a key wins. */
+    fun rememberSecure(key: String, value: String?) =
+        recordSetting(JournalScope.SECURE, key, JournalBefore(value != null, value), null)
```
No change to `writeSetting`/`readSetting`. Keep `JournalScope.SECURE -> false` (`:407`) / `-> null` (`:422`) so unprivileged restore fails closed and retains the WAL. Optionally annotate both: `// secure requires shell; never report success`.

### P1. `MirrorService.kt` companion — single journal accessor
At `:205` (`private var instance: MirrorService? = null`), add:
```kotlin
         private var instance: MirrorService? = null
+        private val journalLock = Any()
+        private var journalInstance: SessionJournal? = null
+        internal fun journal(context: Context): SessionJournal = synchronized(journalLock) {
+            journalInstance ?: SessionJournal(context.applicationContext).also { journalInstance = it }
+        }
```
At `:1101`:
```kotlin
-    private val sessionJournal by lazy { SessionJournal(this) }
+    private val sessionJournal get() = journal(this)
```
All existing `sessionJournal.*` call sites unchanged. Companion Secure sites now share the instance WAL.

### P2. `MirrorService.kt:12709-12712` — density record
```kotlin
     private fun applyXiaomiDesktopDensity(displayId: Int, dpi: Int) {
+        sessionJournal.recordWm(displayId, "density", "reset", dpi.toString())
         applyForcedMetric("setForcedDisplayDensityForUser", displayId, dpi, safeUserId())
         verifyDisplayMetrics(displayId, "density=$dpi")
     }
```

### P3. `MirrorService.kt:12356-12362` — logical-size record
```kotlin
     private fun applyAutoLogicalSize() {
         val service = systemService("window", "android.view.IWindowManager")
         val type = Class.forName("android.view.IWindowManager")
+        sessionJournal.recordWm(targetDisplayId, "size", "reset", "${targetWidth}x$targetHeight")
         type.getMethod("setForcedDisplaySize", Int::class.javaPrimitiveType,
             Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
             .invoke(service, targetDisplayId, targetWidth, targetHeight)
     }
```
`before="reset"` is the correct baseline (panel default); `recordWm` is first-writer-wins so an earlier true baseline survives later resizes.

### P4. `MirrorService.kt` — three Secure writes

Site A, companion launch (`:286-292`); `current` is the pre-mutation raw value captured at `:272-275`:
```kotlin
             if (instance == null && enabled) {
                 val withoutDextop = services.filterNot { it == component }
+                journal(context).rememberSecure(
+                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current.ifBlank { null })
+                journal(context).rememberSecure(
+                    Settings.Secure.ACCESSIBILITY_ENABLED,
+                    runCatching { Settings.Secure.getInt(context.contentResolver,
+                        Settings.Secure.ACCESSIBILITY_ENABLED, 0).toString() }.getOrDefault("0"))
                 Settings.Secure.putString(
                     context.contentResolver,
                     Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                     withoutDextop.joinToString(":")
                 )
```

Site B, `enableForDemo` (`:1006-1009`); `services` still holds the pre-add set here:
```kotlin
             runCatching {
                 enabledForDemo = true
+                val beforeEnabled = Settings.Secure.getString(context.contentResolver,
+                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).ifBlank { null }
+                journal(context).rememberSecure(
+                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, beforeEnabled)
+                journal(context).rememberSecure(
+                    Settings.Secure.ACCESSIBILITY_ENABLED,
+                    runCatching { Settings.Secure.getInt(context.contentResolver,
+                        Settings.Secure.ACCESSIBILITY_ENABLED, 0).toString() }.getOrDefault("0"))
                 if (services.add(component)) {
```

Site C, `disableDextopAccessibilityService` (`:14045-14051`) — currently unreferenced, so this is inert today:
```kotlin
     private fun disableDextopAccessibilityService() {
         runCatching {
             val own = ComponentName(this, MirrorService::class.java)
-            val remaining = Settings.Secure.getString(
+            val before = Settings.Secure.getString(
                 contentResolver,
                 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
-            ).orEmpty().split(':').filter { raw ->
+            )
+            sessionJournal.rememberSecure(
+                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, before?.ifBlank { null })
+            sessionJournal.rememberSecure(
+                Settings.Secure.ACCESSIBILITY_ENABLED,
+                runCatching { Settings.Secure.getInt(contentResolver,
+                    Settings.Secure.ACCESSIBILITY_ENABLED, 0).toString() }.getOrDefault("0"))
+            val remaining = before.orEmpty().split(':').filter { raw ->
                 raw.isNotBlank() && ComponentName.unflattenFromString(raw)?.let { component ->
                     component != own
                 } != false
             }
```
Null/blank `before` → `rememberSecure(key, null)` → `watchdogActions()` emits `settings delete secure <key>` (`:91-95`), deleting the row rather than leaving a NULL value.

### JVM tests (pure, no device)
Extend `WatchdogProtocolTest.kt`:
```kotlin
    @Test
    fun validate_accepts_secure_and_forced_metric_restore_argv() {
        WatchdogProtocol.validate(listOf("settings", "delete", "secure", "enabled_accessibility_services"))
        WatchdogProtocol.validate(listOf("settings", "put", "secure", "accessibility_enabled", "0"))
        WatchdogProtocol.validate(listOf("wm", "density", "320", "-d", "3"))
        WatchdogProtocol.validate(listOf("wm", "size", "reset", "-d", "3"))
    }
```
Extend `SessionWalCodecTest.kt`:
```kotlin
    @Test
    fun secure_setting_and_forced_metric_records_canonicalize() {
        fun record() = JSONObject()
            .put("id", "secure:enabled_accessibility_services").put("kind", "setting")
            .put("namespace", "secure").put("key", "enabled_accessibility_services")
            .put("present", false).put("value", JSONObject.NULL).put("after", JSONObject.NULL)
        val wm = JSONObject().put("id", "wm:density:3").put("kind", "wm")
            .put("displayId", 3).put("command", "density").put("value", "reset").put("after", "320")
        val canonical = SessionWalCodec.canonical(JSONObject()
            .put("mutations", JSONArray().put(record()).put(wm)))
        assertEquals(canonical, SessionWalCodec.canonical(JSONObject()
            .put("mutations", JSONArray().put(record()).put(wm))))
        assertEquals(true, canonical.contains("\"namespace\":\"secure\""))
        assertEquals(true, canonical.contains("\"value\":null"))
        assertEquals(true, canonical.contains("\"command\":\"density\""))
    }
```
`SessionJournal.rememberSecure` / `restoreAll()` themselves stay device-only (need Context/WallpaperManager/privileged shell); do not Robolectric them. No source-path-dependent lint test added (keeps CI deterministic).

## Risks / device testing needed
- Redmi Pad Pro 5G / HyperOS 3, API 36: kill -9 mid-start, reboot — confirm forced density/size reset and `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` row **deleted** (not NULL-valued) when originally absent.
- `journalInstance` is process-wide but not per-session; `rememberSecure` first-writer-wins + `begin` phase guard keep ordering stable. AndroidAuto still owns a separate `SessionJournal` on the same WAL path (out of scope) — do not run Auto and companion sessions concurrently during validation.
- `getInt` wrapped in `runCatching` so a non-numeric Secure value cannot crash the start/demo path.
- Site C is dead code: if re-enabled later, call it **before** `restoreSystemSettings()` (`:14022`) or its record poisons the WAL and re-arms the service during teardown.
- StrictMode/Perfetto: `rememberSecure` performs a Secure read + `fd.sync()`; confirm it runs on the companion start path, not inside a Surface callback (W17 BUG-02).
