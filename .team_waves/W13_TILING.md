# W13B (M7) — Wave 13 Report: Auto-tiling + Tablet Stage Manager

## Scope inspected
- `android/app/src/main/kotlin/moe/n4tsu/dextop/WorkspaceLayoutEngine.kt:14-71` — static string presets (`position()`) + `usableArea()` insets.
- `.../AutoSplitPlacement.kt:11-111` — car-only singleton split transaction, `TaskBridge.setBounds/setWindowingMode(5)`, retry-to-place pattern.
- `.../plasma/CompositedWindowShell.kt:553-608` (`layoutAutoWorkspace`), `:794-819` (poll/settings reflow), `:903-933` (`buildDesktopChrome`), `:2831-2845` (`tileRect`), `:2851-2900` (`setFrame`/display resize), `:3161-3179` (`tile()`), `:4494-4603` (`handleKey`), `:2248-2261/:3025-3037` (restoreFrame), `:474-512` (Book tile chrome).
- `.../plasma/AutoWorkspace.kt:4-58` (2-pane-only `split`, `bounds()`), `.../plasma/DextopAutoView.kt:143-150,244-288` (rail + geometry), `.../plasma/TaskBridge.kt:121-188,286-290`, `.../plasma/BookUi.kt:421` (ComposeView seam).
- `lib/features_page.dart:58-120` — existing 2/3/4-pane presets (`two_columns`, `three_columns`, `four_grid`, `main_left/right`, `main_and_two`, `two_rows`, `top_two_bottom`).
- `docs/architecture/FEATURE_INVENTORY.md:4` — "Missing: dynamic BSP tiling / Stage Manager".
- `android/app/build.gradle.kts:232-233` — `kotlin("test")` + `org.json` already available for pure-Kotlin tests.

## Findings
- **P2** `CompositedWindowShell.kt:4551-4597`: no `KEYCODE_T` binding; Meta+T currently reaches the app. Also `:4501-4520` returns early for `autoMode`, so any shortcut added only to the desktop branch is invisible there. Fix: add `meta && code == KEYCODE_T` in the desktop `when` (and a guarded mirror before the auto early-return), add code to `swallowedKeys`, use Shift+Meta+T to cycle BSP/Dwindle.
- **P2** `WorkspaceLayoutEngine.kt:56-71`: `usableArea()` builds a fresh `createWindowContext()` + `maximumWindowMetrics` query on every call. A tiling reflow on `:794` (500 ms poll) makes this binder churn on a 120 Hz panel. Fix: cache Rect by (displayId, width, height, rotation); invalidate in `onDisplayResized` (`:774`) / config change; pass the cached `Rect` into the tiling engine instead of recomputing.
- **P2** `CompositedWindowShell.kt:3161-3179`: `tile()` forces a `TileZone`, mutates `restoreFrame`, and `setFrame(..., true)` resizes the app display. Reusing it for dynamic tiling would fight `TaskBridge.setBounds` on backends A/E and corrupt maximize/snap restore. Fix: separate `TILED` path + a single `TaskBoundsGateway` seam; detach-to-floating on drag instead of `tile()`.
- **P2** `WorkspaceLayoutEngine.kt:27-53` / `lib/features_page.dart:58-120`: presets are static string→Rect maps; closing one window leaves a hole. Fix: keep the names as *tree seeds* (balanced `Split` trees with thirds/halves ratios) so existing labels/localization stay valid while the tree reflows.
- **P3** `AutoWorkspace.kt:11,39`: two-pane boolean model must not be extended to N panes; `saveAutoSession`(`CompositedWindowShell.kt:346-359`) depends on its JSON shape. Fix: separate `StageManagerState`; leave Auto untouched.
- **P3** `AutoSplitPlacement.kt:11-53`: global `@Synchronized` singleton + `Handler` retries leaks `Context` across sessions; reuse only its *pattern* (one transaction, retry until task appears), not the object. Fix: lifecycle-scoped controller owned by the shell, cleared in `stop()`.
- **P3** `BookUi.kt:421`: Compose/M3E host already exists; do not introduce a second one. Fix: add the rail as another `ComposeView` in `buildDesktopChrome()` using the same theme.

## Deliverable — code skeletons

