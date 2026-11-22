# W12A (M6) — Wayland Compositor Rust Port Report

## Scope inspected
`cpp/wayland/compositor.c` ( globals/lifecycle 3165–3229, JNI 3239–3378, fd/dmabuf 2151–2204, deck filter 1735–1753, 1024-array 279/852/1060 ), `deck_direct.inc`, `deck_ahb_broker.inc`, `deck_ahb_protocol.inc`, `deck_dmabuf.inc`, `deck_buffer_release.inc`, `gpu_present.{c,h}`, smithay 0.7 / wayland-server 0.31 docs, sibling `W10_CARGO.md`, `W10_RUSTCORE.md` (`DextopFlags.compositor`, single `libdextop_core.so`, cargo-ndk). No builds, no source edits, no token.

## Findings
- **P0 — Hard 1024-window ceiling.** `compositor.c:279` `struct xdg *xdgs[1024]` indexed by `id & 1023`; loops at `:852`, `:1060`, `:3037` scan all 1024 every frame. A 1000-window soak leaves 24 slots for popups/IDs — wraparound aliases live windows (`xdg_by_id:340-343`), presentation feedback is misrouted, O(N) per frame at 120 Hz. **Fix:** Rust `slotmap::SlotMap`/`Arena<WindowId>` keyed by monotonically increasing opaque ID; per-surface `HashMap<SurfaceId, WindowId>` for feedback; no fixed cap, O(1) lookup.
- **P0 — AHB zero-copy is Deck/Steam-only, not "all clients".** `deck_direct.inc:137-145` requires `xdg->deck && deck_direct`, `deck_ahb_protocol.inc:12` requires `is_deck_client`, `compositor.c:1751` hides `banner_ahb_v1`/deck dmabuf from everyone else. Ordinary GTK/Qt/browser clients are forced through mmap+`convert_row` (`compositor.c:505-530,562-577`) or Vulkan import. **Fix:** generalize the AHB global to all clients behind capability token; `ahb::import_all()` maps *every* client dmabuf to an AHB; direct ASurfaceControl layer per toplevel, GPU fallback via `VK_ANDROID_external_memory_android_hardware_buffer` (already wired `gpu_present.c:160,186`).
- **P1 — fd lifetime by raw `int` + NULL-source leaks.** `params_add:2151-2159`, `dmabuf` (`:189-214`), `deck_direct_frame.fd/fence` (`deck_direct.inc:5-13`), `gpu_job.fence` (`compositor.c:217-227`); `wl_event_loop_add_fd` NULL checked only ad-hoc (`deck_direct.inc:69-73,166-167`). BUG-04/05 patterns recur. **Fix:** `OwnedFd`/`BorrowedFd` end-to-end; calloop `Generic` sources with `Interest::READABLE|HUP|ERROR`; SCM_RIGHTS helpers return `OwnedFd`; drop = close, no cleanup "later".
- **P1 — weak auth + per-attach dlopen.** `is_deck_client:1735-1747` reads 64 KB stack buffer of `/proc/<pid>/environ` for `DEXTOP_STEAM_INSIDE=1`; `deck_ahb_protocol.inc:34-38` `dlopen`/`dlsym`/`dlclose` on *every* attach (broker already caches at `deck_ahb_broker.inc:10-20`). **Fix:** `SO_PEERCRED` uid check + per-session cookie delivered via `dextop_ahb_v1::authenticate(cookie)`; process-wide cached NDK symbols via `OnceLock`.
- **P1 — hand-rolled release fence protocol.** `deck_direct.inc:79-102` builds `cmsghdr` by hand, `sendmsg` + `close(previous->fd)` + `AHardwareBuffer_release`; EOF path at `:55-58` destroys frame. **Fix:** typed `send_fds`/`recv_fds` over `SOCK_SEQPACKET` with `MSG_CMSG_CLOEXEC`, all fds `OwnedFd`, preserve HUP/ERROR draining (BUG-04) in Rust.
- **P2 — ANativeWindow ownership implicit.** `compositor.c:3246-3253` `ANativeWindow_fromSurface` stored raw in `command`/`xdg`, released only on detach paths. **Fix:** `struct NativeWindow(NonNull<ANativeWindow>)` with `Drop = ANativeWindow_release`.
- **P3 — skeletons unverified locally** (brief rule 1); CI-only compile.

## Deliverable

