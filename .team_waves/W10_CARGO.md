# W10A (M5) — `libdextop_core.so` Cargo / Gradle / CI Integration Report

## Scope inspected
- `rust-core/Cargo.toml`, `rust-toolchain.toml`, `.cargo/config.toml` — **do not exist** (greenfield; search returned none). CMake config `android/app/src/main/cpp/CMakeLists.txt:1-34`; Gradle `android/app/build.gradle.kts:54-163` (`ndkVersion = flutter.ndkVersion`:62, `abiFilters arm64-v8a`:81, `externalNativeBuild` CMake 3.22.1:85-89,131-136, `useLegacyPackaging=true`:126-128, `preBuild` hooks:264,301); JNI naming convention `native_input.cpp:1880-1943`, `linux_bridge.cpp:58-204`; workflows `debug-apk.yml`, `release-apk.yml`, `pr-checks.yml` (SHA-pinned actions, `android-gradle` concurrency group, Flutter 3.47.7 / Java 17 / minSdk 33); sibling `W10_RUSTCORE.md` (crate internals; this report is the build wiring).
- No builds, no source edits, no token used.

## Findings
- **P1 — three requested files are absent.** Must be created before any Rust crate builds. `rust-toolchain.toml` pins target so `cargo-ndk` works without per-runner `rustup target add`.
- **P1 — NDK version must be single-sourced.** `build.gradle.kts:62` uses Flutter's pinned NDK; `cargo-ndk` needs the same NDK or the C++ link and Rust link use different sysroots. Pin one version (e.g. `27.2.12479018`) and set BOTH `flutter.ndkVersion` override and `ANDROID_NDK_HOME` in CI.
- **P1 — Android 16 / API 36 uses 16 KB pages.** NDK r27+ defaults to `-Wl,-z,max-page-size=16384`; set it explicitly in `.cargo/config.toml` so `libdextop_core.so` is loadable on HyperOS 3 (a 4 KB-aligned .so is rejected on 16 KB kernels).
- **P2 — CMake `IMPORTED` does not package a prebuilt .so.** Linking the imported target satisfies `DT_NEEDED` for C++ callers but Gradle won't copy it into the APK. Must also add the cargo-ndk output dir to `jniLibs.srcDirs` (below).
- **P2 — UniFFI Kotlin scaffold calls `System.loadLibrary("dextop_core")`.** Set `cdylib_name = "dextop_core"` in `uniffi.toml` so the control-plane bindings and raw JNI data plane share ONE `libdextop_core.so`; otherwise UniFFI expects a separate `uniffi_*` library.
- **P3 — skeletons unverified (no local compile per brief).**

## Deliverable — exact files

### 1. `rust-toolchain.toml` (create at repo root)
```toml
[toolchain]
channel = "1.85.0"            # bump deliberately; >=1.63 required for OwnedFd (W10B)
components = ["rustfmt", "clippy"]
targets = ["aarch64-linux-android"]
profile = "minimal"
```

### 2. `.cargo/config.toml` (create at repo root)
```toml
# cargo-ndk injects CC/AR/linker + sysroot; no hardcoded NDK path here so host
# `cargo test` / `cargo run --bin uniffi-bindgen` keep working.
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]
```

### 3. `rust-core/Cargo.toml`
```toml
[package]
name = "dextop-core"
version = "0.1.0"
edition = "2021"              # avoids unsafe(no_mangle) churn of edition 2024
rust-version = "1.85"

[lib]
name = "dextop_core"
crate-type = ["cdylib", "rlib"]   # cdylib -> libdextop_core.so; rlib -> sibling crates

[dependencies]
uniffi = { version = "0.29", features = ["cli"] }
jni = "0.21"
libc = "0.2"
log = "0.4"

[[bin]]
name = "uniffi-bindgen"
path = "uniffi-bindgen.rs"
```
`rust-core/uniffi-bindgen.rs`:
```rust
fn main() { uniffi::uniffi_bindgen_main() }
```
`rust-core/uniffi.toml`:
```toml
[bindings.kotlin]
package_name = "moe.n4tsu.dextop.ffi"
cdylib_name = "dextop_core"     # System.loadLibrary("dextop_core") — one .so
```