### 1. Pure Kotlin tree model (no Android imports) — `tiling/TileModel.kt`
```kotlin
package moe.n4tsu.dextop.tiling

data class TRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val w get() = (right - left).coerceAtLeast(0)
    val h get() = (bottom - top).coerceAtLeast(0)
}
@JvmInline value class TId(val v: Long)
enum class Axis { X /* columns */, Y /* rows */; fun other() = if (this == X) Y else X }
enum class Algorithm { BSP, DWINDLE }

sealed interface Node {
    data class Leaf(val id: TId, val ratio: Float = .5f) : Node
    data class Split(val id: TId, val axis: Axis, val ratio: Float, val a: Node, val b: Node) : Node
}

object TileTree {
    private fun Node.contains(id: TId): Boolean = when (this) {
        is Node.Leaf -> this.id == id
        is Node.Split -> a.contains(id) || b.contains(id)
    }
    private fun leaf(n: Node, id: TId): Node.Leaf? = when (n) {
        is Node.Leaf -> n.takeIf { it.id == id }
        is Node.Split -> leaf(n.a, id) ?: leaf(n.b, id)
    }
    private fun last(n: Node): Node.Leaf = if (n is Node.Split) last(n.b) else n as Node.Leaf
    private fun depth(n: Node, id: TId, d: Int = 0): Int = when (n) {
        is Node.Leaf -> d
        is Node.Split -> if (n.a.contains(id)) depth(n.a, id, d + 1) else depth(n.b, id, d + 1)
    }
    private fun parentAxis(n: Node, id: TId): Axis? = when (n) {
        is Node.Leaf -> null
        is Node.Split -> when {
            n.a.contains(id) -> n.axis
            n.b.contains(id) -> n.axis
            else -> parentAxis(n.a, id) ?: parentAxis(n.b, id)
        }
    }
    private fun split(n: Node, target: TId, axis: Axis, added: Node.Leaf,
                      ratio: Float, newId: () -> TId): Node = when (n) {
        is Node.Leaf -> if (n.id == target)
            Node.Split(newId(), axis, ratio.coerceIn(.15f, .85f), n, added) else n
        is Node.Split -> if (n.a.contains(target))
            n.copy(a = split(n.a, target, axis, added, ratio, newId))
        else n.copy(b = split(n.b, target, axis, added, ratio, newId))
    }

    fun insert(root: Node?, added: Node.Leaf, algo: Algorithm,
               focused: TId?, seed: Axis, newId: () -> TId): Node {
        if (root == null) return added
        val target = if (algo == Algorithm.DWINDLE) focused?.let { leaf(root, it) } ?: last(root) else last(root)
        val axis = if (algo == Algorithm.BSP) {
            if (depth(root, target.id) % 2 == 0) seed else seed.other()
        } else (parentAxis(root, target.id) ?: seed).other() // perpendicular = spiral
        return split(root, target.id, axis, added, .5f, newId)
    }

    fun remove(n: Node, id: TId): Node? = when (n) {
        is Node.Leaf -> null
        is Node.Split -> when {
            !n.contains(id) -> n
            n.a is Node.Leaf && n.a.id == id -> n.b
            n.b is Node.Leaf && n.b.id == id -> n.a
            else -> {
                val a = remove(n.a, id); val b = remove(n.b, id)
                when { a == null -> b; b == null -> a; else -> n.copy(a = a, b = b) }
            }
        }
    }
    fun setRatio(n: Node, splitId: TId, ratio: Float): Node = when (n) {
        is Node.Leaf -> n
        is Node.Split -> if (n.id == splitId) n.copy(ratio = ratio.coerceIn(.15f, .85f))
                         else n.copy(a = setRatio(n.a, splitId, ratio), b = setRatio(n.b, splitId, ratio))
    }

    private fun halve(r: TRect, axis: Axis, ratio: Float, gap: Int): Pair<TRect, TRect> {
        val g = gap.coerceIn(0, (minOf(r.w, r.h) - 2).coerceAtLeast(0))
        return when (axis) {
            Axis.X -> {
                val len = (r.w - g).coerceAtLeast(2)
                val fw = (len * ratio).toInt().coerceIn(1, len - 1)
                TRect(r.left, r.top, r.left + fw, r.bottom) to
                    TRect(r.left + fw + g, r.top, r.right, r.bottom)
            }
            Axis.Y -> {
                val len = (r.h - g).coerceAtLeast(2)
                val fh = (len * ratio).toInt().coerceIn(1, len - 1)
                TRect(r.left, r.top, r.right, r.top + fh) to
                    TRect(r.left, r.top + fh + g, r.right, r.bottom)
            }
        }
    }
    fun layout(root: Node?, area: TRect, gap: Int): Map<TId, TRect> {
        val out = LinkedHashMap<TId, TRect>()
        fun walk(n: Node, r: TRect) {
            when (n) {
                is Node.Leaf -> out[n.id] = r
                is Node.Split -> { val (a, b) = halve(r, n.axis, n.ratio, gap); walk(n.a, a); walk(n.b, b) }
            }
        }
        if (root != null) walk(root, area)
        return out
    }
}

// Existing preset names seed balanced trees: two_columns=X(.5), three_columns=X(1/3,.5),
// four_grid=X(.5,Y(.5),Y(.5)), main_left=X(2/3), two_rows=Y(.5), main_and_two=X(2/3,Y(.5)).
fun presetTree(p: String, ids: List<TId>, newId: () -> TId): Node? = TODO("nested Split builder keyed by WorkspaceLayoutEngine position names")
```

