# W14B (M7) — Native Terminal (VT100/Rust) + One-Click Theme Engine Report

## Scope inspected
- `linux/LinuxPty.kt:6-21` (JNI `nativeSpawn/nativeWaitFor/nativeResize`, load-failure catch), `linux/LinuxFrameBridge.kt:13-48` (ASurfaceControl/AHB blit pattern, `nativeAttach/nativeStageAddress/nativePresent`), `linux/LinuxTerminalActivity.kt:28-394` (WebView + `Dextop` JS bridge, 48 KB base64 output chunks at :38/:63-71, key/IME path :149-194/:209-253, hardcoded bg :111/:117).
- `assets/terminal/index.html:1-713` (xterm.js + WebGL/image/unicode-graphemes/web-links addons at :82-86; hardcoded CSS/theme colours :12/:216; 10 000-row scrollback :210; private `_core._renderService.dimensions.css.cell` :165; touch/fling/pinch/selection :301-640; OSC 52/7 handlers :254-269; replay + `LEAVE_PROGRAM` :657-700); `linux/LinuxTerminalKeys.kt:7-71`, `LinuxTerminalIme.kt`, `LinuxTerminalSessions.kt:16-267`, `LinuxContainerService.kt:517-547` (PTY owner, `TERM=xterm-256color`).
- Assets: xterm.js 488 KB, addon-webgl 247 KB, image 79 KB, unicode-graphemes 58 KB, fonts 2.15 MB — **WebView/Chromium renderer heap (~80–150 MB) is the saving**, not the asset bytes.
- Theme: `plasma/Book.kt:61-134` (`BookTheme.scheme/dark/palette/wallpaper` — wallpaper colours hardcoded :113/:126), `plasma/BookUi.kt:224,356-358,421-424` (`scheme` state, `recolor()`, M3E host), `plasma/PlasmaTheme.kt:15-126` (`PlasmaPalette`), `linux/LinuxTheme.kt:15-90` (binary GTK/GSettings only, no accent/Qt), W07/W13 reports, W10_CARGO/W10_RUSTCORE (crate + JNI conventions), W12_GFX (AHB/ASurfaceControl/AChoreographer path). No builds, no source edits, no token.

## Findings (P0/P1/P2/P3)
- **P1 — WebView is the terminal's whole cost and its entire data path.** `LinuxTerminalActivity.kt:110-126` owns a Chromium WebView; every PTY chunk is Base64'd and `evaluateJavascript`'d (`:63-71`), every key/IME event crosses the JS bridge (`:318-375`). On a 12.1" 120 Hz tablet with 10 k scrollback rows (`index.html:210`) this is ~80–150 MB and a per-chunk allocation storm. Fix: Rust `dextop-vt` owns the grid; feed PTY bytes through a DirectByteBuffer/JNI (no Base64, no JS); render with AHB/Vulkan on a SurfaceView. Measured via `dumpsys meminfo --local` before/after.
- **P1 — Theme is four disconnected sources of truth.** `Book.kt:70-73` (M3 scheme), `Book.kt:105-134` (hardcoded wallpaper), `index.html:12,216` + `LinuxTerminalActivity.kt:111,117` (hardcoded ANSI/bg), `LinuxTheme.kt:21-90` (binary GTK only). `BookUi.recolor()` (`:356`) refreshes chrome only; terminal/GTK/Qt stay stale. Fix: one `ThemeSpec` → generator fan-out (below), persisted in the session WAL (BUG-01 pattern) with a generation guard.
- **P1 — Qt applications get no theme at all.** `LinuxTheme.script()` (`LinuxTheme.kt:47-88`) writes `theme.env`, GTK 3/4 ini and gsettings only. Fix: extend the same script with `qt5ct/qt6ct.conf` (Colours/Palette) and `QT_STYLE_OVERRIDE`, derived from the same `ThemeSpec`; document that already-open apps apply on relaunch (`LinuxTheme.kt:12-13`).
- **P2 — xterm.js internals used as API.** `fitLocally()` reads `_core._renderService.dimensions.css.cell` (`index.html:165`); WebGL context-loss fallback (`:227-231`) and grapheme addon (`:221-224`) add state. Fix: Rust rasterizer (`ab_glyph`) owns cell metrics; single atlas, no DOM/CSS path.
- **P2 — Terminal behaviours are JS-only and must be ported for parity.** OSC 52/7/8 (`:254-269`), alternate-screen/mouse-mode scroll (`index.html:390-407`), selection handles/fling/pinch (`:301-559`), replay/`LEAVE_PROGRAM` (`:657-700`), TalkBack strings (`:92-93`). Fix: implement as `VtEvent`s + native gesture/accessibility code; port `LEAVE_PROGRAM` verbatim into the replay path.
- **P2 — Keys/IME should stay in Kotlin, not migrate to Rust.** `LinuxTerminalKeys.encode()` + `LinuxTerminalIme` already produce correct bytes/composition; keep `LinuxTerminalActivity.key()`/input connection, retarget from `web.evaluateJavascript("dextopKey…")` to the PTY session write used by the JS bridge today (`:318-325`).
- **P3 — Must be toggle-supported.** Mirror W12's `dextop.compositor=c` rollback: ship `dextop.terminal=webview|native` (default `webview` until M8), plus `dextop.vt.renderer=canvas|vulkan`; if `libdextop_core.so` fails to load (same `runCatching` as `LinuxPty.kt:7-13`) or Vulkan/AHB is unavailable, force the existing WebView path. Delete `assets/terminal` only after sign-off.

