# Web Research — Wave 22 Report (Remaining-feature prioritization for Redmi Pad Pro 5G / Android 16)

## Scope inspected
Read BRIEF, W01–W21 reports (esp. W20_WEB, W21_WEB), `docs/architecture/*` (FEATURE_INVENTORY, IMPLEMENTATION_ROADMAP, PERFORMANCE_OPTIMIZATION_PLAN, HYPEROS_3_COMPATIBILITY_ANALYSIS, RISK_REGISTER), `CHANGELOG.md`, git log (HEAD `f60fce3`), source tree. Web-only; no source edits, no token.

**Shipped at HEAD:** M0 BUG-01..06, M1 `CapabilitySnapshot`, M2 FSM + atomic WAL + 15 s detached watchdog; native Compose Book shell exists. **Not shipped:** M3 54-screen Compose migration (Flutter still builds: `pubspec.yaml`, `build.gradle.kts:8`), M4 backends A–F, M5/M6 `libdextop_core.so` (no `Cargo.toml` anywhere), M7 portals/tiling/palette/terminal, M8 QA harness. W06–W18 are *design sketches*, not merged code. Prioritization below therefore weights **finish-and-verify** over new greenfield features.

## Findings

**P0 — 16 KB page alignment is a hard release gate, not a feature.** All native payload is 4 KB-aligned (`cpp/wayland/*`, PRoot/fastpath/preload, `jniLibs/arm64-v8a/libpulse*.so`); from 2027-02-01 Play refuses updates for API 35+ apps that lack 16 KB support, Android 16 shows a backcompat warning dialog, Android 17 can make backcompat fatal. Dextop cannot reach users at all without this. Fix: NDK r28 / `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`, AGP ≥8.5.1, uncompressed `.so`, `check_elf_alignment.sh` + `zipalign -c -P 16` CI gate, replace `PAGE_SIZE` with `sysconf(_SC_PAGESIZE)`. https://developer.android.com/guide/practices/page-sizes ; https://developer.android.com/about/versions/16/behavior-changes-all#16-kb-compatibility-mode (W20_WEB:29–34, W12_WEB P1)

**P0 — Session survival is the biggest day-to-day UX win, and it is half-built.** M0–M2 shipped WAL/watchdog, but W18 found unjournaled forced `wm size/density` and three `Settings.Secure` writes, W15A found *zero* executable on-device QA harness, W15B found a P0 arbitrary privileged-command injection, W21 confirmed only static compile cleanliness. On HyperOS 3 (aggressive background killing, boot-time key resets) a session that dies and cannot restore is the top user complaint. Fix: land W18 patches + W15 QA (`04_watchdog_kill9.sh`, `05_fd_leak_1000.sh`) + W15B `PrivilegeGate` before any new feature; keep hidden-API calls probe-gated/non-fatal per W20_WEB. https://developer.android.com/about/versions/16/changes/non-sdk-16 ; https://github.com/LSPosed/AndroidHiddenApiBypass

**P0 — Cut backend B/E expectations; invest in C+F.** The closest production evidence (LSPosed module on Xiaomi) shows even HyperOS 4/Android 17 hides the desktop button, re-enables mirroring via non-AOSP `SecondaryDisplayPolicy`, ships a single-display launcher, and cannot expose AOSP resizable decorations/floating windows are fixed-size. It also *requires DP Alt Mode*, which this tablet lacks (USB 2.0, no DP Alt Mode — `HYPEROS_3_COMPATIBILITY_ANALYSIS.md`). Native OEM desktop is unreachable for a sideloaded third-party app on this SKU. Fix: scope backend E to "best-effort settings + documented limits", make C (composited virtual display) the primary and F the graceful fallback; M1 capture still missing (W20_RELEASE P3). https://github.com/the-marox/HyperDesktopUnlock ; https://www.mi.com/global/product/redmi-pad-pro/specs/

**P1 — File-chooser portal + image clipboard are the largest functional gap in Linux mode.** `xdg-desktop-portal-kde` is installed but no OpenFile/SaveFile backend exists; GTK/Qt apps cannot reach scoped `content://` storage; clipboard is text-only so image copies are silently lost (W14A: P0/P0). Standard portal contract is async `OpenFile`/`SaveFile` returning `file://` URIs, with results persisted via the Documents portal — exactly what the scoped bridge must emulate. Fix: reuse the already-authenticated device bridge (no new listener), preserve `/media/android/.portals` in `syncAll()`, `ParcelFileDescriptor` for image AIDL, focus-gate clipboard reads. https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.FileChooser.html ; https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens

**P1 — Keyboard/pointer UX is the highest leverage-per-effort feature.** Android 16 desktop guidance expects full keyboard navigation, standard shortcuts, Keyboard Shortcuts Helper (Meta+/), `Escape` as local cancel, and first-class right-click/hover/scroll/drag — Dextop currently has no single shortcut source of truth and its palette is a legacy View popup (W13A/B/C). Fix: one `ShortcutRegistry` → `onPreviewKeyEvent` (KeyDown, `repeatCount==0`) + `onProvideKeyboardShortcuts` + inline palette hints; Ctrl+K palette; per-session `requestPointerCapture` bound to focused surface and released on FSM teardown. https://developer.android.com/design/ui/desktop/guides/interaction/keyboard ; https://developer.android.com/develop/ui/compose/touch-input/keyboard-input/keyboard-shortcuts-helper ; https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens

