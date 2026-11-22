# W13A — Command Palette + Snap Layouts (M7) — Wave 13 Report

## Scope inspected
`plasma/BookUi.kt` (1–962: `BookUi`, `BookChrome`, `BookHost` wiring, `compose()`, `showOverlay()`/`hideOverlaySoon()`, `launcherView`/`panelView`/`snapView` at 451–481, `Overlay()` 594–622, `SnapMenu` 545–591), `plasma/BookPanels.kt` (`BookPanelSurface` 61–70, `Sym`/`BookListItem` via `BookKit.kt`), `plasma/PlasmaLauncher.kt` (`LauncherHost` 25–42, `PlasmaSearch` 56–174 incl. `apps()`/`calculate()`/`fuzzy()`, `KRunnerView` 575–640, key handling 597–606), `plasma/PlasmaOverlays.kt` (`PlasmaApp` 207–214, `PlasmaAppRepository` 216–275), `plasma/BookWidgets.kt` (`BookHost` 44–63, `BookStore` 81–99, `GridSpot`), `WorkspaceLayoutEngine.kt` (`fit` 14, `position` 23–54, `usableArea` 56–71), `session/DesktopSessionFsm.kt` (`MutableStateFlow`/`asStateFlow` 92–93), `AppCatalog.kt` (`launchableApps` 15, `launch(packageName, displayId, bounds)` 59–79), `plasma/PlasmaShell.kt` (`handleKey` 1319–1409; META tracking 1325–1333; alt+space KRunner 1369; `tile`/`TileZone` 530/1412), `plasma/CompositedWindowShell.kt` (`handleKey` 4494–4619; **meta+space already → KRunner at 4577**; typing fallback 4603–4611), `plasma/PlasmaDesktopActivity.kt` (`onKeyDown` 78–79), `plasma/AutoWorkspace.kt` (`bounds()` 47–58), `plasma/PlasmaWindows.kt` (`TileZone` 8). No builds, no source edits, no token.

## Findings (P0/P1/P2/P3)

- **P1 — KRunner is legacy View-based and not a command palette.** `KRunnerView` (PlasmaLauncher.kt:577) is a `LinearLayout` with a `PlasmaSearchField`; results are app launches + calculator only; `showKRunner()` is View-popup hosted (PlasmaShell.kt:1008, CompositedWindowShell.kt:4257); `meta && KEYCODE_SPACE` is already consumed by it (CompositedWindowShell.kt:4577). No action registry, no StateFlow, no shortcut hints, no grouped actions. Fix: repoint Super+Space to a native Compose `CommandPaletteOverlay` fed by one `CommandPaletteModel`/`ShortcutRegistry`; keep `KRunnerView` as legacy fallback behind `LauncherHost.openCommandPalette()` returning false.
- **P1 — No single keyboard source of truth; global and Compose paths diverge.** Global keys go through shells' `handleKey` with `metaDown`/`metaCombo`/`swallowedKeys` (View `KeyEvent`); Compose panels have no `onPreviewKeyEvent` dispatcher; nothing publishes to `Activity.onProvideKeyboardShortcuts`. Fix: `ShortcutRegistry` drives (a) `handleKey` bindings, (b) `onPreviewKeyEvent`, (c) `onProvideKeyboardShortcuts`, (d) inline palette hints. KeyDown-only + `repeatCount == 0` (cf. W13C).
- **P1 — Snap is button-only, not magnetic.** `SnapMenu` (BookUi.kt:545–591) offers maximize/left/right; `TileZone` + `WorkspaceLayoutEngine.position()` provide static rects; no drag-time zone highlight, no FancyZones layouts/editor, no persistence. Fix: `SnapLayoutModel` (StateFlow) + `SnapLayoutOverlay` drawn through BookUi's existing `showOverlay()`; `magnetize()` thresholds; rects resolved via `WorkspaceLayoutEngine.position()` so geometry stays identical to existing tiling.
- **P2 — Repository seam is not observable.** `PlasmaAppRepository.apps` is `@Volatile` + callback `refresh(done)`; `AppCatalog` is the RPC-level catalog/launch. Fix: new `CommandRepository` composes both, exposes a StateFlow-friendly snapshot; `SnapLayoutStore` persists layouts via `BookStore`/session WAL JSON (atomic, cf. BRIEF BUG-01).
- **P2 — Workspaces are mutable-field, not StateFlow.** `AutoWorkspace` (primary/secondary/split/ratio) uses plain vars. New models use StateFlow but mirror those semantics (one zone per window, split ratio) — parity, not a parallel model.
- **P3 — Android 16 desktop windowing is system-owned.** Keep palette/zones as additive overlays inside `chrome.primaryArea()`/`WorkspaceLayoutEngine.usableArea()`; detect desktop-windowing mode and degrade (backend F) rather than duplicate the system taskbar.

