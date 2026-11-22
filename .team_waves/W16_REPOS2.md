# W16B (Web Agent) — Wave 16 Report

## Scope inspected
Web search/fetch only (GitHub, crates.io, Android docs). No source edits, no token, no local builds. Target: Redmi Pad Pro 5G / Android 16 (API 36) / HyperOS 3 / arm64-v8a / minSdk 33 / 16 KB pages / 120 Hz. Each entry: URL — offering — exact Dextop milestone applicability.

## Findings (P0/P1/P2/P3)

### Rust on Android (M5 / M8)
- **P0** https://github.com/bbqsrc/cargo-ndk — v4.1.2, MSRV 1.86, `cargo ndk` / `ndk-test` / `ndk-env` / `ndk-runner`; emits correct `jniLibs` layout; v4.0.0+ adds `--link-builtins`, `--link-cxx-shared`, and 16 KB linker flags for NDK<27. → **M5** build `libdextop_core.so` in GitHub Actions only (brief: never build locally); **M8** 16 KB gate.
- **P0** https://github.com/mozilla/uniffi-rs — control-plane bindings. Experimental `uniffi-bindgen-kotlin-jni` (JNI-based; large perf win, "very fresh") directly matches blueprint "UniFFI control plane + raw JNI data plane". FFI v2 / pointer-only FFI experiment https://github.com/mozilla/uniffi-rs/issues/2752 (move away from JNA toward JNI). Known Kotlin async `RustBuffer` crashes https://github.com/mozilla/uniffi-rs/issues/2624. → **M5** control plane; **M8** stability — pin version, avoid async-over-JNA until FFI v2 stable.
- **P0** https://github.com/rustls/rustls (v0.23.46) + https://github.com/rustls/rustls-platform-verifier (v0.7.1) — memory-safe TLS 1.2/1.3; Android verifier uses Android Trust Manager via small Kotlin init component (`init_with_env`/`init_with_runtime`), revocation API 24+. → **M5** data-plane networking; **M8** security sign-off.
- **P1** https://github.com/RustAudio/cpal — AAudio backend, MSRV 1.85, API 26+; when embedded via JNI must call `ndk_context::initialize_android_context(vm, activity)` from `JNI_OnLoad` or panic. → **M5/M7** if audio needed.
- **P1** https://github.com/jelychow/oboe-rust — Rust-native AAudio/OpenSL ES FFI + JNI handle layer (alpha; not drop-in for C++ Oboe). https://github.com/fhenskens/trombone — pure Rust AAudio→OpenSL auto-fallback. C++ baseline https://github.com/google/oboe. → **M5/M7**; keep JNI calls off audio RT thread, use lock-free ring (`rtrb`).

### Wayland compositors in Rust (M4 / M6)
- **P0** https://github.com/Smithay/smithay — compositor building blocks, core + official/wlroots/KDE protocols; `anvil`/`smallvil`; has `AI.md`. → **M6** Rust compositor for backend D (standalone) and C (composited virtual display).
- **P0** https://github.com/J-x-Z/termux-wayland — Smithay daemon + C++ Android bridge via public `ASurfaceControl` (API 29+), `SCM_RIGHTS` FD passing, `wl_shm`/`linux-dmabuf`, JSON IPC with explicit "replace with zero-copy binary format" TODO; GPL-3.0, alpha; TODOs mirror BUG-04/BUG-05. → **M4** backends C/D; **M6** architecture reference.
- **P0** https://github.com/wmww/tawc — `tawcroot` (single-process, faster PRoot alternative) + Smithay compositor + libhybris fork working on stock Android; explicitly notes phantom process killer (Android 12+, >32 procs) and Android 14+ "Disable child process restrictions" — matches our 32-process constraint. → **M3/M4/M6**.
- **P1** https://github.com/Shelnutt2/wldroid — wlroots compositor + PRoot + VirGL + custom AHardwareBuffer allocator + JNI + Compose `CompositorSurface`; independent Gradle AAR modules; MIT. → **M4** backend D; **BUG-05** AHB zero-copy reference.
- **P1** https://github.com/Xtr126/wlroots-android-bridge — `wlr_allocator` on AHardwareBuffers (minigbm), `ASurfaceTransaction_setBuffer` per window, `cros_gralloc_handle.h` parsing for format/stride/offset/planes. → **BUG-05** / **M6** zero-copy AHB.
- **P2** https://github.com/GlassHaven/wayland-android — labwc cross-compile for Android with `ahb_allocator.c` + virglrenderer; **M6** backend D. https://github.com/denialwm/droidloom — Android apps as Wayland windows with zero-copy linux-dmabuf v4 + frame-timeline/insets protocols (new product-neutral protocols worth adopting). https://github.com/pinnacle-comp/pinnacle — Smithay config-in-Lua/Rust reference.

