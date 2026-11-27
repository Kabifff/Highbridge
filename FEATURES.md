# Highbridge feature comparison

How Highbridge compares with the official Meshtastic and MeshCore apps and with MeshWave,
as of version 0.5.0 (November 2026). ✓ = has it, ◐ = partly, ✗ = not yet.

## Meshtastic (official Android app)

| Feature | Highbridge | Notes |
|---|---|---|
| Bluetooth, USB and Wi-Fi/TCP connection | ✓ | Wi-Fi uses the radio's network API (port 4403) |
| Channels, direct messages, delivery receipts | ✓ | |
| Replies and emoji reactions | ✓ | |
| Quick replies / canned messages | ✓ | Radio canned messages editable too |
| Message search, resend failed, delete | ✓ | |
| Notifications: per-chat levels, @mentions, reply and mark read from the notification | ✓ | |
| Node list, favorites, ignore (synced to radio) | ✓ | |
| Node detail: signal, hops, battery, altitude, telemetry graphs | ✓ | Battery, voltage, channel use, temperature |
| Traceroute and traceroute history | ✓ | Also drawn on the map |
| Map: nodes, waypoints (send, receive, delete), offline areas | ✓ | Street, topo, USGS topo, satellite, hybrid, terrain, water, night, .mbtiles |
| Range test, with heatmap | ✓ | |
| Telemetry: device, environment, power | ✓ | Settings and readings |
| Radio settings: region, preset, hops, power | ✓ | |
| Device role, rebroadcast, position, power, security, managed mode | ✓ | |
| Modules: MQTT, telemetry, neighbor info, store & forward, range test, external notification, detection sensor, paxcounter, canned messages, serial, audio, remote hardware, ambient lighting, traffic management, TAK | ✓ | |
| MQTT client proxy (radio uses the phone's internet) | ✓ | The phone relays to the broker set on the radio |
| Licensed (ham) mode | ✓ | |
| Restart, shut down, node list reset, factory reset | ✓ | |
| Remote admin of other nodes | ✓ | Needs your key in that node's admin keys |
| Firmware update | ✓ | USB (UF2, ESP32 serial), nRF52 Bluetooth DFU, ESP32 over Wi-Fi or Bluetooth, release checker |
| Export (CSV) | ✓ | Messages |
| Export and import radio config (.cfg DeviceProfile) | ✓ | Same format as the official apps |
| TAK integration | ◐ | ATAK position bridge (CoT over UDP) |
| Home-screen widget | ✓ | Radios, unread, check-in, SOS |
| Android Auto | ✓ | Messages read aloud and answered by voice |
| Translations | ◐ | Spanish and Norwegian for most screens; a few messages stay in English |

## MeshCore (official companion app)

| Feature | Highbridge | Notes |
|---|---|---|
| Bluetooth and USB connection | ✓ | |
| Contacts, direct messages, channels, delivery receipts | ✓ | |
| Hashtag (#) channels and private channels, QR sharing | ✓ | Reads MeshCore app channel links |
| Contact QR codes: share yours, scan others | ✓ | |
| Zero-hop and flood adverts | ✓ | |
| Repeater and room server login, status, remote commands | ✓ | Room server posts show who wrote them |
| Path reset and path editing, trace path | ✓ | |
| Telemetry requests | ✓ | Cayenne LPP: battery, temperature, humidity, pressure |
| Radio presets, power, regions (frequency bands) | ✓ | Region scope (flood scope) too, on firmware 1.10+ |
| Discover screen (recent adverts) | ✓ | With one-tap Add |
| @mentions, per-channel notification levels | ✓ | |
| Import/export radio configuration | ✓ | Name, radio settings, power, channels, region |
| Antenna coverage prediction tool | ✓ | Link budget, radio horizon, Okumura-Hata; plus measured coverage via wardriving |
| Map of contacts | ✓ | |

## MeshWave

| Feature | Highbridge | Notes |
|---|---|---|
| Voice over LoRa | ✓ | Codec2 voice messages, and live push-to-talk (1-second blocks, best on fast presets) |
| Photos over LoRa with progress | ✓ | |
| Geofences with mesh alerts | ✓ | Circle, polygon |
| Trail drift alerts | ✓ | "Trail" geofence around a route |
| Trail history | ✓ | GPX tracks |
| Offline maps | ✓ | |
| Approved/trusted nodes filter | ✓ | Favorites filter on map and node list |
| Bearing and distance to a node | ✓ | Compass screen |
| Elevation difference to a node | ✓ | Node page and compass |
| Topology view | ✓ | |
| Live group location on a map | ✓ | |
| Signal coverage heatmap | ✓ | Range test heatmap and wardriving coverage |
| Weather forecasting | ✓ | Barometer trend (Zambretti) |
| Meshtastic and MeshCore on one map | ✓ | Plus Reticulum in the full edition |
| AR landmark identification | ◐ | Peaks, towers and viewpoints from OpenStreetMap, kept for offline use where you've been; not a 1.9 million-feature offline database |
| AR view of nodes and pins, AR waypoint drop | ✓ | |
| Map views | ✓ | 9, including hybrid and terrain, plus the AR view |

## Ham radio features in other mesh apps

| Feature | Highbridge | Notes |
|---|---|---|
| Meshtastic licensed (ham) mode | ✓ | Plus automatic station ID every 10 minutes while transmitting |
| APRS over the internet (APRS-IS) | ✓ | Stations near you on the map, two-way messaging with acknowledgements |
| APRS through a KISS TNC | ✓ | Bluetooth LE (BLE KISS API), classic Bluetooth, USB, or TCP (Direwolf); KISS or TNC2 text |
| LoRa APRS | ✓ | Through a tracker or iGate running LoRa APRS firmware, as its TNC |
| APRS positions: plain, compressed and Mic-E; objects and items | ✓ | |
| Position beacons, status, map symbols | ✓ | |
| Mesh ⇄ APRS gateway with call sign registration (like aprstastic) | ✓ | Only registered licensed users reach APRS |
| Maidenhead grid squares | ✓ | You, nodes and the compass |
| Call sign lookup | ✓ | callook.info (FCC) and HamDB |
| MeshCom | ✗ | Its own ham mesh network, mostly in Europe |

## Only in Highbridge

Bridge between Meshtastic and MeshCore (and Reticulum), wardriving with a live public coverage
map on stpaulmesh.com, airtime limits per region, Reticulum/LXMF and NomadNet pages (full edition),
Morse keyer, check-in and SOS, power profiles, duress PIN.
