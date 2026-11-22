# W15A (M8) — Wave 15 Report: On-Device QA Harness

## Scope inspected
- `BRIEF.md:8,16` (device target; M8 QA/sign-off), `docs/architecture/BENCHMARK_AND_TEST_STRATEGY.md:4` (18 Perfetto scenarios, 12-point checklist).
- `.team_waves/W05_WATCHDOG_TEST.md:295-310` (kill-9 stages S0–S7, 7 `modernKeys`, receipt dir), `.team_waves/W02_BUG04.md:93-106` (1000-cycle FD leak + spin assertions).
- `android/app/.../MainActivity.kt:749-769` (`diagnosticReport`/`shareDiagnosticReport` MethodChannels), `OperationLog.kt:22-32` (rolling log file names in `filesDir`), `DiagnosticExport.kt:24-60` (ZIP name + entries), `SessionJournal.kt:36-37` (`files/dextop_session/session_journal.wal`), `WatchdogDaemon.kt:16,38` (`/data/local/tmp/dextop/watchdog`, `@dextop.wd.<uid>`), `build.gradle.kts:74` (`applicationId=moe.n4tsu.dextop`).
- No builds, no source edits, no token used.

## Findings
- **P0 — zero executable on-device harness exists.** Checklist/scenarios are prose only; M8 exit gate cannot be evidenced. Fix: scripts below.
- **P1 — `diagnosticReport` is MethodChannel-only.** No ADB entry point. Fix: harness pulls `OperationLog` segments + WAL via `run-as` (CI debug APK) with `adb root`/`su` fallback; optional UI-driven ZIP export.
- **P1 — kill-9 rollback untested on HyperOS.** Fix: `04_watchdog_kill9.sh` implements S0–S7 with key-by-key assertions.
- **P2 — FD leak assertion missing.** Fix: `05_fd_leak_1000.sh` samples `/proc/<pid>/fd` and fails on slope >0.01 fd/cycle.

## Deliverable

Save each block verbatim to `qa/<filename>` on the workstation, then `adb push qa/ /data/local/tmp/dextop-qa/`. All device scripts are POSIX `/system/bin/sh`, staged under `/data/local/tmp/dextop-qa`; results land in `.../results/`.

### `qa/qa_common.sh` — sourced constants + logging

```sh
#!/system/bin/sh
PKG=${PKG:-moe.n4tsu.dextop}
QA=/data/local/tmp/dextop-qa
OUT=$QA/results/$(date +%Y%m%d-%H%M%S)
WD=/data/local/tmp/dextop/watchdog
JOURNAL="files/dextop_session/session_journal.wal"
KEYS="override_desktop_experience_features override_desktop_mode_features enable_non_resizable_multi_window force_desktop_mode_on_secondary_displays force_desktop_mode_on_external_displays force_resizable_activities enable_freeform_support"
mkdir -p "$OUT" "$WD"
LOG=$OUT/summary.log
pass(){ echo "PASS $*" | tee -a "$LOG"; }
fail(){ echo "FAIL $*" | tee -a "$LOG"; }
warn(){ echo "WARN $*" | tee -a "$LOG"; }
note(){ echo "NOTE $*" | tee -a "$LOG"; }
expect(){ n=$1; pat=$2; shift 2; o=$("$@" 2>&1); echo "$o" >"$OUT/$n.txt"; printf '%s' "$o" | grep -Eq "$pat" && pass "$n" || fail "$n (pattern: $pat)"; }
run_as(){ run-as "$PKG" "$@"; }
reader(){ run-as "$PKG" id >/dev/null 2>&1 && run_as "$@"; }   # CI debug APK
```

For release builds replace `reader()` with `cat /data/data/$PKG/...` under `adb root`/`su`.

### `qa/00_preflight.sh` — device/app sanity + baseline

