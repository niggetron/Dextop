# W14A (M7) — Portal ↔ DocumentsUI & wl_data_device ↔ ClipboardManager Bridge Report

## Scope inspected

- `linux/LinuxFolders.kt` (tree picker + persistable grants `:95-135`, RPC `serve/handle/target/open` `:188-297`, `syncAll/clear` `:152-186`, placeholder `:417-439`, `LinuxFolderPickerActivity :443-464`).
- `linux/LinuxChannel.kt` (`pickDocument :240-244`, `addFolder/removeFolder :85-94`, `clipboardSync/setClipboardSync :477-481`).
- `linux/LinuxDeviceBridge.kt` (auth/accept `:55-124`, manifest `state() :134-171`, dispatch `serve() :342-391`).
- `linux/LinuxContainerService.kt` (shell listener `:54-75`, clipboard `:971-983`, session hooks `:640,774`).
- `cpp/linux_android_folders.c` (`relay()` SCM_RIGHTS `:65-112`, `inside()/under_root() :141-169`, `dextop_folder_open :249-281`).
- `cpp/wayland/compositor.c` (clipboard `:1450-1660`, `emit :324-330`, `CMD_CLIPBOARD :2972`, JNI `:3336-3343`).
- `linux/WaylandCompositor.kt :40-56,144-145`, `linux/LinuxAppSession.kt :157-160,287-295`, `linux/X11Clipboard.kt`, `linux/LinuxClipboard.kt`, `linux/LinuxSharing.kt :8-39`, AIDL `ILinuxContainerService.aidl :74,171-186`, `ILinuxContainerCallback.aidl :16-17`, `LinuxDesktopSessions.kt :29-33` (ships `xdg-desktop-portal-kde`).
- No builds, no source edits, no token. All skeletons CI-only.

## Findings

- **P0 — No FileChooser portal backend.** `xdg-desktop-portal-kde` is installed (`LinuxDesktopSessions.kt:33`) but no Dextop backend exists; GTK/Qt `GtkFileChooserNative`/`QFileDialog` OpenFile/SaveFile calls fail or fall back to a path picker that cannot see scoped `content://` storage. Users hit this in Android mode (no whole-storage), the exact mode `/media/android` was built for. **Fix:** scoped portal backend (§Deliverable A) reusing the *already authenticated* device bridge, never a new listener.
- **P0 — Clipboard is text-only.** `compositor.c:1462,1583-1601` offers only `TEXT_MIMES`, caps 1 MiB, ignores `image/*`; `LinuxClipboard.kt:28-49` handles `ClipData` text only; `X11Clipboard.kt:119-133` serves 4 text atoms. Copying an image in a guest app is silently lost. **Fix:** image MIMEs + JNI byte-array callback + `ClipData.newUri`+grant (§B).
- **P1 — `syncAll()` would delete portal results.** `LinuxFolders.kt:182` removes every root child not in `folders`; adding results under `/media/android/.portals` needs an explicit preserve rule or results vanish on session sync. Also `clear(scope)` must release portal grants.
- **P1 — Binder 1 MB limit.** Passing an 8 MiB image as `byte[]` over AIDL fails (`TRANSACTION_FAILED`). Must use `ParcelFileDescriptor`/memfd for image AIDL; JNI byte[] only for compositor→app.
- **P1 — Background clipboard reads.** API 36 denies `ClipboardManager.primaryClip` without focus; existing `LinuxClipboard.toLinux` is focus-gated (`:36-39`) and image sync must reuse the same gate (`LinuxWindowActivity.kt:781-806`).
- **P2 — JSON/base64 is the wrong image carrier.** `emit()` uses `NewStringUTF` (`:324-330`); a 1 MiB PNG becomes a 1.4 MB JNI string. Add a typed callback instead of extending `{"type":"clipboard"}`.
- **P3 — Schemas unverified locally** (brief rule 1); CI-only compile.

## Deliverable

### A. Scoped xdg-desktop-portal ↔ DocumentsUI bridge

**Topology.** Guest GTK/Qt → D-Bus `org.freedesktop.portal.FileChooser` → `xdg-desktop-portal` → `dextop.portal` impl (`dextop-portal`, C) → **existing** abstract `dextop-devices-<32hex>` bridge prefix `P ` → app `LinuxDeviceBridge.serve` → `LinuxPortal` → `ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT_TREE` (`LinuxPortalActivity`) → persistable grant + placeholder under `/media/android/.portals/<handle>/`. Result fd is reused through the existing `F O` path; no new URI is ever minted by the guest.

