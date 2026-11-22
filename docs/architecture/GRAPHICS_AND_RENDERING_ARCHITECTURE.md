# GRAPHICS_AND_RENDERING_ARCHITECTURE

DESIGN TARGET: runtime toggle `dextop.compositor=rust|c` would gate a Rust compositor until a 1000-window soak passes; no Rust compositor or toggle exists in tree. The zero-copy change shipped this session is the C `deck_ahb_broker.inc` stack-buffer/dlopen/auth hardening (BUG-05).
Dual-plane FFI: UniFFI control plane; raw JNI 64KB SPSC input ring + ANativeWindow/RawFd data plane. Zero-copy ASurfaceControl + AHardwareBuffer + sync_fence + AChoreographer. Hardware cursor on dedicated ASurfaceControl layer (2D matrix only). Frame rate 120/60/0 Hz via ASurfaceTransaction_setFrameRate. ADPF for frame budgets; thermal guard disables blur at MODERATE. Runtime toggle dextop.compositor=rust|c gates the Rust compositor until 1000-window soak passes.
