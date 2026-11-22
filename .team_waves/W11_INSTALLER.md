# W11A (M5) — Rust Streaming Linux Image Installer Report

## Scope inspected
- `android/.../linux/LinuxContainerService.kt:1733-1926` (`runInstall`, `downloadPrebuilt`, `sha256`, `downloadLinuxContainers`, `download`, `extract`, `unpack`), `:2080-2088` (`currentProgress`/`progress`), `:398-426` (`installWithOptions`/cancel), `:313-365` (`initialize`).
- `LinuxResumableDownload.kt:1-90`, `LinuxImageManifest.kt:1-52` (ECDSA P-256 manifest), `LinuxRootfsArchive.kt:15-140` (streaming tar, path guards, hardlink fallback), `LinuxProot.kt:7-28` (`LinuxPaths`), `:83-158` (binds), `:243-313` (`guest`).
- `tool/linux_image/{device_build.sh,guest_finish.sh,LinuxImageSigner.java:22-74}`; `ILinuxContainerCallback.aidl:4-12`; `LinuxChannel.kt:639-645`; `LinuxCatalog.kt:94-97`.
- Sibling designs `W10_CARGO.md` (one `libdextop_core.so`, UniFFI Kotlin package `moe.n4tsu.dextop.ffi`, cargo-ndk/16 KB pages), `W10_RUSTCORE.md` (`rust/crates/*` layout, `DextopFlags` toggle + fallback pattern).
- No builds, no source edits, no token used.

## Findings
- **P0 — signature algorithm mismatch.** Shipped manifest is ECDSA P-256 `SHA256withECDSA` over base64 payload, key pinned in Kotlin (`LinuxImageManifest.kt:15,22-43`; signer `LinuxImageSigner.java:27,57-74`). Requested Ed25519 is not verifiable by shipped APKs. Fix: Rust crate verifies **both** — v1 legacy P-256 (`p256` crate, SPKI `VerifyingKey`, DER `Signature`) and v2 Ed25519 (`ed25519-dalek`, raw 32-byte key), selected by `manifest.format`; `keyId` pins the active key. Signer gains `sign-ed25519`; Kotlin keeps its verifier until v2 rollout. One pinned-key source of truth, mirrored as Rust const + Kotlin const.
- **P1 — HTTP/1.1 only.** `HttpURLConnection` (`LinuxContainerService.kt:1833-1838`) + single-threaded buffer loop (`:1850-1880`) and resume via `Range` (`LinuxResumableDownload.kt:46-88`) never negotiate HTTP/2. On HyperOS 3 mobile, one TLS connection with a 60 s read timeout stalls a ~600 MB image. Fix: `reqwest::blocking` with rustls + ALPN h2, HTTP/2 range resume, stall counter (8 attempts) and `416` truncation preserved.
- **P1 — extraction is single-threaded and Samsung-specific.** `.tar.zst` is decoded by exec'ing `libzstd_cat.so` because Samsung rejects traced Android executables (`LinuxContainerService.kt:1882-1925`); `.xz` uses XZ-for-Java at 256 KB (`LinuxRootfsArchive.kt:39-93`). Fix: statically link `zstd` (with `zstdmt`) and `xz2` (`parallel` feature) into `libdextop_core.so` — no subprocess, no traced-exec risk; materialize entries on a bounded worker pool.
- **P1 — full rootfs per container; no immutable base.** `extract()` deletes and renames a whole tree per container (`LinuxContainerService.kt:1883-1902`); Samsung denies hard links so `LinuxRootfsArchive.kt:95-110` copies. Fix: content-addressed shared `base/<sha256>/` (read-only) + per-container clone created with `FICLONE` (reflink), copy fallback, + separate `/home` delta bound into the guest. Android shell cannot mount overlayfs, so whole-tree CoW must be reflink-or-copy — do not claim true CoW on filesystems lacking FICLONE.
- **P1 — progress is not a stream.** `currentProgress` holds only the last value in a `ConcurrentHashMap` and fans out to AIDL (`LinuxContainerService.kt:2080-2088`; AIDL `:6-9`; `LinuxChannel.kt:639-645`). Fix: UniFFI `InstallEvents` → Kotlin `MutableStateFlow<InstallProgress>` → same AIDL `onProgress`/`onLog` (wire contract unchanged) + Compose collector for the migrated UI.
- **P2 — cancellation is cooperative at chunk granularity.** `checkCancelled()` only between reads (`:1822-1824`, `LinuxResumableDownload.kt:77`). Fix: `Arc<AtomicBool>` polled per tar entry/chunk and an explicit `cancel(id)` FFI that also drops the connection.
- **P2 — manifest URL trust is Kotlin-side only** (`LinuxImageManifest.kt:36-41`: prefix allowlist, `.tar.zst`, 64-hex sha, `size>0`, `minProtocol`). Rust must re-check all of these (defence in depth) before any I/O; the Kotlin check is bypassed if the Rust backend is called directly.
- **P3 — no local compile per brief; all skeletons unverified.** No `Cargo.toml`/`.udl` exists yet (W10_CARGO.md:3).