**Wire protocol** (same auth as `F `/`C `: app UID, or shell UID + `ownsProcess(pid)`; newline-terminated request):

| request | answer |
|---|---|
| `P O <token> <b64 mime csv> <b64 name>` OpenFile | 1 status byte (0/errno) |
| `P S <token> <b64 mime> <b64 name>` SaveFile | 1 status byte |
| `P D <token>` SelectFolder | 1 status byte |
| `P Q <token> <0|1>` poll (0=peek,1=consume) | status byte + `u32 BE len` + UTF-8 JSON; on `consume=1`, one extra 1-byte SCM_RIGHTS frame carrying one fd per file |
| `P X <token>` cancel/revoke | 1 status byte |

JSON: `{"status":"pending"}` | `{"status":"cancelled"}` | `{"status":"ok","handle":"<32hex>","files":[{"name":"x.png","mime":"image/png","writable":false,"path":"/media/android/.portals/<handle>/x.png","size":1234,"mtime":1710000000}]}`. Token = 128-bit random hex; ≤32 pending; 5-min expiry.

**Kotlin skeleton — new `linux/LinuxPortal.kt`:**

```kotlin
internal object LinuxPortal {
  private const val TAG = "DextopLinuxPortal"
  const val PREFIX = ".portals"
  private const val TOKEN = Regex("[a-f0-9]{32}")
  private const val MAX_MIME = 512; private const val MAX_NAME = 255
  private const val EINVAL=22; private const val ENOENT=2; private const val EBUSY=16
  private const val EACCES=13; private const val EIO=5

  private class Pending(val token:String, val container:String, val op:Char,
      val mime:String, val name:String, val at:Long = System.currentTimeMillis()) {
    @Volatile var result: Result? = null
    @Volatile var consumed = false
  }
  private class Result(val ok:Boolean, val handle:String="", val uri:Uri?=null,
      val name:String="", val size:Long=0, val mime:String="")
  private val pending = ConcurrentHashMap<String, Pending>()

  fun prefs(scope: Context) = scope.getSharedPreferences(
      "linux-portal-${(scope as LinuxFolders.Scope).mode}-${scope.id}", Context.MODE_PRIVATE)
  fun grants(scope: Context): Map<String,String> = prefs(scope).all.mapNotNull {
      (it.value as? String)?.let { v -> it.key to v } }.toMap()

  fun serve(app: Context, scope: Context, socket: LocalSocket, request: String) {
    val f = request.split(' ')
    val status = try {
      when (f.getOrNull(1)) {
        "O", "S", "D" -> {
          val token = f.getOrNull(2)?.takeIf { TOKEN.matches(it) } ?: throw Err(EINVAL)
          val op = f[1].single()
          val mime = decode(f.getOrNull(3)).take(MAX_MIME)
          val name = safeName(decode(f.getOrNull(4)))
          check(pending.size < 32) { Err(EBUSY) }
          pending[token] = Pending(token, (scope as LinuxFolders.Scope).id, op, mime, name)
          launch(app, scope, pending[token]!!); 0
        }
        "Q" -> { answer(scope, socket, f); -1 }        // answered inline
        "X" -> { pending.remove(f.getOrNull(2))?.result = Result(false); 0 }
        else -> EINVAL
      }
    } catch (e: Err) { e.code } catch (_: SecurityException) { EACCES }
      catch (e: Exception) { Log.w(TAG, "portal request failed", e); EIO }
    if (status >= 0) socket.outputStream.run { write(status); flush() }
  }

  private fun launch(app: Context, scope: Context, p: Pending) {
    Handler(Looper.getMainLooper()).post {
      val intent = when (p.op) {
        'D' -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        'S' -> Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                 .setType(p.mime.ifEmpty { "*/*" }).putExtra(Intent.EXTRA_TITLE, p.name)
        else -> Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                 .setType(p.mime.ifEmpty { "*/*" })
      }
      LinuxPortalActivity.request(app, p.token, intent) { uri ->
        Thread { runCatching { finish(app, scope, p, uri) }
          .onFailure { Log.w(TAG, "portal result failed", it); p.result = Result(false) } }.start()
      }
    }
  }

  private fun finish(app: Context, scope: Context, p: Pending, uri: Uri?) {
    if (uri == null) { p.result = Result(false); return }
    // Only a URI returned by DocumentsUI is ever granted; guest strings are never opened.
    val flags = if (p.op == 'S') Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION
    app.contentResolver.takePersistableUriPermission(uri, flags)
    val info = LinuxFolders.documentInfo(scope, uri)   // expose existing info()/Entry internally
    val handle = UUID.randomUUID().toString().replace("-", "")
    val dir = File(LinuxFolders.root(scope), "$PREFIX/$handle"); dir.mkdirs()
    LinuxFolders.placeholder(File(dir, info.name), info.size, info.modified) // expose internally
    prefs(scope).edit().putString(handle, uri.toString()).commit()
    p.result = Result(true, handle, uri, info.name, info.size, info.mime)
  }

  private fun answer(scope: Context, socket: LocalSocket, f: List<String>) {
    val p = pending[f.getOrNull(2)] ?: throw Err(ENOENT)
    check(p.container == (scope as LinuxFolders.Scope).id)   // token bound to requesting container
    val r = p.result
    val json = when {
      r == null -> """{"status":"pending"}"""
      !r.ok -> """{"status":"cancelled"}"""
      else -> """{"status":"ok","handle":"${r.handle}","files":[{
        "name":"${jsonQ(r.name)}","mime":"${r.mime}","writable":${p.op == 'S'},
        "path":"/media/android/$PREFIX/${r.handle}/${jsonQ(r.name)}",
        "size":${r.size},"mtime":0}]}"""
    }.replace(Regex("\\s+"), "")
    val bytes = json.toByteArray(Charsets.UTF_8); val out = socket.outputStream
    out.write(0); out.write(ByteBuffer.allocate(4).putInt(bytes.size).array()); out.write(bytes)
    if (f.getOrNull(3) == "1" && r?.uri != null && !p.consumed) {
      val pfd = scope.contentResolver.openFileDescriptor(r.uri,
          if (p.op == 'S') "rw" else "r") ?: throw Err(EIO)
      pfd.use {
        socket.setFileDescriptorsForSend(arrayOf(it.fileDescriptor))
        out.write(0); out.flush()                       // SCM_RIGHTS attached to this byte
        socket.setFileDescriptorsForSend(null)
      }
      p.consumed = true
    } else out.flush()
  }

  fun clear(scope: Context) { // called from LinuxFolders.clear(scope)
    pending.keys.removeAll { true }
    prefs(scope).all.keys.forEach { handle ->
      runCatching { File(LinuxFolders.root(scope), "$PREFIX/$handle").deleteRecursively() } }
    prefs(scope).edit().clear().commit()
  }
  private class Err(val code:Int): Exception()
  private fun decode(s: String?) = runCatching {
      String(Base64.decode(s.orEmpty(), Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
  private fun safeName(s: String) = s.trim().replace('/', '_').replace('\u0000', '_').take(MAX_NAME)
      .ifEmpty { "document" }
  private fun jsonQ(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
  class LinuxPortalActivity : Activity() { /* same request/waiting map as LinuxDocumentActivity */ }
}
```

