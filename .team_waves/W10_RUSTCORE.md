# W10B (M5/M6) — Rust Core Crates Report

## Scope inspected
- `BRIEF.md:13-16` (Rust data plane `libdextop_core.so`, UniFFI control / raw JNI data plane, 64 KB SPSC ring), `W04_WATCHDOG.md:19-49` (Kotlin detached daemon, abstract socket protocol, pidfd JNI fast path), `W04_WATCHDOG.md:83-88` (M5 deferral note), BUG-04/BUG-05 (fd leaks).
- No source edited, no builds, no token used.

## Findings
- **P1 — M2 watchdog is Java-only; M5 must not fork-lift it blindly.** Keep it as the shipped default/fallback; introduce the Rust daemon behind a toggle so a bad `libdextop_core.so` cannot brick rollback.
- **P1 — fd lifetime is the real reason to rewrite in Rust.** BUG-04's `wl_event_loop_add_fd` NULL leaks and W04's 3 poll sources (socket, death-poll, deadline) are exactly what `OwnedFd` + `ppoll` make leak-free. Close-on-drop is the invariant, not a cleanup "later".
- **P1 — pidfd is conditional.** `pidfd_open` needs Linux ≥5.3; Xiaomi/API 36 kernels normally have it, but the existing `/proc/<pid>/stat` starttime check must remain as fallback (W04:14,87). Never use `PR_SET_PDEATHSIG` (wrong direction after `setsid`).
- **P2 — DirectByteBuffer SPSC must keep head/tail in Rust-owned memory.** Exposing the *whole* ring as a Java `ByteBuffer` with Kotlin `VarHandle` atomics is the only zero-JNI-per-event design; the ABI offsets are part of the public contract and must be frozen now.
- **P2 — ABI/endian contract.** Ring header uses native-order 8-byte atomics; arm64 target is LE, but document it and add a compile-time `target_endian = "little"` guard.
- **P3 — no local compile** (brief rule 1); skeletons are unverified by a compiler.

## Deliverable

### Crate layout (paths; not created)
```
rust/
  crates/dextop-watchdog/     # [[bin]] dextop-watchdog + lib (pidfd/OwnedFd helpers)
  crates/dextop-input-ring/   # cdylib -> linked into libdextop_core.so
  crates/dextop-core/         # existing M5 hub; re-exports both, owns JNI_OnLoad
android/app/src/main/cpp/CMakeLists.txt  # add_subdirectory(../../../../rust ...) via cargo-ndk output
```

---

### Crate 1 — `dextop-watchdog` (detached replacement)

Cargo:
```toml
[package]
name = "dextop-watchdog"
version = "0.1.0"
edition = "2021"
[dependencies]
libc = { version = "0.2", features = ["extra_traits"] }
[lib]
crate-type = ["staticlib"]      # linked into libdextop_core.so for the JNI death helper
[[bin]]
name = "dextop-watchdog"        # staged to /data/local/tmp/dextop/wd, exec'd by shell
path = "src/main.rs"
```

