# Phase 2 contract and validation register

Phase 1 preserved in local commit `185c3c6`; original template in `dcb39bb`.
Scope: BLE scanning/advertisements, bounded in-memory history, and explicit
authorized GATT service enumeration, characteristic reads and notifications.
No Bluetooth Classic discovery, advertising, characteristic payload writes,
raw HCI, impersonation, pairing automation, background services, persistent
storage or other radio acquisition. Classic remains feature detection only.

## Assumptions and acceptance

| Assumption | Status | Impact if wrong | Validation |
|---|---|---|---|
| RF observations may support location analysis | Conservative scope decision | Inappropriate neverForLocation assertion would filter results/misstate use | Request fine/coarse location with BLE scan; no assertion |
| Nearby BLE transmitters are present | Unknown | Scan may return no results | Bounded physical Pixel scan; never create fake observations |
| A specific GATT peripheral is authorized | Pending user identification | Connection/read may reach another owner's device | No automated connections to unidentified devices; explicit UI confirmation |
| Existing radio/security settings remain unchanged | Required | Security/privacy behavior changes | Only app permission prompts; report disabled radio/location |

Acceptance: build/unit/lint checks, physical passive BLE discovery with real RSSI
and source timestamps where available, bounded histories, no scanning at launch,
explicit stop/time limit and foreground cleanup, denial-safe permission handling.
GATT must enumerate/read/notify on an authorized compatible peripheral before
its hardware behavior can be called verified. Missing hardware tests remain
NOT VERIFIED even when compilation and synthetic tests pass.

Implementation decisions: separate scanner and GATT interfaces, constructor
injection, coroutine/StateFlow UI, configurable 10/20/30-second scan sessions and
low-power/balanced/low-latency modes, restart cooldown, no automatic scan loops.
Advertisements are untrusted bounded binary data; parse lengths/UUID byte order
explicitly and retain malformed/truncation indicators. RSSI unavailable sentinel
is null. Observed addresses may rotate and are not persistent physical identities.
Retain at most 256 devices and 1,024 observations in memory; no disk/export in
this phase. Scan delivery is bounded and is not lossless packet capture.
GATT uses one in-flight operation, timeout/disconnect, stale callback rejection,
API 33 value callbacks and API 31/32 compatibility paths. Notifications require
explicit CCCD configuration, not just local registration. Leaving Bluetooth or
backgrounding stops scans and closes GATT without automatic restart.

Sources reviewed:
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices
- https://developer.android.com/reference/android/bluetooth/BluetoothGatt
- https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback
- https://developer.android.com/reference/android/bluetooth/le/ScanResult

Validation observations and remaining gates follow below; hardware success is
limited to the operations actually exercised.

## Implementation inventory

| File / component | Change |
|---|---|
| `acquisition/bluetooth/BleModels.kt` | Scanner/GATT interfaces, observations with wall/source/monotonic timestamps, nullable RSSI, bounded history, characteristic/value contracts |
| `acquisition/bluetooth/AdvertisementParser.kt` | Bounded AD decoder, 16/32/128-bit service UUIDs, local name, company IDs/payloads, signed Tx power, raw bytes and malformed/truncated flags |
| `acquisition/bluetooth/AndroidBleScanner.kt` | Public LE scanner callbacks, location/permission/hardware checks, cooldown, cancellation cleanup, mid-scan Bluetooth/location state receiver |
| `acquisition/bluetooth/BluetoothViewModel.kt` | Timed structured collection, throttled UI publication, retained discovery history, explicit start/stop and selected-device connection |
| `acquisition/bluetooth/AndroidGattClient.kt` | API 37 connection settings/executor; API 31–36 handler fallback; service discovery, serialized read/CCCD operations, notifications, timeouts and close |
| `ui/BluetoothScreen.kt` | Permissions, scan duration/intensity, real observations, expandable advertisements/history, authorized connection confirmation, service/characteristic controls |
| `MainActivity.kt`, `ui/AtlasApp.kt` | Constructor injection, normal app permission flow, lifecycle/navigation cleanup, actual BLE dashboard state and last measurement |
| `permissions/RadioPermissionManager.kt`, manifest | BLE_SCAN plus coarse/fine location for RF surveying; no advertising, Internet or background permissions |
| `model/RadioCapability.kt`, `data/AndroidCapabilityRepository.kt` | Capability copy updated for implemented BLE collection; other radio acquisition remains unavailable |
| `BluetoothTest.kt` | Parser edge/fuzz cases, byte order, retention, timing, cancellation, permission policy and explicit connection-selection tests |
| `BlePhysicalInstrumentedTest.kt` | Opt-in passive Pixel observations, normal OS permission dialogs, timeout/stop/cooldown/navigation/background checks |
| `CapabilityInstrumentedTest.kt` | Exact expected permission set updated to match implemented operations; networking/advertising/background permissions remain absent |
| README, .gitignore, this report | Current phase, repeatable commands, ignored local build logs and evidence |