**`LinuxFolders.kt` deltas:** make `Scope` `internal class Scope(context, val id, val mode)`; expose `documentInfo()`/`placeholder()` as `internal`; in `target()` add before the `folders()` lookup:

```kotlin
if (parts[0] == LinuxPortal.PREFIX && parts.size >= 3) {
  val uri = Uri.parse(LinuxPortal.grants(context)[parts[1]] ?: throw Errno(EACCES))
  return Target("${LinuxPortal.PREFIX}/${parts[1]}", uri, parts.drop(2).joinToString("/"))
}
```
(so `shadow()` = `root/.portals/<handle>/<name>` and `F O` reuses `resolve()`/`openFileDescriptor`); in `syncAll()` preserve `".portals"` (`if (it !in folders && it != LinuxPortal.PREFIX) deleteTree(...)`); in `clear(scope)` call `LinuxPortal.clear(scope)`; remove `".portals"` from generic `clear(context)` cleanup too.

**`LinuxDeviceBridge.kt` delta** in `serve()` after the `F ` branch:

```kotlin
if (request.startsWith("P ")) {
  val container = LinuxContainerClient.service()?.processContainer(socket.peerCredentials.pid)
      ?.takeIf(String::isNotEmpty) ?: return
  LinuxPortal.serve(app, LinuxFolders.scope(app, container), socket, request); return
}
```
`state()`: append `portal\n` when enabled. Bump `LinuxContainerService.PROTOCOL_VERSION` 46 → 47; add `xdg-desktop-portal-dextop` to `LinuxDesktopSessions.kt:33` packages.

