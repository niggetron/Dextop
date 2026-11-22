# BENCHMARK_AND_TEST_STRATEGY

PLANNED (not implemented): 18 Perfetto/Macrobenchmark scenarios, 12-point cross-OEM hardware checklist, 2 h soak. CI currently runs flutter analyze/test, Android unit tests, APK build, localization parity; this session adds 3 JVM test classes only. No benchmarks, no on-device validation. No local builds in this session.
18 Perfetto/Macrobenchmark scenarios (idle, 1/4/8 windows, rapid drag/resize, multitouch, display connect/disconnect, resolution change, rotation, workspace switches, VRR, 2h soak, sleep/wake, kill-9 recovery, low-memory trim, thermal load). 12-point cross-OEM hardware checklist. CI runs flutter analyze/test, Android unit tests, APK build, localization parity. No local builds in this session.
