# Phase 4 cellular contract

Phase 3 preserved in local commit `e29827e`. Goal: explicit foreground LTE/NR
diagnostics, reported cells/neighbors, service/registration and per-subscription
callbacks using public APIs. No radio/network setting changes, calls, SMS,
protected subscriber identifiers, modem commands, background service or Internet.

| Assumption / boundary | Status | Consequence / validation |
|---|---|---|
| Active subscriptions and LTE/NR reports exist | Unknown | Physical test must report actual available data; do not fabricate NR |
| Device-wide cached cells identify a specific SIM | False; SDK implementation is device-wide | Label cache separately; never assign its rows to a SIM |
| A carrier 5G icon implies NR signal values | False | Separate TelephonyDisplayInfo hints from measured LTE/NR values |
| User permits phone status and location access for cellular diagnostics | Explicit Phase 4 scope; runtime grant still required | Request only on module action, fail closed on denial |
| LTE RSSNR is always in tenths of dB | False for the selected public API | Installed SDK documents getRssnr as dB; do not divide by ten |

Add READ_PHONE_STATE for CellInfoListener and active-subscription access; reuse
coarse+fine location, requiring precise location and enabled location services.
Do not add READ_PHONE_NUMBERS, READ_PRECISE_PHONE_STATE, privileged permissions,
INTERNET or background location. Only subscription ID and slot index are copied
from SubscriptionInfo; no phone number, ICCID, IMSI or subscriber credential API.

Implement separate repository, typed records, ViewModel and Compose screen.
60-second explicit sessions, bounded rows/history, no polling/automatic retries,
one initial cell update plus manual requests spaced at least 30 seconds. Public
updates may return cached data, errors or nothing; 15-second request timeout.
Stop cancels app delivery and unregisters listeners; public cell-update requests
already submitted cannot be cancelled at the modem. Subscription changes stop
the session for explicit review/restart; never silently switch SIM context.

Cells retain registration flag, reported primary/secondary connection status,
wall receipt and source boot-relative timestamps, source API and unavailable
metrics as null. SignalStrength callbacks are subscription signal reports,
not values attributed to an invented serving cell. CellInfo rows do not carry
subscription IDs: callback/request context is reported, not per-row ownership.
Missing neighbors do not imply no neighboring cells exist. LTE/NR reports remain
separate in dual-connectivity conditions. No inference of NSA/SA from an icon.

Acceptance: build/unit/lint/device checks, normal runtime permission prompts,
real supported metrics on the Pixel, safe stop/navigation/background behavior,
explicit cache/sentinel/error handling, honest LTE/NR/dual-SIM coverage limits.
No capability is hardware-verified until the physical test actually observes it.

Sources: installed API 37 SDK public TelephonyManager, TelephonyCallback, CellInfo,
CellSignalStrengthLte/Nr, SubscriptionManager, ServiceState and NetworkRegistrationInfo;
https://developer.android.com/reference/android/telephony/TelephonyManager ;
https://developer.android.com/reference/android/telephony/TelephonyCallback ;
https://developer.android.com/reference/android/telephony/CellSignalStrengthNr ;
https://developer.android.com/reference/android/telephony/SubscriptionManager ;
https://developer.android.com/reference/android/telephony/NetworkRegistrationInfo .

## Implementation inventory

Main-source paths are relative to `app/src/main/java/com/example/atlas`:

| File / component | Change |
|---|---|
| `acquisition/cellular/CellularModels.kt` | Typed cells/signals/registration, nullable metrics and identifiers, cache/receipt/source provenance, request outcomes and cooldown |
| `acquisition/cellular/CellularMapper.kt` | LTE/NR public getters, sentinel/range handling, cell role/identity, subscription signal and public service registration mapping |
| `acquisition/cellular/AndroidCellularRepository.kt` | Permission/hardware checks, minimal active-slot extraction, selected-subscription callbacks, explicitly device-wide cache, timed callback update, subscription/location change and cleanup |
| `acquisition/cellular/CellularViewModel.kt` | Structured 60-second sessions, explicit refresh/selection, pending/cancelled distinction, visible errors, 64 cell/128 signal report bounds |
| `ui/CellularScreen.kt` | Contextual permissions, slots, separate carrier display and LTE/NR metrics, registration, reported cells/roles, freshness and retained history |
| `MainActivity.kt`, `ui/AtlasApp.kt` | Simple injection, permission refresh, lifecycle/navigation cleanup, acquisition permission status and last received report on dashboard |
| `permissions/RadioPermissionManager.kt`, manifest | Only READ_PHONE_STATE added, alongside existing foreground location permissions |
| `data/AndroidCapabilityRepository.kt` | Capability text points to implemented collection without inventing modem-power state |
| `CellularTest.kt` | Thirteen synthetic unit tests covering metric units/sentinels/ranges, context, cache, bounds, cooldown, cancellation, failures and explicit SIM selection |
| `CellularPhysicalInstrumentedTest.kt` | Opt-in real available metrics/registration/cells, normal OS grants, cooldown and stop/navigation/background checks |
| `DashboardInstrumentedTest.kt`, `CapabilityInstrumentedTest.kt` | Explicit phone/location denial remains disabled; exact permitted manifest set; OS deny-variant handling |
| README, .gitignore, this report, evidence | Phase instructions, repeatable commands, ignored local logs and selected redacted artifacts |

