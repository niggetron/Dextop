---
name: dextop-wayland-ahb-zero-copy
description: Dual-plane FFI and zero-copy AHardwareBuffer/ASurfaceControl invariants for Dextop's Wayland compositor. Use when editing native C/C++ compositor, AHB broker, direct presentation, or future Rust compositor code.
---

# Dextop Wayland / AHB Zero-Copy Guard

## Non-negotiable rules

1. **Dual-plane boundary:** control plane = UniFFI async RPCs; data plane = 64 KB SPSC direct ByteBuffer + raw JNI (`ANativeWindow`, raw FD). Never marshal high-frequency input through the control plane.
2. **RAII FD ownership.** Every `fence`, `AHardwareBuffer` FD and `wl_event_loop_source` has exactly one owner; use `OwnedFd`-style discipline (or explicit destroy on every error path). BUG-04 leaked FDs on a NULL `wl_event_loop_add_fd` result.
3. **epoll correctness.** `recvmsg()` returning 0 means EOF, and -1 with `errno != EAGAIN/EWOULDBLOCK` is fatal → destroy the frame and remove its source. Returning 0 without removing the source causes a 100% CPU busy-spin (BUG-04).
4. **No per-allocation dlsym.** Cache `libnativewindow.so` symbols once at init; no `dlopen`/`dlsym`/`dlclose` per AHB. No 64 KB stack buffers — use heap/`mmap` (BUG-05).
5. **Socket auth:** `SO_PEERCRED` (`uid == appUid`) + per-session capability token. Never gate features on `/proc/<pid>/environ` string matching.
6. **Zero-copy for all clients**, not only `DEXTOP_STEAM_INSIDE=1`. Gate behind a runtime toggle `dextop.compositor=rust|c` until soak-tested.
7. **VSync via `AChoreographer_postVsyncCallback`; frame rate via `ASurfaceTransaction_setFrameRate` (120/60/0 Hz); dedicated hardware cursor layer updated by 2D matrix only.**

## Review checklist

- [ ] Every error path destroys FDs/sources.
- [ ] No blocking recv in event loop.
- [ ] Symbols cached; no stack giants.
- [ ] Peer authenticated.
- [ ] 1,000-window FD-leak soak planned.

## Regression shield

BUG-04 (epoll spin + FD leak), BUG-05 (dlsym churn + stack + weak auth).
