# W9B (M4) — Backend D: Standalone Wayland (Mode B) Decoupling — Wave 9 Report

## Scope inspected
- `linux/LinuxContainerService.kt:23-49,312-365,620-686,1474-1480` — dual-mode service; `androidContext != null` ⇒ in-app runtime, `proot.androidMode=true` (`337`), `attachSurface/detachSurface/pointer/key/text/scroll` AIDL surface, shell-only ops already throw (`231-243`).
- `linux/LinuxRuntimeService.kt:15-55` + `LinuxExecutionMode.kt:5-37` + `LinuxContainerClient.kt:333-377` — normal bound FGS under app UID already wires `LinuxContainerService(applicationContext)` with zero privileged shell.
- `linux/LinuxDesktopActivity.kt:63-128,165-226` — full-screen `SurfaceView` (`DesktopSurface`), `show()`→`startDesktop/updateDisplay/attachSurface`, absolute pointer/key forwarding, edge-to-edge via insets.
- `linux/LinuxWindowActivity.kt:154-156,586-614,993-1053` — per-window freeform `SurfaceView` + `am task resize` (`:379-408`), `TYPE_APPLICATION_OVERLAY` popups (`:649-682`), pointer capture — the coupling D must shed.
- `linux/WaylandCompositor.kt:40-57,98-124` + `cpp/wayland/compositor.c:230-244,774-803,2887-2927,3094-3097,3246-3254` + `gpu_present.c:39-103,341-419,444-525`.
- `linux/LinuxXwaylandHost.kt:31-59`, `LinuxAppSession.kt:138-150`, `LinuxDesktopSession.kt:38-82,228-240`, `LinuxModeContainers.kt:30-33`, `LinuxGraphics.kt`, sibling `W03_BACKEND.md:187-222` (BackendSelector/nextAfter + D stub).
- No builds, no source edits, no token.

## Findings
- **P0 — D is currently not standalone.** The Wayland path only runs with either (a) per-xdg-toplevel Android tasks (`LinuxWindowActivity` → `attachWindow` → `attachSurface` keyed by xid) which need freeform/overlay/`am task resize` (`LinuxWindowActivity.kt:379-408,649-682`), or (b) rootful Xwayland whose single server window is attached by `LinuxXwaylandHost`. Neither is forced into the no-privilege in-app runtime, so on a stock arm64 Android 16 device without Shizuku/shell D cannot start. **Fix:** D = `LinuxExecutionMode.ANDROID` + one full-screen root-output Surface; no task windowing, no overlay, no settings writes, no rotation changes.
- **P0 — compositor binds one `ANativeWindow` per xdg toplevel** (`compositor.c:2910-2926`, `xdg->window`, `window_draw:774`). A whole Linux desktop cannot appear in a single Surface. **Fix:** add a root-output target (`comp->output_window`, attached once with id 0) and draw all mapped toplevels into it in stacking order (subsurfaces still via existing `composite()`:579-625). Keep per-toplevel path for C/E; gate root output with a flag so D and C share one binary.
- **P1 — GPU env is set only on the apps path** (`LinuxAppSession.kt:142-146`) but `compositor.c:3094-3097` needs `DEXTOP_LINUX_GPU_{DRIVER,LIBRARY,HOOKS}`; `LinuxXwaylandHost` never sets them, so D would silently use CPU `ANativeWindow_lock` compositing. **Fix:** set the three env vars in `LinuxXwaylandHost.start`/new desktop-session start from `applicationInfo.nativeLibraryDir` before `WaylandCompositor(...)`.
- **P1 — `LinuxDesktopActivity.show()` exits to Xvfb on failure** (via `runtimeStart` → `useXvfb`, `LinuxContainerService.kt:592,599-608`), silently switching D into the X11 backend. **Fix:** D sets `setupOptions.displayBackend=wayland`, asserts `desktopState()==2`/compositor running; if Wayland is unavailable, fail the attempt to the FSM (`BackendHealth.FAILED`) → F, never flip to Xvfb.
- **P1 — no dedicated Surface attach for a root output.** Existing `attachSurface(containerId, surface)` (`LinuxContainerService.kt:656`) routes to `LinuxDesktopSession.attachSurface` which either attaches to Xvfb frame bridge or to `LinuxXwaylandHost`'s one toplevel. **Fix:** add AIDL `attachRenderOutput(containerId, surface, w, h, dpi, refresh)` that attaches the Surface to the compositor root output (`WaylandCompositor.attachOutput`), plus `detachRenderOutput`.
- **P2 — D must inherit nothing from E.** `PhoneRotationController`, `DesktopModeConfigurator`, `am task resize`, `TYPE_APPLICATION_OVERLAY` are all E/C concerns (`W03_BACKEND.md:20,12-13`). D only uses public NDK (`ANativeWindow`, `AHardwareBuffer`, `ASurfaceControl`, Vulkan) + `SurfaceView`, `requestPointerCapture` optional; no hidden API.
- **P2 — container storage** is app-private in Android mode (`LinuxModeContainers.kt:30-32`, `LinuxRuntimeService.kt:50` calls `LinuxPaths.initializeAndroid`); no `/data/local/tmp`, no shell unpack. The Mode B rootfs asset must ship in `noBackupFilesDir/linux-android` via the existing installer (`LinuxProot.androidMode=true`).
- **P3 — F truth string** must be an ARB key (`W08_L10N.md:8`) resolved by `NativeStrings.text`, not a literal baked into D.