## Deliverable

### 1. Crate skeleton (paths; not created)
```
rust/crates/dextop-vt/
  Cargo.toml          # no_std-friendly lib; deps: ab_glyph, unicode-width, smallvec; feature "vulkan"
  src/lib.rs          # Vt facade + damage + VtEvent/VtPalette records
  src/parser.rs       # incremental ESC/CSI/OSC/DCS/UTF-8 state machine
  src/grid.rs         # cell grid + bounded scrollback ring + cursor/saved-cursor + scroll regions
  src/osc.rs          # OSC 0/2 title, 7 cwd, 8 links, 52 clipboard, 1337 notify
  src/render.rs       # ab_glyph atlas -> instanced quads; AHB/Vulkan or 2D Canvas backend
  src/ffi.rs          # #[cfg(target_os="android")] raw JNI, registered in libdextop_core.so JNI_OnLoad
  tests/corpus.rs     # PTY captures replayed against xterm.js golden screen dumps
```
```rust
// src/lib.rs — pure core, testable on host (no Android deps)
pub struct Cell { pub glyph: u32, pub fg: Rgb, pub bg: Rgb, pub attrs: u16 } // bold/dim/italic/underline/strike/inverse/wide
pub struct Vt { grid: Grid, parser: Parser, modes: Modes, damage: Damage }
pub enum VtEvent { Title(String), Cwd(String), Clipboard(String), Bell,
                   Resize { rows: u16, cols: u16 }, Link { x: u16, y: u16, uri: String } }
pub struct VtPalette { pub ansi: [Rgb; 16], pub fg: Rgb, pub bg: Rgb, pub cursor: Rgb,
                       pub selection: Rgb }
impl Vt {
    pub fn new(rows: u16, cols: u16, scrollback: usize) -> Self;      // 10 000 rows ring
    pub fn write(&mut self, bytes: &[u8], out: &mut Vec<VtEvent>);    // CSI/SGR/DECSC/DECRC/1049/2004/…
    pub fn resize(&mut self, rows: u16, cols: u16) -> VtEvent;
    pub fn set_palette(&mut self, p: &VtPalette);                    // one-click theme seam
    pub fn screen(&self) -> &[Cell];
    pub fn take_damage(&mut self) -> Damage;                         // dirty rows -> partial redraw
    pub fn select(&mut self, c0: u16, r0: u32, c1: u16, r1: u32) -> String;
}
```
`parser.rs` state: `Ground|Esc|CsiParam|CsiIntermediate|Osc|Dcs|Utf8{n}`; SGR 30–37/90–97/40–47, `38;5;n`/`48;5;n`, `38;2;r;g;b`; DEC private modes `?25` cursor, `?1049` alt screen, `?2004` bracketed paste, `?1000/1002/1003/1006` mouse; wrap/tab/erase/IL/DL/ICH/DCH/DECSTBM. ~600 LOC, no `vte` dependency needed (keeps .so small). Feature `vulkan` builds `render.rs`; otherwise a Canvas backend draws the atlas bitmap via Android `Canvas.drawBitmapMesh` as a degraded but functional path.

