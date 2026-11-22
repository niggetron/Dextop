# Docs Audit — Wave 22 Report

## Scope inspected
`README.md`, `CHANGELOG.md`, all 22 `docs/architecture/*.md` (added this session), and the real diff `4af285f..HEAD` (123 files). Verified against tree: no `Cargo.toml`/`*.rs`; no Compose plugin or `ui/` package in Gradle; 65 Dart files under `lib/`, 353 Kotlin files; `MirrorService.kt` still 14,134 lines. No builds, no source edits, no token.

## Findings — claims inaccurate about what ships
- **P1** `CHANGELOG.md:5-7` — note claims the beta.4 section "describes the M0–M2 work"; its bullets are verbatim beta.3 content (DextopLog, SAF export, Xiaomi switches, release workflow). Zero WAL/FSM/watchdog/capability/epoll/AHB/swipe bullets.
- **P1** `DEVELOPMENT_TRACKER.md:3` — "Current milestone: M0 … native BUG-04/05/06 pending" — false: BUG-04/05/06 shipped in `68e47213`; M1/M2 also shipped.
- **P1** `TECHNOLOGY_STACK_COMPARISON.md:3` — "Flutter/Dart: retired" — false; Flutter is the shipping host UI. Rust "chosen" but absent.
- **P1** `HIGH_LEVEL_DESIGN.md:3`, `IMPLEMENTATION_ROADMAP.md:3` — present Compose/Rust/backends A–F as architecture layers without status; none exist.
- **P2** `FEATURE_INVENTORY.md:3` — "session restoration (BUG-01)" as partial/buggy — now fixed.
- **P2** `INPUT_SYSTEM_ARCHITECTURE.md:3` — "Fix BUG-06 immediately" — done; Rust input/SPSC/NEON described as current — designed only.
- **P2** `DEVICE_COMPATIBILITY_MATRIX.md:3` — "Backend E+D" — designed only; "unverified on HW" is correct (no device validation this session).
- **P2** `BENCHMARK_AND_TEST_STRATEGY.md:3`, `GRAPHICS_AND_RENDERING_ARCHITECTURE.md:3` — 18 benchmarks/VRR/Rust compositor toggle/soak described as present — planned only.
- **P3** All 22 architecture files have duplicate H1 headings (`# FOO` + `# Foo`). README `beta.3` (`:176,:221`) is internally consistent: beta.4 is not released; it is silent on M0–M2 and its device tables are inherited community/upstream reports, not this session's validation.

## Deliverable — exact corrected text (replace the quoted current line)

**CHANGELOG.md:5-7**
```
> The bullets below were carried over from beta.3 (diagnostics, Xiaomi switches,
> release workflow) and do NOT describe this session's work. Actually in this
> tree: BUG-01 atomic WAL restores every mutated key (all 7 SDK-36 modernKeys +
> Secure writes, fsync before mutation, reverse-order verified restore); BUG-02
> off-main cancellable wm IPC; BUG-04 epoll spin/FD-leak fix; BUG-05 AHB broker
> stack-buffer/dlopen/auth fix; BUG-06 LaptopSwipeDecoder LRU synchronization;
> M1 CapabilitySnapshot + tool/hyperos_capability_capture.sh; M2
> DesktopSessionFsm + detached 15 s watchdog + 3 JVM test classes. None of
> M3–M8 has shipped. The `v2.0.0-beta.3` tag predates these fixes; beta.4 is
> not yet released.
```

**DEVELOPMENT_TRACKER.md:3**
```
Status: M0–M2 implemented in tree (BUG-01/02/04/05/06 in b743936 + 68e47213; M1 CapabilitySnapshot + capture script; M2 DesktopSessionFsm + detached watchdog + WAL tests). M3–M8 are design-only wave reports: no repositories, Compose shell, Rust crates, backends A–F, portals/terminal, or QA/security sign-off in source. Flutter remains the shipping host UI. No on-device validation this session. Builds verified by GitHub Actions only (no local builds).
```

**TECHNOLOGY_STACK_COMPARISON.md:3**
```
DESIGN TARGET (not shipped): Kotlin+Compose M3E for UI + orchestration; Rust libdextop_core.so for data plane/Wayland/graphics/audio/installer/ADB TLS/input; C kept for PRoot/fastpath/preload; C++ phased out. Rejected: Go, C#, Zig. SHIPS TODAY: Flutter/Dart host UI (lib/, flutter Gradle plugin, `flutter build apk`) plus native Kotlin; only M0–M2 native fixes added. No Rust crate, no Compose module, no backend A–F code. Full 15-criteria matrix in master blueprint Part D.
```

**HIGH_LEVEL_DESIGN.md:3**
```
DESIGN TARGET (layers 1–5 not shipped): 1 Presentation (Compose M3E); 2 Orchestration (FSM, Workspace, Window, Display, Input coordinators); 3 Domain models; 4 Backends A–F + PrivilegeProvider; 5 Rust native core; 6 C kernel/guest boundary; 7 Persistence/recovery/diagnostics. SHIPS NOW: Flutter/Dart presentation + native Kotlin services; only DesktopSessionFsm, atomic WAL and detached watchdog exist; no Compose module, backends A–F, or Rust core. Full Mermaid HLD in master blueprint Part F.1.
```

