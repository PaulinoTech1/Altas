# Atlas network observatory

A local, dependency-free Python 3 app for Termux PRoot. SQLite stores explicit target inventories, TCP observations, service metadata and changes. The mobile dashboard includes response rates, device history, a 24-hour briefing and JSON export.

## Run

```sh
cd /root/atlas
python3 atlas.py
```

Open http://127.0.0.1:8765 on the Pixel. Add IP addresses or hostnames for your own devices, choose ports, and click **Observe now**. Automatic checks run every five minutes. No subnet sweep runs automatically.

```sh
python3 atlas.py --port 8765 --interval 600 --database ./atlas.sqlite3
python3 -m unittest discover -s tests -v
```

The server binds only to loopback. Keep it running to collect history. Android can suspend Termux or terminate its processes; continuous overnight collection is not guaranteed. The SQLite file persists across restarts; back it up while the app is stopped.

## Evidence and limits

A TCP connection measures service reachability, not device power state. Filtered or closed selected ports are recorded as unreachable, never proof a device is offline. Response rate is the fraction of all stored checks that got at least one TCP response. DNS failure is recorded as unknown and does not generate false service-closure events.

HTTP metadata is collected on 80, 443, 8000, 8080 and 8443 with a bounded response read and no redirect following. SSH banners are read on port 22. TLS certificate SHA-256 fingerprints are retained for change detection; metadata collection accepts self-signed certificates and does not assert certificate trust. No credentials are sent. Latency measures TCP connection establishment.

History API returns the latest 200 checks per target; the dashboard change log and briefing use the latest 200 events. SQLite retains all observations, so database size grows with use. Export downloads current inventory and the displayed changes; copy the database for full history. Checks are bounded to 64 targets and 32 ports each, with four device workers and eight port workers per device.

This first version does not include radio capture, multicast discovery, automatic topology inference, a language-model assistant, or remote sensor ingestion. The briefing is deterministic and based on stored observations. Atlas is standalone here because existing project source was not located.

## Wi-Fi discovery

**Discover local devices** detects the current `wlan0` IPv4 subnet with interface ioctls and checks common TCP ports on up to 254 hosts, excluding the phone. Discovery only accepts a private subnet of at most 256 addresses and runs on demand. Up to 32 hosts are checked concurrently with 300 ms per connection. Accepted or explicitly refused connections count as response evidence; filtering and timeouts do not. Responding IPs are added to monitoring, up to the 64-target inventory limit, and observed afterward. Discovery progress and response evidence are available in `/api/state`. The phone's Wi-Fi address is refreshed on each run. Router isolation, VPN routing, proxies or firewalls may affect results; a refusal is network response evidence rather than proof of device identity. No MAC addresses or device identities are inferred.

## Device intelligence

Use **Collect names & advertisements** for a bounded four-second SSDP M-SEARCH on the Wi-Fi interface, plus reverse DNS lookups with two-second process deadlines. The app records SSDP server, service type, USN and location fields, without fetching advertised URLs. Device identities persist in SQLite. Evidence is untrusted device-provided text and is escaped in the dashboard. SSDP failure is reported in the collection status. A missing reverse DNS name does not imply the device is absent.

Expanded service observations include selected HTTP response headers, TLS version/cipher, decoded certificate subject/issuer/SAN/validity and the certificate fingerprint. Port service labels are hints. No login, print payload, MQTT subscription or credential probing occurs. Search matches inventory/evidence; each device expands to its stored evidence and can be renamed.

If `termux-wifi-connectioninfo` is available, collection attempts it with a six-second deadline. Android Wi-Fi APIs concern this phone's connection. Other clients' associated SSIDs and their transmitted Wi-Fi probe requests are unavailable through the current IP-only access. Router-authorized client data or a separate compatible capture sensor would be needed for those observations. Do not infer SSID membership from sharing an IP subnet.

## Inventory management and backups