## Deliverable — exact file skeletons

### NEW `android/app/src/main/kotlin/moe/n4tsu/dextop/plasma/CommandPalette.kt`
```kotlin
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
package moe.n4tsu.dextop.plasma

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.*

internal enum class PaletteKind { APP, ACTION, COMMAND, CALC, SETTING, SNAP }
internal data class PaletteEntry(
    val id: String,
    val label: String,
    val subtitle: String? = null,
    val glyph: String? = null,                 // drawn by Sym()
    val keywords: List<String> = emptyList(),
    val shortcut: String? = null,              // "Super+Space"
    val group: String = "Actions",
    val kind: PaletteKind = PaletteKind.ACTION,
    val run: (PaletteContext) -> Unit,
)
internal data class PaletteContext(val displayId: Int, val windowId: Int?)
internal data class PaletteUiState(
    val open: Boolean = false,
    val query: String = "",
    val entries: List<PaletteEntry> = emptyList(),
    val selected: Int = 0,
)

/** Observable model; mirrors DesktopSessionFsm's StateFlow shape (DesktopSessionFsm.kt:92). */
internal class CommandPaletteModel(private val repo: CommandRepository) {
    private val _ui = MutableStateFlow(PaletteUiState())
    val ui: StateFlow<PaletteUiState> = _ui.asStateFlow()
    private var ctx = PaletteContext(0, null)
    private var catalog: List<PaletteEntry> = emptyList()

    fun toggle(windowId: Int? = null, displayId: Int = 0) {
        if (_ui.value.open) close() else open(windowId, displayId)
    }
    fun open(windowId: Int? = null, displayId: Int = 0) {
        ctx = PaletteContext(displayId, windowId)
        repo.refresh { catalog = buildList { addAll(repo.actions(ctx)); addAll(repo.apps(ctx)) } }
        _ui.value = PaletteUiState(true, "", rank(catalog, ""), 0)
    }
    fun close() { _ui.value = PaletteUiState() }
    fun query(q: String) { _ui.value = _ui.value.copy(query = q, entries = rank(catalog, q), selected = 0) }
    fun move(delta: Int) {
        val s = _ui.value
        _ui.value = s.copy(selected = (s.selected + delta).coerceIn(0, s.entries.lastIndex.coerceAtLeast(0)))
    }
    fun activate(): PaletteEntry? {
        val e = _ui.value.entries.getOrNull(_ui.value.selected)
        close()
        e?.run(ctx)
        return e
    }
    /** Reuses PlasmaSearch scoring/fuzzy (PlasmaLauncher.kt:56). */
    internal fun rank(all: List<PaletteEntry>, raw: String): List<PaletteEntry> {
        val q = raw.trim().lowercase()
        if (q.isEmpty()) return all.take(40)
        val scored = all.mapNotNull { e ->
            val hay = (listOf(e.label) + e.keywords + e.subtitle.orEmpty()).joinToString(" ").lowercase()
            val score = when {
                hay == q -> 0
                hay.startsWith(q) -> 1
                hay.split(' ', '-', '_').any { it.startsWith(q) } -> 2
                hay.contains(q) -> 3
                PlasmaSearch.fuzzyMatch(hay, q) -> 4
                else -> null
            }
            score?.let { it to e }
        }.sortedWith(compareBy({ it.first }, { it.second.label.lowercase() })).map { it.second }
        val calc = PlasmaSearch.calculate(raw)?.let { answer ->
            PaletteEntry("calc:$answer", answer, tr("nativePlasmaCalculatorCopy"), "calculate",
                kind = PaletteKind.CALC) { repo.copyToClipboard(answer) }
        }
        return (listOfNotNull(calc) + scored).take(40)
    }
}

/** One table → dispatcher, Shortcut Helper, palette hints. Compose key path. */
internal data class Shortcut(
    val key: Key, val label: String, val group: String, val actionId: String,
    val meta: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false,
)
internal object ShortcutRegistry {
    val all = listOf(
        Shortcut(Key.Space, "Open Command Palette", "Shell", "palette.toggle", meta = true),
        Shortcut(Key.K, "Open Command Palette", "Shell", "palette.toggle", ctrl = true),
        Shortcut(Key.DirectionLeft, "Snap window left", "Windows", "snap.left", meta = true),
        Shortcut(Key.DirectionRight, "Snap window right", "Windows", "snap.right", meta = true),
        Shortcut(Key.Escape, "Dismiss", "Shell", "palette.close"),
    )
    /** Keydown-only, repeat-filtered; used at shell root (W13C P1). */
    fun dispatch(e: KeyEvent, palette: CommandPaletteModel, snap: SnapLayoutModel): Boolean {
        if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount != 0) return false
        return when {
            e.key == Key.Space && e.isMetaPressed -> { palette.toggle(); true }
            e.key == Key.Escape && palette.ui.value.open -> { palette.close(); true }
            e.key == Key.DirectionDown && palette.ui.value.open -> { palette.move(1); true }
            e.key == Key.DirectionUp && palette.ui.value.open -> { palette.move(-1); true }
            e.key == Key.Enter && palette.ui.value.open -> { palette.activate() != null; true }
            e.key == Key.DirectionLeft && e.isMetaPressed -> { snap.cycle(-1); true }
            e.key == Key.DirectionRight && e.isMetaPressed -> { snap.cycle(1); true }
            else -> false
        }
    }
}

/** Added to BookUi.kt's Overlay() (BookUi.kt:594); M3E surface, initial focus on query. */
@Composable
internal fun CommandPaletteOverlay(model: CommandPaletteModel) {
    val s by model.ui.collectAsState()
    if (!s.open) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().blockPointer(), contentAlignment = Alignment.TopCenter) {
        Surface(
            Modifier.widthIn(min = 480.dp, max = 720.dp).padding(top = 96.dp)
                .clip(RoundedCornerShape(28.dp))
                .onPreviewKeyEvent { ShortcutRegistry.dispatch(it, model, model.snapLayouts() ) },
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                TextField(
                    value = s.query, onValueChange = model::query,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true, shape = RoundedCornerShape(20.dp),
                    placeholder = { Text(tr("uiSearchAppsFromYourPhone")) },
                    leadingIcon = { Sym("search", MaterialTheme.colorScheme.onSurfaceVariant, 22.dp) },
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(s.entries) { i, e ->
                        val selected = i == s.selected
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { model.query(s.query); model.move(i - s.selected); model.activate() }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        ) {
                            if (e.glyph != null) Sym(e.glyph, MaterialTheme.colorScheme.onSurfaceVariant, 20.dp)
                            Column(Modifier.weight(1f)) {
                                Text(e.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                if (e.subtitle != null) Text(e.subtitle!!, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            e.shortcut?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}
```
> Wiring note: expose `CommandPaletteModel.snapLayouts` as a constructor arg (`CommandPaletteModel(repo, snap)`) in the real edit so `ShortcutRegistry.dispatch` can reach both; shown as `model.snapLayouts()` above for brevity. Group headers can reuse `BookGroup`/`BookListItem` (BookKit.kt:267/235) instead of a flat list.