Rust skeleton — RAII + event loop (`src/lib.rs`):
```rust
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::io;

const WD_ARM_MS: u64 = 15_000;
const SLOT: &str = "dextop.wd.";                  // + uid (abstract socket)

struct Pidfd(OwnedFd);
impl Pidfd {
    fn open(pid: i32, start_ticks: u64) -> io::Result<Option<Self>> {
        if read_starttime(pid)? != start_ticks { return Err(io::Error::from_raw_os_error(libc::ESRCH)); }
        let fd = unsafe { libc::pidfd_open(pid as libc::pid_t, 0) };
        if fd < 0 && io::Error::last_os_error().raw_os_error() == Some(libc::ENOSYS) {
            return Ok(None);                       // kernel < 5.3: caller falls back to /proc poll
        }
        if fd < 0 { return Err(io::Error::last_os_error()); }
        Ok(Some(Self(unsafe { OwnedFd::from_raw_fd(fd) })))  // CLOEXEC via pidfd_open flags=0? use PIDFD_CLOEXEC(1<<0)
    }
}

fn read_starttime(pid: i32) -> io::Result<u64> { /* parse /proc/<pid>/stat field 22, W04:14 */ }

pub fn run(uid: u32, token: &str) -> io::Result<()> {
    // 1) all three sources are OwnedFd: no close-on-drop omissions (BUG-04)
    let listen: OwnedFd = unsafe { OwnedFd::from_raw_fd(libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0)) };
    bind_abstract(&listen, format!("\0{SLOT}{uid}"))?;   // SUN_LEN incl. NUL, no filesystem inode
    unsafe { libc::listen(listen.as_raw_fd(), 4) };
    let timer: OwnedFd = timerfd(WD_ARM_MS);             // CLOCK_MONOTONIC, one-shot
    let mut pidfd: Option<Pidfd> = None;

    loop {
        // 2) ppoll over listener + timer + optional pidfd (single 250 ms-capable wait)
        let mut fds = vec![pollfd(&listen, libc::POLLIN), pollfd(&timer, libc::POLLIN)];
        if let Some(p) = &pidfd { fds.push(pollfd(&p.0, libc::POLLIN)); }
        let n = ppoll(&mut fds, None)?;                  // EINTR retried
        if n == 0 { /* /proc starttime recheck when pidfd is None */ }
        if fds[0].revents != 0 {                          // accepted connection -> OwnedFd
            let conn: OwnedFd = unsafe { OwnedFd::from_raw_fd(libc::accept4(listen.as_raw_fd(), std::ptr::null_mut(), std::ptr::null_mut(), libc::SOCK_CLOEXEC)) };
            match protocol::read_frame(&conn, token, 256 * 1024)? {
                Msg::Arm { pid, start, actions } => {
                    pidfd = Pidfd::open(pid, start)?;     // replaces old Pidfd; old fd closes now
                    arm_timer(&timer, WD_ARM_MS);
                    snapshot = actions;                   // highest armSeq wins
                }
                Msg::Heartbeat => arm_timer(&timer, WD_ARM_MS),
                Msg::Ack { .. } => { write_receipt()?; return Ok(()); }  // exit, no rollback
            }
        }
        if fds[1].revents != 0 || pidfd_dead(&pidfd) {
            rollback(snapshot.iter().rev())?;            // reverse argv, W04:67
            return Ok(());
        }
    }
}
```
`main.rs`: read token from stdin (`IFS= read -r`), call `dextop_watchdog_run(uid, token)`, `SIGKILL` self after ACK/rollback. Wire protocol stays **byte-identical** to `WATCHDOG_PROTOCOL` (`"DW01"` magic + LE length + JSON + CRC32) so either daemon can be swapped mid-wave.

FFI / JNI surface (in `libdextop_core.so`):
```rust
#[no_mangle] pub extern "C" fn dextop_watchdog_run(uid: u32, token: *const c_char) -> c_int;
#[no_mangle] pub extern "C" fn dextop_watchdog_wait_death(
    pid: i32, start_ticks: u64, timeout_ms: i32) -> c_int; // DEAD=1 ALIVE=0 ERR=-1
```
```c
JNIEXPORT jint JNICALL
Java_moe_n4tsu_dextop_WatchdogNative_nativeWaitDeath(
    JNIEnv*, jclass, jint pid, jlong startTicks, jint timeoutMs);
```

Kotlin bridge stubs:
```kotlin
// WatchdogBackend.kt — single contract; SessionJournal depends only on this (W04:41-48 unchanged)
interface WatchdogBackend { fun arm(a: Arm); fun heartbeat(); fun ack(id: String); fun close() }

object WatchdogNative {
    init { System.loadLibrary("dextop_core") }
    external fun nativeWaitDeath(pid: Int, startTicks: Long, timeoutMs: Int): Int
}

class RustWatchdogBackend(private val access: PrivilegedAccess, pkg: String) : WatchdogBackend {
    // start(): stage prebuilt binary, then:
    // access.executeWithInput(listOf("nohup","/system/bin/setsid", WD_BIN, pkg, uid.toString()), token)
    // send frames over LocalSocket("@dextop.wd.$uid") using existing WatchdogProtocol frames
}

object WatchdogFactory {
    fun create(ctx: Context, access: PrivilegedAccess, pkg: String): WatchdogBackend =
        if (System.getProperty("dextop.watchdog.rust", "false") == "true")
            try { RustWatchdogBackend(access, pkg) }
            catch (e: Throwable) { KotlinWatchdogClient(ctx, access, pkg) }  // ALWAYS the fallback
        else KotlinWatchdogClient(ctx, access, pkg)
}
```
**Keeping Kotlin watchdog as fallback:** `WatchdogDaemon.kt`/`WatchdogClient.kt` from M2 stay compiled and in the APK; default toggle is `false`. `WatchdogFactory` catches `UnsatisfiedLinkError`, missing staged binary, socket-bind failure and socket write failure, then delegates every subsequent call to `KotlinWatchdogClient`. Both backends emit the same frames, so `SessionJournal` never branches; re-arming on failure re-arms whichever backend owns the socket (single-winner abstract socket name). ACK receipts are shared, so no double rollback. ProGuard keeps both daemon classes and the Rust JNI facade.