Renderer: rasterize DextopTermMono + SymbolsNerdFontMono + CJK fallback into an R8 glyph atlas (one upload, resize-only rebuild); draw each dirty row as instanced quads (bg quad + glyph quad), cursor/selection as overlays. Present with triple AHB + `ASurfaceControl_setBuffer` + `AChoreographer_postVsyncCallback` and W12's thermal governor — re-render only on damage; cursor blink is a 2-quad frame (<0.5 ms). Target ≤2 ms/frame for 80×24, zero missed VSyncs.

### 2. FFI — one `libdextop_core.so`, typed control + raw data plane (W10 convention)
```rust
// src/ffi.rs — registered from dextop-core::JNI_OnLoad (W10_CARGO.md:87-111)
#[no_mangle] pub extern "system" fn Java_moe_n4tsu_dextop_ffi_DextopVtNative_create(
    env: JNIEnv, _: JClass, rows: jint, cols: jint, scrollback: jint) -> jlong; // Box<Vt> -> handle
#[no_mangle] pub extern "system" fn Java_moe_n4tsu_dextop_ffi_DextopVtNative_write(
    env: JNIEnv, _: JClass, h: jlong, buf: JByteBuffer, off: jint, len: jint); // direct buffer, no copy
#[no_mangle] pub extern "system" fn Java_moe_n4tsu_dextop_ffi_DextopVtNative_attach(
    env: JNIEnv, _: JClass, h: jlong, surface: JObject, w: jint, hgt: jint) -> jlong;
#[no_mangle] pub extern "system" fn Java_moe_n4tsu_dextop_ffi_DextopVtNative_present(
    env: JNIEnv, _: JClass, h: jlong) -> jboolean;  // called from AChoreographer, never main looper
#[no_mangle] pub extern "system" fn Java_moe_n4tsu_dextop_ffi_DextopVtNative_setPalette(
    env: JNIEnv, _: JClass, h: jlong, argb: JIntArray); // from ThemeSpec
// + resize, select, selectedText, scroll, destroy
```
```kotlin
// NEW linux/DextopVtNative.kt — facade; load-failure is caught exactly as in LinuxPty.kt:7-13
internal object DextopVtNative {
    init { System.loadLibrary("dextop_core") }
    external fun create(rows: Int, cols: Int, scrollback: Int): Long
    external fun write(h: Long, buf: ByteBuffer, off: Int, len: Int)
    external fun resize(h: Long, rows: Int, cols: Int)
    external fun attach(h: Long, surface: Surface, w: Int, h: Int): Long
    external fun present(h: Long): Boolean
    external fun setPalette(h: Long, argb: IntArray)
    external fun select(h: Long, c0: Int, r0: Int, c1: Int, r1: Int)
    external fun selectedText(h: Long): String
    external fun destroy(h: Long)
}
```
UniFFI (control plane only, small): `callback interface VtSink { title(id, s); directory(id, path); clipboard(s); bell(); resized(rows, cols) }` and records `VtPalette`/`ThemeSpec` in `moe.n4tsu.dextop.ffi`; JNI `VtSink` callbacks marshal `VtEvent` to Kotlin on the UI thread (same `runOnUiThread` pattern as `LinuxTerminalActivity.sink`, `:62-94`). PTY reading stays in `LinuxTerminalSessions.read()` — its sink output is routed to `DextopVtNative.write` (direct `ByteBuffer`) instead of Base64/JS when native is selected.

