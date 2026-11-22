# W17B (variant analysis) — Wave 17 Report

## Scope inspected
As-implemented, read-only: `wayland/deck_direct.inc`, `wayland/deck_ahb_broker.inc`,
`wayland/deck_ahb_protocol.inc`, `wayland/deck_buffer_release.inc`, `wayland/deck_slot_wait.inc`,
`wayland/gpu_present.c:116-135`, `wayland/compositor.c` (gpu_job paths 217-228, 423-503,
628-771, 1534-1602, 1735-1754, 2858-2899, 3005-3114, 3150-3228),
`input/LaptopSwipeDecoder.kt`, `Watchdog{Daemon,Client,Protocol}.kt`,
`WatchdogProtocolTest.kt`, `privilege/PublisherKeys.kt`.

State: BUG-04/05/06 primary sites are patched. Sibling instances below are still live.
Clean controls: `deck_direct.inc:73,167` and `compositor.c:3184` check `wl_event_loop_add_fd`;
`deck_buffer_release.inc:51-52` has an idle-source fallback; `LaptopSwipeDecoder.kt:31,37-45,143-164` serializes every access-order LRU touch and returns immutable snapshots — no unsynchronized-map sibling remains there.

## Findings

### P1 — Forgeable Deck auth (BUG-05 "weak environ auth" sibling, unfixed)
- `compositor.c:1735-1747` `is_deck_client()`: reads marker `DEXTOP_STEAM_INSIDE=1` from `/proc/<pid>/environ` via `wl_client_get_credentials()`; **uid/gid are ignored**. Any process in the container can `execve()` with that env var and gain Deck globals (`filter_deck_global:1749-1754`), zero-copy dmabuf v4, AHB broker, direct presentation and `deck_no_vsync` 120 Hz pacing (`toplevel_app_id:1761`). The single `read(fd,data,sizeof data)` at :1741 is also short-read/truncation prone. Called on every global bind → repeated 64 KB stack frames.
- `deck_ahb_broker.inc:22-54` (marker at :33-43): bounded reader was added (good) but the auth is still an env marker; same UID + forged env = arbitrary AHB allocation. Comment at :28 admits this is deferred.
- `deck_ahb_protocol.inc:12,53` inherits `is_deck_client()` for attach/bind.
- **Fix:** credential-bound token, not env marker. `if (uid != getuid()) return false;` then compare a per-session 192-bit secret (`PublisherKeys.issue(context,"deck")` / `SecureRandom`, constant-time `CRYPTO_memcmp`) that the Deck launcher supplies out-of-band (socket name or one-shot file), plus `/proc/<pid>/stat` starttime validation using `WatchdogDaemon.isAlive` logic. Cache the boolean per `wl_client` to avoid repeated procfs scans. Remove the NUL-delimited marker scan entirely.

### P1 — Unchecked command eventfd registration (BUG-04 sibling)
- `compositor.c:3218` `comp->command_source = wl_event_loop_add_fd(comp->loop, comp->command_fd, ...)` unchecked. On failure every JNI `post()` (`post:3005-3011`) writes to an fd nobody watches: no window attach/configure/pointer/quit ever runs, and `nativeStop` cannot terminate the compositor thread.
- **Fix:** mirror the `listen_fd`/`display` failure path at :3170-3176: `if (!comp->command_source) { close(comp->command_fd); wl_display_destroy(comp->display); DeleteGlobalRef(...); free(comp); return 0; }`.

### P2 — gpu_job fence watch: unchecked add_fd + HUP-only spin (BUG-04 siblings)
- `compositor.c:745-746` `job->source = wl_event_loop_add_fd(...)` unchecked. The job is already linked at :739 with refs held on images and dmabufs (:735-738); a NULL source means no `gpu_job_ready` completion and release latency depends entirely on the 16 ms `gpu_jobs_collect` poll fallback (:2874, :3027, :3103).
- `compositor.c:457-475` `gpu_job_ready()` gates on `(mask & WL_EVENT_READABLE)` only and re-polls with `poll(...,0)`. A HUP/ERROR-only delivery returns 0 with the source still registered → BUG-04-style level-triggered spin until the next frame tick; bmapped fence FDs are closed by the driver on GPU reset.
- **Fix:** register `WL_EVENT_READABLE | WL_EVENT_HANGUP | WL_EVENT_ERROR` at :745; check the result and LOGW/finish on NULL. In `gpu_job_ready`, handle `mask & (WL_EVENT_HANGUP|WL_EVENT_ERROR)` by calling `gpu_job_finish(job)` (it removes the source and closes the fence at :445-446), saving `comp`/`window_id` before the finish as the current code already does.

### P2 — Clipboard read source unchecked (BUG-04 + missing-source-removal sibling)
- `compositor.c:1599` `read_state->event = wl_event_loop_add_fd(comp->loop, fds[0], ...)` unchecked; `read_state` (:1594) and `fds[0]` leak permanently if NULL, and the selection owner's write is never consumed. `clipboard_readable` itself does remove the source on EOF (:1575), so the only gap is the NULL path.
- **Fix:** after the add_fd, `if (!read_state->event) { wl_data_source_send_send(comp->selection_source, chosen, fds[1]); close(fds[1]); close(fds[0]); free(read_state); return; }`.