### PRoot alternatives (M3 / M4)
- **P0** https://github.com/proot-me/proot-rs — Rust ptrace PRoot, prebuilt `aarch64-linux-android` tarball, GPL-3.0; still per-syscall ptrace cost. → **M3** keep C only for PRoot/fastpath/preload; **M8** GPL obligations.
- **P0** tawcroot (https://github.com/wmww/tawc, above) — single process, no ptrace trap per syscall; no real sandbox (escapable). → **M3/M4** preferred low-overhead path to prototype.
- **P1** https://github.com/Meapri/androlinux-runtime-lab/blob/main/docs/research/open-source-low-overhead-runtime-candidates.md — hybrid recommendation: keep PRoot, prototype LD_PRELOAD path mapper, probe seccomp user-notification; bubblewrap/nsjail/gVisor blocked by Android sandbox. Open-source `fake-chroot-ng` (from https://github.com/termux/termux-app/issues/5245). → **M3** research spike.
- **P1** https://github.com/Droid-Deck/DroidDeck/blob/main/docs/development/proot-performance.md — measured trap costs (native 0.1 µs vs ptrace 6.2–61 µs); Android app seccomp blocks `rseq`/`set_robust_list`/`faccessat2`/`openat2`; glibc SIGSYS at first `pthread_create`; `GLIBC_TUNABLES=glibc.pthread.rseq=0` mitigation. → **M8** perf/security.
- **P2** https://github.com/zproot/zproot-android — Zig ptrace tracer with W^X (`lib*.so` naming), SELinux, zygote seccomp, phantom-killer notes; MIT. Zig rejected by blueprint → reference only.

### zstd / xz streaming (M5)
- **P0** https://github.com/gyscos/zstd-rs — C binding, `stream::copy_encode`/`copy_decode`, `Encoder`/`Decoder`; async via async-compression. → **M5** data-plane compression.
- **P0** https://github.com/philipc/ruzstd (a.k.a. https://github.com/KillingSpark/zstd-rs) — pure Rust zstd decoder (+basic encoder), `no_std`, `StreamingDecoder`/`FrameDecoder`, no C toolchain (best for Android cross-compile); caveat single-frame streams. → **M5**.
- **P1** https://github.com/paddor/zrip — pure Rust zstd codec, streaming `FrameEncoder`/`FrameDecoder` with `reset()` reusing allocations, LDM; **M5** encode without C. https://github.com/kylewlacy/zstd-framed — seekable multi-frame + async (tokio/futures); **M5** chunked streaming.
- **P0** https://github.com/portable-network-archive/liblzma-rs (v0.4.9; XZ 5.8; fork of https://github.com/alexcrichton/xz2-rs) — xz read/write streams; dropped tokio → use `async-compression` with `lzma` feature. → **M5** xz.
- **P1** https://github.com/hasenbanck/lzma-rust2 — pure Rust LZMA/LZMA2/LZIP/XZ encode+decode, `no_std`, multithreaded, `mem_limit` (v0.25.0 2026-11-01); `no_std`/no-C xz option. https://github.com/gendx/lzma-rs, https://github.com/AlexanderSchuetz97/xz4rust (no_std/no_alloc; moved to Codeberg).

### 16 KB page alignment tooling (M5 / M8)
- **P0** https://android.googlesource.com/platform/system/extras/+/refs/heads/main/tools/check_elf_alignment.sh + https://developer.android.com/guide/practices/page-sizes — official CI gate: `zipalign -c -P 16 -v 4`, NDK r28 default 16 KB, r27− flags `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`, AGP ≥8.5.1, `bundletool dump config | grep alignment`, `adb shell getconf PAGE_SIZE` = 16384; Android 16 `PRODUCT_CHECK_PREBUILT_MAX_PAGE_SIZE` / `atest elf_alignment_test`. → **M5** build config; **M8** release gate for every `.so` (Rust + C).
- **P0** https://github.com/po4yka/rust-skills/blob/main/skills/rust-android-build/SKILL.md — workspace-root `.cargo/config.toml` cfg tables for 16 KB flags + `--build-id=sha1`; warns `RUSTFLAGS`/`CARGO_ENCODED_RUSTFLAGS` silently override config flags; device props to disable backcompat; Play blocks non-compliant updates from 2027-02-01. → **M5/M8**.
- **P1** CI helpers: https://github.com/paulocoutinhox/android-16kb-validator (Python), https://github.com/hotbrainstech/verify-16k-page-align (shell/npm, exit codes), https://github.com/c1982/is16kbready (Go), https://github.com/LEAFECODES/Check-16kb-page-size-for-filetypes (also checks `p_vaddr`/`p_offset` congruence, not just `p_align` — closest to linker's real validation), https://github.com/meemanali/Android-16-kb-Alignment-Checker, https://github.com/hhalai0334/android-16Kb-checker. → **M8** QA matrix.
- **P1** https://github.com/gradium-ai/xn-ptts/blob/main/android/build.sh — real Rust cdylib recipe: `CARGO_TARGET_*_RUSTFLAGS` with `max-page-size=16384`, `unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS`, delete stale `jniLibs`; **M5** CI. https://github.com/ThatWorld/cargo-ndk-plugin and https://github.com/willir/cargo-ndk-android-gradle — Gradle plugins wiring `cargo ndk` into AGP variants; **M5**.

### New agent skill repo (cross-cutting M3/M5/M8)
- **P0** https://github.com/po4yka/rust-skills — 51 production-Rust agent skills, BSD-3-Clause, pin via `gh skill install ... --pin v0.5.0`. FFI bundle: `rust-jni`, `uniffi-boundary`, `uniffi-packaging-versioning`, `ffi-error-progress-cancel`, `rust-cxx`; build bundle: `rust-android-build`, `rust-cross-compilation`, `rust-native-linking`, `cargo-workflows`. Maps directly to the 5 repo-local skills to author (`dextop-proot-c-boundary`, `dextop-wayland-ahb-zero-copy`, etc.) and to BUG-04/05, UniFFI/JNI boundary, and 16 KB packaging. Install one bundle only (~2k token budget).

## Deliverable (web citations)
All URLs above are primary sources consulted. Top picks to add to the skills catalog beyond the brief's list: `po4yka/rust-skills` (Rust Android/FFI/UniFFI/16 KB), `Smithay/smithay` + `J-x-Z/termux-wayland` + `wmww/tawc` (Rust Wayland on Android), `proot-me/proot-rs` + `Droid-Deck` perf doc (PRoot alternatives), `philipc/ruzstd` + `hasenbanck/lzma-rust2` (pure-Rust streaming, no C toolchain), official `check_elf_alignment.sh` + `LEAFECODES` checker (16 KB QA).

## Risks / device testing needed
- UniFFI `uniffi-bindgen-kotlin-jni` and FFI v2 are experimental; pin a version and avoid async-over-JNA on Android 16 until validated on device (`RustBuffer` crash reports exist).
- GPL-3.0 on `termux-wayland`/`proot-rs`; use as reference only or clear licensing before vendoring.
- LD_PRELOAD path virtualization cannot cover static binaries/direct syscalls; tawcroot is escapable; seccomp user-notification kernel/config availability must be probed on HyperOS 3.
- 16 KB backcompat mode can mask misalignment: disable props / use fatal mode on a 16 KB emulator image; require `getconf PAGE_SIZE`=16384 on device before release sign-off.
- Use only public NDK APIs (`ASurfaceControl`, `AHardwareBuffer`, `AAudio`, `AChoreographer`, ADPF, pointer capture); isolate any hidden OEM API behind versioned adapters (BUG-05).
- Phantom process killer (Android 12+, >32 child processes) affects PRoot/rootfs workloads on the tablet.
- No local builds — all cargo/Gradle verification must run in GitHub Actions per brief.