### 2. Controller — `tiling/TilingController.kt`
```kotlin
class TilingController(private var algorithm: Algorithm = Algorithm.BSP) {
    private var next = 0L
    private fun id() = TId(++next)
    private var root: Node? = null
    private val windowIds = HashMap<Int, TId>()
    private val floating = HashSet<TId>()
    private var focused: TId? = null
    var enabled: Boolean = false
        private set

    fun toggle(): Boolean { enabled = !enabled; return enabled }
    fun setEnabled(on: Boolean) { enabled = on }
    fun cycleAlgorithm(): Algorithm {
        algorithm = if (algorithm == Algorithm.BSP) Algorithm.DWINDLE else Algorithm.BSP
        return algorithm
    }
    fun open(windowId: Int, seed: Axis = Axis.X) {
        if (windowId in windowIds) return
        val leaf = Node.Leaf(id())
        windowIds[windowId] = leaf.id
        focused = leaf.id
        if (enabled) root = TileTree.insert(root, leaf, algorithm, focused, seed, ::id)
    }
    fun close(windowId: Int) {
        val id = windowIds.remove(windowId) ?: return
        floating -= id
        if (focused == id) focused = null
        if (enabled) root = root?.let { TileTree.remove(it, id) }
    }
    fun focus(windowId: Int) { focused = windowIds[windowId] }
    fun isFloating(windowId: Int) = windowIds[windowId] in floating
    fun toggleFloating(windowId: Int) { /* detach: remove from tree, add to floating; re-insert on second call */ }
    fun resize(splitId: TId, r: Float) { root?.let { root = TileTree.setRatio(it, splitId, r) } }
    fun bounds(area: TRect, gap: Int): Map<Int, TRect> {
        val tiles = TileTree.layout(root, area, gap)
        return windowIds.entries.associate { (w, t) -> w to (tiles[t] ?: area) }
    }
    fun serialize(): String = TODO("JSON: algo, enabled, tree recursively, floating ids")
    fun restore(json: String) { TODO("mirror of serialize; validate ids before adopt") }
}
```

### 3. Shell wiring — `CompositedWindowShell.kt`
```kotlin
private val tiling = TilingController()
private interface TaskBoundsGateway { fun apply(window: V2Window, bounds: Rect) }
// Backends A/E: setWindowingMode(task, TaskBridge.WINDOWING_MODE_FREEFORM) + setBounds(task, bounds)
// Backends C/D: no-op — setFrame + resizeWindowDisplay is authoritative (avoid double bounds/feedback).

private fun applyTiling(on: Boolean) {
    tiling.setEnabled(on)
    val wins = windows.filter { isShown(it) && !it.minimized }
    if (!on) {
        wins.forEach { w ->
            w.tile = TileZone.NONE
            setFrame(w, w.restoreFrame.takeIf { !it.isEmpty } ?: clampFrame(Rect(w.frame)), true)
        }
    } else {
        val area = workspaceLayoutEngine.cachedUsableArea(displayId, width, height)
        val gap = dp(6)
        val bounds = tiling.bounds(area.toTRect(), gap)
        wins.filterNot(tiling::isFloating).forEach { w ->
            bounds[w.id]?.let { applyTiledBounds(w, it) }
        }
    }
    invalidateDesktopPanel()
}

private fun applyTiledBounds(w: V2Window, r: TRect, animate: Boolean = true) {
    if (w.tile == TileZone.NONE && w.restoreFrame.isEmpty) w.restoreFrame = Rect(w.frame)
    w.tile = TileZone.NONE                    // distinct from MAXIMIZE/snap; M3E corners stay
    val rect = r.toAndroidRect()
    if (animate) animateFrame(w, rect) else setFrame(w, rect, resizeDisplay = true)
    boundsGateway.apply(w, rect)
}

// in handleKey's desktop `when`:
meta && code == KeyEvent.KEYCODE_T && !shift   -> { applyTiling(!tiling.enabled); true }
meta && code == KeyEvent.KEYCODE_T && shift    -> { tiling.cycleAlgorithm(); applyTiling(true); true }
// call tiling.open/close/focus from createWindow/close/activate; reflow in
// onDisplayResized, onWindowsChanged (debounced), reloadSettings, taskbarInsetsChanged.
```
Seed `TilingController` from the existing 2/3/4-pane presets (`WorkspaceLayoutEngine.position()` names) so the Features UI keeps working; Super+T then promotes that seeded tree into dynamic mode.

