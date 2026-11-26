# Third-party notices

Highbridge is free software. You can redistribute it and/or modify it under the terms of the
**GNU General Public License, version 3 or (at your option) any later version** (see `LICENSE`).

Copyright (C) 2026 Kabifff and the Highbridge contributors.

Highbridge is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

Source code: https://github.com/Kabifff/Highbridge

Every component below is under a license compatible with GPL-3.0. The full license texts ship
inside the app (Settings › Open-source licenses) and in `app/src/main/assets/licenses/`.

## Included in the source and the app

| Component | Version | License | Copyright | Where |
|---|---|---|---|---|
| Meshtastic protobufs | commit ae43c7c | GPL-3.0 | Meshtastic contributors | `protocol-meshtastic/src/main/proto` |
| Codec2, speech codec subset | commit 310777b | LGPL-2.1 | David Rowe and contributors; Perens LLC (`pack.c`) | `core-media/src/main/cpp/codec2` |
| KISS FFT, part of Codec2 | bundled with Codec2 | BSD-3-Clause | Mark Borgerding | `codec2/kiss_fft*.c`, `_kiss_fft_guts.h` |

The Codec2 files are unmodified copies from https://github.com/drowe67/codec2. The `codebook*.c` files were generated from Codec2's codebook data by its own `generate_codebook` tool. Highbridge links them into `libhighbridge_codec2.so` through `codec2_jni.c`, which is GPL-3.0-or-later. LGPL-2.1 §3 allows using this code under the GPL.

## Linked libraries (downloaded by Gradle at build time, packaged in the app)

| Component | Version | License | Copyright |
|---|---|---|---|
| AndroidX and Jetpack Compose (activity, core, lifecycle, navigation, room, documentfile, compose ui/material3/icons) | Compose BOM 2024.12.01, Room 2.6.1 | Apache-2.0 | The Android Open Source Project |
| Kotlin standard library, kotlinx.coroutines | 2.1.0, 1.9.0 | Apache-2.0 | JetBrains s.r.o. and Kotlin contributors |
| Wire runtime, Okio | 5.3.11, 3.9.1 | Apache-2.0 | Square, Inc. / Block, Inc. |
| osmdroid | 6.1.20 | Apache-2.0 | osmdroid contributors |
| ZXing core | 3.5.3 | Apache-2.0 (see NOTICE) | ZXing authors |
| zxing-android-embedded | 4.3.0 | Apache-2.0 | JourneyApps |
| usb-serial-for-android | 3.8.1 | MIT | Google Inc.; Mike Wakerly |
| Nordic Android DFU Library | 2.5.0 | BSD-3-Clause | Nordic Semiconductor |
| Chaquopy runtime | 16.1.0 | MIT | Chaquo Ltd and contributors |
| Python | 3.12 | PSF-2.0 (GPL-compatible) | Python Software Foundation |
| OpenSSL, bundled with Python | 3.0.15 | Apache-2.0 | The OpenSSL Project Authors |
| SQLite, bundled with Python | — | Public domain | — |
| pySerial | 3.5 | BSD-3-Clause | Chris Liechti |

## Not included: Reticulum (RNS) and LXMF

Highbridge's Reticulum support uses the RNS and LXMF Python packages by Mark Qvist. Their
"Reticulum License" adds two use restrictions: no systems meant to purposely harm people, and no
use in AI or machine-learning training datasets. GPL-3.0 §7 and §10 don't allow further
restrictions in a GPL program, so these packages are **not shipped** in Highbridge's source or
app.

When a user turns Reticulum on, the app shows that license and asks first. Only after the user
agrees does it download the two packages from PyPI onto that phone, checked against fixed
SHA-256 sums (`app/src/main/java/org/highbridge/app/rns/RnsInstaller.kt`).

Highbridge's own Reticulum glue code (`app/src/main/python/highbridge_rns.py`) is GPL-3.0-or-later.

## Data

Map tiles and data © OpenStreetMap contributors, available under the Open Database License (ODbL).
The topographic style uses OpenTopoMap (CC-BY-SA).
