# Atlas · local network observatory

Atlas runs on an unrooted Android phone through Termux and Debian PRoot. It observes reachable TCP services, stores evidence in SQLite, and explores imported BLE captures. The dashboard and interactive network map are served at **http://127.0.0.1:8765**.

## Run

Requires Python 3 and a Rust toolchain. Build the native engine before starting:

```sh
cd /root/atlas/native
cargo build --release --locked
cd ..
python3 atlas.py
```

For other check intervals or database locations:

```sh
python3 atlas.py --interval 600 --port 8765 --database ./atlas.sqlite3
```

The CLI prints a 256-bit local control token once. Enter it in the dashboard to unlock API access; it stays only in page memory. Every API request (including exports and sensor ingestion) requires `X-Atlas-Control: TOKEN`. Optionally set `ATLAS_CONTROL_TOKEN` to exactly 64 hexadecimal characters before starting; otherwise each restart rotates the token.

The default check interval is five minutes. The server binds only to IPv4 loopback. Android may suspend or stop Termux, so uninterrupted background collection is not guaranteed. No application permissions or radio privileges are bypassed.

## Workspaces

### Assembly research lab

Use **Research lab** for assembly-backed analysis of recorded TCP timings or imported BLE captures: descriptive statistics, RSSI histograms, payload bit changes, timing-quality checks and candidate change points. JSON and HTML notebooks retain methods, evidence bounds and build/source hashes. A manual microbenchmark compares the five ARM64 kernels with Rust references. See [Research methods and kernel contracts](docs/research.md) for limits, definitions, reproducibility and interpretation.

### Network map

Atlas checks Wi-Fi link information every two seconds and refreshes the visible map every five seconds, including immediately when returning to the browser tab. A changed visible address/subnet creates a new observation context and starts a bounded discovery sweep. Periodic rediscovery also finds new devices on the same Wi-Fi (every five minutes by default). Configure `--discovery-interval` to change that interval, with a 60-second minimum. Old collectors stop accepting results after the context changes. New targets appear progressively during discovery.

The map defaults to **Current Wi-Fi**. Select an earlier context to inspect history; historical results are labeled and never presented as current reachability. Target addresses and device metadata are unique within their context, so `192.168.1.20` on one network does not inherit another network's identity or history. Existing pre-context observations are retained under **Earlier observations (network unknown)**. A full legacy inventory does not consume the new context's 64-target allowance.

Android currently withholds SSID/BSSID information. Without a fresh optional user-supplied status hint (documented below), two Wi-Fi networks exposing identical visible address/subnet fields cannot be distinguished reliably. Use **Start fresh network context** in that case; it preserves history and rediscovers the current subnet. A DHCP address change can also create a new context. Contexts describe observation visits, not verified SSID identities. A disconnect hides previous local network targets; imported BLE and external sensor observations retain their separate provenance.

The default workspace visualizes local IP observations, imported BLE addresses, and external sensor reports. Search by address, label, model or service; filter IP/BLE nodes; zoom, pan, and select nodes with touch, mouse or keyboard. Maps are paginated to 36 device nodes per page, plus their observer nodes. The inspector shows evidence, timestamps, identity snapshots and recent changes, and can download a device report.

**Edges mean that an observer recorded evidence.** They do not establish physical router/switch topology, device-to-device traffic, SSID membership, or a relationship between BLE and IP identities. BLE rotating addresses remain separate. Model/manufacturer values are device claims; port-based classifications are hints.

### Capture lab

Import a Nordic scan log through file upload or paste. The supported line format is:

```text
12:34:56.123 AA:BB:CC:DD:EE:FF -45 05095465737403030D18
```

This is a synthetic format example, not a real observation. Arbitrary nRF CSV schemas are not automatically supported. The UI explains unsupported imports. nRF Connect's private data and Bluetooth permissions are not accessible to Atlas; nRF must export data first. A direct automation-service launch was unsuccessful on this phone.