### NEW `android/app/src/main/kotlin/moe/n4tsu/dextop/plasma/SnapLayouts.kt`
```kotlin
package moe.n4tsu.dextop.plasma

import android.graphics.Rect
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.*
import moe.n4tsu.dextop.WorkspaceLayoutEngine
import kotlin.math.abs

internal enum class SnapZone {
    NONE, MAXIMIZE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT,
    BOTTOM_LEFT, BOTTOM_RIGHT, WIDE_LEFT, WIDE_RIGHT,
    THIRD_LEFT, THIRD_CENTER, THIRD_RIGHT,
}
internal data class SnapZoneLayout(val id: String, val name: String, val zones: List<SnapZone>) {
    companion object {
        val PRESETS = listOf(
            SnapZoneLayout("halves", "Left / Right", listOf(SnapZone.LEFT, SnapZone.RIGHT)),
            SnapZoneLayout("quadrants", "Four corners", listOf(SnapZone.TOP_LEFT, SnapZone.TOP_RIGHT,
                SnapZone.BOTTOM_LEFT, SnapZone.BOTTOM_RIGHT)),
            SnapZoneLayout("thirds", "Three columns", listOf(SnapZone.THIRD_LEFT, SnapZone.THIRD_CENTER,
                SnapZone.THIRD_RIGHT)),
            SnapZoneLayout("focus", "Focus + right", listOf(SnapZone.WIDE_LEFT, SnapZone.RIGHT)),
        )
    }
}
internal data class SnapUiState(
    val active: Boolean = false,
    val draggingWindowId: Int? = null,
    val pointer: Offset = Offset.Zero,
    val highlighted: SnapZone = SnapZone.NONE,
    val layouts: List<SnapZoneLayout> = SnapZoneLayout.PRESETS,
    val chosenLayout: Int = 0,
    val menuForWindow: Int? = null,
)

/** Magnetic snap: layout geometry comes from WorkspaceLayoutEngine.position() (parity, no new math). */
internal class SnapLayoutModel(
    private val engine: WorkspaceLayoutEngine,
    private val store: SnapLayoutStore,
    private val displayId: Int,
) {
    private val _ui = MutableStateFlow(SnapUiState(layouts = store.load()))
    val ui: StateFlow<SnapUiState> = _ui.asStateFlow()
    var area: Rect = Rect(0, 0, 1, 1)          // set from chrome.primaryArea()/usableArea()
    var onApply: ((Int, Rect) -> Unit)? = null  // shell seam: applyFrame / bridge.setBounds

    fun beginDrag(windowId: Int) { _ui.value = _ui.value.copy(active = true, draggingWindowId = windowId) }
    /** Call on every pointer move while dragging; returns the magnetized zone. */
    fun drag(x: Float, y: Float): SnapZone {
        val s = _ui.value
        val z = magnetize(Offset(x, y), s.layouts.getOrNull(s.chosenLayout) ?: return SnapZone.NONE)
        _ui.value = s.copy(pointer = Offset(x, y), highlighted = z)
        return z
    }
    fun endDrag(): Rect? {
        val s = _ui.value
        val rect = if (s.highlighted != SnapZone.NONE) rectOf(s.highlighted) else null
        _ui.value = s.copy(active = false, draggingWindowId = null, highlighted = SnapZone.NONE)
        return rect
    }
    fun menu(windowId: Int) { _ui.value = _ui.value.copy(menuForWindow = windowId) }
    fun choose(index: Int) { _ui.value = _ui.value.copy(chosenLayout = index.coerceIn(0, _ui.value.layouts.lastIndex)) }
    fun cycle(delta: Int) { choose(_ui.value.chosenLayout + delta) }
    fun apply(windowId: Int, zone: SnapZone) { rectOf(zone)?.let { onApply?.invoke(windowId, it) } }
    fun rectOf(zone: SnapZone): Rect = engine.position(displayId, area.width(), area.height(), zone.layoutName())

    companion object {
        const val MAGNET_PX = 24f
        fun SnapZone.layoutName() = when (this) {
            SnapZone.LEFT -> "half_left"; SnapZone.RIGHT -> "half_right"
            SnapZone.TOP -> "top_half"; SnapZone.BOTTOM -> "bottom_half"
            SnapZone.TOP_LEFT -> "grid_top_left"; SnapZone.TOP_RIGHT -> "grid_top_right"
            SnapZone.BOTTOM_LEFT -> "grid_bottom_left"; SnapZone.BOTTOM_RIGHT -> "grid_bottom_right"
            SnapZone.WIDE_LEFT -> "wide_left"; SnapZone.WIDE_RIGHT -> "wide_right"
            SnapZone.THIRD_LEFT -> "narrow_left"; SnapZone.THIRD_RIGHT -> "narrow_right"
            SnapZone.THIRD_CENTER -> "center"
            SnapZone.MAXIMIZE, SnapZone.NONE -> "maximize"
        }
    }
    /** Snap within MAGNET_PX of a zone's boundary or centre; else NONE. */
    private fun magnetize(p: Offset, layout: SnapZoneLayout): SnapZone {
        var best = SnapZone.NONE
        var bestD = MAGNET_PX
        layout.zones.forEach { z ->
            val r = rectOf(z)
            val centre = Offset((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
            val edges = listOf(abs(p.x - r.left), abs(p.x - r.right), abs(p.y - r.top), abs(p.y - r.bottom))
            val d = minOf((p - centre).getDistanceSquared().let { kotlin.math.sqrt(it) }, edges.minOrNull() ?: MAGNET_PX)
            if (d < bestD) { bestD = d; best = z }
        }
        if (p.x - area.left <= MAGNET_PX) best = SnapZone.LEFT
        if (area.right - p.x <= MAGNET_PX) best = SnapZone.RIGHT
        if (p.y - area.top <= MAGNET_PX) best = SnapZone.TOP
        if (area.bottom - p.y <= MAGNET_PX) best = SnapZone.BOTTOM
        return best
    }
    fun persist() { store.save(_ui.value.layouts) }
}

/** Added to BookUi.kt's Overlay(); draws translucent zone targets, not a full editor. */
@Composable
internal fun SnapLayoutOverlay(model: SnapLayoutModel) {
    val s by model.ui.collectAsState()
    if (!s.active && s.menuForWindow == null) return
    val colors = MaterialTheme.colorScheme
    val layout = s.layouts.getOrNull(s.chosenLayout) ?: return
    Box(Modifier.fillMaxSize().blockPointer()) {
        layout.zones.forEach { z ->
            val r = model.rectOf(z)
            Box(
                Modifier.offset { IntOffset(r.left, r.top) }
                    .size(width = dpPx(r.width()), height = dpPx(r.height()))
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, if (z == s.highlighted) colors.primary else colors.outlineVariant,
                        RoundedCornerShape(12.dp))
                    .background(if (z == s.highlighted) colors.primary.copy(alpha = .35f)
                                else colors.surfaceContainer.copy(alpha = .25f)),
            )
        }
    }
}
@Composable private fun dpPx(px: Int) = with(androidx.compose.ui.platform.LocalDensity.current) { px.toDp() }
```
> Editor (Win+Shift+` in W13C): add `SnapLayoutEditor` composable later; it writes through `SnapLayoutModel.persist()`. `SnapZone.NONE` maximises via existing `toggleMaximize()` (PlasmaShell.kt:531) for parity.

### NEW `android/app/src/main/kotlin/moe/n4tsu/dextop/plasma/CommandRepository.kt`
```kotlin
package moe.n4tsu.dextop.plasma

import android.content.Context
import android.graphics.Rect
import moe.n4tsu.dextop.AppCatalog
import org.json.JSONArray

/** Repository seam over the existing catalog (AppCatalog.kt:14) + app repo (PlasmaOverlays.kt:216). */
internal class CommandRepository(private val context: Context, private val host: BookHost, private val catalog: AppCatalog) {
    private val apps = PlasmaAppRepository(context)
    fun refresh(done: () -> Unit) = apps.refresh(done)
    fun apps(ctx: PaletteContext): List<PaletteEntry> = apps.apps.map { app ->
        PaletteEntry("app:${app.packageName}", app.label, tr("nativePlasmaApplication"),
            keywords = listOf(app.packageName), kind = PaletteKind.APP) {
            catalog.launch(app.packageName, ctx.displayId)   // AppCatalog.launch(pkg, displayId, bounds)
        }
    }
    fun actions(ctx: PaletteContext): List<PaletteEntry> = buildList {
        add(PaletteEntry("action.overview", tr("nativePlasmaShowDesktop"), glyph = "dashboard",
            shortcut = "Super+W") { host.leave(LeaveAction.LOGOUT) })
        add(PaletteEntry("action.settings", tr("nativePlasmaSystemSettings"), glyph = "settings") {
            host.openBookSettings() })
        addAll(SnapZoneLayout.PRESETS.mapIndexed { i, l ->
            PaletteEntry("snap:${l.id}", l.name, tr("uiResize"), glyph = "grid_view", kind = PaletteKind.SNAP) {
                // applied by caller through SnapLayoutModel.choose(i) + ctx.windowId
            }
        })
    }
    fun copyToClipboard(text: String) = host.copyToClipboardCompat(text)
    fun shutdown() = apps.shutdown()
}
```
`LauncherHost` already has `copyToClipboard` (PlasmaLauncher.kt:41); add the same one-line method to `BookHost` (BookWidgets.kt:44–63) rather than the `*Compat` placeholder.

### NEW `android/app/src/main/kotlin/moe/n4tsu/dextop/plasma/SnapLayoutStore.kt`
```kotlin
package moe.n4tsu.dextop.plasma

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persisted layouts; hook into the session WAL (BRIEF BUG-01) — one JSON blob, atomic apply. */
internal class SnapLayoutStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("dextop_book", Context.MODE_PRIVATE)
    fun load(): List<SnapZoneLayout> {
        val raw = prefs.getString(KEY, null) ?: return SnapZoneLayout.PRESETS
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val z = o.getJSONArray("zones")
                SnapZoneLayout(o.getString("id"), o.getString("name"),
                    (0 until z.length()).map { SnapZone.valueOf(z.getString(it)) })
            }
        }.getOrDefault(SnapZoneLayout.PRESETS)
    }
    fun save(layouts: List<SnapZoneLayout>) {
        val a = JSONArray(layouts.map { l ->
            JSONObject().put("id", l.id).put("name", l.name)
                .put("zones", JSONArray(l.zones.map { it.name }))
        })
        prefs.edit().putString(KEY, a.toString()).apply()
    }
    private companion object { const val KEY = "snap_layouts" }
}
```

### EDITS to existing files (exact anchors)
- **`plasma/BookUi.kt`** — class fields near 249–258:
  ```kotlin
  private val snapModel = SnapLayoutModel(
      moe.n4tsu.dextop.WorkspaceLayoutEngine(context, host.environment(), "BookPalette"),
      SnapLayoutStore(context), displayId).also { it.area = area; it.onApply = { id, r -> chrome.applySnapRect(id, r) } }
  val paletteModel = CommandPaletteModel(CommandRepository(context, host, moe.n4tsu.dextop.AppCatalog(context)), snapModel)
  ```
  New public methods next to `snapView()` (478–481):
  ```kotlin
  fun showCommandPalette(windowId: Int? = null) { paletteModel.toggle(windowId); showOverlay() }
  fun hideCommandPalette() { paletteModel.close(); hideOverlaySoon() }
  fun showSnapMenu(windowId: Int) { snapModel.menu(windowId); showOverlay() }
  fun onWindowDrag(windowId: Int, x: Float, y: Float) { snapModel.drag(x, y); showOverlay() }
  fun onWindowDragEnd(windowId: Int): Rect? { val r = snapModel.endDrag(); hideOverlaySoon(); return r }
  fun paletteOpen() = paletteModel.ui.value.open
  ```
  In `Overlay()` (594–622) add `CommandPaletteOverlay(paletteModel)` and `SnapLayoutOverlay(snapModel)` before `drag?.let`. `hideOverlaySoon()` (433) must also check `paletteOpen()`.
- **`plasma/PlasmaLauncher.kt`** — `LauncherHost` (25–42): add `fun openCommandPalette(): Boolean = false` default. In `KRunnerView.showOnKey` path, `KEYCODE_SPACE` with Meta → delegate to host. Promote `PlasmaSearch.fuzzy` (private, 79) to `internal fun fuzzyMatch` for `CommandPaletteModel.rank`.
- **`plasma/PlasmaShell.kt`** — `handleKey` (1319): add `meta && code == KEYCODE_SPACE -> { bookUi.showCommandPalette(activeTaskId); true }` beside existing alt+space (1369); include `bookUi.paletteOpen()` in `shellTextInputActive()`/popup checks (4604 analogue). `onProvideKeyboardShortcuts` in `PlasmaDesktopActivity` generates groups from `ShortcutRegistry.all`.
- **`plasma/CompositedWindowShell.kt`** — replace `meta && KEYCODE_SPACE -> { showKRunner(); true }` at 4577 with the Compose palette call (keep `showKRunner()` for alt+space); add palette check to `shellTextInputActive()` gate at 4603.
- **`WorkspaceLayoutEngine.kt`** — add `"center" -> Rect(usable.left + third, top, usable.right - third, bottom)` and `"maximize" -> Rect(usable)` to `position()` (23–54); all `SnapZone` rects reuse `usableArea()` (56–71).
- **`plasma/BookPanels.kt`** — no new panel; palette rows reuse `BookPanelSurface`/`BookListItem`; add a "Command Palette" toggle row in `MainPage()` (261) calling `host.openCommandPalette()`.

## Repository seams
| Seam | Existing | New | Responsibility |
|---|---|---|---|
| App source | `PlasmaAppRepository` (callback), `AppCatalog.launchableApps` | `CommandRepository` | Snapshot apps + actions into `PaletteEntry`, launch via `AppCatalog.launch(pkg, displayId, bounds)` |
| Settings/flags | `BookStore` SharedPreferences | `SnapLayoutStore` | Persist `SnapZoneLayout` JSON; migrate to session WAL atomic write |
| Geometry | `WorkspaceLayoutEngine.position()/usableArea()`, `PlasmaWindows.TileZone` | `SnapLayoutModel.rectOf()/magnetize()` | Zone geometry + magnetic hit test; no duplicate math |
| Host/callback | `BookHost`, `BookChrome`, `LauncherHost` | +`openCommandPalette()`, +`applySnapRect(id, rect)`, +`copyToClipboard()` | Shell applies frames; UI never touches `TaskBridge` directly |
| Keyboard | shell `handleKey`, `PlasmaDesktopActivity.onKeyDown` | `ShortcutRegistry` + Compose `onPreviewKeyEvent` + `onProvideKeyboardShortcuts` | One binding table, three consumers |

## Parity with existing workspaces
- **Geometry:** every `SnapZone` maps to an existing `WorkspaceLayoutEngine.position()` string (`half_left`, `grid_top_right`, `wide_left`, …) and existing `TileZone`; `fit()` (14) clamps drags to `usableArea()`, identical to `AutoWorkspace.bounds(width,height,gap,ratio)` behaviour.
- **Lifecycle:** palette/snap overlays use the same `ComposeView` + `showOverlay()`/`hideOverlaySoon()` + `blockPointer()` pattern as tooltip/volume/drag (BookUi.kt:426–440, 312–323), so they auto-clean on `destroy()` (337).
- **State:** StateFlow models mirror `DesktopSessionFsm` (`MutableStateFlow` + `asStateFlow`, pure reducer style) while `AutoWorkspace`'s primary/secondary/split/ratio map to "one zone per window"; no second layout truth.
- **Theming/i18n:** M3E `MaterialExpressiveTheme`/`MotionScheme.expressive()`, `Sym()`, `tr()`/`trf()` from `BookKit.kt` — same as panels/launcher.
- **Legacy:** `KRunnerView`/`showKRunner()` retained as fallback; `PlasmaSearch` and `LeaveAction` reused, not forked.

## Risks / device testing needed
- HyperOS 3 may consume/remap Meta; verify Super+Space with a real BT keyboard on the Redmi Pad Pro 5G; keep Ctrl+K and Alt+Space fallbacks; `onProvideKeyboardShortcuts` (Meta+/) on non-Meta keyboards.
- Global `handleKey` `swallowedKeys` must not swallow palette typing; test type-ahead while focus stays on the app window (CompositedWindowShell.kt:4603 path).
- Magnetic threshold `MAGNET_PX=24f` and zone highlight redraw at 120 Hz on 12.1" — Perfetto trace; keep geometry/resolve off the main looper (cf. BUG-02).
- Snap persistence must be atomic in the session WAL; stale HyperOS callbacks must not reapply an old generation (cf. `DesktopSessionFsm` generation guard).
- TalkBack/switch-access with palette open: initial focus on query, M3E focus rings, modal focus trap, focus restoration on dismiss; keyboard-only acceptance test in M8.
- Coexist with Android 16 desktop windowing/system taskbar under backends A/B/E; degrade to overlay-only under F.