### 4. Stage Manager rail (12.1" tablet) — `plasma/stage/StageRail.kt`
```kotlin
data class StageTask(val taskId: Int, val pkg: String, val label: String, val icon: Drawable?)
data class StageStack(val key: String, val label: String, val tasks: List<StageTask>,
                      val collapsed: Boolean = false, val previewSurface: Any? = null)

@Composable
fun StageRail(state: StageUiState, onOpen: (String) -> Unit, onCollapse: (String) -> Unit,
              modifier: Modifier = Modifier) {
    LazyColumn(modifier.width(state.railWidth).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        items(state.stacks, key = { it.key }) { stack ->
            Card(Modifier.clickable { onOpen(stack.key) }.heightIn(min = 48.dp)) {
                Row { /* icon from StageTask, label, count badge, live preview slot */ }
            }
        }
    }
}

// Preview at 2560x1600 @240dpi ≈ 1067x667dp:
@Preview(name = "Stage rail 12.1in", device = "spec:width=2560px,height=1600px,dpi=240",
         showBackground = true)
@Composable private fun StageRailPreview() {
    MaterialExpressiveTheme { StageRail(StageUiState(StageStack.samples, 280.dp, null), {}, {}) }
}
```
- Host in `buildDesktopChrome()`: `ComposeView(displayContext).setContent { ... }` (pattern from `BookUi.kt:421`), width from `usableArea()`. Feed a `StateFlow<StageUiState>` rebuilt in `onWindowsChanged` from `tasks()` (`CompositedWindowShell.kt:294`) + `bridge.tasks(displayId)`; stacks group by package, most-recent task first; collapse keeps the task alive (mirrors `AutoWorkspace` "visible panes independent of display lifetime" rule).
- Promote stack → `tiling.open(windowId); applyTiling(true); applyTiledBounds(...)`; demote → `bridge.sendToBack(task)` + remove from tree, keep display alive. Live preview = icon/count state MVP; optional scaled mirror surface (max 2–3) reusing the app's private display, never one per background stack.

## Test plan
1. **Pure-Kotlin unit** (`src/test/kotlin/.../tiling/TileTreeTest.kt`, deps already present at `build.gradle.kts:232-233`): BSP axis alternates with depth; Dwindle axis is perpendicular at each split (spiral); 200 random insert/close ops keep `leafCount == openWindows`, no overlap, coverage == `area - gap*(n-1)` to rounding, dims >= 1 px; `remove` collapses sibling and returns null on last leaf; ratio clamp; presetTree 2/3/4 outputs exact expected rects; JSON round-trip.
2. **Compose preview / Robolectric-lite**: `StageRailPreview` renders; 48 dp min touch targets; 280 dp rail; long stack list scrolls without recomposition storms (`@Stable` state).
3. **Device (Redmi Pad Pro 5G, API 36, HyperOS 3, 12.1" 120 Hz)**: Super+T toggle < 8.3 ms; BSP↔Dwindle cycle; 2/3/4 windows + rail; rotate portrait/landscape and external display; backends A/C/E; `dumpsys activity activities` bounds match leaf rects on A/E, private display sizes on C; suspend/resume restores tree from WAL JSON; no double bounds on C, no focus loss when tapping rail; stage preview cap respected in `dumpsys SurfaceFlinger`.

## Risks / device testing needed
- Hidden APIs stay behind `TaskBridge` (already isolated); HyperOS 3 may reject `resizeTask` → existing `WindowContainerTransaction` fallback at `TaskBridge.kt:160-164` must be exercised on-device.
- Reflow must be handler-debounced and reuse cached insets; per-poll `createWindowContext` (finding P2) would drop frames at 120 Hz.
- Do not change `AutoWorkspace`/`saveAutoSession` schema; keep tiling state in its own WAL/prefs key.
- Live per-stack surfaces are expensive on shared tablet memory; cap previews and release on `stop()`.
- Meta+T must be swallowed (`swallowedKeys`) or the focused app also receives `T`.
