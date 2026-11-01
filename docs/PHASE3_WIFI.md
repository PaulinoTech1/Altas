# Phase 3 Wi-Fi surveying contract

Phase 2 preserved locally in Git commit `2d6d6c9`. Scope is permitted Wi-Fi
surveying and connection diagnostics in the existing app, not changes to Wi-Fi
connections, hardware settings or other radio collectors. Existing BLE code and
evidence are preserved. No new runtime/service dependencies are planned.

## Assumptions / decision boundaries

| Assumption | Status | Impact if wrong | Validation |
|---|---|---|---|
| User permits local foreground Wi-Fi survey | Explicit Phase 3 request | Privacy / scope | No acquisition before module action; no background service |
| Wi-Fi/location services remain enabled | To be checked, never changed automatically | API failure / no results | Preflight reads; display disabled state |
| Visible APs and a connected Wi-Fi network exist | Unverified | Hardware acceptance limited | Physical Pixel scan and connection callback |
| A scan broadcast belongs to this app's request | Not assumed | False attribution of freshness | Android broadcasts are shared; per-result source timestamps retained |
| Scan request rejection proves throttling | Not assumed | Misdiagnosis | Label rejection as throttling/OS state possible; rate guard tested separately |

Goal: real AP RSSI/frequency/channel/security/channel-width observations, current
Wi-Fi connection properties, bounded signal history and a survey dashboard.
No monitor mode, capture/injection, SSID/BSSID unredaction bypass, automated
association, Internet probing or persistent storage/export. No zero defaults for
missing data. SSID/BSSID can reveal location and remain in app memory by default.

Acceptance: build/unit/lint/device checks, actual Pixel AP data with source age,
correct cached/unavailable/failed labels, precise-location permissions requested
only on activation, stop/navigation/background cleanup, and honest scan request
rejection/throttle handling. Unsupported and unexecuted paths remain NOT VERIFIED.

Permissions: reuse COARSE + FINE_LOCATION (request together, precise required),
ACCESS_WIFI_STATE and ACCESS_NETWORK_STATE. Add CHANGE_WIFI_STATE only for
WifiManager.startScan. NEARBY_WIFI_DEVICES is not a replacement for location on
startScan/getScanResults; omit it unless an actual chosen API requires it. No
INTERNET, background location or neverForLocation assertion.

Design: independent WifiSurveyRepository and ViewModel, callback-driven foreground
session with explicit stop and a 60-second limit; at most one explicit startScan
request per session, no auto-poll/retry. App rate guard allows at most four scan
requests per rolling two minutes, with 30-second spacing even when OS throttling
is disabled. OS policies may be stricter; no attempts to bypass them. Stop means
unregister/cancel app collection, not abort an OS scan already requested: the
public Wi-Fi API does not offer cancellation of that hardware operation.

Cache snapshots are explicitly labeled cached even when young. Updated broadcasts
do not make every row fresh: each AP's boot-relative timestamp determines age.
Duplicated cached observations do not create synthetic new signal samples.
Current connection uses location-aware NetworkCallback transportInfo (API 31+),
not deprecated WifiManager.connectionInfo. Signal history is bounded and shows
retained snapshots, not live or lossless RF capture. Root/hidden APIs are excluded.

Sources reviewed:
- https://developer.android.com/develop/connectivity/wifi/wifi-scan
- https://developer.android.com/develop/connectivity/wifi/wifi-permissions
- https://developer.android.com/reference/android/net/wifi/ScanResult
- https://developer.android.com/reference/android/net/wifi/WifiInfo
- https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
- Installed API 37 SDK public sources (ScanResult, WifiInfo, ConnectivityManager).

Implementation and executed validation results follow below. Hardware claims are
limited to the physical operations actually observed.

## Implementation inventory

Main source paths below are relative to `app/src/main/java/com/example/atlas`.

| File / component | Implemented behavior |
|---|---|
| `acquisition/wifi/WifiModels.kt` | Nullable metrics/identifiers, source timestamps and delivery classification, per-row age/freshness, channel/band/width normalization, rolling request guard and bounded history |
| `acquisition/wifi/AndroidWifiSurveyRepository.kt` | Public WifiManager requests/cache/results broadcasts, precise-location and hardware checks, throttling policy query, location-aware ConnectivityManager callbacks, receiver/callback cleanup |
| `acquisition/wifi/WifiViewModel.kt` | Explicit 60-second sessions, manual requests, failure propagation, retained snapshots, connection-loss handling and cancellation |
| `ui/WifiScreen.kt` | Survey controls, permissions, OS/app request limits, per-band/channel AP counts, connection properties, AP details and timestamped RSSI chart/history |
| `ui/AtlasApp.kt` | Wi-Fi navigation, actual collection state/last received result, foreground UI-only age clock and page-specific scroll state |
| `MainActivity.kt`, `permissions/RadioPermissionManager.kt` | Simple constructor injection, contextual location request, rechecked grants, onStop cancellation |
| Manifest, `data/AndroidCapabilityRepository.kt` | Only CHANGE_WIFI_STATE added; capability text updated to implemented surveying |
| `WifiTest.kt` | Twelve synthetic normalization/freshness/rate/history/cancellation/error tests |
| `WifiPhysicalInstrumentedTest.kt` | Opt-in actual APs, public channel-converter comparison, connection snapshot, spaced scan history, cooldown and stop/navigation/background checks |
| `DashboardInstrumentedTest.kt`, `CapabilityInstrumentedTest.kt` | Real location denial keeps collection disabled; manifest permission set updated for the actual scan call |
| `.gitignore`, README, this report, `docs/evidence/phase3/*` | Current phase, repeatable commands, selected redacted evidence and ignored local logs |

