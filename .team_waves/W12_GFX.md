# W12B (M6) — Graphics / Presentation Report

## Scope inspected
- `android/app/src/main/cpp/wayland/gpu_present.c:39-103,341-419,444-525` — Vulkan/ASurfaceControl window; triple AHB `deck_output`, sync-FD acquire/release, `QueuePresentKHR` fallback.
- `deck_output.inc:1-155`, `deck_slot_wait.inc:1-38` — AHB pool, `ASurfaceTransaction_setBuffer/setGeometry/setOnComplete`, non-blocking `poll` of release fences, GPU-side fence wait.
- `deck_direct.inc:2-4,79-116,118-194` — direct AHB path; release fence returned to compositor over `SCM_RIGHTS` socketpair; `setGeometry`/`setZOrder`/transparency.
- `compositor.c:1391-1407` (`pointer_set_cursor` `{}`, `ti_cursor` no-op at 2289), `LinuxPointerGrabs.kt:76-86` — **no cursor rendering path**; only XFixes transparency probe for pointer capture.
- Kotlin rate control: `PlasmaV2Window.kt:260`, `LinuxWindowActivity.kt:1021-1026`, `DisplayMirrorBackend.kt:572-577,656-682,749-751`, `DisplayEnvironmentSettings.kt:91-101`, `MirrorService.kt:1404,1950-1952`.
- No `AChoreographer`, `AThermal`, ADPF, or `ASurfaceTransaction_setFrameRate` usage anywhere in C/C++/Kotlin.
- Verified NDK signatures (AOSP `surface_control.h` / `choreographer.h`, android-15 tag): `setFrameRate` API30, `setFrameRateWithChangeStrategy` API31, `clearFrameRate` API34, `setCrop/setPosition/setBufferTransform/setScale` API31, `setOnCommit` API31, `setBufferWithRelease` API36, `AChoreographer_postVsyncCallback` API33. No source edited, no builds, no token used.

## Findings
- **P0 — no hardware cursor.** `compositor.c:1395` `set_cursor` and `:2289` `set_cursor_rectangle` are empty; `deck_direct.inc:174` places only one full-window buffer. Wayland/X clients cannot show a cursor, and a software cursor would force a full scene re-render per pointer move at 120 Hz. Fix: dedicated `ASurfaceControl` child of the deck control; set the cursor AHB once (`setBuffer` with `-1` fence), then move with **matrix-only** transactions (`setPosition`/`setScale`, never `setBuffer`), coalesced to one per VSync. Z-order above deck (deck=1 → cursor=2).
- **P0 — rate hints are producer-side and can be silently ignored.** `LinuxWindowActivity.kt:1024` uses `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` (a non-seamless 60→120 switch is dropped); `PlasmaV2Window.kt:260` uses default compatibility; `setRequestedRefreshRate` is reflection-guarded (`DisplayMirrorBackend.kt:573-577`). Fix: set the *display* intent on the SurfaceFlinger layer with `ASurfaceTransaction_setFrameRate(tx, control, 120/60/0, DEFAULT)` (0 = let system choose = "off"), plus `ASurfaceTransaction_setFrameRateWithChangeStrategy(..., STRATEGY_ALLOW_MISMATCH)` for 120; keep Kotlin `setFrameRate` for the producer so both ends agree.
- **P1 — no VSync pacing.** Presentation is demand-driven (Wayland commit / fence signal). At 8.33 ms/frame an un-paced submit either tears or queues a frame and adds a frame of latency. Fix: drive the deck submit from `AChoreographer_postVsyncCallback` (API 33, minSdk 33 OK) running on the compositor's Looper/ALooper; pick the preferred frame timeline and pass `ASurfaceTransaction_setDesiredPresentTime` to SurfaceFlinger.
- **P1 — no thermal/performance governor.** Sustained 120 Hz + Adreno blit on HyperOS will thermally throttle; the app then drops frames unpredictably. Fix: ADPF loop — `AThermal_registerThermalStatusListener` + frame-timeline jank count from `ASurfaceTransaction_setOnCommit`/`ASurfaceTransactionStats_getPresentFenceFd` → hysteresis state machine 120 → 60 → 0 (system choice). Never claim 120 Hz without reporting actually presented frames.
- **P2 — per-frame SCM_RIGHTS socketpair is avoidable on API 36.** `deck_direct.inc:79-102` marshals the previous release fence back to the Wayland thread through a socketpair. `ASurfaceTransaction_setBufferWithRelease` (API 36) delivers the release fence directly to a C callback; keep the socketpair as fallback for API 33–35 behind a versioned adapter.
- **P2 — `setGeometry` is deprecated (API 31).** `deck_direct.inc:174`, `deck_output.inc:146`, `gpu_present.c` use it for a fixed full-window rect. Prefer `setCrop`+`setPosition`+`setScale`; this is also the primitive for magnification.
- **P3 — callbacks are cross-thread.** `ASurfaceTransaction_setOnComplete` may fire on any thread (`surface_control.h`); existing code correctly never touches Wayland objects (`deck_direct.inc:2-4`). Keep that invariant; all cursor/rate state updates must be posted back to the owning compositor thread.