No new dependencies, JNI, settings change, hidden API, manifest receiver,
background service, listener socket, Internet or external runtime. Existing
Bluetooth/Wi-Fi acquisition source is preserved. `.idea/vcs.xml` remains untouched.

## Important contracts and limits

- Cache scope is never inferred from createForSubscriptionId: the SDK's
  getAllCellInfo implementation is device-wide. Cached batches have no SIM ID.
  Callback/request batches name their requested subscription context while
  explicitly disclosing that CellInfo does not identify the owner of every row.
- Only local subscription ID and slot index are copied. No phone number, ICCID,
  IMSI, IMEI, SIM serial, carrier credentials or full SubscriptionInfo/ServiceState
  dumps are stored. MCC/MNC and cell identities are permitted location-sensitive
  network data, kept in app memory and omitted from evidence logs.
- LTE CI and NR NCI have separate int/long sentinel handling. PCI zero and SINR
  zero remain legitimate values. Unavailable/out-of-range metrics stay null.
  LTE RSSNR is public-API dB, not divided by ten. The Android 12/API 31 source
  was also checked: its constructor normalizes RSSNR to -20..30. Primary source:
  https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/telephony/java/android/telephony/CellSignalStrengthLte.java .
- At most eight subscription choices, 64 cells per batch, eight signal strengths
  and eight registration rows are normalized. Histories retain at most 64 cell
  batches / 128 signal reports; delivery flow is bounded to 32 events. Identical
  cell rows/context are deduplicated. Report counts are not packet or tower counts.
- Signal history records preserve their subscription context across selection
  changes. They are never mapped to a cell identity by guesswork. A 5G carrier
  display hint is shown independently from NR values and does not prove NSA/SA
  mode or active dual connectivity.
- Registration uses API 34+ network booleans with API 31–33 compatibility getters
  explicitly noted as potentially affected by carrier overrides. Voice service
  state is labeled voice, not substituted for packet-service registration.
- All callback types are unregistered on cleanup; finally blocks also attempt
  the remaining cleanup if one removal throws. Subscription changes stop the
  session for review. Source ages are boot-relative; receipt time is separate.
  A successful update can still contain cached cells. No update is treated as
  fresh merely because a response arrived.
- A pending request becomes CANCELLED when Atlas stops waiting. This does not
  claim that Android/modem work was aborted. Timeouts/errors and empty lists are
  distinct outcomes; no error is converted to zero or a successful empty result.

## Executed validation

**High confidence for the tested NR paths on this physical Pixel 10 Pro XL,
Android 17/API 37.** LTE measurements and dual-SIM physical behavior are not
verified by an NR-only, one-active-subscription test. The presence of a model
class, a passing synthetic test or a carrier display hint is not hardware proof.

