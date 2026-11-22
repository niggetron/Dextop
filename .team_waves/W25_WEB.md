# Web Research — Wave 25 Report (APK verification in CI, Android 16 / API 36)

## Scope inspected
Web-only research (no source edits, no token). Authoritative sources: developer.android.com (aapt2, zipalign, page-sizes, target-sdk, AGP release notes), AOSP `check_elf_alignment.sh`, man7 `readelf(1)`, Android Developers Blog. All commands below verified against SDK 36 build-tools (36.0.0 / 36.1.0).

## Findings (terse, cited)

- **16 KB requirement**: Android 15+ AOSP supports 16 KB pages. Google Play: apps targeting API 35+ must support 16 KB on 64-bit devices; new apps/updates from **Nov 1, 2025**, and from **Feb 1, 2027** updates lacking support are blocked. Target API 36 mandatory for new apps/updates from **Aug 31, 2026**. Dextop (API 36, arm64-v8a, native `libdextop_core.so` + C/PRoot) is in scope. [page-sizes](https://developer.android.com/guide/practices/page-sizes) · [Play blog 2025-05](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html) · [target-sdk](https://developer.android.com/google/play/requirements/target-sdk)
- **Two independent checks**: (1) ELF `PT_LOAD` `p_align` >= 0x4000 inside each arm64-v8a/x86_64 `.so`; (2) ZIP 16 KB page alignment of uncompressed `.so` entries in the APK. AAB must report `PAGE_ALIGNMENT_16K` via `bundletool dump config --bundle=app.aab | grep alignment`. [page-sizes](https://developer.android.com/guide/practices/page-sizes) · [16KB guide](https://xckevin.com/en/blog/android-16kb-page-size-elf-ndk/)
- **Toolchain defaults**: AGP >= 8.5.1 + NDK r28+ build 16 KB-aligned by default. NDK r27 and lower need `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`. SDK 36: `build-tools;36.0.0` (AGP 9.x default; NDK default 28.2.13676358), `platforms;android-36`. [page-sizes](https://developer.android.com/guide/practices/page-sizes) · [AGP 9.5.0 notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes) · [NDK revision history](https://developer.android.com/ndk/downloads/revision_history)
- **RELRO**: GNU_RELRO end `(VirtAddr+MemSiz) % 0x4000 == 0` OR no writable data after RELRO in same LOAD; otherwise SIGSEGV on 16 KB devices. [page-sizes](https://developer.android.com/guide/practices/page-sizes#relro)
- **zipalign ordering**: with `apksigner`, zipalign **before** signing. Build-tools >= 35 apksigner defaults `--lib-page-alignment 16384` and uses an "Android ZIP Alignment Extra Field" (`0xd935`), changing padding; `--alignment-preserved` keeps existing padding. `-P` requires build-tools **35.0.0-rc3+** → fine on 36.x. [zipalign](https://developer.android.com/tools/zipalign) · [apksigcopier #105](https://github.com/obfusk/apksigcopier/issues/105)

## Deliverable — exact commands (CI, `$BT=$ANDROID_HOME/build-tools/36.0.0`, `$NDK=.../ndk/28.x`)

```bash
BT="$ANDROID_HOME/build-tools/36.0.0"
NDKTOOLS="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
# sdkmanager "build-tools;36.0.0" "platforms;android-36" "ndk;28.2.13676358"

# 1) Manifest / badging sanity — syntax: aapt2 dump <subcmd> <file.apk> [options]
"$BT/aapt2" dump badging app.apk > badging.txt      # subcmd FIRST, apk second
grep -E 'sdkVersion|targetSdkVersion|native-code|application-label' badging.txt
# options: --no-values | --file <file> | -v   [https://developer.android.com/tools/aapt2]

# 2) ZIP 16 KB alignment check — -c checks only; -P <4|16|64>; 4 = generic alignment
"$BT/zipalign" -c -P 16 -v 4 app.apk | tail -n 1    # expect "Verification successful"
# use -P 16 for .so; apksigner sign after zipalign (or rely on apksigner 16 KB default)

# 3) AOSP all-in-one ELF+ZIP checker (APK | .so dir | .apex), Linux/macOS
check_elf_alignment.sh app.apk                      # prints ALIGNED/UNALIGNED per arm64-v8a lib
# source: https://android.googlesource.com/platform/system/extras/+/refs/heads/main/tools/check_elf_alignment.sh
# script internally: zipalign -v -c -P 16 4 "$apk" | egrep 'lib/arm64-v8a|lib/x86_64|Verification'
#                   objdump -p "$so" | grep LOAD | awk '{print $NF}' -> want 2**14+

# 4) Manual ELF inspection: extract, then per arm64-v8a .so
unzip -q app.apk 'lib/arm64-v8a/*.so' -d apk_out
"$NDKTOOLS/llvm-readelf" -Wl apk_out/lib/arm64-v8a/*.so | grep -E 'LOAD|GNU_RELRO|Type'
# or host GNU readelf: readelf -lW libfoo.so | grep -E 'LOAD|RELRO'
#   -l/--program-headers/--segments -> LOAD rows; final Align column >= 0x4000
#   -W wide = one line per segment (no 80-col wrap)
readelf -dW apk_out/lib/arm64-v8a/libdextop_core.so   # -d/--dynamic: NEEDED/SONAME deps (audit prebuilts)
# NDK alt: llvm-objdump -p libfoo.so | grep LOAD -> "align 2**14" (2**12 = 4KB = FAIL)
# RELRO gate: (GNU_RELRO VirtAddr+MemSiz) % 0x4000 == 0  [man7 readelf(1); page-sizes doc]

# 5) AAB page alignment (Play bundle path)
bundletool dump config --bundle=app.aab | grep alignment   # expect PAGE_ALIGNMENT_16K
```

Citations: [aapt2](https://developer.android.com/tools/aapt2) · [zipalign](https://developer.android.com/tools/zipalign) · [page-sizes](https://developer.android.com/guide/practices/page-sizes) · [check_elf_alignment.sh](https://android.googlesource.com/platform/system/extras/+/refs/heads/main/tools/check_elf_alignment.sh) · [readelf(1)](https://man7.org/linux/man-pages/man1/readelf.1.html)

## Risks / device testing needed
- Verify on-device: `adb shell getconf PAGE_SIZE` must return `16384` (16 KB emulator system image or Cuttlefish arm64; Redmi Pad Pro 5G HyperOS 3 kernel page size to be captured in M1). [page-sizes](https://developer.android.com/guide/practices/page-sizes)
- Host `readelf`-based script reads only the **first** LOAD segment; `llvm-readelf -Wl`/APK Analyzer covers all LOAD segments + RELRO — prefer the latter for the CI gate.
- 4 KB backcompat mode exists but emits a warning and is not reliable; do not rely on it for native `libdextop_core.so`.
- Hidden/OEM APIs behind adapters do not affect alignment; but any third-party prebuilt `.so` must be 16 KB rebuilt/reimported or the gate fails.