## Deliverable — design & code sketches

### 1. Layer tree & cursor (C, new `deck_cursor.inc`, not created)
```c
struct deck_cursor { ASurfaceControl *c; AHardwareBuffer *img; int w,h,hsx,hsy; float scale; bool hidden; };

static bool cursor_init(struct deck_cursor *k, ANativeWindow *parent, AHardwareBuffer *img,
                        int w,int h,int hsx,int hsy) {
    k->c = ASurfaceControl_createFromWindow(parent, "Dextop HW cursor");   // child of window
    if (!k->c) return false;
    AHardwareBuffer_acquire(img); k->img = img;
    k->w=w; k->h=h; k->hsx=hsx; k->hsy=hsy; k->scale=1.f;
    ASurfaceTransaction *tx = ASurfaceTransaction_create();
    ASurfaceTransaction_setBuffer(tx, k->c, img, -1);       // one-time, no acquire fence
    ARect src = {0,0,w,h};
    ASurfaceTransaction_setCrop(tx, k->c, &src);
    ASurfaceTransaction_setPosition(tx, k->c, -hsx, -hsy);
    ASurfaceTransaction_setScale(tx, k->c, 1.f, 1.f);       // matrix-only from here on
    ASurfaceTransaction_setZOrder(tx, k->c, 2);             // above deck layer (1)
    ASurfaceTransaction_setBufferTransparency(tx,k->c,ASURFACE_TRANSACTION_TRANSPARENCY_TRANSLUCENT);
    ASurfaceTransaction_setVisibility(tx,k->c,ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    ASurfaceTransaction_apply(tx); ASurfaceTransaction_delete(tx);
    return true;
}
/* Called once per VSync if x/y/scale/hotspot changed. NO setBuffer, NO fence. */
static void cursor_move(struct deck_cursor *k, int x,int y, float scale) {
    if (!k->c) return;
    ASurfaceTransaction *tx = ASurfaceTransaction_create();
    ASurfaceTransaction_setPosition(tx, k->c, x - k->hsx, y - k->hsy);
    if (scale != k->scale) {
        ASurfaceTransaction_setScale(tx, k->c, scale, scale);
        k->scale = scale;
    }
    ASurfaceTransaction_apply(tx); ASurfaceTransaction_delete(tx);   // ~0.05 ms, no GPU
}
static void cursor_hide(struct deck_cursor *k, bool hide) { /* setVisibility HIDE/SHOW */ }
static void cursor_destroy(struct deck_cursor *k) { /* reparent(NULL) + release + AHWBuffer_release */ }
```
Wire `pointer_set_cursor` (`compositor.c:1395`) to decode `wl_buffer`→AHB via the existing broker (`deck_ahb_broker.inc`) and call `cursor_init`/`cursor_move`; when the surface is fully transparent (`LinuxPointerCursor.hidden`, `LinuxPointerGrabs.kt:78-85`) call `cursor_hide` so pointer capture keeps working.