### 3. One-click theme engine
```
ThemeSpec(seedArgb: Int, mode: auto|light|dark, contrast: standard|medium|high, generation: Long)
  ├─ BookTheme.scheme(dynamic/baseline) -> BookUi.scheme state -> recolor()   [chrome/taskbar, BookUi.kt:224/356/423]
  ├─ BookTheme.palette() -> PlasmaPalette -> compositor windows/panels          [Book.kt:76-101]
  ├─ BookTheme.wallpaper(w,h,dark,seed) -> parameterized gradient Bitmap         [Book.kt:105-134]
  ├─ derive VtPalette: ANSI 0-7 from Material tonal palettes (seed), 8-15 brightened;
  │  fg/bg = scheme.onSurface/surface; cursor/selection = primary/primaryContainer
  │  -> DextopVtNative.setPalette(h, argb)  (native); CSS-var injection (fallback WebView)
  └─ LinuxTheme.apply(spec): theme.env + GTK3/4 ini + gsettings + qt5ct/qt6ct +
       QT_STYLE_OVERRIDE, one atomic runCommand, skip if applied[container]==dark [LinuxTheme.kt:36-45]
```
One `ThemeEngine.apply(spec)` writes the JSON spec to the session WAL (BUG-01 atomic write, all mutated keys), bumps `generation`, and fans out; `BookUi` listens like `storeListener` (`BookUi.kt:273`) and calls `recolor()` + terminal `setPalette`. Seed from wallpaper dominant colour or Material You; high-contrast derives ANSI from `contrast` level. Old generation callbacks are ignored (FSM generation guard).

### 4. Migration steps (all behind toggles)
1. **M7a (off by default):** add `dextop-vt` crate + unit tests against captured PTY traces; wire into `libdextop_core.so` via W10 CMake/cargo-ndk; no UI change. `dextop.terminal=webview` default.
2. **M7b:** `LinuxTerminalActivity.onCreate` chooses `TerminalSurface` (SurfaceView + `DextopVtNative`) or the existing `TerminalView` WebView from `System.getProperty("dextop.terminal", "webview")`, overridable by pref. `sink.output` routes bytes to `write()` or the existing Base64 path. Keep `LinuxTerminalSessions`, `LinuxTerminalKeys`, `LinuxTerminalIme` and all tab/PTY lifecycle unchanged.
3. **M7c:** land `ThemeEngine`/`ThemeSpec`, parameterize `BookTheme.wallpaper`, remove hardcoded terminal/CSS colours, extend `LinuxTheme.script` with Qt. Toggle `dextop.theme_engine=v1|legacy`.
4. **M7d:** parity gate — long-press/word/selection handles, fling, pinch font size, alternate screen, mouse tracking, OSC 8/52/7, replay notice, IME composition, hardware keys, TalkBack. A/B both renderers on device; capture Perfetto + `dumpsys meminfo`.
5. **M8:** flip default to `native` only if RSS saving ≥80 MB, ≤2 ms/frame, 0 missed VSyncs and zero A/B regressions. Retain both toggles through sign-off; delete `assets/terminal` + WebView bridge only afterwards. `dextop.vt.renderer=canvas` stays as Vulkan failure fallback.

## Risks / device testing needed
- HyperOS 3 Vulkan/AHB driver quirks (format/stride/UBWC) and 16 KB page alignment (`W10_CARGO.md:9-12`); test AHB attach/present on the Redmi Pad Pro 5G with `dumpsys SurfaceFlinger`, keep Canvas fallback and thermal governor (W12).
- Chinese/Japanese IME `commitText`/composing path on a native View — verify `LinuxTerminalIme` parity, no double send; hardware-key modifier mapping via `LinuxTerminalKeys`.
- Font parity: Nerd glyphs, CJK and colour emoji (`index.html:207`) — `ab_glyph` atlas must not regress; retain emoji fallback.
- 10 k-row scrollback and replay `LEAVE_PROGRAM` must reproduce xterm.js output exactly; golden corpus diff before removing WebView.
- TalkBack: xterm.js exposes `promptLabel` (`index.html:92-93`); native SurfaceView needs `AccessibilityNodeInfo` with buffer text + selection actions.
- GTK/Qt theme applies on next app launch (`LinuxTheme.kt:12-13`) — validate whether on-click must also restart open apps; gsettings bus discovery (`LinuxTheme.kt:70-85`) is fragile on HyperOS.
- Theme WAL writes must be atomic and generation-guarded (BUG-01 / `DesktopSessionFsm`) so a themed terminal cannot use a stale palette after rotation/display change.