### Staged port (toggle `dextop.compositor=rust|c`, default `c` until soak green)
- **S0 Shim (no behavior change):** rename C JNI symbols at compile time (`-DJava_moe_n4tsu_dextop_linux_WaylandCompositor_nativeStart=dextop_c_start` … all 17 in CMake); Rust `libdextop_core.so` owns the public JNI names, `nativeSetBackend(jint)` sets `static BACKEND: AtomicU8`, every native* dispatches to `dextop_c_*` or Rust. Kotlin `WaylandCompositor.kt` unchanged; `DextopFlags.compositor` read once at session start (`W10_RUSTCORE.md:242-251`).
- **S1 Protocol parity:** smithay display on the same abstract socket, all globals, xdg-shell/shm/viewporter/decoration/seat/output + CPU (pixman) path; wire-diff against `tool/wayland_smoke/*` clients.
- **S2 dmabuf + fd ownership:** `DmabufHandler`, OwnedFd everywhere, AHB broker ported, buffer-release queue (`deck_buffer_release.inc`) in Rust; still Steam-only zero-copy; run 1000-window soak on CPU path.
- **S3 AHB for all clients:** generalize banner global + cookie auth + import/render; GPU/direct path; soak gate on GPU/direct path.
- **S4 Retire C:** drop `cpp/wayland/*` from CMake (keep PRoot/fastpath/preload), flip default to `rust` only after device soak.

### Crate skeleton (paths only; sibling of `dextop-watchdog`/`dextop-input-ring`)
```
rust/crates/dextop-wayland/
  Cargo.toml
  src/lib.rs        # pub use; re-exported from dextop-core -> libdextop_core.so
  src/display.rs    # Display::new(), abstract socket, global list, global_filter
  src/state.rs      # DextopState + all delegate_* macros + handlers
  src/surface.rs    # pending/commit latch, subsurfaces, cached state (mirror compositor.c:991-1138)
  src/xdg.rs        # toplevel/popup roles, SlotMap<WindowId, Window>, configure/map
  src/dmabuf.rs     # DmabufHandler/AHB attach to dmabuf; release queue
  src/ahb.rs        # OnceLock NDK symbols; import_all(dmabuf)->AHB; AHardwareBuffer_createFromHandle
  src/direct.rs     # ASurfaceControl zero-copy layer (port deck_direct.inc), release fences
  src/broker.rs     # AHB allocation broker SOCK_SEQPACKET + SCM_RIGHTS (port deck_ahb_broker.inc)
  src/fd.rs         # OwnedFd wrappers, send_fds/recv_fds, HUP/ERROR Interest
  src/input.rs      # command event source, seat/keyboard/pointer from 64 KB SPSC ring
  src/jni.rs        # exported JNI names + dispatch to dextop_c_* / Rust
  src/soak.rs       # 1000-window harness + metrics
```
`Cargo.toml` (feature-lean for bionic; no drm/gbm/udev/libseat):
```toml
[package]
name = "dextop-wayland"
version = "0.1.0"
edition = "2021"
[dependencies]
smithay = { version = "0.7", default-features = false, features = ["wayland_frontend","renderer_pixman","backend_allocator_dmabuf"] }
wayland-server = "0.31"
wayland-backend = { version = "0.3", features = ["server_system"] }
calloop = "0.14"
libc = "0.2"
rustix = { version = "1", features = ["fs","process","net"] }
slotmap = "1"
thiserror = "2"
tracing = "0.1"
[lib]
crate-type = ["rlib"]   # linked into dextop-core cdylib -> libdextop_core.so
```

### Handler sketch (smithay 0.7)
```rust
delegate_compositor!(State); delegate_subcompositor!(State); delegate_shm!(State);
delegate_buffer!(State); delegate_dmabuf!(State); delegate_xdg_shell!(State);
delegate_xdg_decoration!(State); delegate_seat!(State); delegate_output!(State);
delegate_viewporter!(State); delegate_presentation!(State);
// banner_ahb_v1: low-level wayland-server global generated from XML, filter by cookie.

impl DmabufHandler for State {
  fn dmabuf_state(&mut self) -> &mut DmabufState { &mut self.dmabuf }
  fn dmabuf_imported(&mut self, _dh: &DisplayHandle, client: ClientId, dmabuf: Dmabuf) {
    // validate via renderer.import_dmabuf(&dmabuf, None); then ahb::import_all(&dmabuf)
    // store ClientBuffer { dmabuf, ahb: Option<Ahb>, handles: Vec<OwnedFd> } in surface userdata
    // notify Buffer notifier on success (anvil pattern)
  }
}
impl BufferHandler for State {
  fn buffer_destroyed(&mut self, buffer: &WlBuffer) { self.buffers.remove(buffer); }
}
impl CompositorHandler for State {
  fn commit(&mut self, dh: &DisplayHandle, surface: &WlSurface) {
    surface::latch(surface); xdg::map_if_ready(dh, surface); self.dirty.insert(id);
  }
}
```
`Dmabuf::builder(size, format, modifier, flags)`, `handles() -> BorrowedFd`, `map_plane`/`sync_plane` replace `mmap`+`DMA_BUF_IOCTL_SYNC` (`compositor.c:505-530`; smithay Dmabuf docs, URL below).