### 2. Frame-rate control (C + Kotlin)
```c
/* one transaction per deck buffer submit; rate is set only on mode change */
static void gfx_set_rate(ASurfaceTransaction *tx, ASurfaceControl *ctl, int fps /*120|60|0*/) {
    if (fps == 0) ASurfaceTransaction_clearFrameRate(tx, ctl);       // API34; else setFrameRate(tx,ctl,0,0)
    else ASurfaceTransaction_setFrameRateWithChangeStrategy(
             tx, ctl, (float)fps, ASURFACE_TRANSACTION_FR_RATE_CHANGE_STRATEGY_DEFAULT,
             ASURFACE_TRANSACTION_FR_RATE_CHANGE_STRATEGY_ALLOW_MISMATCH);   // API31
}
```
```kotlin
// DesktopFrameRateController.kt — single owner, replaces scattered hints
object DesktopFrameRateController {
    fun apply(surface: Surface?, fps: Int) {            // 120 / 60 / 0 = system choice
        surface?.runCatching {
            if (fps == 0) setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            else setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                              Surface.CHANGE_FRAME_RATE_ALWAYS)   // not ONLY_IF_SEAMLESS
        }
    }
    fun maxHostHz(display: Display): Float =
        chooseMirrorRefreshRate(display.supportedModes.map { it.refreshRate }) ?: 60f
}
```
C side owns the layer hint; Kotlin side keeps the producer hint in sync — both are required on HyperOS, neither alone is sufficient.

### 3. VSync loop + ADPF/thermal governor (C sketch)
```c
#include <android/choreographer.h>
#include <android/thermal.h>
static AChoreographer *choreo; static AThermal *thermal;

static void vsync(const AChoreographerFrameCallbackData *d, void *arg) {
    size_t i = AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex(d);
    int64_t present = AChoreographerFrameCallbackData_getFrameTimelineExpectedPresentationTimeNanos(d,i);
    int64_t deadline= AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos(d,i);
    governor_tick(deadline);                 // ADPF: jank/presented-frame accounting
    deck_direct_present_if_dirty(...);        // existing path, now paced
    cursor_move_if_dirty(...);                // matrix-only
    ASurfaceTransaction_setDesiredPresentTime(tx, present);
    AChoreographer_postVsyncCallback(choreo, vsync, arg);   // re-arm once per frame
}
static void thermal_cb(AThermalStatus s, void *arg) {
    // NONE/LIGHT=0, MODERATE=2, SEVERE=3, CRITICAL=4 ...
    if (s >= THERMAL_STATUS_MODERATE) governor_request(60);
    if (s >= THERMAL_STATUS_SEVERE)   governor_request(0);   // system choice / 30 cap
}
void gfx_start(void) {
    choreo = AChoreographer_create();                 // must be created on a Looper thread
    thermal = AThermal_acquireManager();
    AThermal_registerThermalStatusListener(thermal, thermal_cb, NULL);
    AChoreographer_postVsyncCallback(choreo, vsync, NULL);
}
```
Governor: 120 default → 60 after 3 consecutive runs with ≥2 missed VSyncs/60 frames **or** ≥5 s MODERATE; → 0 at SEVERE; back to 120 only after 30 s of LIGHT/NONE and zero drops. Hysteresis is mandatory to avoid visible rate flapping. ADPF frame reporting uses `ASurfaceTransaction_setOnCommit` + `ASurfaceTransactionStats_getPresentFenceFd` (present fence is the authoritative "was actually shown" signal); feed `present - expected_present` into the governor, not just `setOnComplete`.

### 4. Magnification via setCrop/setScale (C)
```c
/* zoom about a focal point, no buffer copy / no GPU pass */
static void gfx_zoom(ASurfaceTransaction *tx, ASurfaceControl *ctl,
                     int bw,int bh, float z, float fx,float fy) {
    float cw = bw/z, ch = bh/z;
    int l = clamp(fx-cw/2,0,bw-cw), t = clamp(fy-ch/2,0,bh-ch);
    ARect crop = {l,t,(int)(l+cw),(int)(t+ch)};
    ASurfaceTransaction_setCrop(tx, ctl, &crop);
    ASurfaceTransaction_setScale(tx, ctl, bw/cw, bh/ch);   // centerpoint (0,0)
}
```
Use only for z in [1,3] with integer-ish crop; beyond that (or for rotated buffers) fall back to `linux_gpu_present_scene` (`gpu_scene.inc` shader sampling, `LINUX_GPU_MAX_DRAWS=64`). Apply zoom to the cursor layer too, or re-map pointer coordinates — otherwise the hotspot drifts.