**Guest C skeleton — new `cpp/portal/dextop_portal.c`** (D-Bus already present in the container via KDE portal):

```c
#include <dbus/dbus.h>
#define MAX_JSON (1u<<20)
static char sock[108];
/* framed reply: 1 status byte, u32 BE len, JSON, optional SCM_RIGHTS byte */
static int relay(const char *line, char **json, size_t *len, int *fd); /* same syscalls as folders.c relay */
static int relay_frame(const char *line, char **json, size_t *len, int *fd) {
  if (relay(line, json, len, fd) != 0) return -1;
  if (fd && *fd >= 0) { unsigned char sink; char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov={&sink,1}; struct msghdr m={.msg_iov=&iov,.msg_iovlen=1,
      .msg_control=cbuf,.msg_controllen=sizeof(cbuf)};
    ssize_t n; do n=recvmsg(socket_fd_last(),&m,MSG_CMSG_CLOEXEC); while(n<0&&errno==EINTR);
    if(n!=1){ close(*fd); *fd=-1; return -1; } }  /* fd arrives CLOEXEC */
  return 0;
}
static DBusHandlerResult open_file(DBusConnection *bus, DBusMessage *msg, void *u) {
  char token[33]; random_hex(token);
  char line[2048]; snprintf(line,sizeof line,"P O %s %s %s",token,
      b64(dbus_mime(msg)), b64(dbus_name(msg)));        /* no URI/path from guest */
  if (relay(line,NULL,NULL,NULL)!=0) return dbus_err(msg,"bridge");
  for (int i=0;i<1200;i++) {                            /* 5 min @250 ms */
    char *json=NULL; size_t n=0;
    if (relay_frame(line2("P Q %s 0",token),&json,&n,NULL)!=0) return dbus_err(msg,"poll");
    if (strstr(json,"\"pending\"")) { free(json); usleep(250000); continue; }
    int fd=-1; relay_frame(line2("P Q %s 1",token),&json,&n,&fd);
    /* parse handle/files[]; uris = file:///media/android/.portals/<handle>/<name>, fds=[fd] */
    DBusMessage *r = dbus_message_new_method_return(msg);
    dbus_message_append_args(r, DBUS_TYPE_ARRAY, DBUS_TYPE_STRING, &uris, count,
                                DBUS_TYPE_ARRAY, DBUS_TYPE_UNIX_FD, &fds, count, DBUS_TYPE_INVALID);
    dbus_connection_send(bus,r,NULL); dbus_message_unref(r);
    relay(line2("P X %s",token),NULL,NULL,NULL); return DBUS_HANDLER_RESULT_HANDLED;
  }
  return dbus_err(msg,"timeout");
}
int main(void) { /* request org.freedesktop.impl.portal.Dextop; register
  /org/freedesktop/portal/desktop with FileChooser.OpenFile/SaveFile/SelectFolder;
  read socket name from /tmp/dextop-devices.conf "socket " line */ }
```
Ship `/usr/share/xdg-desktop-portal/portals/dextop.portal` (`DBusName=org.freedesktop.impl.portal.Dextop`, `Interfaces=org.freedesktop.impl.portal.FileChooser;`, `UseIn=dextop;Dextop; plasma;`) and `dextop-portals.conf` (`[preferred] default=dextop; org.freedesktop.impl.portal.FileChooser=dextop;`). Fallback if D-Bus absent: `LD_PRELOAD` GTK `gtk_file_chooser_native_*` → same `P ` relay (documented, not required).

