/* Android allocates gralloc buffers on behalf of the Deck glibc WSI.
 * Each connection owns one AHB; closing it drops that allocation reference.
 * Worker state is independent of the compositor and cannot touch Wayland. */
#include <dlfcn.h>
#include "deck_ahb_wire.h"

/* BUG-05: resolve libnativewindow symbols once per process instead of on every
 * AHB allocation. The handle is intentionally never dlclose'd: dlsym pointers
 * would dangle, and the loader ref is tiny. */
static pthread_once_t g_ahb_once = PTHREAD_ONCE_INIT;
static void *g_ahb_nw = NULL;
static const void *(*g_ahb_get_handle)(const AHardwareBuffer *) = NULL;

static void deck_ahb_cache_symbols(void) {
    g_ahb_nw = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
    if (g_ahb_nw) {
        g_ahb_get_handle = (const void *(*)(const AHardwareBuffer *))
            dlsym(g_ahb_nw, "AHardwareBuffer_getNativeHandle");
    }
}

static bool deck_ahb_peer_allowed(int fd) {
    struct ucred peer; socklen_t length = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &length)) return false;
    /* Reject other UIDs outright; the guest runs under the app's UID. */
    if (peer.uid != getuid()) return false;
    /* BUG-05: read the environment in bounded stack chunks instead of a 64 KB
     * stack buffer. The full v2 token scheme lands with the Rust core (M5). */
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/environ", peer.pid);
    int env = open(path, O_RDONLY | O_CLOEXEC);
    if (env < 0) return false;
    const char marker[] = "DEXTOP_STEAM_INSIDE=1";
    char window[sizeof(marker) + 4096];
    size_t carried = 0;
    bool found = false;
    for (;;) {
        ssize_t n = read(env, window + carried, sizeof(window) - carried - 1);
        if (n <= 0) break;
        size_t total = carried + (size_t)n;
        window[total] = 0;
        for (size_t i = 0; i + sizeof(marker) - 1 < total; ++i) {
            if ((!i || window[i-1] == 0) && !memcmp(window + i, marker, sizeof(marker) - 1)) {
                found = true;
                break;
            }
        }
        if (found) break;
        carried = total > sizeof(marker) - 1 ? sizeof(marker) - 1 : total;
        if (carried) memmove(window, window + total - carried, carried);
    }
    close(env);
    return found;
}

static void *deck_ahb_worker(void *data) {
    int fd = (int)(intptr_t)data;
    AHardwareBuffer *buffer = NULL;
    struct deck_ahb_request request;
    struct deck_ahb_reply reply = {.status = -1};
    struct timeval timeout = {.tv_sec = 5};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    if (!deck_ahb_peer_allowed(fd) || recv(fd, &request, sizeof(request), 0) != sizeof(request) ||
        request.version != DECK_AHB_WIRE_VERSION || request.operation != 1 ||
        !request.desc.width || !request.desc.height || request.desc.width > 16384 || request.desc.height > 16384 ||
        request.desc.layers != 1 || (request.desc.format != 1 && request.desc.format != 2) ||
        request.desc.rfu0 || request.desc.rfu1) goto done;
    AHardwareBuffer_Desc desc;
    memcpy(&desc, &request.desc, sizeof(desc));
    const uint64_t allowed = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
        AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | (UINT64_C(1) << 28);
    if (desc.usage & ~allowed) goto done;
    if (AHardwareBuffer_allocate(&desc, &buffer)) goto done;
    /* BUG-05: use the process-wide cached symbol; no per-allocation dlopen. */
    pthread_once(&g_ahb_once, deck_ahb_cache_symbols);
    struct header { int version, num_fds, num_ints, data[]; };
    const struct header *handle = g_ahb_get_handle ? g_ahb_get_handle(buffer) : NULL;
    if (!handle || handle->num_fds < 1 || handle->num_fds > DECK_AHB_MAX_FDS ||
        handle->num_ints < 0 || handle->num_ints > DECK_AHB_MAX_INTS) goto done;
    AHardwareBuffer_describe(buffer, &desc);
    reply.status = 0; memcpy(&reply.desc, &desc, sizeof(desc));
    reply.num_fds = handle->num_fds; reply.num_ints = handle->num_ints;
    memcpy(reply.ints, handle->data + handle->num_fds, handle->num_ints * sizeof(int));
    char ancillary[CMSG_SPACE(DECK_AHB_MAX_FDS * sizeof(int))] = {0};
    struct iovec iov = {&reply, sizeof(reply)};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = ancillary,
        .msg_controllen = CMSG_SPACE(handle->num_fds * sizeof(int))};
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(handle->num_fds * sizeof(int));
    memcpy(CMSG_DATA(c), handle->data, handle->num_fds * sizeof(int));
    if (sendmsg(fd, &msg, MSG_NOSIGNAL) != sizeof(reply)) goto done;
    /* Idle buffers can live indefinitely. Only a handle-transfer request is
     * bounded, so a crashed client cannot leave a blocked transfer thread. */
    timeout.tv_sec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    for (;;) {
        char byte, control[CMSG_SPACE(sizeof(int))] = {0};
        struct iovec in = {&byte, 1};
        struct msghdr command = {.msg_iov = &in, .msg_iovlen = 1,
            .msg_control = control, .msg_controllen = sizeof(control)};
        ssize_t n = recvmsg(fd, &command, MSG_CMSG_CLOEXEC);
        if (n < 0 && errno == EINTR) continue;
        if (n != 1) break;
        int target = -1;
        struct cmsghdr *rights = CMSG_FIRSTHDR(&command);
        if (rights && rights->cmsg_level == SOL_SOCKET && rights->cmsg_type == SCM_RIGHTS &&
            rights->cmsg_len == CMSG_LEN(sizeof(int))) memcpy(&target, CMSG_DATA(rights), sizeof(target));
        int status = -1;
        if (target >= 0) {
            timeout.tv_sec = 5;
            setsockopt(target, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
            status = AHardwareBuffer_sendHandleToUnixSocket(buffer, target);
            close(target);
        }
        if (send(fd, &status, sizeof(status), MSG_NOSIGNAL) != sizeof(status)) break;
    }
done:
    if (buffer) AHardwareBuffer_release(buffer);
    close(fd);
    return NULL;
}

static int deck_ahb_accept(int fd, uint32_t mask, void *data) {
    int client = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
    if (client < 0) return 0;
    pthread_t thread;
    if (pthread_create(&thread, NULL, deck_ahb_worker, (void *)(intptr_t)client)) close(client);
    else pthread_detach(thread);
    return 0;
}