## Deliverable — exact wiring

### 1. Activation gate (pure; from `W03_BACKEND.md:222`)
```kotlin
object LinuxModeBackend : DesktopBackend {
  override val id = BackendId.D
  override val requiredPrivilege = PrivilegeRequirement.NONE          // no shell, no secure settings
  override fun canActivate(env: DesktopEnvironment, caps: CapabilitySnapshot) = when {
    "arm64-v8a" !in caps.platform.abis          -> Probe.no("nativeBackendAbiUnsupported")
    caps.identity.sdk < 33                       -> Probe.no("nativeBackendSdkUnsupported")
    !caps.graphics.ahbZeroCopy.ok || !caps.graphics.asurfaceControl.ok ->
                                                   Probe.no("nativeProbeGraphicsUnavailable")
    !caps.linux.modeBContainer.ok || !caps.linux.waylandLibrary.ok ->
                                                   Probe.no("modeBContainerMissing") // packaged asset check only
    else -> Probe.yes("standalone Wayland Mode B")
  }
  // initialize/teardown: see below. No Settings writes, no rotation, no WindowManager calls.
}
```
`BackendSelector.plan()` order for the pad is unchanged: `E → D → F`; `nextAfter(plan, E)=D`, `nextAfter(plan, D)=F` (`W03_BACKEND.md:199-220`).