### 4. `rust-core/src/lib.rs` — control plane (UniFFI) + data plane (raw JNI)
```rust
uniffi::setup_scaffolding!();          // exports UniFFI checksum/symbols

// ---- UniFFI CONTROL PLANE (typed, Kotlin generated bindings) ----
#[derive(uniffi::Enum)]
pub enum BackendId { A, B, C, D, E, F }

#[derive(uniffi::Record)]
pub struct CoreStatus { pub version: String, pub abi: String, pub ring_bytes: u32 }

#[uniffi::export]
pub fn core_status() -> CoreStatus {
    CoreStatus { version: env!("CARGO_PKG_VERSION").into(), abi: "arm64-v8a".into(),
                 ring_bytes: 65_536 }
}
#[uniffi::export]
pub fn select_backend(id: BackendId) -> bool { /* FSM handoff; default false until M6 */ true }

// ---- RAW JNI DATA PLANE (hot path; no UniFFI boxing) ----
#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    vm: *mut jni::sys::JavaVM, _reserved: *mut std::ffi::c_void,
) -> jni::sys::jint {
    let vm = match unsafe { jni::JavaVM::from_raw(vm) } { Ok(v) => v, Err(_) => return -1 };
    let env = match vm.attach_current_thread() { Ok(e) => e, Err(_) => return -1 };
    let methods = [jni::JNINativeMethod {
        name: c"nativeCreate".as_ptr(),
        signature: c"()J".as_ptr(),
        fn_ptr: input_ring_create as *mut _,
    }];
    match env.register_native_methods(
        c"moe/n4tsu/dextop/ffi/InputRingNative", &methods) {
        Ok(_) => jni::sys::JNI_VERSION_1_6,
        Err(_) => -1,
    }
}
#[no_mangle]
pub extern "system" fn input_ring_create(
    env: jni::JNIEnv, _cls: jni::sys::jclass,
) -> jni::sys::jlong {
    // mmap 64 KB ring; NewDirectByteBuffer over Rust-owned memory (see W10B:141-238)
    0
}
```
Kotlin data-plane facade: `object InputRingNative { init { System.loadLibrary("dextop_core") }; external fun create(): Long }`. Keep `JNI_OnLoad` exports in a `#[cfg(target_os = "android")]` module; host builds compile but don't register.

### 5. `android/app/src/main/cpp/CMakeLists.txt` — append inside the existing `arm64-v8a` block (after line 33)
```cmake
    # cargo-ndk drops libdextop_core.so here BEFORE the Gradle build.
    # Guard so pure-Kotlin / rollback builds still configure without Rust.
    set(DEXTOP_CORE_SO "${CMAKE_CURRENT_SOURCE_DIR}/rust-libs/arm64-v8a/libdextop_core.so")
    if(EXISTS "${DEXTOP_CORE_SO}")
        add_library(dextop_core SHARED IMPORTED)
        set_target_properties(dextop_core PROPERTIES IMPORTED_LOCATION "${DEXTOP_CORE_SO}")
        target_link_libraries(dextop_input dextop_core)   # C++ -> dextop_* FFI
    else()
        message(WARNING "libdextop_core.so missing; building without Rust core")
    endif()
```

### 6. `android/app/build.gradle.kts` — append near `android { ... }` (after line 163)
```kotlin
// cargo-ndk output is packaged as arm64 jniLibs (CMake IMPORTED only links).
android.sourceSets.getByName("main").jniLibs.srcDir("src/main/cpp/rust-libs")

// Explicit build hook; dev builds skip unless DEXTOP_BUILD_RUST=true (CI sets it).
val buildRustCore by tasks.registering(Exec::class) {
    group = "rust"
    workingDir = rootProject.file("..")
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-p", "33",
        "-o", "android/app/src/main/cpp/rust-libs",
        "build", "--release", "--manifest-path", "rust-core/Cargo.toml")
    onlyIf { System.getenv("DEXTOP_BUILD_RUST") == "true" }
}
tasks.named("preBuild") { dependsOn(buildRustCore) }
```

