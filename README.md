# Highbridge

**One bridge for every mesh.** Highbridge is an Android app for off-grid LoRa mesh radios. It talks to **Meshtastic**, **MeshCore** and **Reticulum (RNode)** radios from one app, and can bridge public channels between them.

It's named for Saint Paul's High Bridge, which joins the West 7th neighborhood to the West Side bluffs. It's made in Saint Paul and works with any LoRa band in the world.

> Status: early. The code builds and its logic tests pass, but it has not been tested on real radios yet. Please report what works and what doesn't.

## Features

- **Three protocols in one app**
  - Meshtastic over Bluetooth LE and USB-C (official protobufs)
  - MeshCore companion radios over Bluetooth LE and USB-C
  - Reticulum and LXMF, running the official RNS and LXMF Python packages inside the app (Chaquopy). These are a one-time download (see License)
  - Reticulum can use an RNode over USB-C or Bluetooth, Wi-Fi (AutoInterface) or TCP
- **USB-C auto-detect.** Plug a radio in and Highbridge works out whether it runs Meshtastic, MeshCore or RNode firmware.
- **Worldwide regions**
  - All 27 Meshtastic regions, each with its legal power limit and airtime limit
  - MeshCore presets checked against each country's band
  - RNode settings kept inside your region's band
- **Bridge.** Carries public channel messages between any two networks, or all three. It runs in the background and survives reboots. It never bridges direct messages, stops echoes, tags each message with where it came from, and trims messages to fit the other network.
- **Photos and voice notes over LoRa.**
  - Photos are compressed to WebP.
  - Voice uses Codec2 at 700C, 1200 or 1300 bps.
  - Both are sent in 156-byte frames with CRC32, then reassembled, with resend requests (NACK) for missing frames.
- **Private channels**
  - Random 256-bit (Meshtastic), 128-bit (MeshCore) or 512-bit (Reticulum group) keys
  - Shared face to face with a QR code
- **Map**
  - OpenStreetMap, with standard, topographic and night styles
  - Show one network or all of them, hide stale nodes
  - Signal rings, a range-test heatmap and geofences
  - Offline `.mbtiles` import
- **Field tools**
  - Topology graph by hop count and SNR
  - Compass that points to a node
  - Geofences: circle, area and trail drift
  - One-tap check-in and SOS
  - Offline Zambretti barometer forecast
  - Morse encoder/decoder and a 700 Hz CW straight key
  - Range test logger with CSV export
  - GPX 1.1 tracks
  - ATAK Cursor-on-Target bridge
  - Airtime (duty-cycle) meter
- **MeshCore admin.** Repeater login, status and remote commands, plus a visual path editor.
- **NomadNet browser.** Reads Micron (`.mu`) pages over Reticulum.
- **USB-C terminal and flashing**
  - Multi-protocol command line
  - nRF52 UF2 flasher
  - ESP32 ROM-bootloader flasher with MD5 verification
  - Nordic Bluetooth DFU
  - Firmware update checker using GitHub Releases
- **Privacy**
  - PIN lock with lockout after wrong guesses
  - Optional duress PIN that wipes the app
  - AES-256-GCM encrypted backup and restore
- **Power and location**
  - No Google Play Services
  - GPS every 35 s (Fast) or every 5 min (Battery Saving)
  - Performance, Balanced and Ultra-Low Power profiles, with an automatic switch at low battery

## Build

Requires JDK 17+, the Android SDK (platform 35, build-tools, NDK 28.2.13676358, CMake 3.22.1), and Python 3.12 on the build machine for Chaquopy.

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

If Python 3.12 isn't at `/usr/bin/python3.12`, change `buildPython` in `app/build.gradle.kts`.

The app targets arm64 phones (almost every Android phone from the last several years).

## Project layout

| Module | What's in it |
|---|---|
| `core-protocol` | Shared models, LoRa regions and presets, airtime math, Bluetooth and USB links |
| `protocol-meshtastic` | Meshtastic protobufs (Wire) and the Meshtastic client |
| `protocol-meshcore` | MeshCore companion protocol codec and client |
| `protocol-reticulum` | KISS / RNode control and the Reticulum client (Python engine interface) |
| `core-media` | Media framing, image compression, Codec2 (C sources + JNI), voice recording |
| `feature-bridge` | Bridge engine and background service |
| `app` | Screens, database, radio manager, field tools, flashers, Python engine (`src/main/python`) |

## License

Highbridge is free software under the **GNU General Public License v3.0 or later** (see `LICENSE`).
Copyright (C) 2026 Kabifff and the Highbridge contributors. It comes with ABSOLUTELY NO WARRANTY.

Everything bundled in the source and the app uses a GPL-3.0-compatible license:
- Meshtastic protobufs: GPL-3.0
- Codec2: LGPL-2.1
- Apache-2.0, MIT and BSD libraries
- Python: PSF

`THIRD_PARTY_NOTICES.md` lists each component with its version and copyright. The full license texts are in `app/src/main/assets/licenses/` and in the app under **Settings › Open-source licenses**.

**Reticulum (RNS) and LXMF are not included.** Their Reticulum License adds use restrictions that the GPL doesn't allow inside a GPL program. The app downloads them from PyPI only when a user turns Reticulum on and agrees to that license. The download is checked against fixed SHA-256 sums.

If you share a Highbridge APK, you must also offer its matching source code, for example by linking to the release tag in this repository.

Highbridge isn't affiliated with the Meshtastic, MeshCore or Reticulum projects.