### FFI surface (exact)
```rust
// jni.rs — public names UNCHANGED; C symbols renamed in CMake as dextop_c_*
#[no_mangle] pub extern "system" fn Java_moe_n4tsu_dextop_linux_WaylandCompositor_nativeSetBackend(_: JNIEnv, _: jclass, v: jint);
#[no_mangle] pub extern "system" fn Java_moe_n4tsu_dextop_linux_WaylandCompositor_nativeStart(...) -> jlong;
#[no_mangle] pub extern "system" fn Java_moe_n4tsu_dextop_linux_WaylandCompositor_nativeStop(...);
// … nativeAttach / Detach / Output / DeckVsync / Configure / Activate / SetState /
//    Close / Pointer / Axis / Key / Clipboard / Text / LockName / UnlockName
```
```rust
// internal data-plane API (nativeWindow = ANativeWindow_fromSurface, owned)
pub struct Handle { /* calloop::Channel<Command> + join handle */ }
pub fn start(socket: &str, w: i32, h: i32, refresh_mhz: i32,
             sink: Box<dyn Fn(&str) + Send>) -> io::Result<Handle>;
impl Handle {
  fn attach(&self, id: u32, win: NativeWindow, flags: DeckFlags);
  fn detach(&self, id: u32); fn configure(&self, id: u32, w: i32, h: i32);
  fn activate(&self, id: u32); fn set_state(&self, id: u32, max: bool, full: bool);
  fn close(&self, id: u32); fn pointer(&self, id: u32, a: i32, x: i32, y: i32, b: i32);
  fn axis(&self, dx: i32, dy: i32); fn key(&self, code: i32, down: bool);
  fn text(&self, s: &str); fn clipboard(&self, s: &str);
  fn deck_vsync(&self, ns: u64); fn output(&self, w: i32, h: i32, refresh: i32);
  fn quit(&self);
}
```
Wire events keep the existing JSON shape (`emit_window` `compositor.c:361-377`) so `DesktopSessionFsm` never branches.

### 1000-window soak gate (`src/soak.rs` + `tool/wayland_smoke/`)
- 1000 `xdg_toplevel` clients + popups, 512² linear dmabuf/memfd, each attached to an AHB; commit churn at 120 Hz for 10 min; 8 real Android `Surface`s in a rotating pool; kill -9 100 clients mid-transaction.
- **Pass criteria:** fd count stable (`RLIMIT_NOFILE=1024` during soak → any leak is fatal), zero `EAGAIN` spin / HUP-only dispatch, RSS < 512 MB, p99 commit→present < 8.3 ms at 120 Hz, every window maps/configures/unmaps exactly once, all `wl_buffer.release`s sent, AHB refcount returns to baseline. Gate in CI (`rust-core` job) + on-device Run 1; only then flip `dextop.compositor` default.

### Web citations
- smithay 0.7.0 `Dmabuf` builder/handles/map/sync: https://docs.rs/smithay/latest/smithay/backend/allocator/dmabuf/struct.Dmabuf.html
- `DmabufHandler`/`delegate_dmabuf`: https://smithay.github.io/smithay/smithay/wayland/dmabuf
- `ImportDma` renderer trait: https://smithay.github.io/smithay/smithay/backend/trait.ImportDma.html
- anvil state (delegate macros, `DmabufFeedback`, notifier): https://raw.githubusercontent.com/Smithay/smithay/master/anvil/src/state.rs
- releases/features: https://github.com/Smithay/smithay/releases

## Risks / device testing needed
- smithay 0.7 pulls wayland-server 0.31/calloop 0.14 — verify pure-Rust abstract-unix-socket bind + `SO_PEERCRED` under bionic (C path works today); disable all drm/gbm/udev/libseat features, target aarch64-linux-android.
- `libdextop_core.so` now contains smithay: enforce 16 KB `LOAD` alignment (`W10_CARGO.md:10`) and `cdylib_name="dextop_core"` (`W10_CARGO.md:12`).
- `native_handle_t` layout + `AHardwareBuffer_createFromHandle` with HyperOS 3 vendor gralloc; verify st_dev/st_ino identity check (`deck_ahb_protocol.inc:39-41`) still holds; verify `ASurfaceTransaction_setBuffer` with imported AHB + sync_fd on Adreno/Turnip.
- Frame budget with 1000 live windows and 120 Hz on the tablet; thermal/ADPF interaction; GPU driver via adrenotools `dlopen` (`gpu_present.c:120`) must stay C FFI — no local compile (CI-only).
- JNI symbol rename via CMake macros must cover all 17 exports; verify `nm -D libdextop_core.so` shows exactly one definition per JNI name and C fallback resolves.
