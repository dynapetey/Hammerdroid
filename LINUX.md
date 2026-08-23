# GT FLASH for Linux

This package is a native Linux desktop front end for the OBDX Pro GT over Bluetooth RFCOMM serial. It includes its own Java 17 runtime; Java, Gradle, .NET, and Android SDKs are not required on the target computer.

## Supported now

- Enumerate and refresh Linux serial devices
- Connect and disconnect an OBDX Pro GT
- Initialize DVI J1850 VPW mode
- Read adapter firmware, hardware version, and vehicle voltage
- Identify a compatible PCM and display its OS ID and VIN
- Retry slow PCM identification requests
- Reset the adapter
- Keep a bounded, privacy-aware activity log

PCM flash-file reading, calibration/full writes, recovery, and VIN writing are not yet implemented. Do not use this build as a flashing tool.

## Bluetooth setup

Install BlueZ tools through your Linux distribution, enable Bluetooth, and pair the adapter:

```bash
bluetoothctl
power on
scan on
pair ADAPTER_MAC
trust ADAPTER_MAC
quit
```

Bind the adapter's Bluetooth serial channel:

```bash
sudo rfcomm bind /dev/rfcomm0 ADAPTER_MAC 1
```

If your account cannot open the device, add it to the serial-device group used by your distribution (commonly `dialout`), then sign out and back in:

```bash
sudo usermod -aG dialout "$USER"
```

## Run or install

Extract the downloaded ZIP or tarball. Run it in place:

```bash
./hammerdroid-linux-x64/run-hammerdroid.sh
```

Or install it for the current user:

```bash
./hammerdroid-linux-x64/install.sh
$HOME/.local/bin/gt-flash
```

The package targets 64-bit x86 Linux. It does not install system Bluetooth packages or modify Bluetooth configuration automatically.