## Deliverable

Crate: `rust/crates/dextop-installer/` (sibling of W10B crates; re-exported from `dextop-core` so UniFFI symbols land in the single `libdextop_core.so`). No separate `.so`, no `uniffi.toml` of its own.

### `Cargo.toml`
```toml
[package]
name = "dextop-installer"
version = "0.1.0"
edition = "2021"
[lib]
crate-type = ["rlib"]
[dependencies]
uniffi = "0.29"
reqwest = { version = "0.12", default-features = false, features = ["blocking", "rustls-tls"] }
sha2 = "0.10"
hex = "0.4"
base64 = "0.22"
serde_json = "1"
ed25519-dalek = "2"
p256 = { version = "0.13", features = ["pkcs8"] }   # legacy v1 manifest
zstd = { version = "0.13", features = ["zstdmt"] }
xz2 = { version = "0.1", features = ["parallel"] }
tar = "0.4"
crossbeam-channel = "0.5"
libc = "0.2"
log = "0.4"
```

### `src/lib.rs` — UniFFI contract (proc-macro style, matching W10_CARGO.md:69-83)
```rust
uniffi::setup_scaffolding!();          // only in dextop-core; installer defines types/exports

#[derive(uniffi::Enum)] pub enum Phase { Prepare, Download, Verify, Extract, Done, Error }
#[derive(uniffi::Record)] pub struct Manifest {
    pub version: String, pub url: String, pub size: u64, pub sha256: String,
    pub format: i32, pub key_id: String, pub min_protocol: i32,
}
#[derive(uniffi::Record)] pub struct InstallRequest {
    pub id: String, pub manifest_json: String, pub public_keys_json: String,
    pub protocol: i32, pub image_cache: String, pub base_dir: String,
    pub container_dir: String, pub workers: u8,
}
#[derive(uniffi::Record)] pub struct InstallOutcome { pub base_path: String, pub sha256: String }
#[derive(Debug, uniffi::Error)] pub enum InstallError {
    Manifest(String), Http(String), Checksum(String), Archive(String), Cancelled, Io(String)
}

#[uniffi::export(callback_interface)]
pub trait InstallEvents: Send + Sync {
    fn on_progress(&self, id: String, phase: Phase, percent: i32, message: String);
    fn on_log(&self, id: String, line: String);
}

#[uniffi::export] pub fn parse_manifest(json: String, keys_json: String, protocol: i32) -> Result<Manifest, InstallError>;
#[uniffi::export] pub fn verify_cached(path: String, sha256: String) -> Result<bool, InstallError>;
#[uniffi::export] pub fn install(req: InstallRequest, events: Box<dyn InstallEvents>) -> Result<InstallOutcome, InstallError>; // blocking; call on Dispatchers.IO
#[uniffi::export] pub fn install_cancel(id: String);   // AtomicBool + connection cancel
```
`dextop-core/src/lib.rs` re-exports: `pub use dextop_installer::{install, install_cancel, parse_manifest, verify_cached, InstallEvents, ...};` so generated Kotlin lives in `moe.n4tsu.dextop.ffi` (W10_CARGO.md:60-65).

