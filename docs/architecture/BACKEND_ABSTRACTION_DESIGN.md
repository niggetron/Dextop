# BACKEND_ABSTRACTION_DESIGN

# Backend Abstraction Design
DesktopBackend interface: id, requiredPrivilege, canActivate(env, caps), initialize(config, journal), validateHealth(handle), applyWindowBounds(taskId, bounds, displayId), teardown(handle, reason). BackendId sealed interface with data object per backend and ScopedDeviceOem(profileId). A DesktopSessionFsm drives selection, WAL snapshot, watchdog arming, validation, 15s user confirmation, active/suspended/recovering/rolling-back/restoring states.
