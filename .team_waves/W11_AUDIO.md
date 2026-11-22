# W11B (M5) — Audio / Rust AAudio MMAP Daemon Report

## Scope inspected
- `android/app/src/main/kotlin/moe/n4tsu/dextop/linux/LinuxDeckAudio.kt:27-121` — spawns `libpulseaudio.so` as a separate app-UID process; stages 3 modules from assets; writes `default.pa` (`module-native-protocol-tcp` + `module-directaudio-native-sink`); 256-byte SecureRandom cookie; 3-strike/30s restart watchdog.
- `third_party/deck-audio/module-directaudio-native-sink.c:177-389,494-569,648-786` — the real AAudio engine: 48 kHz / float / stereo SPSC ring (`da_relay_proto.h:69-125`), adaptive buffer grow/decay, route-change reopen, stalled-callback watchdog, soft-knee limiter, suspend/resume.
- `LinuxAudio.kt:27-121,156-172` + `LinuxAudioPlayer.kt:35-113` — fallback stack: container-side PulseAudio null sink + `parec` raw PCM over Binder FD → Java `AudioTrack` (LOW_LATENCY, adaptive).
- `LinuxSteam.kt:379-393` — Steam/Proton picks DirectAudio iff `pactl info` over `tcp:127.0.0.1:$port` succeeds, else container fallback. Guest config `gaming/pulse-client.conf:1-3` disables `enable-shm`/`enable-memfd`.
- `tool/build_16k_native_libs.sh:55-92,112-126`, `tool/stage_deck_audio.py:28-36` — prebuilt provenance; on-disk total **2.2 MB**: `libpulse.so` 324K, `libpulseaudio.so` 68K, `libpulsecommon-13.0.so` 464K, `libpulsecore-13.0.so` 628K, `libprotocol-native.so` 140K, `module-directaudio-native-sink.so` 28K, `module-native-protocol-tcp.so` 12K (+ `libltdl`/`libsndfile`/`libffi`).
- `CastCompatibilityStreamer.kt:101-130,145-156` — video-only fMP4 (single H.264 `addTrack`); **no audio track**, so Cast never touches PulseAudio.
- `docs/architecture/PERFORMANCE_OPTIMIZATION_PLAN.md:4`, `ARCHITECTURE_DECISION_RECORDS.md:4` (ADR-09), `IMPLEMENTATION_ROADMAP.md:4` (M5).

## Findings
- **P1 — 2.2 MB of EOL PA 13 prebuilts** (`jniLibs/arm64-v8a/libpulse*.so`, `assets/deck-audio/modules/*.so`). Bionic-patched 2019 code, needs bespoke 16 KB rebuild (`build_16k_native_libs.sh:20,105-109`); one LGPL C module is the only thing that makes sound work. Fix: ADR-09 Rust in-process daemon; remove files only on the criteria below.
- **P1 — MMAP cannot be the only transport.** `gaming/pulse-client.conf:1-3` explicitly disables shm/memfd for FEX/Proton under PRoot. Fix: Rust server must accept both `SCM_RIGHTS` memfd fast path and plain `PA_COMMAND_WRITE_RAW` socket path.
- **P1 — extra process + daemon hop.** `LinuxDeckAudio.kt:60-68` forks a second process solely because bionic `libaaudio` cannot load from glibc; latency = guest→TCP→PA→ring→AAudio→AudioFlinger, with PA's own buffering. Fix: run Rust inside `libdextop_core.so` (app UID, public AAudio), keep identical TCP endpoint/cookie so `LinuxSteam.kt:379-393` detection is unchanged.
- **P2 — wire protocol is internal/undocumented.** Must mirror PA 13's `native-protocol.h` / `native-common.h` packet framing, auth challenge, command tags and sink/cork/latency semantics; guest rootfs may ship libpulse 13 or 16. Fix: pin codec to PA 13 behavior, version the codec module, conformance-test both guest versions.
- **P2 — mic path still needs container PA.** `LinuxAudio.kt:104-107` uses `module-remap-source` + `pactl subscribe`; Rust daemon replaces only the Android-side playback server, not the container install. Fix: deletion scope excludes guest `pulseaudio`/`pulseaudio-utils` packages.
- **P3 — Cast stream has no audio** (`CastCompatibilityStreamer.kt:148-156`). Do not route Cast through the mixer; removing PA is Cast-neutral.