### `src/verify.rs`
- Parse envelope `{"payload": b64, "signature": b64, "keyId": "..."}`; verify raw payload bytes before JSON parse. v2: `VerifyingKey::from_bytes` + `Signature::from_slice` + `verify_strict`. v1 (legacy): `p256::PublicKey::from_public_key_der` + `p256::ecdsa::DerSignature`.
- Then re-check `format`, `distro == debian`, `arch == arm64`, url prefix `https://github.com/NarYuki/dextop-linux-images/releases/download/` + `.tar.zst`, sha = `[0-9a-f]{64}`, `size > 0`, `minProtocol <= protocol`. Pin keys in `keys.json` (base64 SPKI for v1, raw base64 for v2); never trust a key embedded in the manifest.

### `src/download.rs` — HTTP/2 range resume
```
let client = reqwest::blocking::Client::builder().use_rustls_tls().http2_prior_knowledge()? ...
```
Use `.http2_prior_knowledge()` only for h2c test endpoints; for the public HTTPS endpoint let ALPN negotiate h2 (rustls default). Loop: `offset = part.metadata().len()`; if `offset == size` → done; GET with `Range: bytes={offset}-`; `206` require `Content-Range: bytes {offset}-.../*`, append; `200` truncate+restart; `416` delete part; other → error. Stall counter 8 with capped backoff (mirrors `LinuxResumableDownload.kt:26-43`). `@openat(APPEND)` + `File::sync_data()` on completion. Digest: SHA-256 over the **final full file** (resume-safe; costs one extra read, same as shipped `sha256()` at `:1790-1802`). On mismatch delete `.part` and restart once, then fail. On cancel leave `.part` intact for the next attempt.

### `src/extract.rs` — parallel zstd/xz + hardened tar
- Decoder: `.zst` → `zstd::stream::read::Decoder` with `zstdmt` thread pool; `.xz` → `xz2::read::XzDecoder::new(...).multithread(workers)`. Wrap in `CountingReader` (atomic bytes) for progress; no subprocess.
- `tar::Archive` entries; for each: reject leading `/`, `\0`, `..` escape, require normalized target `starts_with(root)`; skip `dev/`; collect symlinks/hardlinks and dir modes; apply after files, dirs deepest-first (same invariants as `LinuxRootfsArchive.kt:29-35,94-119`); rewrite absolute symlinks relative to guest root; hardlink failure → copy (Samsung).
- Regular files ≤ 8 MiB → `WriteJob { rel, mode, bytes }` to bounded `crossbeam_channel` (capacity `workers*4`); `workers = min(4, available_parallelism())` (phone memory limit); larger files streamed on the reader thread. All workers poll `AtomicBool`. Progress = decompressed bytes / expected-uncompressed size when manifest supplies it, else bytes/MiB + entry count (keep `-1` percent semantics of AIDL `:5-6`).
- Commit: extract into `base/.staging-<sha256>/`, write `.dextop-base.json` marker **after** SHA-256 passes, `chmod` tree read-only, atomic rename; delete staging on error/cancel (mirrors `LinuxContainerService.kt:1883-1902`). Verify `bin/sh || usr/bin/sh` exists before commit.

### `src/base.rs` — immutable base + `/home` delta
```
linux/
  image-cache/<sha256>.tar.zst            # verified, shared
  base/<sha256>/                          # immutable, .dextop-base.json, chmod a-w
  containers/<id>/
    config.json  install.log  delta/home/<user>/  delta/etc/{passwd,group,machine-id}
    rootfs/                                # per-container clone (FICLONE, else copy)
```
- `base` is never written after commit; multiple containers share it. Installer creates `containers/<id>/rootfs` by `ioctl(FICLONE)` clone of base files (ext4/f2fs), falling back to `copy_file_range`/`std::fs::copy` where unsupported; mutable set (`etc`, `var`, `home`, `root`, `tmp`, `run`, `usr/local`) is always a private copy. `/home/<user>` is a bindable delta directory, seeded empty from base, and is the unit for snapshot/export/restore (`LinuxContainerService.kt:1229-1341`).
- `LinuxProot.guest()` gains base/delta-aware binds (`LinuxProot.kt:83-158`): keep `-r` at the container clone; add `-b <delta>/home/<user>:<home>` and identity-file binds. No overlayfs mount (shell uid cannot mount).