- Each session stores its name, source text, SHA-256 digest, sensor provenance, import time and packet sequence.
- An exact source/content/sensor reimport reuses its existing session. Choose **Create a distinct session** to retain identical content as a separate capture, for example on another day. A supplied session key cannot be reused for different content.
- Packet search matches stored decoded fields and raw hex. It supports session, exact address and minimum RSSI filters, with keyset pagination (50 rows/page in the UI, maximum 100 via API).
- Compare sessions to see added, missing and shared addresses and signal statistics. This compares addresses, not proven stable device identities.
- Replay uses within-session sequence, rather than combining unrelated imports. A backward clock jump greater than 12 hours is treated as midnight; smaller clock reordering and actual calendar dates remain ambiguous. Replay advances at 150 ms per packet, not original speed. It displays the first 2,000 matching packets.
- Download original source logs or self-contained HTML reports, including capture signal charts and evidence.

Imports are bounded to 1 MB and 10,000 matching packets. Unmatched line counts and import limits are reported. The shared-folder importer remains available in **Inventory**, reading supported text files from `/sdcard/Download/AtlasBridge` on demand. It is not a live watcher. Existing legacy BLE samples migrate into a clearly labeled legacy session; their unavailable source files and original dates are not invented.

### Alerts and identity history

Rules cover new BLE addresses, new observed network services, certificate changes, repeated checks without TCP responses, and stale observations. Enable/disable generation and configure missed-check and stale-time thresholds. Acknowledge records individually. Alerts are historical records, not automatically cleared current incident states, and no Android notification integration is enabled. The observer additionally writes a local status file; it does not install a notification writer.

Alerts and identity snapshots update after collection runs, BLE imports, and map/feed refreshes. Existing network events remain available, while identity snapshots begin with this feature's introduction. Snapshot history is bounded to the latest 100 changes per entity in the UI/API; the database retains all entries. Imported BLE signal fluctuations do not themselves create identity changes.

### Sensor gateway

Create a named sensor credential in **Sensors**. Its random token is shown once; only its SHA-256 digest is stored. Credentials can be revoked. Sensors submit observations to the same localhost server; remote machines need an authenticated tunnel. The app does not expose a network-wide listening socket.

BLE submission:

```sh
curl http://127.0.0.1:8765/api/v2/ingest \
  -H 'Content-Type: application/json' \
  -H "X-Atlas-Control: $ATLAS_CONTROL_TOKEN" \
  -H "Authorization: Bearer $ATLAS_SENSOR_TOKEN" \
  --data-binary @sensor-capture.json
```

```json
{"source":"sensor.log","name":"Desk survey","text":"12:34:56.123 AA:BB:CC:DD:EE:FF -45 020106"}
```

IP submission:

```json
{"observations":[{"host":"192.168.1.20","status":"responding","services":[{"port":80,"state":"open"}]}]}
```

IP batches accept 1–64 observations, at most 32 services each, and at most 16 KiB per observation. Addresses must be numeric IPs. Status is `responding`, `no_response` or `unknown`; service state is `open` or `unreachable`. Submitted metadata is untrusted sensor evidence. It is displayed separately and never overwrites local scans. The map shows up to 32 sensors and 128 latest external sensor/address observations. Replaying the same IP batch creates a new observation; external batch deduplication is not provided.

### Collection health

Shows database size, journal mode, running collectors, process start time, last error, stale/unobserved targets and the latest 30 collection runs with durations and item counts. Inventory reports and a consistent full SQLite backup are downloadable. Database size reflects SQLite pages, not the temporary WAL file or free disk space. The stale health count uses a fixed 30-minute window; alert staleness uses its configured threshold.

### Inventory and network collection