Commands (installed Android Studio JDK 25):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --console=plain
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.atlas.CellularPhysicalInstrumentedTest' '-Pandroid.testInstrumentationRunnerArguments.atlas.cellularHardware=true' --console=plain
./gradlew.bat :app:installDebug --console=plain
```

Final regular regression passed: **44 unit tests** (13 cellular, 12 Wi-Fi,
12 Bluetooth, 6 foundation, 1 retained template example), **7 device regression
tests passed** and **3 acquisition hardware tests explicitly skipped** in the
ordinary invocation. Cellular hardware acquisition was executed separately with
its flag; BLE/Wi-Fi hardware acquisition was not rerun in this phase. Lint:
**0 errors, 15 warnings**, no protective check disabled. Remaining warnings are
existing dependency-update notices and unused/redundant template items.

Normal OS Phone and precise foreground Location prompts were used. No shell
grant, phone-mode change, network preference, data-toggle, SIM provisioning,
Advanced Protection or other security-setting change. Permission grant alone
was verified not to start collection. One active subscription was reported.

An initial physical run returned 9 cells (1 registered, 8 nonregistered), base
technology NR, voice in service and 3 registration rows. The update request was
DELIVERED. Across that latest cell batch plus subscription signal report:

| Metric | Available field values | Min | Max | Units |
|---|---:|---:|---:|---|
| LTE RSRP / RSRQ / RSSNR | 0 each | Unavailable | Unavailable | dBm / dB / dB |
| NR SS-RSRP | 10 | -121 | -100 | dBm |
| NR SS-RSRQ | 10 | -17 | -11 | dB |
| NR SS-SINR | 1 | 10 | 10 | dB |

The final evidence run returned **6 cells (1 registered, 5 nonregistered)**, NR
base technology, voice in service and 3 registration rows, with update DELIVERED.
All 6 rows had a PCI and a source timestamp; a full cell ID was available on
1 row. There was 1 reported primary serving role and no secondary serving role.

| Final metric | Available field values | Min | Max | Units |
|---|---:|---:|---:|---|
| LTE RSRP / RSRQ / RSSNR | 0 each | Unavailable | Unavailable | dBm / dB / dB |
| NR SS-RSRP | 7 | -117 | -101 | dBm |
| NR SS-RSRQ | 7 | -16 | -11 | dB |
| NR SS-SINR | 1 | 3 | 3 | dB |

Counts combine cell rows and a separate subscription SignalStrength report;
they are not counts of unique physical sites or independent RF observations,
and do not prove SINR exists on every cell. The final physical test passed in
11.029 seconds (Gradle invocation BUILD SUCCESSFUL in 20 seconds), no skips.
Report windows differed (an intermediate run also returned 12 rows); this is
not a performance or RF calibration comparison or a stable coverage claim.

The physical test passed explicit refresh cooldown, stop, navigation cleanup,
background cleanup and no-auto-resume assertions. After each stop, the accepted
repository-event count stayed unchanged for 1,500ms. This is bounded app-delivery
evidence, not a proof of all modem/controller internal state or a soak test.
Request response and CellInfo source timestamps remain separate: DELIVERED does
not establish a new modem measurement. No raw identity values are logged.

## Failures and corrections

- Initial compilation passed. Lint identified two raw integer switches for
  domain/transport; public constants replaced the literals. Recognition of an
  unsupported legacy CDMA class was changed to an explicit Other class label,
  avoiding a deprecated class dependency without claiming CDMA normalization.
- The first denial regression expected only permission_deny_button. Android
  used permission_deny_and_dont_ask_again_button as well. The test now targets
  actual deny controls, still requires a successful denial action and verifies
  Start remains disabled. The exact resource IDs are retained as evidence.
- A later denial run hit StaleObjectException during OS dialog transition. The
  test now reacquires within four bounded attempts, logs the transition and
  asserts no unresolved deny dialog remains. It does not skip the test, grant
  permission, or weaken the denied-access assertion.
- Source review corrected signal-history context across slot selection and
  distinguished cancellation of an app wait from an invented modem timeout.
- The existing Gradle/protobuf JDK Unsafe deprecation warning remains visible.

## Residual gates — NOT VERIFIED

- LTE RSRP/RSRQ/RSSNR acquisition on hardware: no LTE values were supplied by
  the current NR network. The LTE decoder's units/ranges/sentinels have synthetic
  tests and public-source review, not physical LTE evidence.
- Dual active subscriptions, real SIM hot-swap/profile changes, NSA dual
  connectivity and cross-slot attribution. Selection/cancellation/context have
  synthetic tests; one active subscription cannot validate dual-SIM hardware.
- Physical permission revocation, disabled location, airplane/radio power-off,
  no-SIM/no-service, real API denial, missing hardware, modem error/timeout,
  forced rate-limit exhaustion and fully redacted identity scenarios were not
  induced. Handling exists; coverage is source/unit evidence as applicable.
- Older API execution, crowded-cell stress, battery impact, long-run reliability,
  TalkBack/font-scale/landscape and optimized release builds remain unverified.
  The 60-second session expiry is tested using virtual coroutine time; actual
  physical sessions were stopped explicitly before that limit.
- NR metric availability is network/OEM dependent. A nonregistered row is not
  a guaranteed neighbor relation or a complete neighbor survey. No SA/NSA mode
  claim is made solely from the NR technology or carrier display.

No raw modem/HCI, subscriber credentials, carrier privilege, root, association
change, packet capture, telephony control, persistent database or export was
introduced. Room/SAF remain Phase 6; GATT physical validation remains a separate
Phase 2 gate. Test-runner reinstall clears volatile history and app grants; the
final installation starts idle and requests app permissions again on action.

## Retest with Wi-Fi off and cellular as default data transport

At the operator's request, repeated the physical test with an additional required
transport check. Wi-Fi was already off, mobile data already enabled, and location
enabled when inspected; no setting was changed or restored by this retest.

```powershell
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.atlas.CellularPhysicalInstrumentedTest' '-Pandroid.testInstrumentationRunnerArguments.atlas.cellularHardware=true' '-Pandroid.testInstrumentationRunnerArguments.atlas.requireCellularDefault=true' --console=plain
```

Before collection and again when the metric snapshot was taken, the test verified:
- WifiManager.isWifiEnabled = false.
- Default network TRANSPORT_CELLULAR = true, TRANSPORT_WIFI = false, VPN = false.
- Android NET_CAPABILITY_INTERNET = true and NET_CAPABILITY_VALIDATED = true.

This closes the previous evidence gap around which data transport was active.
It is Android's reported route and validation, not an Atlas socket/HTTP traffic
test or throughput benchmark. No INTERNET permission or network probe was added.
Wi-Fi being the default route does not prevent the earlier observed NR radio
measurements; RF reporting and default data routing are different evidence.

The opt-in physical retest passed with no skips. One active subscription, NR base
technology, voice in service, three registration rows and a DELIVERED cell update
were reported. The latest batch contained **3 cells (1 registered, 2 nonregistered)**.
All three had a PCI and source timestamp; one full cell ID was available.

| Metric | Available field values | Min | Max | Units |
|---|---:|---:|---:|---|
| LTE RSRP / RSRQ / RSSNR | 0 each | Unavailable | Unavailable | dBm / dB / dB |
| NR SS-RSRP | 4 | -110 | -103 | dBm |
| NR SS-RSRQ | 4 | -17 | -12 | dB |
| NR SS-SINR | 1 | 6 | 6 | dB |

Counts include the latest cell rows and the separate subscription signal report.
Cooldown, stop, navigation and background/resume checks passed again. LTE remains
NOT VERIFIED: removing Wi-Fi did not cause LTE values to appear, and no LTE-only
radio mode was forced. No claim of per-cell data traffic, SA/NSA proof or complete
neighbor coverage follows from this test.

Only the hardware test was changed, adding an optional required cellular-default
check; production app code and APK are unchanged. The existing unit/regression
results were not rerun for this test-only branch. Evidence is retained separately
in `docs/evidence/phase4/cellular-default-device.xml` (serial redacted) and
`cellular-default-observations.txt` (aggregate values only), preserving the earlier
physical runs. Local command log: `phase4-cellular-default-retest.log`.

## Evidence and delivery

Paths are relative to the project root:
- `app/build/outputs/apk/debug/app-debug.apk`: tested development/debug APK.
  SHA-256: `403BA6EF173164C50EF026CD357182984BB211E1B0E25983CBE18EAD6685F828`.
- `docs/evidence/phase4/cellular-unit-tests.xml`: 13 focused cellular unit tests.
- `docs/evidence/phase4/regression-device.xml`: 7 passed / 3 opt-in skips; serial
  redacted. `initial-regression-device.xml` preserves the first selector failure.
- `docs/evidence/phase4/hardware-device.xml`: final physical test passed without
  skips; serial redacted.
- `docs/evidence/phase4/cellular-observations.txt`: final aggregate RF, field
  availability, cooldown and cleanup evidence; no identity values.
- `docs/evidence/phase4/first-hardware-device.xml`, `first-cellular-observations.txt`:
  earlier development observations, distinct from final evidence.
- `docs/evidence/phase4/permission-controls.txt`: observed OS deny controls.
- `docs/evidence/phase4/lint-results-debug.txt`: full lint findings.
- `docs/evidence/phase4/pixel-launch.txt`, `pixel-cellular-idle.png`: final launch
  and inactive UI evidence, without cell/SIM identity values.
- Local ignored `phase4-*.log` files retain compilation, regression, hardware
  and install runs. Full generated test reports/Logcat are under `app/build`;
  subsequent Gradle runs overwrite them, so selected results above are retained.
- `app/build/phase4-app-logcat.txt`: final 40-line app-process sample; no error
  or warning lines observed. Cold launch returned `Status: ok`, 426ms (one
  observation, not a benchmark). App process and idle Cellular UI were verified.
  `git diff --check` passed.

Phase 4 source edits remain reviewable against Phase 3 checkpoint `e29827e` in
the working tree. No remote push or unrelated-project changes.