**Security rules (scoped URIs only).**
1. Guest sends selector constraints only — never `content://`/`file://` URIs or absolute paths. Reject any request carrying a URI scheme.
2. `takePersistableUriPermission` only on the URI returned by DocumentsUI, with flags narrowed by op (`r` vs `rw`); no blanket grant, no `wholeStorage` implication.
3. Grants stored per container (`linux-portal-<mode>-<id>`), bound to token's container and re-checked against `processContainer(peer.pid)` on every `P Q`.
4. Result paths confined to `/media/android/.portals/<handle>/<valid name>`; reuse `LinuxFolders.valid()`-style checks, `NOFOLLOW_LINKS` on placeholder deletion; `EXDEV` on cross-tree rename already in `LinuxFolders.rename`.
5. Peer auth is the device bridge's: app UID or shell UID + `ownsProcess(pid)`; no new unauthenticated listener; token is 128-bit random; 32-pending cap + 5-min expiry.
6. fd hygiene: `MSG_CMSG_CLOEXEC` guest-side, `setFileDescriptorsForSend(...)` + explicit `null` clear app-side, close on every error/EOF path, 30 s relay timeout (avoid BUG-04 spin), kill pending on container stop/reset.
7. MIME/name are bounded, base64-decoded, NUL/`..`/`/` stripped; never passed to a shell.
8. Clipboard (§B) honors `LinuxClipboard.enabled()` and the focus gate; only `text/*` and `image/png|jpeg|webp`; HTML/URI-list/custom MIME rejected so a paste cannot smuggle a URI grant.

### B. wl_data_device ↔ ClipboardManager text/image sync

**C (`cpp/wayland/compositor.c`).** Keep text path, add image:
```c
#define IMAGE_MIMES {"image/png","image/jpeg","image/webp"}
#define CLIP_MAX_IMG (8u<<20)
static const char *const IMAGE_MIMES[] = IMAGE_MIMES;
/* comp: selection_bytes/len/mime + selection_is_image */
static int clipboard_readable(...) {               /* extend existing */
  size_t cap = comp->selection_is_image ? CLIP_MAX_IMG : (1u<<20);
  ... if (count<0 && (errno==EAGAIN||errno==EINTR)) return 0;
  if (comp->selection_is_image) {
    jbyteArray a = (*env)->NewByteArray(env, read_state->size);
    (*env)->SetByteArrayRegion(env, a, 0, size, (jbyte*)data);
    jstring mime = (*env)->NewStringUTF(env, read_state->mime);
    (*env)->CallVoidMethod(env, comp->callback, comp->on_clipboard_image, a, mime);
  } else { /* existing {"type":"clipboard","text":...} emit() */ }
  wl_event_source_remove(read_state->event); close(fd); free(...); return 0;
}
static void read_selection(struct comp *c) { /* pick text or image mime; set selection_is_image */ }
static void offer_selection(...) { /* image source → offer IMAGE_MIMES */ }
static void offer_receive(...) { /* comp->selection_bytes → write(fd), not selection_text */ }
static void handle_clipboard_image(struct comp *c, const uint8_t *b, size_t n, const char *mime);
/* CMD_CLIPBOARD_IMAGE: cmd->bytes/cmd->len/cmd->mime (heap ptr via post(), like cmd->text) */
/* nativeStart: cache on_clipboard_image = GetMethodID(cbcls,"onClipboardImage","([BLjava/lang/String;)V") */
JNIEXPORT void JNICALL Java_..._nativeClipboardImage(JNIEnv *e, jclass c, jlong h,
    jstring mime, jbyteArray data) { cmd=command(CMD_CLIPBOARD_IMAGE,0);
    cmd->len=(*e)->GetArrayLength(e,data); cmd->bytes=malloc(cmd->len);
    (*e)->GetByteArrayRegion(e,data,0,cmd->len,(jbyte*)cmd->bytes); cmd->mime=strdup(...);
    post(COMP(h),cmd); }
```
`emit()`/JSON stays text-only; image uses a typed callback, avoiding base64 and JNI string caps.

