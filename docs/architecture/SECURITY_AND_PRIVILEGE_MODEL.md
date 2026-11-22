# SECURITY_AND_PRIVILEGE_MODEL

# Security and Privilege Model
PrivilegeGate: typed commands only, no raw shell concatenation; allowlisted global keys; WAL fsync before mutation; least privilege (embedded ADB/Shizuku/Stellar/Root); SO_PEERCRED + per-session token for guest sockets; PII scrubbing (IMEI/MAC/SSID/Android ID/email/paths) in diagnostics; scoped xdg-desktop-portal URIs. diagnostics ZIP sensitive sections default OFF behind consent.
