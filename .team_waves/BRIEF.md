# Shared agent brief — Dextop M0–M8 rebuild

Read this first. Hard constraints:
- NEVER build/compile locally. No gradle, flutter build, cargo build. GitHub Actions only.
- NEVER print or store the GitHub token (it is in the git remote URL).
- CODE AGENTS: do not modify source. Write ONLY your assigned report file under `/root/projects/Dextop/.team_waves/`.
- WEB AGENTS: use websearch/webfetch; cite URLs. Write ONLY your report file.
- Remember: target is Xiaomi Redmi Pad Pro 5G, Android 16 (API 36), HyperOS 3, 12.1" 120 Hz tablet, arm64-v8a, minSdk 33. Prefer stable public NDK APIs (ASurfaceControl, AHardwareBuffer, AAudio, AChoreographer, ADPF, requestPointerCapture) before hidden APIs. Isolate every hidden API behind versioned adapters.

## Blueprint essentials (from the master engineering blueprint in the Ai folder)

- Existing: 82k LOC Kotlin, 16.8k LOC C/C++, ~31k LOC hand-written Dart host UI. Native Compose Book shell already exists.
- Strategy: Kotlin+Compose M3E for UI; Rust `libdextop_core.so` for data plane; keep C only for PRoot/fastpath/preload; retire Flutter; reject Go/C#/Zig.
- Six backends: A=public Android desktop, B=native OEM desktop, C=composited virtual display, D=standalone Wayland Linux Mode B, E=scoped OEM (Redmi Pad Pro 5G HyperOS 3), F=graceful compatibility.
- `DesktopSessionFsm` single source of truth; atomic JSON WAL + 15 s detached watchdog; typed `PrivilegeGate`; UniFFI control plane + raw JNI data plane; 64 KB SPSC input ring.
- Milestones: M0 BUG-01..06, M1 ADB capability capture, M2 probe engine+FSM+WAL+watchdog, M3 repositories+Compose migration+remove Flutter, M4 backends A–F, M5 Rust crates, M6 Rust Wayland/graphics, M7 desktop features, M8 QA/security/docs sign-off.

## Critical bugs to fix in M0

- BUG-01: `SessionJournal` restores only 3 of 7 SDK-36 `modernKeys` → leaks 4 settings. Fix: atomic WAL JSON storing every mutated key, restore all.
- BUG-02: `DesktopModeConfigurator.configureDisplay()` posts blocking `wm` IPC on main looper, never cancels. Fix: session-scoped `CoroutineScope(Dispatchers.IO)` + cancel on teardown.
- BUG-03: `MirrorService.kt` 14k LOC God object. Fix: decompose into domain services + StateFlow FSM.
- BUG-04: `cpp/wayland/deck_direct.inc` `recvmsg(...) != 1 -> return 0` busy-spins epoll on EOF/error; `wl_event_loop_add_fd` NULL leaks FDs.
- BUG-05: `deck_ahb_broker.inc` 64 KB stack buffer + dlopen/dlsym/dlclose per allocation + Steam-only zero-copy + weak `/proc/<pid>/environ` auth.
- BUG-06: `input/LaptopSwipeDecoder.kt` access-order LinkedHashMap mutated unsynchronized across prewarm/decode.

## Skills to follow (skills catalog)

Official: `android/skills`, `Kotlin/kotlin-agent-skills` (+ Kotlin MCP `https://mcp.kotlinlang.org/mcp`), `flutter/skills`, `dart-lang/skills`, `firebase/agent-skills`, `anthropics/skills` (skill-creator), `trailofbits/skills`.
Performance/testing: `skydoves/compose-performance-skills`, `skydoves/android-testing-skills`, `aldefy/perfetto-android-skills`, `hah23255/adb-android-control`.
Systems/graphics: `leonardomso/rust-skills`, `mohitmishra786/low-level-dev-skills`, `tarkansarim/CppStudio`, `claude-dev-suite/claude-dev-suite` (rust-cross-compile), `HermeticOrmus/linux-sysadmin-skills`.
UI/compose: `haidrrrry/compose-kotlin-agent-skills`, `anhvt52/jetpack-compose-skills`, `gdlbo/material-skill`, **`https://github.com/hamen/material-3-skill`** (user requested), `revanthkumarJ/android-skills`.
Orchestration: `obra/superpowers` (systematic-debugging, TDD, subagent-driven-development, verification-before-completion).
Five repo-local skills to author: `dextop-privilege-wal-guard`, `dextop-hyperos-oem-backends`, `dextop-wayland-ahb-zero-copy`, `dextop-proot-c-boundary`, `dextop-flutter-to-compose-parity`.

## Report format

```
# <Role> — Wave <N> Report
## Scope inspected
## Findings (P0/P1/P2/P3 with file:line, why it matters on Android 16/HyperOS tablet, concrete fix)
## Deliverable (design/code sketch or web citations)
## Risks / device testing needed
```
Be terse and specific. Return only the file path when done.