### 2. Kotlin Activity / View (new `linux/ModeBDesktopActivity.kt`, manifest entry mirrors `LinuxDesktopActivity` at `AndroidManifest.xml:298-305`, `exported=false`, `resizeableActivity=true`, full screen)
```kotlin
class ModeBDesktopActivity : Activity() {
  private lateinit var container: String
  private lateinit var screen: OutputSurface          // ONE full-screen SurfaceView = the Linux desktop
  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    container = intent.getStringExtra("container") ?: return finish()
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)   // or insetsController.hide(systemBars)
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    screen = OutputSurface(this)
    root.addView(screen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    setContentView(root)
  }
  private inner class OutputSurface(c: Context) : SurfaceView(c), SurfaceHolder.Callback {
    init { isFocusable = true; isFocusableInTouchMode = true
           setOnTouchListener { _,e -> forward(e) }; setOnGenericMotionListener { _,e -> forward(e) } }
    override fun surfaceCreated(h: SurfaceHolder) = publish(h.surface)
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) = publish(h.surface)
    override fun surfaceDestroyed(h: SurfaceHolder) {
       LinuxContainerClient.service()?.runCatching { detachRenderOutput(container) } }
    private fun publish(surface: Surface) {
      val w = width; val h = height; if (w<=0||h<=0||!surface.isValid) return
      holder.setFixedSize(w, h)                      // 1:1 producer pixels; SF scales to view
      val dpi = (96f * resources.displayMetrics.density).roundToInt().coerceIn(96, 480)
      val hz = (display?.refreshRate ?: 60f) * 1000 // refreshMilliHz
      LinuxContainerClient.call(this@ModeBDesktopActivity, { linux ->
        linux.startDesktop(container, w, h, dpi)     // reuse existing desktop session
        linux.updateDisplay(container, false, w, h, dpi, hz)
        check(linux.attachRenderOutput(container, surface, w, h)) { "renderOutputAttachFailed" }
      }, {}, { showFailureAndFailOverToF(it) })
    }
  }
  // input: absolute coordinates in the root output; no freeform, no per-window tasks
  private fun forward(e: MotionEvent): Boolean {
    val linux = LinuxContainerClient.service() ?: return false
    val x = { v: Float -> (v * screen.width / width ).roundToInt().coerceIn(0, width  - 1) }
    val y = { v: Float -> (v * screen.height/ height).roundToInt().coerceIn(0, height - 1) }
    LinuxPointerForwarder.forward(e, x(e.x), y(e.y), pointer, resources.displayMetrics.density,
      send = { a,px,py,b -> linux.pointer(container, a, px, py, b) },
      scroll = { dx,dy -> linux.scroll(container, dx, dy) },
      key = { c,d -> linux.key(container, c, d) })
    return true
  }
  override fun dispatchKeyEvent(e: KeyEvent): Boolean { /* same map as LinuxDesktopActivity:147-163:
      LinuxKeymap.evdev → linux.key; unicode → linux.text; BACK stays Android */ }
}
```
AIDL additions to `ILinuxContainerService`: `boolean attachRenderOutput(String id, Surface s, int w, int h)`, `void detachRenderOutput(String id)`. In `LinuxContainerService` delegate to `sessions[id]?.wayland?.attachOutput(s, w, h)`; if the session was started with rootful Xwayland, `LinuxXwaylandHost.attachSurface(s)` already does the single-toplevel attach (`LinuxXwaylandHost.kt:51-56`) — reuse it as the first D implementation; native multi-toplevel output is the enhancement below.

### 3. Native Surface wiring (`WaylandCompositor.kt` + `cpp/wayland/compositor.c`, `gpu_present.c`)
```kotlin
// WaylandCompositor.kt — new JNI, same load path (:30-38) and lifecycle (:146-158)
@JvmStatic private external fun nativeAttachOutput(handle: Long, surface: Surface, w: Int, h: Int)
@JvmStatic private external fun nativeDetachOutput(handle: Long)
@Synchronized fun attachOutput(s: Surface, w: Int, h: Int) { if (running) nativeAttachOutput(handle, s, w, h) }
```
```c
/* compositor.c */
enum command_type { …, CMD_OUTPUT_ATTACH, CMD_OUTPUT_DETACH };      // :230-234
/* nativeAttachOutput: mirror nativeAttach (:3246-3254) */
cmd->window = ANativeWindow_fromSurface(env, surface);
cmd->a = w; cmd->b = h;   // post CMD_OUTPUT_ATTACH
/* command loop: CMD_OUTPUT_ATTACH → set comp->output_window (release old),
   comp->output_w/h, ANativeWindow_setBuffersGeometry(...RGBA_8888), draw_root_output(comp); */
static void draw_root_output(struct comp *c) {
  /* CPU: ANativeWindow_lock once; memset; for each mapped non-popup xdg in stacking order,
     composite(xdg->surface, target, stride, w, h, xdg->geo_x, xdg->geo_y)  (reuse :579-625);
     ANativeWindow_unlockAndPost.  GPU: one linux_gpu_window for output (gpu_present.c:341),
     gpu_collect_scene(each toplevel, x, y, draws, job, 0) (compositor.c:628-667) then
     linux_gpu_present_scene(...) (gpu_present.c:444-525). */
}
/* frame timer / surface_commit redraw path: when comp->output_window, call draw_root_output
   instead of per-xdg window_draw(:774); keep wp_presentation_feedback via presentation_submitted */
/* CMD_OUTPUT_DETACH / quit: ANativeWindow_release(output_window); linux_gpu_window_destroy */
```
Reuse unchanged: `nativeStart/nativeStop/nativeOutput/nativePointer/nativeAxis/nativeKey/nativeText/nativeClipboard`, `Choreographer` deck-VSync loop (`WaylandCompositor.kt:83-96`, gives 120 Hz pacing), the abstract Wayland socket + `wayland-0.lock` name holding (`LinuxXwaylandHost.kt:31-34`), the AHB zero-copy/Deck-Direct layers (`deck_direct.inc`, `deck_output.inc`), and `presentation_submitted` (`compositor.c:826-865`).

