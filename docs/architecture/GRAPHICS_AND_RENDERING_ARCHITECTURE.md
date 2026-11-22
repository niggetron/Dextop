# GRAPHICS_AND_RENDERING_ARCHITECTURE

# Graphics and Rendering Architecture
Dual-plane FFI: UniFFI control plane; raw JNI 64KB SPSC input ring + ANativeWindow/RawFd data plane. Zero-copy ASurfaceControl + AHardwareBuffer + sync_fence + AChoreographer. Hardware cursor on dedicated ASurfaceControl layer (2D matrix only). Frame rate 120/60/0 Hz via ASurfaceTransaction_setFrameRate. ADPF for frame budgets; thermal guard disables blur at MODERATE. Runtime toggle dextop.compositor=rust|c gates the Rust compositor until 1000-window soak passes.
