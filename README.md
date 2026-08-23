# GT FLASH Android

Native Android client for an OBDX Pro GT over Bluetooth Classic SPP.

Current hardware operations are adapter connection, voltage reporting, and VPW PCM identification by VIN and OS ID. PCM flash read/write/recovery is not enabled until the complete kernel, image-validation, erase/write, and final CRC workflow is ported and verified on real hardware.

GitHub Actions builds a debug-installable APK and an unsigned release APK for every commit to `main`. Download the `hammerdroid-<commit>` workflow artifact.