## Deliverable — Rust `libdextop_audio` design sketch

Crate skeleton (inside `libdextop_core.so`, `crate-type = ["cdylib","rlib"]`):
```
rust/libdextop_audio/
  Cargo.toml      # tokio, bytes, thiserror, tracing, nix(memfd/SCM_RIGHTS),
                  # ringbuf, libc, aaudio-sys(path), pulse-wire(path)
  src/lib.rs      # start(port, cookie, toggle) -> AudioHandle; stop()
  src/wire.rs     # PA packet codec: 32-bit BE length + command/channel/tag body
                  # AUTH(cookie), CREATE_PLAYBACK_STREAM, WRITE_RAW, CORK,
                  # SET_PLAYBACK_VOLUME, GET_PLAYBACK_LATENCY, DRAIN, EXIT
  src/server.rs   # AF_UNIX /tmp/dextop-pulse/native + 127.0.0.1 TCP; cookie + SO_PEERCRED
  src/mixer.rs    # per-stream SPSC ring + f32 gain, sum, soft-knee limiter (port limit())
  src/mmap.rs     # memfd_create + mmap; SCM_RIGHTS on stream create; raw-write fallback
  src/output.rs   # AAudio out stream: buffer/burst, xrun grow/decay, reopen, watchdog, suspend
  aaudio-sys/     # NDK FFI
```
NDK AAudio FFI (`aaudio-sys`, `#[link(name="aaudio")]`, opaque `AAudioStream`/`Builder`):
```rust
extern "C" {
  fn AAudio_createStreamBuilder(b: *mut *mut Builder) -> i32;
  fn AAudioStreamBuilder_setDirection(b:*mut Builder, d:i32);            // AAUDIO_DIRECTION_OUTPUT
  fn AAudioStreamBuilder_setSharingMode(b:*mut Builder, m:i32);          // EXCLUSIVE→SHARED fallback
  fn AAudioStreamBuilder_setPerformanceMode(b:*mut Builder, m:i32);      // LOW_LATENCY
  fn AAudioStreamBuilder_setUsage(b:*mut Builder, u:i32);                // USAGE_GAME
  fn AAudioStreamBuilder_setFormat(b:*mut Builder, f:i32);               // PCM_FLOAT
  fn AAudioStreamBuilder_setChannelCount/SampleRate(b:*mut Builder, v:i32); // 2 / 48000
  fn AAudioStreamBuilder_setDataCallback(b:*mut Builder, cb:DataCb, ctx:*mut c_void);
  fn AAudioStreamBuilder_setErrorCallback(b:*mut Builder, cb:ErrorCb, ctx:*mut c_void);
  fn AAudioStreamBuilder_openStream(b:*mut Builder, s:*mut *mut Stream) -> i32;
  fn AAudioStream_requestStart/requestStop/close(s:*mut Stream) -> i32;
  fn AAudioStream_getFramesPerBurst/BufferSizeInFrames/BufferCapacityInFrames(s:*mut Stream)->i32;
  fn AAudioStream_setBufferSizeInFrames(s:*mut Stream, n:i32)->i32;
  fn AAudioStream_getXRunCount(s:*mut Stream)->i32;
  fn AAudioStream_getCalculatedLatencyMillis(s:*mut Stream)->i32;        // API 32+, minSdk 33 ok
}
```
Callback is `extern "C"` and allocation/lock-free: atomically read `ridx`, copy from the mix SPSC ring, apply limiter/fade, write to `audioData`, Release-store `ridx`; no JNI, no mutex. Port the C engine's constants (`DA_DEFAULT_MS`, 2-burst floor, 64-frame fades, 1 s watchdog) from `module-directaudio-native-sink.c:79-91`.

