---
name: dextop-proot-c-boundary
description: Protects Dextop's C kernel/guest boundary (PRoot, droiddeck-fastpath, droiddeck-preload) from unsafe rewrites. Use when touching third_party/proot, fastpath, preload, useLegacyPackaging, or Steam/FEX/glibc compatibility.
---

# Dextop PRoot / C Boundary Guard

## Non-negotiable rules

1. **Do not rewrite `third_party/proot`, `droiddeck-fastpath`, or `droiddeck-preload` in Rust.** They hook `ptrace`/syscalls/`LD_PRELOAD`; the bottleneck is the kernel, not the language. Rust rewrites risk allocator/TLS re-entrancy deadlocks and break upstream Steam/FEX/glibc patches.
2. **`useLegacyPackaging = true` is mandatory.** `libproot.so`, `libproot_loader.so`, `libvirgl_server.so`, `libzstd_cat.so` are ELF executables named `.so` so AGP ships them; the privileged shell executes them from `nativeLibraryDir`. Never switch to `extractNativeLibs=false`.
3. **Keep `arm64-v8a` only**; enforce 16 KB page alignment (`-Wl,-z,max-page-size=16384`) and no `libc++_shared.so` dependency for shell-loaded libs.
4. **Preserve compatibility patches** for Steam/FEX/glibc and the guest `LD_PRELOAD` chain (socket shim + /proc stand-ins + session preload).
5. **C changes must be minimal, reviewed, and CI-verified** (APK ELF alignment + no stale RUNPATH + hash manifest).

## Review checklist

- [ ] No Rust rewrite of C shims.
- [ ] `useLegacyPackaging` unchanged.
- [ ] arm64 + 16 KB alignment intact.
- [ ] Guest preload order preserved.
- [ ] CI APK verification step green.

## Regression shield

Steam/FEX/glibc breakage, `dlopen failed: library "libc++_shared.so" not found`, unaligned 16 KB libraries.