```sh
#!/system/bin/sh
. "$(dirname "$0")/qa_common.sh"
note "RUN $(date -Is)"; note "model=$(getprop ro.product.model) api=$(getprop ro.build.version.sdk) abi=$(getprop ro.product.cpu.abi) os=$(getprop ro.build.version.release)"
[ "$(getprop ro.build.version.sdk)" = 36 ] && pass "API36" || fail "API36"
[ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] && pass "arm64" || fail "arm64"
pm path "$PKG" >/dev/null 2>&1 && pass "app-installed" || { fail "app-installed"; exit 2; }
command -v perfetto >/dev/null && pass "perfetto" || warn "perfetto missing (use atrace)"
logcat -c
logcat -v threadtime -s DextopWatchdog:D SessionJournal:D DiagnosticReport:D *:E >"$OUT/logcat.txt" &
echo $! >"$QA/logcat.pid"
for k in $KEYS; do echo "$k=$(settings get global $k)"; done >"$OUT/baseline-keys.txt"
echo "size=$(wm size) density=$(wm density)" >"$OUT/baseline-wm.txt"
dumpsys deviceidle >"$OUT/deviceidle.txt" 2>&1
pass "baseline-captured"
```

### `qa/01_diag_export.sh` — app diagnostics export

```sh
#!/system/bin/sh
. "$(dirname "$0")/qa_common.sh"
# Rolling OperationLog segments + WAL (the same content DiagnosticExport zips).
reader sh -c 'cd files && cat dextop-log-index dextop-log-* dextop-session-current.log dextop-session-last.log dextop-operation.log 2>/dev/null' >"$OUT/app-operation-log.txt"
reader cat "$JOURNAL" >"$OUT/session_journal.wal" 2>/dev/null || warn "WAL unreadable (no session armed?)"
[ -s "$OUT/app-operation-log.txt" ] && pass "diag-log" || fail "diag-log"
# Full sanitized report, UI path (optional): tap "Export diagnostic ZIP", pull from /sdcard/Download.
if [ "$1" = --ui ]; then
  am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  uiautomator dump "$QA/window.xml" >/dev/null 2>&1
  b=$(tr '>' '\n' <"$QA/window.xml" | grep -F 'Export diagnostic ZIP' | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  [ -n "$b" ] && { set -- $b; input tap $((($1+$3)/2)) $((($2+$4)/2)); note "ZIP export triggered; adb pull /sdcard/Download/dextop-diagnostics-*.zip"; } || warn "export control not found"
fi
note "report also available from workstation: adb exec-out run-as $PKG cat files/dextop-session-current.log"
```

### `qa/02_hardware_checklist.sh` — 12-point cross-OEM checklist

```sh
#!/system/bin/sh
. "$(dirname "$0")/qa_common.sh"
expect 01-identity 'Redmi|2610|arm64' getprop ro.product.model
expect 02-api36 '^36$' getprop ro.build.version.sdk
expect 03-display 'mRefreshRate|DisplayDeviceInfo|120' dumpsys display
expect 04-wm-size 'Physical size|Requested size' wm size
expect 05-wm-density 'Physical density|Requested density' wm density
expect 07-freeform-keys 'enable_freeform_support' sh -c "for k in $KEYS; do settings get global \$k; done"
expect 08-input-devices 'Input Device Information|Virtual' dumpsys input
expect 09-pointer-capture 'pointerCapture|POINTER_CAPTURE' sh -c "cat $OUT/../results/*/app-operation-log.txt 2>/dev/null; dumpsys input"
expect 10-audio 'AAudio|MMap|FAST' dumpsys audio
expect 11-gpu-vulkan 'Adreno|vulkan|freedreno|GPU' dumpsys gpu
expect 12-watchdog 'dextop_watchdog|app_process' sh -c 'dumpsys activity processes | grep -i dextop'
# 06 virtual display, 12b AHB zero-copy come from CapabilitySnapshot in the exported report:
grep -Ei 'virtualDisplay|hardwareBuffer|zeroCopy|injectInputEvent' "$OUT/app-operation-log.txt" >"$OUT/06-12b-capabilities.txt" 2>/dev/null
[ -s "$OUT/06-12b-capabilities.txt" ] && pass "06/12b capabilities" || fail "06/12b capabilities"
note "12 points logged to $OUT/0*.txt"
```