Latency target <20 ms: device buffer = **2 bursts** (e.g. 96–192 frames @48 kHz ⇒ 4–8 ms), ring target = 2 bursts (≤8 ms), `LOW_LATENCY` + EXCLUSIVE if granted; target total in-daemon ≤16 ms leaving ≤4 ms AudioFlinger headroom, verified by `AAudioStream_getCalculatedLatencyMillis` + Perfetto `audio` trace. Grow a burst per xrun, decay after 10 s quiet with remembered floor (existing behavior).

Per-stream volume mixer: each guest playback stream owns `{ring: memfd, gain: AtomicU32(f32), cork: bool}`; `pactl set-sink-input-volume` maps to `SET_PLAYBACK_VOLUME` updating gain; mix = `Σ gᵢ·ringᵢ` into 48 kHz f32 stereo, then one limiter, one ring to AAudio. `GET_PLAYBACK_LATENCY` reports ring+device frames.

Runtime toggle: `AudioBackend = Rust | LegacyPulse`, key persisted through the WAL/DesktopModeConfigurator with default `Rust`; overridable via `persist.dextop.audio.backend`. `LinuxDeckAudio.ensure()` becomes a JNI facade: Rust mode starts the in-process daemon and returns the same port/cookie; on AAudio open/bind failure auto-fallback spawns the old `libpulseaudio.so` path. Guest and `LinuxSteam.kt` need no changes.

**Deletion criteria (all required before removing prebuilts):**
1. `pactl info/list sinks/set-sink-input-volume/subscribe` parity and Steam's `tcp:` probe (`LinuxSteam.kt:388-390`) pass on both guest libpulse 13 and 16.
2. Both memfd/SCM_RIGHTS and `WRITE_RAW` transports pass; cookie auth + peer-UID check; no regression for `enable-shm=no` clients.
3. Measured <=20 ms latency, <1 xrun/min, route-change reopen <200 ms, display-off suspend/resume, limiter/fade — on the Redmi Pad Pro 5G.
4. Toggle fallback verified on API 33 and HyperOS 3; telemetry shows >=99% sessions on Rust with zero fallback for one release, then flip default.
5. Rust cdylib passes the 16 KB page / no-RUNPATH gate currently in `build_16k_native_libs.sh:105-109`.
Then delete: `libpulseaudio/libpulsecore/libpulsecommon/libpulse/libltdl/libsndfile/libffi.so`, 3 `assets/deck-audio/modules/*.so`, PA section of `build_16k_native_libs.sh`, `stage_deck_audio.py` (keep Turnip), `third_party/deck-audio/{pulseaudio-13.0-android.patch,module-directaudio-native-sink.c,da_relay_proto.h,LICENCES,manifest.json}`.

## Risks / device testing needed
- **On-device AAudio latency** may exceed 20 ms on HyperOS 3 / Bluetooth routes; if so, keep 2-burst floor and report measured value rather than claiming the target. Test speaker / USB-C / BT / headset, 120 Hz display-off suspend.
- Wire protocol is PA-internal; pin to PA 13 and test guest libpulse 16 negotiation; `SCM_RIGHTS` across PRoot's socket shim needs explicit testing.
- FEX `enable-shm=no` raw path is the likeliest regression; add it to QA (also `parec` mic monitor path must keep working with container PA).
- Security: keep 256-bit SecureRandom cookie + 0600 (`LinuxDeckAudio.kt:49-51`); loopback TCP is UID-unauthenticated, add `SO_PEERCRED` for AF_UNIX and keep cookie for TCP.
- RAM: in-process Rust removes one PA process, helping the <=130 MB budget; verify no per-stream ring leak under 20+ clients.
- No builds were run (brief constraint); all numbers are design targets pending device measurement.