Add known targets manually or run **Discover local devices**. Discovery checks common TCP ports on the current private `wlan0` IPv4 subnet, bounded to 256 total addresses. It excludes the phone itself. Accepted and refused TCP connections count as response evidence; silent/filtered devices may not appear. Discovery runs on demand, periodically, and automatically on startup or detected Wi-Fi context changes, with 32 host workers. Monitoring uses up to four device workers, eight port workers per device, 64 targets per network context and 32 configured ports per target.

Device controls include labels, notes, monitoring ports and pause/resume. Pausing stops manual/scheduled observation batches for that target; network-wide discovery and advertisement collection can still contact its address. All history is preserved.

Metadata includes HTTP titles/selected headers, SSH banners, TLS certificate fingerprints and decoded certificate details, TLS version/cipher, reverse DNS and SSDP advertisements. SSDP device descriptions are read only from the advertising numeric IP, without redirects, up to 64 KiB, with XML entity declarations rejected. TLS metadata collection accepts self-signed certificates; it does not assert trust. No credentials, print payloads, MQTT subscriptions or login attempts are sent.

No TCP response is not proof that a device is powered off. TCP refusal can originate from a firewall. RSSI does not establish distance. A shared IP subnet does not prove a shared SSID. Raw Wi-Fi capture, Bluetooth HCI and USB SDR access are unavailable in the current Android sandbox.

## Error contract and architecture

See [Failure contract and recovery](docs/failures.md) for stable codes, defined outcomes, retry semantics and compatibility changes, and [Module boundaries](docs/architecture.md) for ownership and development checks. The shared catalog is [failure_codes.json](failure_codes.json).

## Implementation and resource bounds

The new investigation/query/storage logic is Rust (`native/src/investigate/`), alongside the Rust capture parser and ARM64 NEON decoder. The existing Python HTTP server and collectors remain; browser interactions use local JavaScript/SVG without CDN dependencies. Rust operations run in isolated subprocesses, limited to two at a time with a bounded wait and 20-second execution deadline. Imports use one transaction and prepared statements; SQLite uses WAL and indexed session/address/cursor queries. The one-time legacy migration streams packets rather than loading the entire old capture table into memory.

Dependencies are pinned in `native/Cargo.lock`. SQLite is bundled through rusqlite. The ARM64 hex decoder validates bytes and uses 16-character NEON blocks with a scalar tail; portable Rust is the non-AArch64 fallback. See [native/BENCHMARK.md](native/BENCHMARK.md) for earlier local microbenchmarks. Those timings are not end-to-end import guarantees.

All queries have response limits: 200 recent sessions, 100 alert records, 100 identity snapshots, 200 target events, 1,000 address summaries per session comparison, 256 BLE nodes, and 2,000 replay points. Packet search uses bounded output pages but substring searches can scan stored data. Full data remains in SQLite. Explicit context-scoped retention is available in Collection health; automatic deletion remains disabled. Retained source files, contracts, gaps and collection records continue to grow until managed externally.

## Verification

```sh
cd native
cargo test --locked
cargo build --release --locked
cd ..
python3 -m unittest discover -s tests -v
node --check static/api-client.js
node --check static/app.js
node --check static/investigate.js
```

Tests use temporary databases. No synthetic captures or sensor credentials are added to the live inventory. A pre-migration database backup is retained locally under `backups/`; databases, logs, build artifacts and captures are excluded from Git.

Nordic scan format reference: https://github.com/NordicSemiconductor/Android-nRF-Connect/blob/main/documentation/Automated%20tests/README.md

Optional GUI interaction checks use an isolated synthetic fixture, never the live database:

```sh
npm install --prefix /tmp/atlas-gui-check jsdom --no-audit --no-fund
python3 tests/gui_fixture.py
# In another terminal:
NODE_PATH=/tmp/atlas-gui-check/node_modules node tests/gui_dom.cjs
NODE_PATH=/tmp/atlas-gui-check/node_modules node tests/gui_network.cjs
NODE_PATH=/tmp/atlas-gui-check/node_modules node tests/gui_research.cjs
NODE_PATH=/tmp/atlas-gui-check/node_modules node tests/gui_evidence.cjs
```