**P1 — Drop forced-rotation as a feature; fix predictive back instead.** On `sw >= 600dp` with target 36, `screenOrientation`, `setRequestedOrientation()` and resizability are *ignored*; predictive back is default and `KEYCODE_BACK` is no longer dispatched. `PhoneRotationController`'s freeze and accessibility key interception are therefore ineffective/hazardous on this exact tablet. Fix: stop investing in rotation lock; preserve state through the resulting activity re-creation (WAL already does), migrate back handling to predictive APIs, retest key capture. https://developer.android.com/about/versions/16/behavior-changes-16#adaptive-layouts

**P1 — 120 Hz pacing + thermal governor before VRR.** Demand-driven presentation at 8.33 ms/frame tears or queues a frame; sustained 120 Hz + Adreno blit on a midrange Snapdragon will throttle unpredictably. Fix: `AChoreographer_postVsyncCallback` + `ASurfaceTransaction_setDesiredPresentTime` + ADPF/thermal hysteresis 120→60→0; measure presented frames, never claim 120 Hz unverified. Defer variable refresh until this baseline exists. https://developer.android.com/ndk/reference/group/choreographer ; https://source.android.com/docs/core/perf/performance-hint-api (W12_GFX, W12_WEB)

**P2 — Rust data plane: real but indirect gains; gate behind toggles.** `libdextop_core.so` / Rust watchdog / AAudio / streaming installer save RAM and latency (WebView terminal alone 80–150 MB), but vanilla Smithay is not an Android backend, `ndk-sys` lacks `ASurfaceControl`, and dmabuf→AHB import is VNDK/hidden and gralloc-specific. Fix: ship the C compositor as default, add one versioned `android-ffi` adapter, prefer public `AHardwareBuffer_allocate`→EGL→`setBuffer` zero-copy, and keep `dextop.compositor=c|rust`, `dextop.terminal=webview|native` toggles through M8. https://github.com/eirkkk/winland-Android ; https://docs.rs/ndk-sys/latest/ndk_sys/ ; https://developer.android.com/ndk/reference/group/a-hardware-buffer (W12_WEB, W14B, W10C)

**P2/P3 — Drop or defer (low real-world value / high risk on this SKU).**
- **Defer full Rust Wayland port + 1024-window slotmap rewrite** — typical tablet use opens <20 windows; 1000-window ceiling is not a user-visible problem (W12A P0).
- **Defer AHB import for all clients** — hidden VNDK + MediaTek gralloc incompatibility; keep the public allocate/render/submit path (W12_WEB P0).
- **Defer FancyZones editor / dynamic BSP / Stage Manager rail** — ship simple persistent snap zones first (W13A/B).
- **Defer Rust AAudio daemon and reflink installer base** until PulseAudio/download are proven broken on device (W11A/B).
- **Defer Flutter retirement** — no user-visible gain; migrate screens incrementally behind parity, never block a release (W08_SHELL/W08_RETIRE).
- **Drop Cast audio (W11 P3) and native OEM desktop (B/E) claims** — no practical path on this tablet.

## Deliverable (final prioritized roadmap)
| # | Priority | Feature | Why / gate |
|---|---|---|---|
| 1 | P0 | 16 KB ELF + zip alignment, CI gate; resolve W20 version skew | Release blocker; Play Feb 2027 |
| 2 | P0 | Land W18 journaling + W15A QA harness + W15B `PrivilegeGate`; kill-9/restore device test | Session survival = top UX win |
| 3 | P0 | Backend C virtual display + F fallback; M1 on-device capture; scope E to best-effort | B/E blocked by HyperOS + no DP Alt Mode |
| 4 | P1 | Scoped FileChooser portal + image clipboard | Linux mode daily workflow blocker |
| 5 | P1 | `ShortcutRegistry`, Ctrl+K palette, Shortcuts Helper, per-session pointer capture | Keyboard-first 12.1" desktop |
| 6 | P1 | Predictive-back migration; retire rotation-freeze; state preservation | API 36 sw600dp behavior |
| 7 | P1 | `postVsyncCallback` + ADPF thermal 120/60/0 governor | Stable 120 Hz; precedes VRR |
| 8 | P2 | Rust core skeleton behind toggles → terminal (RAM) → AAudio → installer; keep C compositor | Indirect gains, high risk |
| 9 | Defer/drop | Wayland port, AHB import, tiling editor/Stage Manager, VRR, Flutter removal, Cast audio, B/E native desktop | See P2/P3 list |

## Risks / device testing needed
1. `adb shell getconf PAGE_SIZE` on the Redmi Pad Pro 5G; run `check_elf_alignment.sh` on the release APK; if 16384 with 4 KB `.so`, expect compat dialog.
2. Run/check in M1 HyperOS capture (still absent) before claiming any backend E/D behavior.
3. Verify kill-9 recovery and reboot restore of all 7 `modernKeys`; verify hidden-API probe disables strategies non-fatally.
4. Verify portal grant lifetime in `syncAll()` and image clipboard with focus; test predictive back + `requestPointerCapture` together.
5. Measure presented frames under sustained 120 Hz to confirm the thermal governor actually holds budget; do not enable VRR on these results.
