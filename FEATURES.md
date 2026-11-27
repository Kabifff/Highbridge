# Highbridge feature comparison

How Highbridge compares with the official Meshtastic and MeshCore apps and with MeshWave,
as of version 0.3.0 (November 2026). ✓ = has it, ◐ = partly, ✗ = not yet.

## Meshtastic (official Android app)

| Feature | Highbridge | Notes |
|---|---|---|
| Bluetooth and USB connection | ✓ | Also Wi-Fi/TCP: ✗ |
| Channels, direct messages, delivery receipts | ✓ | |
| Replies and emoji reactions | ✓ | |
| Quick replies / canned messages | ✓ | Radio canned messages editable too |
| Message search, resend failed, delete | ✓ | |
| Node list, favorites, ignore (synced to radio) | ✓ | |
| Node detail: signal, hops, battery, altitude, telemetry log | ✓ | Charts of metrics over time: ◐ (list, no graph) |
| Traceroute and traceroute history | ✓ | Traceroute drawn on the map: ✗ |
| Map: nodes, waypoints (send, receive, delete), offline areas | ✓ | Street, topo, USGS topo, satellite, night, .mbtiles |
| Range test, with heatmap | ✓ | |
| Telemetry: device, environment, power | ✓ | Settings and readings |
| Radio settings: region, preset, hops, power | ✓ | |
| Device role, rebroadcast, position, power, security, managed mode | ✓ | |
| Modules: MQTT, telemetry, neighbor info, store & forward, range test, external notification, detection sensor, paxcounter, canned messages | ✓ | Serial, audio, remote hardware, ambient lighting, traffic management, TAK module: ✗ |
| MQTT client proxy (radio uses the phone's internet) | ◐ | Setting only; the phone doesn't relay MQTT yet |
| Licensed (ham) mode | ✓ | |
| Restart, shut down, node list reset, factory reset | ✓ | |
| Remote admin of other nodes | ✗ | |
| Firmware update | ◐ | USB (UF2, ESP32 serial), nRF52 Bluetooth DFU, release checker; ESP32 over-the-air: ✗ |
| Export (CSV) | ✓ | Messages |
| TAK integration | ◐ | ATAK position bridge (CoT over UDP) |
| AR view, home-screen widget, Android Auto, translations | ✗ | |

## MeshCore (official companion app)

| Feature | Highbridge | Notes |
|---|---|---|
| Bluetooth and USB connection | ✓ | |
| Contacts, direct messages, channels, delivery receipts | ✓ | |
| Hashtag (#) channels and private channels, QR sharing | ✓ | Reads MeshCore app channel links |
| Contact QR codes: share yours, scan others | ✓ | |
| Zero-hop and flood adverts | ✓ | |
| Repeater and room server login, status, remote commands | ✓ | Room server chat view: ◐ (posts appear as messages) |
| Path reset and path editing, trace path | ✓ | |
| Telemetry requests | ✓ | Cayenne LPP: battery, temperature, humidity, pressure |
| Radio presets, power, regions (frequency bands) | ✓ | MeshCore scope "regions": ✗ |
| Discover screen (recent adverts) | ◐ | Heard nodes show in Nodes; no separate screen |
| @mentions, per-channel notification levels | ✗ | |
| Import/export radio configuration | ✗ | App backup covers app data only |
| Antenna coverage prediction tool | ✗ | Real measured coverage via wardriving instead |
| Map of contacts | ✓ | |

## MeshWave

| Feature | Highbridge | Notes |
|---|---|---|
| Voice over LoRa | ◐ | Codec2 voice messages (record, then send); live push-to-talk streaming: ✗ |
| Photos over LoRa with progress | ✓ | |
| Geofences with mesh alerts | ✓ | Circle, polygon |
| Trail drift alerts | ✓ | "Trail" geofence around a route |
| Trail history | ✓ | GPX tracks |
| Offline maps | ✓ | |
| Approved/trusted nodes filter | ✓ | Favorites filter on map and node list |
| Bearing and distance to a node | ✓ | Compass screen |
| Elevation difference to a node | ◐ | Node altitude shown; difference not computed |
| Topology view | ✓ | |
| Live group location on a map | ✓ | |
| Signal coverage heatmap | ✓ | Range test heatmap and wardriving coverage |
| Weather forecasting | ✓ | Barometer trend (Zambretti) |
| Meshtastic and MeshCore on one map | ✓ | Plus Reticulum in the full edition |
| AR landmark identification (1.9 million features) | ✗ | Needs a large offline landmark database |
| AR waypoint drop | ✗ | |
| Map views | ◐ | 6 of MeshWave's 9 (no hybrid or AR view) |

## Only in Highbridge

Bridge between Meshtastic and MeshCore (and Reticulum), wardriving with a live public coverage
map on stpaulmesh.com, airtime limits per region, Reticulum/LXMF and NomadNet pages (full edition),
Morse keyer, check-in and SOS, power profiles, duress PIN.