**IMPLEMENTATION_ROADMAP.md:3**
```
M0 — IMPLEMENTED except BUG-03 (God-object decomposition NOT done; MirrorService still 14.1k LOC). M1 — IMPLEMENTED as read-only CapabilitySnapshot + capture script (no on-device capture run). M2 — IMPLEMENTED: DesktopSessionFsm + WAL + detached 15 s watchdog + tests. M3 repositories/54-screen Compose migration/Flutter removal — DESIGNED ONLY (W06–W08). M4 backends A–F — DESIGNED ONLY. M5 Rust crates — DESIGNED ONLY (no Cargo.toml/.rs). M6 Rust Wayland/graphics/VRR — DESIGNED ONLY. M7 snap/tiling/palette/portals/terminal — DESIGNED ONLY. M8 soak/security/docs sign-off — NOT STARTED. Exit gates in master blueprint Part K.
```

**FEATURE_INVENTORY.md:3**
```
Implemented: [existing list]. Fixed this session: session restoration (BUG-01 — atomic WAL, all modernKeys + Secure writes), teardown IPC (BUG-02), epoll spin/FD leak (BUG-04), AHB broker stack/dlopen/auth (BUG-05), swipe-LRU race (BUG-06). Partial/buggy: zero-copy graphics (Steam-only). Missing / designed only: xdg-desktop-portal file picker, BSP tiling / Stage Manager, unified clipboard portal, Rust terminal, backends A–F, Compose host UI, Rust data plane.
```

**INPUT_SYSTEM_ARCHITECTURE.md:3**
```
DESIGN TARGET: unified Rust input thread (uinput + 64 KB SPSC ring + ARM64 NEON swipe decoder), mmap+FST swipe decoder, privileged injectInputEvent/uinput for cross-display. SHIPS NOW: Kotlin LaptopSwipeDecoder + native_input.cpp; BUG-06 synchronization FIXED, mmap+FST migration NOT done; no Rust input thread/SPSC ring. requestPointerCapture and non-fatal input refusals already hold. Rate-limited rejection logs: not verified.
```

**DEVICE_COMPATIBILITY_MATRIX.md:3**
```
Xiaomi HyperOS 3 / Redmi Pad Pro 5G: Backend E+D is a DESIGN TARGET only — no backend A–F code in tree; P0, unverified on HW (no device validation this session; M1 capture tool added but not run). [rest of line unchanged]
```

**BENCHMARK_AND_TEST_STRATEGY.md:3**
```
PLANNED (not implemented): 18 Perfetto/Macrobenchmark scenarios, 12-point cross-OEM hardware checklist, 2 h soak. CI currently runs flutter analyze/test, Android unit tests, APK build, localization parity; this session adds 3 JVM test classes only. No benchmarks, no on-device validation. No local builds in this session.
```

**GRAPHICS_AND_RENDERING_ARCHITECTURE.md:3** (replace final sentence)
```
DESIGN TARGET: runtime toggle `dextop.compositor=rust|c` would gate a Rust compositor until a 1000-window soak passes; no Rust compositor or toggle exists in tree. The zero-copy change shipped this session is the C `deck_ahb_broker.inc` stack-buffer/dlopen/auth hardening (BUG-05).
```

## Implementation status (honest)

| Item | Status | Evidence in diff |
| --- | --- | --- |
| M0 BUG-01/02/04/05/06 | **Implemented** | SessionJournal WAL, DesktopModeConfigurator, two `.inc` files, LaptopSwipeDecoder |
| M0 BUG-03 (MirrorService split) | **Deferred** | MirrorService.kt still 14,134 LOC; only journal/cancel/scoped-tablet edits |
| M1 capability capture | **Implemented (code only)** | CapabilitySnapshot.kt, tool/hyperos_capability_capture.sh; no device run |
| M2 FSM + WAL + watchdog | **Implemented** | DesktopSessionFsm, WatchdogClient/Daemon/Protocol, 3 test classes |
| M3 repos / 54-screen Compose / Flutter removal | **Designed only** | W06/W07/W08 reports; 65 Dart files + Flutter plugin remain |
| M4 backends A–F | **Designed only** | W03/W09 reports; no backend source |
| M5 Rust crates | **Designed only** | W10/W11 reports; no Cargo.toml/.rs |
| M6 Rust Wayland / zero-copy / VRR | **Designed only** | W12 reports; C compositor unchanged except BUG-04/05 |
| M7 snap/tiling/palette/portals/terminal | **Designed only** | W13/W14 reports |
| M8 QA / security / docs sign-off | **Not started** | No soak, no security sign-off; this audit only |
| Device validation (Redmi Pad Pro 5G / API 36 / HyperOS 3) | **Not performed** | No ADB capture run; README device rows are inherited reports |

## Risks / device testing needed
- Correcting these docs must not imply M3–M8 shipped; ship only beta.4 M0–M2 notes.
- On-device: run `tool/hyperos_capability_capture.sh` on the Redmi Pad Pro 5G; verify WAL restore after `kill -9`, 15 s watchdog rollback, and hidden-API bypass 6.1 at runtime (all unverified).
- P3: de-duplicate the two H1 headings in every architecture stub.