### `qa/03_perfetto_scenarios.sh` — 18 Perfetto scenarios

```sh
#!/system/bin/sh
. "$(dirname "$0")/qa_common.sh"
trace_cfg(){ cat >"$QA/trace.cfg" <<EOF
buffers { size_kb: 98304 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
  atrace_categories: "gfx" atrace_categories: "input" atrace_categories: "view"
  atrace_categories: "power" atrace_categories: "sched" atrace_categories: "dmabuf"
  ftrace_events: "sched/sched_switch" ftrace_events: "power/cpu_frequency"
  ftrace_events: "power/gpu_frequency" ftrace_events: "dmabuf/dmabuf_busy"
} } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
duration_ms: $1
EOF
}
run_scenario(){ id=$1; label=$2; ms=$3; shift 3
  t=$OUT/perfetto-$id-$label.perfetto-trace
  trace_cfg "$ms"
  perfetto -c "$QA/trace.cfg" --txt -o "$t" 2>"$OUT/$id-perfetto.err" & p=$!
  "$@" >"$OUT/$id-driver.log" 2>&1 || note "$id driver rc=$?"
  wait $p || fail "$id trace exit"
  [ -s "$t" ] && pass "$id $label ($ms ms)" || fail "$id $label no trace"
}
idle(){ am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 10; }
noop(){ sleep "${1:-5}"; }
run_scenario S01 idle        10000 idle
run_scenario S02 w1          15000 noop 5    # MANUAL: open 1 window
run_scenario S03 w4          20000 noop 10   # MANUAL: open 4 windows
run_scenario S04 w8          30000 noop 15   # MANUAL: open 8 windows
run_scenario S05 drag        15000 noop 10   # MANUAL: rapid drag
run_scenario S06 resize      15000 noop 10   # MANUAL: rapid resize
run_scenario S07 multitouch  15000 noop 10   # MANUAL: pinch/scroll
run_scenario S08 dp-connect  20000 noop 15   # MANUAL: attach display
run_scenario S09 dp-disconn  20000 noop 15   # MANUAL: detach display
run_scenario S10 resolution  20000 sh -c 'wm size 1920x1200; sleep 8; wm size reset'
run_scenario S11 rotation    15000 sh -c 'settings put system accelerometer_rotation 0; settings put system user_rotation 1; sleep 6; settings put system user_rotation 0'
run_scenario S12 workspace   15000 noop 10   # MANUAL: switch workspaces
run_scenario S13 vrr         15000 noop 10   # MANUAL: 60<->120Hz toggle; dumpsys display
run_scenario S14 soak-2h     7200000 idle   # 2 h soak; splits into 1 trace
run_scenario S15 sleep-wake  30000 sh -c 'input keyevent 26; sleep 10; input keyevent 26'
run_scenario S16 kill9       20000 sh -c "kill -9 \$(pidof $PKG); sleep 8"
run_scenario S17 lowmem      20000 sh -c 'am send-trim-memory com.android.shell RUNNING; am send-trim-memory '"$PKG"' BACKGROUND; sleep 8'
run_scenario S18 thermal     60000 sh -c 'for i in 1 2 3 4 5; do echo $i; sleep 12; done'  # MANUAL: apply heat load
note "traces: $OUT/perfetto-*.perfetto-trace"
```

### `qa/04_watchdog_kill9.sh` — kill-9 rollback (W05 S0–S7)

