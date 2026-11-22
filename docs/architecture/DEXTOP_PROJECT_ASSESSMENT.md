# DEXTOP_PROJECT_ASSESSMENT

# Dextop Project Assessment
Target: Xiaomi Redmi Pad Pro 5G, Android 16 (API 36), HyperOS 3, 12.1" 120 Hz tablet, arm64-v8a, minSdk 33.
Dextop is a native-heavy Android desktop platform: ~82k LOC Kotlin, ~16.8k LOC C/C++, ~31k LOC hand-written Dart host UI. Do NOT greenfield rewrite: Kotlin desktop shells (Book/Plasma) and Android system services are already mature. The plan is a phased, capability-driven evolution: Kotlin+Compose M3E UI, Rust data plane, C preserved for PRoot/LD_PRELOAD, Flutter retired. See the master blueprint in the user's Ai folder for the full audit.
