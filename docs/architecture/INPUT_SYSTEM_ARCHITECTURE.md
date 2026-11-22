# INPUT_SYSTEM_ARCHITECTURE

# Input System Architecture
Unified Rust input thread: uinput + 64KB SPSC ring + ARM64 NEON swipe decoder. View.requestPointerCapture for raw deltas; privileged injectInputEvent/uinput for cross-display. Preserve multi-touch, hover, right-click, wheel, modifier semantics. Fix BUG-06 immediately (synchronization), then migrate LaptopSwipeDecoder to mmap+FST. Rate-limit rejection logs; never treat a normal refusal as fatal.