**Kotlin.** `WaylandCompositor.kt`: add ctor param `onClipboardImage: (ByteArray,String)->Unit = {_,_->}`, `@Keep fun onClipboardImage(bytes, mime)` in `Callback`, `@JvmStatic external fun nativeClipboardImage(handle, mime, bytes)`, `fun setClipboard(mime: String, bytes: ByteArray)`. `LinuxAppSession.kt`: `@Volatile var onClipboardImage`, forward from `onWaylandEvent` (image callback) and `setClipboard(bytes,mime)` → wayland only; `LinuxDesktopSession.kt:123-131` alike. `LinuxContainerService.kt`: `shareClipboardImage(containerId, mime, bytes)` → `sessions/appSessions.setClipboard(...)` + `callback.onClipboardImage` + `LinuxClipboard.toAndroidImage`. `LinuxClipboard.kt`:
```kotlin
private const val MAX = 8 shl 20
fun toAndroidImage(ctx: Context, bytes: ByteArray, mime: String) {
  if (!enabled(ctx) || bytes.isEmpty() || bytes.size > MAX) return
  val f = File(ctx.noBackupFilesDir, "linux/clipboard/image-${System.currentTimeMillis()}.img")
  f.writeBytes(bytes)
  val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.clipboard", f)
  val clip = ClipData.newUri(ctx.contentResolver, "Linux image", uri).apply {
      setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }   // clipboard keeps the grant
  ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
  trimOldClipImages()                                     // TTL 24 h / max 8 files
}
fun toLinuxImage(ctx: Context, container: String) {       // focus-gated, same as toLinux()
  if (!enabled(ctx)) return
  val item = ctx.getSystemService(ClipboardManager::class.java).primaryClip
      ?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return
  val uri = item.uri?.takeIf { "content" == it.scheme } ?: return
  val mime = ctx.contentResolver.getType(uri) ?: return
  if (!mime.startsWith("image/")) return
  val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes(MAX + 1) } ?: return
  if (bytes.isEmpty() || bytes.size > MAX) return
  val memfd = Os.memfd_create("dextop-clip", 0)
  Os.write(memfd, bytes, 0, bytes.size); Os.lseek(memfd, 0, Os.SEEK_SET)
  LinuxContainerClient.service()?.setClipboardImage(container, mime,
      ParcelFileDescriptor.adoptFd(memfd), bytes.size.toLong())
}
```
AIDL: `ILinuxContainerService.aidl` add `oneway void setClipboardImage(String containerId, String mime, in ParcelFileDescriptor fd, long size) = 91;`; `ILinuxContainerCallback.aidl` add `void onClipboardImage(String containerId, String mime, in ParcelFileDescriptor fd, long size) = 8;`. PFD avoids the 1 MB Binder cap. `LinuxWindowActivity.kt:781-806` focus listener calls `LinuxClipboard.toLinux/toLinuxImage`; `LinuxChannel.kt:477-481` toggle unchanged. **X11 path stays text-only** (X11Clipboard.kt offers no image atom) — document this limitation for backends A/B/C; image sync targets backend D/Wayland.

## Risks / device testing needed

- **Portal (device, Android mode, wholeStorage OFF):** GTK Nautilus/gedit OpenFile → DocumentsUI → file readable in guest by `file://` path *and* by fd; SaveFile creates + writes; SelectFolder; cancel leaves no grant; rotation/task-kill during picker → `P X` cleanup.
- **Grant isolation:** after `removeFolder`/`resetSession`/container removal `persistedUriPermissions` no longer contains portal URIs; token from container A rejected when presented by B (`processContainer` mismatch); guest-supplied `content://` string ignored; attempt to open outside `.portals` returns EACCES.
- **Image clipboard:** copy PNG in guest (GIMP/Firefox) → Android paste; Android copied image/screenshot → guest paste; text/image no echo loop; disable toggle stops both; unfocused app cannot read (API 36); >8 MiB and non-image MIME rejected.
- **fd/CPU regressions:** 500 portal opens → `/proc/<pid>/fd` stable; peer EOF mid-`P Q` returns error without spin (BUG-04 pattern); 32+ concurrent requests get EBUSY; 5-min timeout fires.
- **Compatibility:** `xdg-desktop-portal` version in Debian rootfs vs portal config; `DBUS_TYPE_UNIX_FD` support; Guest socket shim must not rewrite the abstract name (raw syscalls, as in `folders.c:74-78`); Shell/SELinux: app cannot connect to shell-owned socket, hence reuse of `acceptDeviceBridge()`.
- **Perf:** 8 MiB image copy must not stall the 120 Hz compositor thread — `clipboard_readable()` stays event-loop driven with bounded chunks; JNI array copy on compositor thread is the only synchronous cost, measure with Perfetto.