No new dependencies, background service, manifest receiver, network listener,
INTERNET permission, protected identifiers or radio-setting changes. Existing
Bluetooth acquisition code is unchanged. The unrelated `.idea/vcs.xml` remains
untouched and outside the checkpoint.

## Data and resource decisions

- At most 256 AP rows, 8 Wi-Fi connection snapshots and 1,024 source samples.
  History tracks at most 256 BSSIDs and rejects repeated or older source times.
  Redacted BSSIDs or invalid/missing timestamps do not create fabricated grouped
  history. All retained data is volatile app memory; clearing is explicit.
- Receiver work and initial acquisition execute off the UI thread. Callbacks
  use a bounded 32-event stream with drop-oldest overflow; this is not lossless
  packet capture. Source age labels refresh once per second only while the
  Wi-Fi page is visible and the Activity is STARTED; they do not poll hardware.
- A zero/invalid frequency or link rate is unavailable. Android's invalid RSSI
  sentinel and redacted placeholder BSSIDs become null. A real value is not
  synthesized for an unavailable metric. SSID control/format characters are
  removed for display; no HTML/script rendering is involved.
- Channel math follows the installed SDK's public ScanResult mapping and rejects
  off-grid/unknown frequencies. Special 2.4 GHz channel 14 and 6 GHz channel 2
  are handled. This maps reported frequencies to channel numbers; it does not
  assert which channels are legal or supported in a country. AP counts are
  explicitly not channel utilization or RF congestion measurements.
- Recent means a valid, at-most-30-second-old source time, after session start,
  delivered in an updated platform broadcast. Other cache data remains cached;
  older rows are stale, invalid/future timestamps have unknown age. The dashboard
  reports last result **received**, not an invented wall-clock measurement time.
- WifiInfo arrives through location-aware NetworkCallback transportInfo, never
  deprecated WifiManager.connectionInfo. SSID/BSSID redaction and absent
  callbacks remain visible. Link rates are reported properties, not speed tests;
  Android validation is a platform capability flag, not an Atlas network probe.
- Stop unhooks application collection and rejects future flow delivery. It does
  not claim cancellation of an OS scan that is already in progress. A permission
  revocation or disabled-radio/location broadcast fails collection visibly.
  Hardware toggling/revocation testing is separate from source-level handling.

## Verification and remaining gates

**High confidence within the tested scope:** this native implementation returns
real public-API AP measurements and a Wi-Fi connection snapshot on the connected
Pixel 10 Pro XL, Android 17/API 37. Confidence does not extend to unavailable
bands, every carrier/OEM, other OS versions, long-run stability or RF calibration.

