# LOW_LEVEL_DESIGN

# Low-Level Design
Core interfaces: BackendId, PrivilegeAuthority, CapabilitySnapshot, DesktopBackend. DesktopSessionFsm state diagram and 15s detached watchdog sequence in master blueprint Part F.2. Atomic WAL entry format: {seq, op, ts, raw(canonical JSON), crc32(raw)}; fsync before mutation; reverse-order restore; commit only after verification.