### 7. `.github/workflows/rust-core.yml` — reusable arm64 build with caching
```yaml
name: Rust core (arm64)
on:
  workflow_call:
  workflow_dispatch:
permissions:
  contents: read
concurrency:                      # must not run beside Gradle/CMake jobs
  group: android-gradle
  cancel-in-progress: false
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 25
    env:
      ANDROID_NDK_HOME: /opt/android-sdk/ndk/27.2.12479018
      DEXTOP_BUILD_RUST: "true"
    steps:
      - uses: actions/checkout@v4
        with: { persist-credentials: false }
      - name: Rust toolchain (reads rust-toolchain.toml)
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-linux-android
          components: rustfmt
      - name: Cache Rust build
        uses: Swatinem/rust-cache@v2
        with:
          workspaces: rust-core -> target
          key: arm64-ndk27
      - name: Cache cargo-ndk binary
        uses: actions/cache@v4
        with:
          path: ~/.cargo/bin/cargo-ndk
          key: cargo-ndk-3.5-${{ runner.os }}
      - uses: android-actions/setup-android@v3
      - name: Install NDK matching flutter.ndkVersion + API 36
        run: sdkmanager "ndk;27.2.12479018" "platforms;android-36"
      - name: Install cargo-ndk
        run: cargo install cargo-ndk --locked --version 3.5.0
      - name: Build libdextop_core.so
        run: |
          cargo ndk -t arm64-v8a -p 33 \
            -o android/app/src/main/cpp/rust-libs \
            build --release --manifest-path rust-core/Cargo.toml
      - name: Upload .so
        uses: actions/upload-artifact@v4
        with:
          name: libdextop_core-arm64-${{ github.sha }}
          path: android/app/src/main/cpp/rust-libs/arm64-v8a/libdextop_core.so
          if-no-files-found: error
          retention-days: 14
```
Wire into `debug-apk.yml` / `release-apk.yml`: add a `rust-core` job (`uses: ./.github/workflows/rust-core.yml`) before `build`; in `build` add `needs: rust-core` and an `actions/download-artifact@v4` step (`name: libdextop_core-arm64-${{ github.sha }}`, `path: android/app/src/main/cpp/rust-libs/arm64-v8a`) before "Build Debug APK"/"Build arm64 debug APK". Keep `pr-checks.yml` Rust-free (per its no-build policy); optionally add a `rust-fmt` job there (`cargo fmt --check --manifest-path rust-core/Cargo.toml`) with rust-cache.

UniFFI Kotlin bindings: generate once and commit under `android/app/src/main/kotlin/moe/n4tsu/dextop/ffi/` via a **host** build (bindgen reads the host cdylib; loading an arm64 .so cross-arch fails):
```
cargo build -p dextop-core --release --manifest-path rust-core/Cargo.toml
cargo run -p dextop-core --bin uniffi-bindgen -- generate \
  --library rust-core/target/release/libdextop_core.so --language kotlin \
  --out-dir android/app/src/main/kotlin
```

## Risks / device testing needed
- Verify `ANDROID_NDK_HOME` version == Flutter's `ndkVersion` on the runner; a mismatch yields link errors or two C++ runtimes. If Flutter pins a different NDK, override `flutter.ndkVersion` via gradle property rather than drifting.
- Confirm `readelf -l libdextop_core.so` shows `LOAD` alignment ≥16384 on the Redmi Pad Pro 5G (API 36 / 16 KB pages); test `System.loadLibrary("dextop_core")` on device.
- CMake guard means a missing .so silently degrades to no Rust core — acceptable for rollback, but CI must assert the .so is non-empty and that `DT_NEEDED libdextop_core.so` resolves (upload step `if-no-files-found: error` covers presence).
- `JNI_OnLoad` registration runs on the first `System.loadLibrary`; ensure the UniFFI control-plane facade and raw JNI facade load the SAME classloader/library, or registration won't be visible.
- Action SHAs: repo pins exact SHAs; replace tag refs (`@v4`, `@stable`) with the repo's pinned SHAs before merge. No local build performed per brief.