### 4. Session + lifecycle
- `LinuxModeBackend.initialize`: ensure `LinuxExecutionMode.read == ANDROID` (if not, fail closed — an explicit mode switch is user-confirmed via `LinuxContainerClient.switchExecution`, `:380-461`); `LinuxContainerClient.connect`; install/open a Mode B container with `displayBackend=wayland` via existing `installWithOptions`/`startDesktop`; launch `ModeBDesktopActivity`; return `BackendHandle(D, displayId=null, release = { detachRenderOutput(); stopDesktop(); service not destroyed — FGS owns it })`.
- Teardown order: `detachRenderOutput` → `stopDesktop` → `compositor.stop` → (no WAL settings to restore, no rotation, no configurator jobs). No nested sessions with E: FSM waits for E `release()`/`journal.restoreSystemSettings()` to finish before D `initialize()` (`W03_BACKEND.md:13,318`).
- `canActivate` reads only the snapshot; runtime attach result and `validateHealth` (surface valid + `WaylandCompositor.running` + compositor emitting `frame` events) are authoritative; a false attach or dead compositor → `fsm.fail(D, reason)` → `nextAfter → F`.

## Risks / device testing needed
- Packaged Turnip/VirGL + `libdextop_wayland.so`/`libdextop_core.so` Mode B assets are mandatory on arm64; if absent D fails closed to F with an ARB reason string (not a literal).
- Root-output draw must be verified on CPU (`ANativeWindow_lock`) and GPU (`linux_gpu_present_scene` swapchain + AHB) at 120 Hz on the Redmi Pad Pro 5G; check buffer release/fence paths (`deck_buffer_release.inc`, `gpu_job_ready:457`) for leaks.
- Android 16 hyperOS phantom-process/Greezer kills (`W05_WEB:13-18`): the FGS notification + `LinuxPhantomProcesses.ensureLifted` are necessary but not sufficient; verify WAL/FSM recovery to D/F after a kill.
- No privileged shell means no trusted virtual display, no `cmd window`, no overlay permission — D must work with none; confirm native Wayland clients (GTK/Qt/Chromium `--ozone-platform=wayland`, `LinuxCatalog.kt:411-412,736`) map into the single output and that popups are composited internally (no `TYPE_APPLICATION_OVERLAY`).
- Reconcile the `BackendId` sealed interface and `BackendSelector` with `W03_PROBE.md/W03_BACKEND.md` before merge; one declaration per package.
- On-device: root-output attach on rotation/resize, `wl_output` mode update (`compositor.c:2887-2901`), input coordinate scaling with insets/cutout, IME text path, and E→D→F with WAL `phase=idle` before D init.