---

### Crate 2 — `dextop-input-ring` (64 KB SPSC, raw JNI DirectByteBuffer)

Layout (one cache line = 64 B; total exactly 65 536 B):
```
offset 0x000  head: AtomicU64   (consumer owned, 64 B padded)   <- Kotlin VarHandle getAcquire
offset 0x040  tail: AtomicU64   (producer owned, 64 B padded)   <- Kotlin VarHandle setRelease
offset 0x080  dropped: AtomicU64 (telemetry)
offset 0x0C0  data[65440]       // slot = 32 B => 2044 usable slots (ring index = seq & 2043)
```
`InputEvent` (32 B, little-endian, `#[repr(C)]`): `u32 type, u32 action, i32 pointer_id, i32 x, i32 y, u32 pressure, u32 buttons, u64 uptime_ns, u32 flags, u32 _pad`.

Rust skeleton:
```rust
pub const RING_BYTES: usize = 65_536;
pub const SLOT_BYTES: usize = 32;
pub const HEAD_OFF: usize = 0, TAIL_OFF: usize = 64, DROPPED_OFF: usize = 128, DATA_OFF: usize = 192;
pub const MASK: usize = (RING_BYTES - DATA_OFF) / SLOT_BYTES - 1; // 2043

#[repr(C, align(64))]
pub struct InputRing { base: *mut u8 }   // mmap(RING_BYTES), page-aligned
impl InputRing {
    pub fn new() -> io::Result<Self> { /* mmap MAP_ANONYMOUS|MAP_PRIVATE, mlock2(MLOCK_ONFAULT) optional */ }
    #[inline] fn cell(&self, off: usize) -> &AtomicU64 {
        unsafe { &*self.base.add(off).cast::<AtomicU64>() }
    }
    pub fn push(&self, ev: &InputEvent) -> bool {           // producer = Rust input thread
        let t = self.cell(TAIL_OFF).load(Ordering::Relaxed);
        let h = self.cell(HEAD_OFF).load(Ordering::Acquire);
        if t.wrapping_sub(h) > MASK as u64 { self.cell(DROPPED_OFF).fetch_add(1, Relaxed); return false; }
        unsafe { std::ptr::copy_nonoverlapping(ev as *const _ as *const u8,
                 self.base.add(DATA_OFF + (t as usize & MASK) * SLOT_BYTES), SLOT_BYTES) };
        self.cell(TAIL_OFF).store(t.wrapping_add(1), Ordering::Release);
        true
    }
    pub fn pop(&self, out: &mut InputEvent) -> bool { /* Acquire head, copy, Release store head+1 */ }
    pub fn raw(&self) -> (*mut u8, usize) { (self.base, RING_BYTES) }
}
```
FFI:
```rust
#[no_mangle] pub extern "C" fn dextop_input_ring_new() -> *mut InputRing;
#[no_mangle] pub extern "C" fn dextop_input_ring_free(r: *mut InputRing);
#[no_mangle] pub extern "C" fn dextop_input_ring_ptr(r: *const InputRing) -> *mut u8;
#[no_mangle] pub extern "C" fn dextop_input_ring_len() -> usize; // 65_536
#[no_mangle] pub extern "C" fn dextop_input_ring_push(r: *const InputRing, ev: *const InputEvent) -> c_int;
#[no_mangle] pub extern "C" fn dextop_input_ring_pop(r: *const InputRing, out: *mut InputEvent) -> c_int;
#[no_mangle] pub extern "C" fn dextop_input_ring_wake_fd(r: *const InputRing) -> c_int; // eventfd, OwnedFd
```
JNI (handback of the DirectByteBuffer; per-event access stays in Kotlin):
```c
JNIEXPORT jobject JNICALL
Java_moe_n4tsu_dextop_InputRingNative_buffer(JNIEnv*, jclass, jlong handle) {
    return (*env)->NewDirectByteBuffer(env, dextop_input_ring_ptr((void*)handle), 65536);
}
JNIEXPORT jlong JNICALL
Java_moe_n4tsu_dextop_InputRingNative_create(JNIEnv*, jclass);
JNIEXPORT void JNICALL
Java_moe_n4tsu_dextop_InputRingNative_destroy(JNIEnv*, jclass, jlong handle);
JNIEXPORT jint JNICALL
Java_moe_n4tsu_dextop_InputRingNative_wait(JNIEnv*, jclass, jlong handle, jint timeoutMs); // eventfd ppoll
```
Kotlin bridge stub:
```kotlin
object InputRingNative {
    init { System.loadLibrary("dextop_core") }
    external fun create(): Long
    external fun destroy(handle: Long)
    external fun buffer(handle: Long): ByteBuffer       // DirectByteBuffer over the 64 KB ring
    external fun wait(handle: Long, timeoutMs: Int): Int
}

class InputRing(private val h: Long = InputRingNative.create()) : AutoCloseable {
    private val mem: ByteBuffer = InputRingNative.buffer(h).order(ByteOrder.LITTLE_ENDIAN)
    private val headVH = MethodHandles.byteBufferViewVarHandle(LongArray::class.java, LITTLE_ENDIAN)
    private fun full() = (tail - head) == MASK + 1L

    fun publish(type:Int, action:Int, pid:Int, x:Int, y:Int, pressure:Int, buttons:Int, ns:Long, flags:Int): Boolean {
        if (full()) return false
        val t = tail
        val o = DATA_OFF + ((t and MASK.toLong()).toInt() * 32)
        mem.putInt(o, type); mem.putInt(o+4, action); mem.putInt(o+8, pid)
        mem.putInt(o+12, x); mem.putInt(o+16, y); mem.putInt(o+20, pressure)
        mem.putInt(o+24, buttons); mem.putLong(o+28, ns); mem.putInt(o+36, flags)
        headVH.setRelease(mem, TAIL_OFF, t + 1)             // publish AFTER payload writes
        return true
    }
    fun consume(out: InputEvent): Boolean {
        val h = headVH.getAcquire(mem, HEAD_OFF) as Long
        if (h == tail) return false
        /* read 32 B slot; then */ headVH.setRelease(mem, HEAD_OFF, h + 1)
        return true
    }
    override fun close() = InputRingNative.destroy(h)
}
```
Safety rules: Rust never moves/reallocates the mmap while Kotlin holds the buffer; `destroy()` is only called after the last consumer stops; `NewDirectByteBuffer` lifetime == `InputRing` handle; Jetpack consumer uses `VarHandle` acquire/release only (never plain `getLong` on head/tail); one producer, one consumer — assert with a `debug_assert` owner-tid check in `push`/`pop`.