### 5. Rust sketch (`libdextop_core.so` gfx control, greenfield M6)
```rust
// rust/crates/dextop-gfx/src/lib.rs — owns FDs, never blocks the vsync thread
#[link(name="android")] extern "C" {
    fn ASurfaceTransaction_create() -> *mut c_void;
    fn ASurfaceTransaction_setFrameRateWithChangeStrategy(
        t:*mut c_void, c:*mut c_void, fps:f32, compat:i8, strategy:i8);
    fn ASurfaceTransaction_apply(t:*mut c_void);
    fn ASurfaceTransaction_delete(t:*mut c_void);
    fn AChoreographer_postVsyncCallback(c:*mut c_void,
        cb:extern "C" fn(*const c_void,*mut c_void), data:*mut c_void);
}
struct RateGov { hz: AtomicU8, thermal: AtomicI32, drops: AtomicU32 }
impl RateGov {
    fn on_thermal(&self, status:i32) {                 // THERMAL_STATUS_*
        let target = if status >= 3 {0} else if status >= 2 {60} else {120};
        if target != self.hz.load(Relaxed) { self.hz.store(target, Relaxed); }
    }
    unsafe fn push(&self, tx:*mut c_void, ctl:*mut c_void) {
        let hz = self.hz.load(Relaxed);
        if hz == 0 { /* clearFrameRate via dlsym-guarded adapter */ }
        else { ASurfaceTransaction_setFrameRateWithChangeStrategy(tx,ctl,hz as f32,0,2); }
    }
}
extern "C" fn vsync(data:*const c_void, ctx:*mut c_void) {
    let g = unsafe { &*(ctx as *const RateGov) };
    // drain InputRing (W10), submit deck if dirty, re-arm callback
}
```
All sync FDs are `OwnedFd` (close-on-drop) so the BUG-04 fd-leak class cannot reappear; the cursor AHB is an `Arc<AHardwareBuffer>` released only after the last transaction callback. Rust is the control plane only — keep the Vulkan/AHB hot path in C until M6 sign-off (`dextop.compositor=c` default, W10:249).

### 6. 120 Hz frame budget (target: 8.33 ms/frame)
| Item | Cost @2560×1600 | Notes |
|---|---|---|
| Input drain (64 KB SPSC, W10) | ≤0.1 ms | 120–240 Hz pointer events coalesced to one VSync |
| GPU blit AHB→AHB (deck_output) | ~1.5–3 ms | 32.8 MB/frame ≈ 3.9 GB/s; UBWC lowers this |
| Fence export/import + queue submit | ~0.3–0.6 ms | `DMA_BUF_IOCTL_EXPORT_SYNC_FILE` + Vulkan FD import |
| `ASurfaceTransaction` (buffer + cursor matrix + rate) | ~0.2–0.4 ms | cursor move adds no buffer/fence |
| SurfaceFlinger composite + panel | ~3–4 ms | outside app; must be left as headroom |
| **App total** | **≈2.5–4.5 ms** | ≤55 % of 8.33 ms → feasible, ~1.3 frame of slack |

Checks: Perfetto `gfx`/`FrameTimeline` trace must show `expected_present - actual_present < 4 ms`, zero missed VSyncs in a 60 s steady-state run, and GPU `< 5 ms`; if thermal governor flips to 60 more than once per 30 min, lower the 120 Hz ceiling rather than widening hysteresis. Cursor-only frames must stay under 0.5 ms (no re-render).

## Risks / device testing needed
- HyperOS 3 may ignore `setFrameRate` hints on virtual-display / mirrored surfaces; validate on the Redmi Pad Pro 5G with `dumpsys SurfaceFlinger --latency` + a 120 Hz high-speed capture before removing the Kotlin hints.
- `AChoreographer_create` requires a thread with a Looper; the C compositor thread uses `wl_event_loop`, so bridge via `ALooper`/attach to the Activity's looper or call from the Kotlin `Choreographer` callback and post back — do not call from the raw Wayland thread.
- `setBufferWithRelease` is API 36 only; keep the existing SCM_RIGHTS socketpair (`deck_direct.inc:93-98`) as the API33–35 path and exercise both on one device via a runtime toggle.
- AHB cursor image formats/stride from XFixes/Wayland must match the cursor control's buffer layout; test transparency and hotspot clamping at screen edges.
- `setCrop`/`setScale` magnification with non-integer crop can show bilinear blur; A/B against the GPU shader path and confirm the cursor hotspot remains aligned at zoom 1×/2×/3×.
- Thermal thresholds are device-specific — log `AThermal_getCurrentThermalStatus` alongside frame timings and tune hysteresis from real 30-min gaming sessions.
- No local builds were run (brief rule 1); all timings are design targets pending on-device Perfetto measurement.