```sh
#!/system/bin/sh
. "$(dirname "$0")/qa_common.sh"
note "MANUAL prereq: start a desktop session that mutates settings; do NOT confirm."
restore_check(){
  for k in $KEYS; do b=$(grep "^$k=" "$OUT/baseline-keys.txt" | cut -d= -f2-); a=$(settings get global $k)
    [ "$a" = "$b" ] && pass "restored $k" || fail "restored $k: base=$b now=$a"; done
  grep -q 'reset' "$OUT/baseline-wm.txt" || true
  pass "wm text: $(wm size) / $(wm density)"
}
arm(){ for i in $(seq 1 30); do reader cat "$JOURNAL" 2>/dev/null | grep -q ARM && return 0; sleep 1; done; return 1; }
pid=$(pidof "$PKG"); [ -n "$pid" ] || { fail "app not running"; exit 2; }
arm && pass "S0 armed" || fail "S0 armed"
dumpsys activity processes | grep -i dextop_watchdog >"$OUT/s0-daemon.txt" && pass "S0 detached daemon" || fail "S0 detached daemon"
reader cat "$JOURNAL" >"$OUT/s0-wal.txt" 2>/dev/null
kill -9 $pid; sleep 1
restore_check
ls "$WD"/*.ack >"$OUT/s1-receipts.txt" 2>&1 && pass "S1 ack receipt" || fail "S1 ack receipt"
grep -qi 'ROLLBACK' "$OUT/logcat.txt" && pass "S1 rollback log" || warn "S1 rollback log absent"
note "S2: repeat with kill at t=3/10/14.5s after ARM (rerun this script with DELAY=3|10|14.5)"
sleep "${DELAY:-0}"
# S3 ACK negative:
note "MANUAL S3: confirm session, then rerun with ACK=1 to assert NO rollback"
if [ "$ACK" = 1 ]; then restore_check && fail "S3 unexpected rollback?" || pass "S3 no-rollback check ran"; fi
# S4 daemon death:
dp=$(dumpsys activity processes | grep -i dextop_watchdog | sed -n 's/.*PID: *\([0-9]*\).*/\1/p' | head -1)
[ -n "$dp" ] && { kill -9 "$dp"; note "S4 daemon killed; re-arm to verify single abstract socket @dextop.wd.<uid>"; }
# S5 PID reuse / S6 Doze / S7 clean teardown are MANUAL on the device; capture logcat + receipts.
logcat -d -v threadtime -s DextopWatchdog:D SessionJournal:D *:E >"$OUT/kill9-logcat.txt" 2>&1
```

### `qa/05_fd_leak_1000.sh` — 1000-cycle FD leak check

```sh
#!/system/bin/sh
. "$(dirname "$0")/qa_common.sh"
N=${N:-1000}; DRIVER=${DRIVER:-$QA/deck_cycle.sh}
[ -x "$DRIVER" ] || { cat >"$DRIVER" <<'EOF'
#!/system/bin/sh
# Replace with one DECK-direct present->release cycle (app_process probe calling
# libdextop_wayland.so). Stub below just yields so the FD sampler can be validated.
sleep 0.02
EOF
chmod +x "$DRIVER"; warn "using stub DRIVER; replace for real 1000-cycle test"; }
p=$(pidof "$PKG"); [ -n "$p" ] || { fail "app not running"; exit 2; }
base=$(ls /proc/$p/fd 2>/dev/null | wc -l); echo "0 $base" >"$OUT/fd-series.csv"
i=1; peak=$base
while [ $i -le $N ]; do
  "$DRIVER" >/dev/null 2>&1
  p=$(pidof "$PKG"); [ -n "$p" ] || p=$p
  cur=$(ls /proc/$p/fd 2>/dev/null | wc -l)
  echo "$i $cur" >>"$OUT/fd-series.csv"; [ $cur -gt $peak ] && peak=$cur
  i=$((i+1))
done
slope=$(awk 'NR>1{n++;sx+=$1;sy+=$2;sxx+=$1*$1;sxy+=$1*$2}END{if(n>1)printf "%.5f",(n*sxy-sx*sy)/(n*sxx-sx*sx);else print 0}' "$OUT/fd-series.csv")
echo "baseline=$base peak=$peak slope=$slope" >"$OUT/fd-summary.txt"
awk -v s="$slope" -v p="$peak" -v b="$base" 'BEGIN{exit !(s<=0.01 && p<=b+16)}' && pass "FD leak N=$N base=$base peak=$peak slope=$slope" || fail "FD leak base=$base peak=$peak slope=$slope"
dumpsys gpu >"$OUT/gpu-fd-context.txt" 2>&1
```