### Kotlin delegation seam (no AIDL/Flutter changes)
```kotlin
// new: moe.n4tsu.dextop.linux.LinuxInstallBackend
interface LinuxInstallBackend {
    fun fetch(id: String, manifestJson: String, forceKotlin: Boolean,
              onProgress: (String,Int,String)->Unit, onLog: (String)->Unit,
              isCancelled: ()->Boolean): InstalledImage
}
object InstallBackends {
    fun create(nativeLibraryDir: String): LinuxInstallBackend =
        if (DextopFlags.installerRust) try { RustInstallBackend(nativeLibraryDir) }
        catch (t: Throwable) { KotlinInstallBackend() }   // ALWAYS fall back
        else KotlinInstallBackend()
}
object DextopFlags { val installerRust get() = System.getProperty("dextop.installer.rust","false")=="true" }

class RustInstallBackend(nativeLibraryDir: String) : LinuxInstallBackend, InstallEvents {
    init { System.load(File(nativeLibraryDir, "libdextop_core.so").absolutePath) } // shell process: no java.library.path
    private val flow = MutableStateFlow<InstallProgress?>(null)
    override fun onProgress(id:String, phase:Phase, percent:Int, message:String) {
        flow.value = InstallProgress(id, phase.name, percent, message)
        callback(id) { it.onProgress(id, phase.name, percent, message) }   // same AIDL events
    }
    override fun onLog(id:String, line:String) { callback(id) { it.onLog(id, line) } }
}
```
- `LinuxContainerService.runInstall()` lines 1736-1742 become: `val image = backend.fetch(...)`, then the existing `configure(id, distro)` / `finish(id)` / `fail(id, it)` chain unchanged. `installWithOptions`/`cancelInstall` (`:398-426`) call `install_cancel(id)` in addition to the existing `cancelled` set + `activeProcesses` termination. `LinuxChannel.onProgress` (`:639-640`) is unchanged; Compose UI collects `RustInstallBackend.flow` (or a shared `LinuxInstallStateFlow` keyed by id) for the M3 Linux page.
- Toggle default `false` (like W10_RUSTCORE.md:133-137): rollback keeps Kotlin download/extract compiled. Loading in the app (Android) mode uses `System.loadLibrary("dextop_core")` — the shell UserService must use the absolute path above because Shizuku's `app_process` has no guaranteed native library path.

## Risks / device testing needed
- ECDSA→Ed25519 rollout: old APKs must still verify v1; new APKs must reject unknown `keyId` and downgrade attacks; verify with `LinuxImageSigner` vectors and a tampered payload.
- Confirm `reqwest` rustls ALPN actually negotiates h2 with the image host; test flaky mobile → range resume correctness (offset/Content-Range, 416, truncation after cancelled `.part`).
- FICLONE on Redmi Pad Pro 5G f2fs: if unsupported, per-container copy cost remains — measure install time/space and cap concurrent writers to avoid OOM (Android kills background processes aggressively).
- Samsung traced-executable behaviour: the Rust decoder must be purely in-process; test that no child process is spawned during extract and that extraction survives `setprop`/SELinux changes.
- UniFFI callback from Rust worker threads into the shell service: verify no deadlock with Binder `oneway` callbacks, and that `System.load(absolutePath)` succeeds under Shizuku `app_process` (uid 2000) on API 36 / 16 KB pages.
- Path-traversal/symlink-escape regression suite: port `LinuxRootfsArchive.kt:29-35,121-130` cases (absolute links, `..`, `/dev` entries, hardlink-to-symlink) to Rust unit tests; no local compile per brief.