### P2 — 64 KB stack giant (BUG-05 sibling, unfixed)
- `compositor.c:1738` `char path[64], data[65536];` inside `is_deck_client()`. On the pthread compositor thread this is the only >16 KB stack frame in the wayland code (`buffer[16384]` at :1546 is the next largest). Repeated on every global bind; a 64 KB frame plus recursive `composite()`/`gpu_collect_scene()` (depth 64) raises stack-overflow risk on memory-constrained Android 16.
- **Fix:** apply the chunked scanner already in `deck_ahb_broker.inc:37-51`: `char data[4096]` with a read loop that carries `sizeof(marker)-1` bytes of overlap, or a heap buffer capped at 64 KB; also handle short reads (single `read()` at :1741 currently false-negatives on procfs). Combine with the token fix above.

### P2 — Per-attach dlopen/dlsym/dlclose (BUG-05 sibling, unfixed)
- `deck_ahb_protocol.inc:34-38`: `dlopen("libnativewindow.so")`, `dlsym(AHardwareBuffer_getNativeHandle)`, `dlclose()` on **every** `banner_ahb_v1.attach` request. The broker was fixed with `g_ahb_once`/`g_ahb_get_handle` (`deck_ahb_broker.inc:10-20,76`) but the receiver path was not. Loader lock contention on a 120 Hz Deck producer is measurable; `dlclose` also invalidates the pointer contract noted at :37.
- **Fix:** hoist the `pthread_once_t g_ahb_once`, `g_ahb_nw`, `g_ahb_get_handle` declarations + `deck_ahb_cache_symbols()` (`deck_ahb_broker.inc:10-20`) into a shared header included before both .inc files (protocol is included at `compositor.c:2245`, broker only at `:3086`, so the static cache is not yet in scope there). Replace :34-38 with `pthread_once(&g_ahb_once, deck_ahb_cache_symbols); handle = g_ahb_get_handle ? g_ahb_get_handle(ahb) : NULL;` — no `dlclose`, no per-attach loader work.

### P1 adjacent (out-of-category, found while inspecting watchdog) — watchdog never arms
- `WatchdogDaemon.kt:55-101` `handle()` reads exactly **one** frame per accepted socket. `HELLO` sets `authenticated` and replies `HELLO_ACK`, then :51 closes the socket. `WatchdogClient.kt:45-53,56-67` keeps that socket open and sends `ARM`/`HEARTBEAT`/`ACK` on it → daemon never reads them, `armed` stays false, no 15 s rollback on session death.
- **Fix:** loop inside `handle()`: `while (true) { val message = WatchdogProtocol.read(client.inputStream); when(...) {...} }`, breaking on EOF/`IOException`; keep `authenticated` and `armSeq` state for the connection. `ACK` already `exitProcess(0)` at :95. Add a test that drives HELLO→ARM→ACK over one `LocalSocket`.

### Checked clean (no sibling)
- `LaptopSwipeDecoder.kt`: `assetLexicons`/`lexicons` guarded by `assetLock`/`lexiconLock` (:37-41,177-211); access-order `preparedTemplates` always touched under `cacheLock` (:143-145,162-164) with an immutable snapshot (:159); `learnedCounts` is `ConcurrentHashMap` (:31).
- `WatchdogDaemon.kt:62` auth uses `peerCredentials.uid` + `PublisherKeys` 192-bit random key (:34, WatchdogClient.kt:19, PublisherKeys.kt:23-27) — no forgeable-env sibling; `WatchdogProtocol.kt:83-100` validates argv allowlist.
- `gpu_present.c:123` dlsym happens once in `linux_gpu_create`, not per allocation; `linux_*_*.c` interposers all use function-local static caching.
- `deck_direct.inc:29-35,37-76` removes sources on EOF/error/fence and checks both `wl_event_loop_add_fd` results (:69-73, :166-167).

## Deliverable
Prioritized patch list above with exact file:line and concrete fix; no source edits made. Recommended M0 sequencing: (1) watchdog frame loop, (2) command_source check, (3) token auth for `is_deck_client` + AHB broker, (4) gpu_job/clipboard add_fd + HUP handling, (5) 64 KB stack → chunked read, (6) shared AHB symbol cache.

## Risks / device testing needed
- Auth change must keep the real Deck launcher working: capture its env/socket handshake on the Redmi Pad Pro before removing the marker; keep a versioned adapter if the launcher still relies on `DEXTOP_STEAM_INSIDE=1`.
- Token comparison must be constant-time; `PublisherKeys.issue()` rotates the key, so a stale compositor + new launcher combination must re-auth on reconnect.
- gpu_job HUP path needs a GPU-reset test (kill the Turnip client mid-submit) to confirm no spin and no leaked `linux_gpu_image`/dmabuf refs; check `gpu_releases` counter in the 5 s stats log (`compositor.c:3030-3057`).
- Stack reduction changes procfs read semantics; test with >64 KB environment and with a `DEXTOP_STEAM_INSIDE=1` value that spans a chunk boundary.
- Watchdog loop change requires an end-to-end 15 s detach test: kill the app while ARMed and verify the `settings`/`wm` receipts appear in `/data/local/tmp/dextop/watchdog`.
