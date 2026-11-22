# PERFORMANCE_OPTIMIZATION_PLAN

# Performance Optimization Plan
120Hz budget 8.33ms; 60Hz 16.67ms; static idle 0 FPS after 1.5s. Remove 54 Handler allocations/65 postDelayed loops; stream 240Hz input via 64KB SPSC ring (zero JVM allocation); ADPF frame reporting; AChoreographer VSync; AHB zero-copy for all clients; Rust VT100 terminal (80-150MB RAM saved); Rust AAudio daemon (removes ~2MB PulseAudio); streaming rootfs installer 3-5x. RAM budgets: <=130MB host RSS on Redmi Pad Pro 5G.