Device panels now show advertised friendly names, manufacturers and models when available. Description collection reads at most 64 KiB of XML from an HTTP URL whose numeric host exactly matches the SSDP sender; redirects and XML entity declarations are rejected. A description failure is retained as evidence. Advertised identities are device claims, not verified identities.

Search, response-state filters and sorting help explore the inventory. Expand a device to edit its label, notes, monitoring ports or pause state. Pausing stops scheduled and manual observation batches for that target; discovery and advertisement collection remain network-wide actions and may still contact its address. Existing observations remain intact. First observation, last TCP response and total checks summarize its history.

The 24-hour event count uses the full database, while the displayed event log remains limited to 200 entries. **Download full database backup** uses SQLite's online backup API to create a consistent copy containing inventory, identities, all observations and events. `/api/backup` serves that copy. JSON export still contains the current dashboard snapshot.

## nRF Connect file bridge

Atlas cannot read nRF Connect's private app data or inherit its Bluetooth permissions. A direct attempt to launch Nordic's documented automated-test foreground service returned `Not found; no service started` on this phone. A scan-only XML is prepared at `/sdcard/Download/AtlasBridge/atlas_scan.xml`, but has not run successfully.

The **Bluetooth import bridge** accepts text uploads, pasted text, or an explicit import of `.txt`, `.log`, and `.csv` files placed in `/sdcard/Download/AtlasBridge`. It parses Nordic automated scan lines: `HH:MM:SS.mmm AA:BB:CC:DD:EE:FF -45 HEXADVERTISEMENT`. File extensions do not imply support for arbitrary CSV schemas; unsupported formats return a clear error. Provide a real export to adapt another format. No continuous folder watcher is enabled.

Imported advertisements are stored separately from IP targets, with RSSI ranges, raw AD records, decoded names, 16/128-bit service UUIDs and manufacturer IDs/data. Source times lack a date; import time is recorded separately. Randomized Bluetooth addresses do not prove stable device identity. Duplicate detection uses source name plus exact log line; repeated identical lines across separate days may deduplicate because the source format lacks dates. Imports are limited to 1 MB and 10,000 matching lines. No real Bluetooth data has been imported during implementation; tests use isolated temporary databases.

Nordic reference: https://github.com/NordicSemiconductor/Android-nRF-Connect/blob/main/documentation/Automated%20tests/README.md

## Rust capture workbench and ARM64 decoder

New capture parsing and RSSI analysis run in `native/`, a Rust executable. On AArch64 it links `src/hex.S` through `global_asm!`: a bounded AAPCS64 decoder with odd-length and invalid-character checks. Other architectures use a portable Rust decoder. A Rust reference is tested against the assembly for every two-byte UTF-8 input. The application makes one subprocess call per imported file, rather than per packet; SQLite storage and HTTP integration remain in the existing Python app. The browser UI remains JavaScript.

Build and verify:

```sh
cd /root/atlas/native
cargo test --locked
cargo build --release --locked
./target/release/atlas-capture --benchmark
cd ..
python3 -m unittest discover -s tests -v
```

Cargo.lock pins dependencies. serde_json is the only direct Rust dependency. Release builds use LTO. No native code runs in the web-server process; invocation has a ten-second timeout and bounded input. A missing binary produces an explicit import error.

The workbench plots RSSI against source clock time and supports observation-by-observation replay. Replay advances at a fixed 150 ms per observation, not original capture speed. Analysis is limited to the last 2,000 stored samples for one address, and results are sorted by source clock. Logs without calendar dates cannot establish ordering across midnight or multiple days. Existing imports without numeric timestamps are converted when queried. BLE inventory aggregation now uses a grouped query instead of one aggregate query per device.

No real nRF capture was available during implementation. Synthetic captures are used only in isolated tests and benchmarks; the production inventory is not populated with demonstration packets. The app needs an actual supported export to display real signal history.