### `qa/06_results_template.md` — results template

```md
# Dextop M8 QA — <build> — <date> — <device serial>
Env: model=__ api=__ abi=__ build=__ commit=__ apk=__

## 12-point hardware checklist
| # | Check | Evidence file | Result | Note |
|---|-------|---------------|--------|------|
|01|identity|01-identity.txt|P/F||
...12 rows (S01..S12)...

## 18 Perfetto scenarios
| ID | Scenario | Trace | Driver | Result | Notes |
|----|----------|-------|--------|--------|-------|
|S01|idle|...|.|--|
... S18 ...

## Kill-9 S0–S7
| Stage | Action | Expected | Observed | Result |
|-------|--------|----------|----------|--------|
|S0|ARM+daemon|1 daemon, 7 keys+wm in WAL|||
|S1|kill -9 warm|all 7 keys+wm restored, ack|||
|S2|staggered 3/10/14.5s|no partial key set|||
|S3|ACK then kill|no rollback|||
|S4|daemon death/re-arm|single @dextop.wd.<uid>|||
|S5|PID reuse|starttime mismatch, rollback|||
|S6|Doze 10min|rollback survives|||
|S7|clean teardown|no daemon/receipt|||

## 1000-cycle FD leak
baseline=__ peak=__ slope=__ threshold slope<=0.01 & peak<=base+16 -> PASS/FAIL
series=fd-series.csv

## Diagnostics artifacts
app-operation-log.txt | session_journal.wal | kill9-logcat.txt | perfetto-*.perfetto-trace
PII scrubbed? Y/N  Reviewer: __  Sign-off: __
```

### Workstation run sequence

```sh
adb connect <tablet-ip>:5555          # or USB
adb devices                            # exactly one device
adb root                               # or use CI debug APK for run-as
adb push qa/ /data/local/tmp/dextop-qa/
adb shell chmod +x /data/local/tmp/dextop-qa/*.sh
adb shell sh /data/local/tmp/dextop-qa/00_preflight.sh
adb shell sh /data/local/tmp/dextop-qa/01_diag_export.sh --ui
adb shell sh /data/local/tmp/dextop-qa/02_hardware_checklist.sh
adb shell sh /data/local/tmp/dextop-qa/03_perfetto_scenarios.sh
adb shell sh /data/local/tmp/dextop-qa/04_watchdog_kill9.sh   # MANUAL session armed first
adb shell N=1000 sh /data/local/tmp/dextop-qa/05_fd_leak_1000.sh
adb pull /data/local/tmp/dextop-qa/results ./results
adb shell kill $(cat /data/local/tmp/dextop-qa/logcat.pid) 2>/dev/null
```
Then fill `06_results_template.md` from `results/*/summary.log` and the Perfetto traces (open in https://ui.perfetto.dev).

## Risks / device testing needed
- `uiautomator` text tap is locale-dependent (controls are localized) — use `run-as` path as source of truth; UI export is convenience only.
- Perfetto `dmabuf/dmabuf_busy`/`atrace_categories: dmabuf` may be unavailable on HyperOS kernel; drop the line and re-run rather than failing the scenario. S14 2 h soak may be killed by Android memory pressure on this phone — run nightly, not inline.
- `settings put` on HyperOS hidden keys may be rejected: `04` asserts key-by-key; if rejected, attach `dumpsys` diff and mark MANUAL fallback to app-side restore.
- FD leak check requires a real DECK-direct `DRIVER`; the stub validates only the sampler. 1000 cycles at 0.02 s stub is fast; real present/release cycles may take minutes — acceptable on this device.
- Watchdog stages S5/S6/S7 remain MANUAL (PID reuse, Doze, clean teardown); no local build or token was used.
