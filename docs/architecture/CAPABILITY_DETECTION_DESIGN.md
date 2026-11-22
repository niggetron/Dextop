# CAPABILITY_DETECTION_DESIGN

# Capability Detection Design
DeviceEnvironment tree: DeviceIdentity, PlatformVersion, DisplayCapabilities, WindowingCapabilities, InputCapabilities, GraphicsCapabilities, PrivilegeCapabilities, CompatibilityProfile. All probes read-only, side-effect free, with missing-reason strings surfaced to users. Extend the existing CapabilityProbe.kt with trusted/untrusted display, launch-on-secondary, pointer capture, AHB zero-copy, Turnip/VirGL, privilege provider, and input-device/multitouch probes.
