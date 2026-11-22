# CI APK Verification — Wave 25 Report

## Scope inspected
- `.github/workflows/release-apk.yml` (publish flow, lines 138–168; metadata env at 91–128).
- `android/app/build.gradle.kts:44-52,74-100` (suffix/version wiring), `android/app/src/main/cpp/CMakeLists.txt:5-33` (shipped `libdextop_*`).
- `tool/build_16k_native_libs.sh:104-109`, `.agents/skills/dextop-proot-c-boundary/SKILL.md:11-14`, W10/W11/W12/W20/W24 wave reports (16 KB + no-RUNPATH/no-libc++_shared gates).

## Findings
- P2 — Release job has **no post-build binary gate**: a mis-aligned/foreign-ABI/mis-suffixed APK is uploaded and published silently. Fix: insert the verification step below between "Build arm64 debug APK" and "Upload APK as artifact" (all checks are read-only, fail fast, no token use).
- P3 — Tool discovery must not assume a pinned NDK path: resolve `aapt2` from the highest `$SDK/build-tools/*`, `llvm-readelf` from `$SDK/ndk/*` (Flutter pins NDK via `flutter.ndkVersion`). `awk` must avoid `strtonum` (mawk on ubuntu-latest) — parse `0x…`/`2**N` manually.

## Deliverable — exact step to insert (YAML + shell)

```yaml
      - name: Verify release APK (arm64 / 16 KB / badging)
        run: |
          set -euo pipefail
          SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
          [ -n "$SDK" ] || { echo "::error::Android SDK path not set"; exit 1; }
          BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
          AAPT2="$BT/aapt2"
          READELF=$(find "$SDK/ndk" -type f -name llvm-readelf 2>/dev/null | sort -V | tail -1)
          [ -x "$AAPT2" ] || { echo "::error::aapt2 not found under $SDK/build-tools"; exit 1; }
          [ -x "$READELF" ] || { echo "::error::llvm-readelf not found under $SDK/ndk"; exit 1; }

          tmp=$(mktemp -d)
          trap 'rm -rf "$tmp"' EXIT
          unzip -q "$APK_PATH" 'lib/*' -d "$tmp"

          # arm64 only
          abis=$(unzip -Z1 "$APK_PATH" 'lib/*/*.so' | awk -F/ '{print $2}' | sort -u)
          [ "$abis" = "arm64-v8a" ] || { echo "::error::unexpected ABI dirs: ${abis:-none}"; exit 1; }

          # 16 KB PT_LOAD alignment + GNU_RELRO end; mawk-safe (no strtonum).
          cat > "$tmp/elf.awk" <<'AWK'
          function dec(s,   i,c,d,v,a) {
            if (s ~ /^0[xX]/) { s=substr(s,3); v=0; for(i=1;i<=length(s);i++){c=tolower(substr(s,i,1)); d=(c~/[0-9]/)?c+0:(index("abcdef",c)+9); v=v*16+d} return v }
            if (s ~ /^[0-9]+\*\*[0-9]+$/) { split(s,a,"**"); return 2^a[2] }
            return s+0
          }
          $1=="LOAD" && dec($NF)<16384 { print "::error::" f ": PT_LOAD align " $NF " < 16384"; bad=1 }
          $1=="GNU_RELRO" && ((dec($3)+dec($6))%16384)!=0 { print "::error::" f ": GNU_RELRO end not 16 KB aligned"; bad=1 }
          END { exit bad }
          AWK

          dextop=0; input=0
          for f in "$tmp"/lib/arm64-v8a/*.so; do
            [ -e "$f" ] || { echo "::error::no arm64-v8a .so packaged"; exit 1; }
            n=${f##*/}
            "$READELF" -lW "$f" | awk -v f="$n" -f "$tmp/elf.awk" || exit 1
            if "$READELF" -dW "$f" | grep -qE '\(NEEDED\).*libc\+\+_shared\.so'; then
              echo "::error::$n: DT_NEEDED libc++_shared.so"; exit 1
            fi
            if "$READELF" -dW "$f" | grep -qE '\((RUNPATH|RPATH)\)'; then
              echo "::error::$n: stale RUNPATH/RPATH"; exit 1
            fi
            case "$n" in
              libdextop_input.so) input=1; dextop=$((dextop+1));;
              libdextop_*) dextop=$((dextop+1));;
            esac
          done
          [ "$input" -eq 1 ] && [ "$dextop" -ge 2 ] || {
            echo "::error::libdextop_* native libs missing (need libdextop_input.so + >=2 total)"; exit 1
          }

          # Badging: exact suffixed applicationId / versionCode / versionName.
          want="package: name='moe.n4tsu.dextop.${DEXTOP_BUILD_SUFFIX}' versionCode='${DEXTOP_VERSION_CODE}' versionName='${DEXTOP_VERSION_NAME}'"
          badging=$("$AAPT2" dump badging "$APK_PATH")
          printf '%s\n' "$badging" | grep -Fq "$want" || {
            echo "::error::aapt2 badging mismatch; expected: $want"
            printf '%s\n' "$badging" | head -n 1
            exit 1
          }
          echo "APK verified: $want"
```

Insertion point (after line 147, before line 149 of `release-apk.yml`). Env values `APK_PATH`, `DEXTOP_BUILD_SUFFIX`, `DEXTOP_VERSION_NAME`, `DEXTOP_VERSION_CODE` are already exported via `$GITHUB_ENV` at lines 121–127, so no `env:` block is needed. Publish flow (artifact + `gh release upload/edit`) is unchanged; a failed gate simply prevents publication.

## Risks / device testing needed
- SDK image must expose `build-tools/*/aapt2` and `ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf`; add an explicit `flutter config --android-sdk` / setup step only if runner env changes.
- Strict RELRO check follows Android 16 KB guidance; if a prebuilt legitimately lacks `GNU_RELRO`, it is skipped (rule only fires when the segment exists).
- `versionName` containing a single quote would break the `grep -F` literal; current tags are numeric.
- Device confirmation still required: `adb shell getconf PAGE_SIZE` = 16384 and `System.loadLibrary` smoke test on the Redmi Pad Pro 5G (HyperOS 3, API 36). Assets/*.so modules are covered by `tool/build_16k_native_libs.sh:104-109`, not by this APK-level gate.