The fixture binds to `127.0.0.1:8877`. Override `ATLAS_TEST_PORT` for the fixture and `ATLAS_TEST_URL` for the DOM harness if necessary. The harness exercises map selection/search, workspace navigation, session replay, packet filtering, alert acknowledgement, sensor create/revoke and health. It checks script behavior, not layout. Chromium was attempted in this PRoot environment but exited before opening a page, so visual browser verification remains unavailable here.

## Observer evidence, retention and exports

Collection health shows last successful collector completion against wall clock, signed age, overdue status and recorded gaps. `<database>.status.json` is atomically updated on start, collector completion and graceful stop, readable from ordinary Termux/file tooling. Its mtime/state is the last published state, not proof that Android has kept the process running. SQLite checkpoints let restarts, delays beyond the interval and clock discontinuities become explicit gap records; their cause is not inferred.

Each new local batch freezes its selected ports, timeouts, worker counts, bounds, collector source version, network fingerprint and failure catalog. Imported BLE captures and sensor IP posts carry distinct method contracts with unknown remote collection details. Legacy contracts remain unknown. Research and alert batch IDs can be resolved using `/api/v2/collection_contract?id=ID`.

In **Collection health**, select a context to export a self-contained JSON evidence bundle or purge older observations/events. UI purges preserve all identity snapshots. The advanced API permits explicit snapshot IDs; it never deletes contexts or target identities automatically:

```sh
curl http://127.0.0.1:8765/api/v2/purge \
  -H "X-Atlas-Control: $ATLAS_CONTROL_TOKEN" -H 'Content-Type: application/json' \
  --data '{"network_id":"CONTEXT_ID","age_seconds":2592000,"delete_snapshot_ids":[]}'
```

Purge counts are transactional and exact. It applies only to selected-context local TCP observations/events older than the cutoff (and explicitly selected old snapshots). Contracts, alerts, contexts, target configuration, identities, unmarked snapshots, BLE imports and sensor posts remain. Disk file size may not shrink.

`GET /api/v2/evidence_bundle?network_id=CONTEXT_ID&limit=100` exports up to 100 observations and their contracts/catalogs plus bounded gap/context evidence. Follow archive.next_cursor with `cursor` for older pages; every archive records omissions and limits. An 8 MB ceiling rejects oversized pages rather than silently truncating them. No external assets, stable-identity claims or authenticity signatures are added. The SQLite backup is a separate full-database export.

Research notebooks and reports retain exact source hashes, kernel version and analysis truncation flags. They remain descriptive offline analyses, not hypothesis tests or evidence of location or physical topology.

## Optional Wi-Fi status hints

A user-provided file may enrich the phone's context fingerprint. Default path: `<database>.wifi-context.json`; override with `ATLAS_WIFI_CONTEXT_FILE`. Atlas supplies only the reader. A separate user-chosen writer must atomically replace a fresh file; no writer or Android radio permission is provided:

```json
{"address":"192.168.1.10","cidr":"192.168.1.0/24","observed_at":"2026-10-31T12:00:00Z","ssid":"Example","bssid":"AA:BB:CC:DD:EE:FF","network_token":"optional-visit-token"}
```

This is a synthetic format example. Address/cidr must exactly match the visible link; observed_at must include a timezone and be no older than 120 seconds (maximum 5 seconds future skew). Include at least one optional hint; file limit is 4096 bytes, SSID 32 UTF-8 bytes, token 128 bytes, and BSSID must have MAC syntax. Unknown fields are ignored. Missing/malformed/stale files produce explicit capability diagnostics and fall back to address/mask; hint expiry can start a new context. Validated hints retain user-supplied/unverified provenance and cannot prove other devices' SSID membership. Without a fresh hint, identical visible address/mask networks remain indistinguishable; manual context reset remains available.