Main-source paths are relative to `app/src/main/java/com/example/atlas`; unit and
device tests use their corresponding test source roots. No new dependencies.
The pre-existing `.idea/vcs.xml` was left untouched and outside the checkpoint.

## Security / resource review

- No startup acquisition, listeners, root, system changes, advertising, device
  impersonation or automatic pairing/connection. GATT only after a selected
  observed connectable address and explicit UI authorization confirmation.
- Notification subscriptions write the standard CCCD only, explicitly initiated
  by the user. No arbitrary characteristic value writes are exposed.
- GATT callbacks use the main executor/handler, one outstanding ATT operation,
  30-second connection/discovery and 10-second operation limits. Timeout closes
  the connection because continuing after an unknown completion is unsafe.
- Old-connection callbacks are rejected by object identity. Per-connection
  services/characteristics are capped at 128/512 and GATT value history at 128
  values of at most 512 bytes. These bounds have not been stress-tested on a
  real GATT peripheral.
- Scan callback buffer is 64 with drop-oldest overflow; application history is
  1,024 events / 256 addresses. Counts describe observations delivered to the
  application, not RF packets on the air or a lossless callback stream. UI
  snapshots publish at most every 200ms plus final cleanup. Raw ads cap at 1,650
  bytes. Device rows retain stable first-discovery ordering while RSSI updates.
- Disabling Bluetooth/location closes collection on a foreground state broadcast.
  The receiver re-reads trusted services and ignores broadcast-supplied state;
  it is exported only for privileged system Bluetooth broadcasts and unregistered
  with scan cleanup. No manifest receiver or background service is added.
- If Android revokes permission during cleanup, the app cannot force privileged
  stop/close calls. It stops accepting observations and relies on OS revocation
  cleanup; GATT cleanup denial remains visible. This is a platform boundary, not
  proof of successful app-side cleanup under revocation.
- BLE addresses and payloads remain private in app memory, never written to
  application logs or exported. Validation evidence contains only aggregate
  counts/ranges; full device test Logcat remains generated under ignored build
  output. No inference of ownership from proximity, names or pairing records.

## Verification

**High confidence:** public BLE acquisition and the tested foreground cleanup
paths work on this physical Pixel 10 Pro XL (Android 17/API 37). This confidence
does not extend to untested GATT operations, other Android versions or long-run
reliability. **Unknown:** an authorized GATT peripheral has not been identified
by the operator; none of the nearby observed devices was connected to.