Repeatable commands, using the installed Android Studio JDK 25:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --console=plain
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.atlas.WifiPhysicalInstrumentedTest' '-Pandroid.testInstrumentationRunnerArguments.atlas.wifiHardware=true' --console=plain
./gradlew.bat :app:installDebug --console=plain
```

Final regular regression: **BUILD SUCCESSFUL**, 31 unit tests passing (12 Wi-Fi,
12 Bluetooth, 6 foundation and 1 retained template example). Device regression:
**6 passed, 2 opt-in hardware tests skipped** in the ordinary invocation. The
Wi-Fi physical test is separately executed with the explicit flag above; the BLE
hardware test is not rerun in Phase 3. Lint: **0 errors, 15 warnings**, with no
permission/security check suppressed. Existing warnings are dependency update
notices and template resources/API guard. `startScan` deprecation suppression is
documented at its compatibility call site; it is the available public request
API for this permitted full survey, not a hidden-API workaround.

The first physical development run passed:
- Initial updated results: **24 AP rows**, **2.4 and 5 GHz**, **-89 to -49 dBm**,
  24 recent source-timestamped rows, one connection snapshot with actual RSSI and
  frequency. SSID/BSSID access was confirmed for at least one AP without logging
  their values. All available derived channels matched the public SDK converter.
- Two accepted, explicitly initiated scan requests spaced by more than 30 seconds;
  **79 retained source samples**, **24 BSSIDs with multiple actual samples**,
  and the selected signal chart rendered. Sample count includes the explicitly
  classified initial cache and updated observations; it is not a packet count.
- Android's public throttling getter reported **enabled**. An immediate repeat
  request was rejected by Atlas's own cooldown. OS throttle exhaustion was not
  forced, so that branch is not claimed physically verified.
- Explicit stop, navigation away, background and resume passed. The test checked
  that the scan broadcast count stayed unchanged for 1,500ms after each stop and
  resume did not restart collection. The final test also checks retained
  connection snapshots for changes in those windows.

**Final physical run passed:** the initial platform update delivered **29 AP
rows**, all 29 recent, RSSI **-89 to -51 dBm**, across **2.4 and 5 GHz**, and one
connection snapshot. Two spaced accepted requests produced **88 retained source
samples**, with **26 BSSIDs having multiple actual samples**; the selected RSSI
chart rendered. Android throttling was reported enabled. Manual stop, navigation,
background and resume checks passed, including unchanged connection snapshots
and scan-result counts in the 1,500ms quiet windows. The physical test passed in
47.905 seconds (Gradle invocation BUILD SUCCESSFUL in 57 seconds), no skips.
These are reported AP rows/BSSIDs, not proof of that many independent routers.

Actual SSID/BSSID, AP capabilities, width and current connection fields are
displayed when supplied. Successful RSSI/frequency/identifier/timestamp access
and channel conversion do not establish all security modes, channel widths,
bands or connection-field availability. SSID/BSSID remain nullable and can still
be redacted by Android despite a permission grant.

### Failure and correction record

The first ordinary device regression failed to find Back to dashboard after a
module transition. Shared lazy-list scroll state could retain an offset that
recycled the page header. Page-specific list state now opens each page at the
top; the original navigation assertions subsequently passed. No tests were
deleted or relaxed to hide the failure.

Review also found that the bounded BSSID lookup index could forget a source
timestamp while the corresponding event remained in history. The store now
checks retained history when that index misses. A test with deliberate index
eviction proves that repeated/older cache entries do not become new samples.

The existing Gradle/protobuf JDK Unsafe deprecation warning remains visible.
No build failure, lint error or physical permission failure remains in the
passing runs. Permission denial itself was exercised deliberately through the
normal OS location dialog, and left the survey Start button disabled.

### NOT VERIFIED / limitations

- Android's actual throttle-exhaustion/rejected-startScan branch was not forced.
  That status is handled without attributing a cause; the app guard and its
  rolling budget are tested separately. No scan-throttling setting changed.
- Disabled Wi-Fi/location, mid-session permission revocation, missing hardware,
  complete result redaction, and real empty-cache/no-AP environments were not
  induced on the phone. Code handles those states; synthetic tests cover
  redacted/null normalization and acquisition failures. This is not complete
  physical coverage of disabled-radio or permission-revocation behavior.
- The 60-second expiry/cancellation is tested with virtual coroutine time;
  physical sessions were stopped explicitly before expiry. Stop cannot cancel
  OS radio work already submitted. Bounded quiet windows do not prove all
  controller-internal state, durability or long-run reliability.
- 6/60 GHz, 320 MHz, mixed-width/enterprise/security variants, dual Wi-Fi STA,
  roaming/VPN changes, high-density stress, battery usage, older API execution,
  TalkBack/large fonts/landscape and optimized release builds remain unverified.
- No packet capture, injection, channel-utilization measurement, throughput test,
  association change, raw radio access, persistence or exports. Room/SAF remain
  Phase 6. GATT physical verification remains a separate pending Phase 2 gate.

History is volatile app memory. Android's connected-test runner removes test
installations; final reinstall starts idle with empty history and fresh app
permissions. The final APK is left installed, with no background collection.

## Evidence and delivery

Paths relative to this project:
- `app/build/outputs/apk/debug/app-debug.apk`: tested development/debug APK.
  SHA-256: `116DDEA4A4BDBCDC2C21DA38C6E69B97CCB1D6650FDC7EA5553873F920938F57`.
- `docs/evidence/phase3/wifi-unit-tests.xml`: twelve focused Wi-Fi unit tests.
- `docs/evidence/phase3/regression-device.xml`: six passing ordinary device tests,
  two explicit opt-in skips, serial redacted.
- `docs/evidence/phase3/hardware-device.xml`: separately executed final physical
  test, passed without skips, serial redacted.
- `docs/evidence/phase3/wifi-observations.txt`: final aggregate measurements and
  rate/cleanup outcomes; SSID/BSSID values are not included.
- `docs/evidence/phase3/first-hardware-device.xml`, `first-wifi-observations.txt`:
  earlier development-run evidence, kept distinct from final results.
- `docs/evidence/phase3/lint-results-debug.txt`: full final lint warnings.
- `docs/evidence/phase3/pixel-launch.txt`, `pixel-wifi-idle.png`: final launch and
  idle Wi-Fi UI evidence, without nearby network identifiers.
- `phase3-*.log`: ignored local build/test/install logs, retaining the initial
  navigation failure and final passing invocations. Generated HTML reports and
  full device-test Logcat are under ignored `app/build`; selected results above
  survive subsequent Gradle overwrites.
- `app/build/phase3-app-logcat.txt`: final app-process Logcat sample (40 lines,
  no error/warning lines observed). Final cold launch returned `Status: ok`,
  430ms; this single observation is not a launch benchmark. The app process was
  confirmed running and the inactive Wi-Fi page visually inspected.

All Phase 3 edits remain reviewable against the Phase 2 checkpoint `2d6d6c9` in
the working tree. No remote push and no unrelated project changes.