### Runtime toggles
```kotlin
object DextopFlags {
    val compositor: String get() = System.getProperty("dextop.compositor", "c")   // "rust" | "c"
    val rustInput: Boolean get() = System.getProperty("dextop.input.rust", "false") == "true"
    val rustWatchdog: Boolean get() = System.getProperty("dextop.watchdog.rust", "false") == "true"
}
```
Set via `adb shell setprop dextop.compositor rust` bridged at app init (`System.setProperty(...)`), or `-Ddextop.input.rust=true`. Wiring:
- `compositor=rust` → Rust `dextop-core` surface path; `=c` → existing `cpp/wayland` fastpath (default until M6 sign-off).
- `input.rust=true` → Kotlin publishes into `InputRing`; Rust consumes and feeds compositor/Wayland. `false` → existing Kotlin input path (BUG-06 fix stays authoritative).
- Both toggles are read **once** at session start and snapshotted into `DesktopSessionFsm`; no mid-session switching (ring handle and surface are session-scoped resources).

## Risks / device testing needed
- `pidfd_open` availability on the actual Redmi Pad Pro 5G kernel: verify `nativeWaitDeath` returns 1/0/ERR; force the `/proc` fallback via an env override and re-run W04 kill-9 matrix.
- `OwnedFd` requires Rust ≥1.63 and API-level NDK headers for `pidfd_open`/`accept4`/`timerfd_create` (all public, minSdk 33 OK). `PIDFD_CLOEXEC` flag = 1; if the kernel ignores it, fd leak check with `/proc/<wdpid>/fd` count.
- DirectByteBuffer + `VarHandle` atomic view on arm64: run a 2-thread stress test (1 M events) asserting zero loss/duplicate and comparing Rust `pop()` results with Kotlin `consume()` results.
- 64 KB ring sizing: 2044 slots × 32 B; at 120 Hz with burst injection measure drop counter under `dumpsys input` load; tune slot count only via the ABI constants above.
- `mlock2(MLOCK_ONFAULT)` may be blocked by RLIMIT_MEMLOCK on HyperOS — treat as optional, do not fail init.
- No GitHub Actions workflow, NDK toolchain pins, or JNI symbol export map included here (M6 integration); no local builds performed per brief.