Commands (Android Studio bundled JDK 25):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --console=plain
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.atlas.BlePhysicalInstrumentedTest' '-Pandroid.testInstrumentationRunnerArguments.atlas.bleHardware=true' --console=plain
./gradlew.bat :app:installDebug --console=plain
```

The final regular regression command passed with 19 unit tests (12 Bluetooth,
6 foundation, 1 retained template example), 5 device regression tests passing,
and the opt-in hardware test explicitly skipped in that regular invocation.
It was executed separately with the flag above. Lint: **0 errors, 15 warnings**;
no protective check was suppressed. Warnings remain dependency-update notices
and template unused resources / redundant API guard. Earlier GATT deprecation
was removed from the API 37 path; suppressions are limited to documented
deprecated compatibility API calls for API 31–36 and older callback/value paths.

The physical test requests Nearby devices and precise foreground location through
the normal OS dialogs, not shell grants. It verifies that granting permission
alone never starts collection. Bluetooth and location services were already on;
no system/security settings were changed. The test never connects to a peripheral.

**Final APK physical run:** 45 actual observations from 9 reported addresses in
the initial 10-second session, RSSI **-94 to -86 dBm**, all 45 source-timestamped,
zero parser-malformed flags. The opt-in hardware test passed in 41.471 seconds
(Gradle task invocation: BUILD SUCCESSFUL in 50 seconds), including all cleanup
checks below. These are observed addresses, not proof of 9 distinct physical
devices: address randomization can change identity during a survey.

Two development sessions before final UI review:
- First: 59 observations, 7 retained addresses, RSSI -95 to -85 dBm, all 59 with
  source timestamps, zero parser-malformed flags.
- Second: 22 observations, 7 retained addresses, RSSI -95 to -85 dBm, all 22 with
  source timestamps, zero parser-malformed flags.

The differing observation counts come from separate scan sessions and are not a
throughput or accuracy comparison. These sessions passed timed expiry, explicit
stop, restart cooldown, leaving Bluetooth, and background/resume checks. After
each stop/navigation/background operation the test observed no increase in the
application's received-observation count for 1,500ms. Resume stayed idle. This
demonstrates callback/store cleanup over that bounded window; it is not a full
controller/OS internal-state proof or a soak test.

### nRF Connect comparison

The existing `no.nordicsemi.android.mcp` application was opened for a passive
comparison. It started scanning on launch. UI Automator could not dump an idle
hierarchy during its active scan, so a screenshot was inspected, then the visible
Stop Scanning action was used. The subsequent UI hierarchy confirmed the Scan
action was available again. No Connect, Bonded or Advertiser action was used.

At stop, nRF Connect retained 9 rows, with displayed RSSI -99 to -87 dBm. The
earlier Atlas session retained 7 addresses at -95 to -85 dBm; the final Atlas run
later retained 9 addresses at -94 to -86 dBm. These were separate,
non-synchronized windows with changing advertisements/rotating addresses. This
is a qualitative public-API sanity check, **not** per-device equality, calibration,
proof of manufacturer/UUID decoding parity, or a superiority/completeness claim.
Raw nRF UI snapshots remain under ignored `app/build`; the report contains only
aggregate comparison data in `evidence/phase2/nrf-comparison.json`.

## Failures and corrections

- Initial code compiled but lint failed on six GATT permission call sites. Broad
  Exception handling was replaced with explicit SecurityException handling and
  permission contracts on compatibility helpers. No lint baseline/suppression
  was used to hide the errors.
- API 37 deprecated the handler-based connectGatt overload. Added the new
  BluetoothGattConnectionSettings + main executor path, retaining the guarded
  older path. API 31/32 callbacks now distinguish missing values from legitimate
  empty byte values.
- Source review found that a navigation-disposal closure could read the changed
  page value; it now captures a stable Bluetooth-visible value. Physical
  navigation cleanup subsequently passed.
- Device rows were stabilized by first-observed time so RSSI updates do not
  reorder existing rows. Broadcasts close collection if Bluetooth/location is
  disabled, but hardware toggling was deliberately not tested.
- nRF's first implicit launch attempt did not resolve; the package manager's
  resolved MainActivity was launched explicitly. The active-scan idle-dump
  failure was handled using a screenshot and a verified stop action.
- The existing Gradle/protobuf JDK Unsafe deprecation warning remains visible.

## Residual gates

**NOT VERIFIED:** authorized GATT connection, service/characteristic enumeration,
reads, CCCD writes/notifications/indications, unreachable-peripheral timeout and
disconnect behavior against hardware. These are implemented and compiled, but
neither compilation nor a fake GATT interface establishes physical success.

Also NOT VERIFIED: API 31–36 runtime execution, disabling Bluetooth/location or
revoking permissions during acquisition, missing hardware, crowded-environment
stress/battery drain, TalkBack/font-scale/landscape checks, loss rate, long-run
stability and release-signed optimization. Buffer retention/cancellation/parser
tests are synthetic unit evidence; they do not prove all hardware edge cases.

Bluetooth Classic active discovery/profiles, raw HCI, advertising, characteristic
payload writes, cloud/remote services, other radio collectors and Room/SAF
persistence are out of this phase. Phase 2 BLE histories are volatile: Android's
test runner removes test installations and final reinstall starts with empty
history and fresh app permission state. The final app is left installed and idle.

The Bluetooth module must not be called fully hardware-validated until an owned
or explicitly authorized GATT peripheral has passed those remaining tests.

## Evidence and delivery

All paths below are relative to the project root:
- `app/build/outputs/apk/debug/app-debug.apk`: tested development/debug APK.
  SHA-256: `6CE7D5933B6BE602EE49A4189B416C45F47BC36F39DC7732390F81270052577C`.
- `docs/evidence/phase2/bluetooth-unit-tests.xml`: 12 focused Bluetooth tests.
- `docs/evidence/phase2/regression-device.xml`: 5 passed / 1 explicitly opt-in skip.
- `docs/evidence/phase2/hardware-device.xml`: separately executed physical test,
  passed with no skips; hardware serial redacted.
- `docs/evidence/phase2/ble-observations.txt`: final aggregate observations and
  explicit stop/cooldown/navigation/background evidence.
- `docs/evidence/phase2/first-ble-observations.txt`, `first-hardware-device.xml`:
  earlier development run, retained separately from final evidence.
- `docs/evidence/phase2/lint-results-debug.txt`: full lint warnings.
- `docs/evidence/phase2/nrf-comparison.json`: aggregate nRF comparison only.
- `docs/evidence/phase2/pixel-launch.txt`, `pixel-bluetooth-idle.png`: final
  installation/launch UI evidence, with collection inactive.
- `phase2-*.log`: local ignored build/test/install logs, including initial lint
  failure. Generated reports are under `app/build/reports` and actual device
  Logcat under `app/build/outputs/androidTest-results`; these are overwritten by
  subsequent runs, so the selected results above are retained under docs.
- `app/build/phase2-app-logcat.txt`: bounded final app-process Logcat sample.
  Inspected 40 lines after final launch: zero error/warning lines. Final launch
  returned `Status: ok`, cold launch, 412ms (one observation, not a benchmark).
  The installed application was confirmed running and its idle Bluetooth screen
  visually inspected. `git diff --check` passed.

Source edits remain reviewable in the working tree against Phase 1 checkpoint
`185c3c6`; no remote push. No unrelated project or phone security setting changed.
