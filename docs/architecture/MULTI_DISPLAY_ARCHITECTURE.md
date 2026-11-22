# MULTI_DISPLAY_ARCHITECTURE

# Multi-Display Architecture
DisplayTopologyController owns spatial layout, edge crossing, scale/density/rotation. Android 16 topology parcel layout auto-detection is fragile on OEM forks; keep best-effort with a fingerprint override. Record forced size/density in the WAL before mutation (BUG-01). Handle abrupt display unplug with graceful window migration and clean teardown.
