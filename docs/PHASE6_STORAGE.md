# Phase 6 - telemetry and storage

Status: IMPLEMENTED AND VALIDATED for the explicit-save workflow below.
Confidence: high for build/storage/JSON behavior and the cellular-to-storage-to-SAF
path observed on the Pixel. This is not full migration or four-radio acceptance.
NFC acquisition (Phase 5) remains pending and NOT VERIFIED.

## Contract and decisions

Collection remains temporary until History -> Save session measurements.
Saving snapshots never starts a radio. Existing BLE, Wi-Fi and cellular
acquisition/lifecycle/rate limits are unchanged. Normalization uses a background
coroutine; Room performs asynchronous transactional writes. No background collector,
cloud service, Internet/storage permission, custom JNI bridge or new hardware
integration was added. DataStore includes its own packaged native support library.

Atlas retains its modern TelephonyCallback path for cellular signal acquisition.
No third-party code was copied.

Assumptions and outcomes:

| Assumption | Outcome | Remaining consequence |
|---|---|---|
| Explicit saves are the initial persistence workflow | Chosen, clearly labeled in History/README | Unsaved bounded histories disappear on process death |
| Room/KSP supports the installed AGP 9.5 toolchain | Verified by generated schema, build and Pixel database tests | Future dependency upgrades need regression checks |
| Pixel is available for storage/SAF checks | Verified, including real cellular records and local Downloads export | No claim about other document providers or devices |

## Implemented behavior

- Private Room database atlas-telemetry.db; schema version 1 is checked into
  app/schemas/com.example.atlas.telemetry.TelemetryDatabase/1.json. No destructive
  migration fallback and no main-thread database queries.
- Explicit saves normalize bounded BLE advertisement/RSSI history, Wi-Fi scan
  history/current rows/connection snapshots, and cellular cell/signal histories.
  Latest collection errors can be saved as diagnostic records with null values.
  GATT payloads and cellular service/display summaries remain live-session views.
- Stable internal dedupe keys ignore repeated saves of the same observation.
  Exported event IDs are independent random UUIDs; internal hashes are not exported.
  These are measurement records, not inferred device identities or unique towers.
- Search measurement/status, filter by radio, page through history (200 records
  per page), inspect source/permission/freshness/timestamp/metadata details.
- DataStore defaults: seven days, 10,000 records, export redaction enabled.
  Choices: 1/7/30 days and 1,000/10,000/50,000 records. Applying a lower retention
  setting explicitly removes older/excess saved records. Limits count measurement
  rows, not sessions/devices. Writes plus pruning are a Room transaction.
- Retention runs on storage initialization, History/Settings entry, save, export,
  explicit retention check and preference application. No scheduled background
  purge; an unused app may retain expired rows until the next enforcement point.
- Export uses ActivityResultContracts.CreateDocument(application/json) and a
  content URI. No broad storage access or filesystem path guessing. Export covers
  all saved rows after retention, independent of the History filter.
- Exports read 200-row pages within a consistent Room transaction and stream UTF-8
  JSON off the main thread. Cancellation is checked between pages. Document-provider
  blocking I/O is not guaranteed immediately interruptible. No 50,000-row export
  object or in-memory JSON document is constructed by the app.
- Redaction removes interface IDs and all nested metadata, including names,
  SSIDs/BSSIDs, cell/subscription metadata and advertisement payloads. Explicit full
  exports retain these fields and show a warning. Redaction does not anonymize
  timestamps, event correlation or signal patterns. Export copies are outside app
  retention; the selected provider controls its destination and access.
- Cancelled picker, unavailable picker, provider/write and database failures have
  visible outcomes. Failed writes are not reported as success; a provider may leave
  an incomplete document. Application owns the single repository/database without
  retaining Activity instances. Backups remain disabled; no app-level encryption
  or Keystore key is introduced.

## Export schema 1

Top level: schemaVersion, exportedAtMillis, identifiersRedacted, recordCount,
measurements[]. Each record contains:

| Fields | Meaning |
|---|---|
| eventId, radioType | Stable saved record UUID and radio source category |
| eventTimestampMillis | Receipt/event wall clock time, Unix epoch milliseconds |
| sourceTimestampMillis, sourceTimeBasis | Optional source time; ELAPSED_REALTIME_MILLIS or UNAVAILABLE |
| receivedElapsedMillis, bootId | Optional receipt elapsed time and conservative process-scoped clock-origin token |
| interfaceId | Permitted observed identity, nullable; removed in redacted exports |
| measurementType, value, units | Named numeric measurement with explicit units; unavailable values stay JSON null |
| collectionStatus, permissionState | MEASURED/CACHED/UNAVAILABLE or diagnostic FAILED/PERMISSION_DENIED/UNSUPPORTED; acquisition or save permission context |
| sourceApi, freshnessAtReceipt | Source API and immutable freshness classification at receipt, not current/live freshness |
| metadata | Structured device/observation metadata; empty object in redacted exports |

The historical field name bootId is an opaque random process clock-scope token,
not the device's boot ID or a persistent identifier. Compare elapsed times only
within the same token; never treat them as Unix times. No source timestamp is
invented when the platform does not provide one. The schema permits future radio
sources, but no external instrument or NFC acquisition is implemented here.

## Modified files in this phase

- build.gradle.kts, gradle/libs.versions.toml, app/build.gradle.kts: KSP 2.3.13,
  Room 2.8.5, DataStore Preferences 1.3.0, kotlinx serialization JSON 1.9.0 and
  exported Room schema. JSON 1.9.0 is retained with the existing Kotlin 2.2
  toolchain; lint reports a newer version. No compiler/toolchain upgrade was made.
- AtlasApplication.kt and AndroidManifest.xml: application-owned storage only;
  no Phase 6 permission additions.
- telemetry/TelemetryDatabase.kt, TelemetryPreferences.kt,
  TelemetryNormalizer.kt, TelemetryJson.kt, TelemetryRepository.kt,
  TelemetryViewModel.kt: schema, normalization, preferences, persistence, export,
  state/error handling and retention.
- MainActivity.kt: storage ViewModel and SAF result launcher; pending export
  redaction choice survives Activity saved-state recreation.
- ui/AtlasApp.kt, ui/TelemetryScreen.kt: explicit save, history, record details,
  pagination/filter/search, retention and redaction/export controls.
- TelemetryTest.kt, TelemetryStorageInstrumentedTest.kt: JSON/normalization,
  failure propagation, Room reopen/deduplication/rollback/retention/filtering and
  DataStore reopen tests. Storage fixtures use isolated databases/preferences.
- DashboardInstrumentedTest.kt: assert persisted count, rather than assume an
  installed app always has an empty database; preserve the empty-state assertion.
- CellularPhysicalInstrumentedTest.kt: opt-in real cellular save, repeated-save
  deduplication, persisted nulls and Activity recreation assertions.
- README.md, .gitignore, docs and selected evidence: workflow and verification.

These are Phase 6 changes on top of existing Phase 4 working-tree edits. The
Phase 3 checkpoint e29827e and earlier original commits remain intact. Unrelated
.idea/vcs.xml was preserved. No remote push or unrelated-project edits.

## Verification

Installed Studio JDK 25; SDK/target 37, minimum 31; Pixel 10 Pro XL Android 17.
Final debug APK SHA-256:
7CF01FAC079DD3B0FB89982B919F348F8E2683B6ABDC15352B9D1DBAFDEAC4E2.

Passed:

- :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
  :app:lintDebug (final command in phase6-delivery-build.log).
- 56 unit tests, including 12 telemetry tests. JSON covers nulls, zero SINR,
  redaction of nested metadata, escaping, cached source classification, dedupe,
  three-page serialization and propagated writer failure.
- Full Gradle device regression: 11 passed, three opt-in hardware tests skipped.
  Final APK direct instrumentation: 11 passed. Four storage tests cover persisted
  nulls and reopen, transaction rollback, retention/history filtering/pagination,
  and preference persistence/default redaction. Synthetic storage fixtures are
  not RF evidence. Existing permission grants are preserved.
- Separate opt-in real cellular/storage test on final APK passed: 234 total saved
  records, 68 unavailable values, repeat save added zero. Earlier run saved 93
  records with 26 unavailable values. These are metrics/diagnostic records across
  callbacks, not 234 cells or unique measurements from distinct devices.
- Existing cellular stop/navigation/background/no-auto-resume checks passed again.
  NR was reported; LTE values were not supplied and remain NOT VERIFIED.
- Force-stop/relaunch preserved saved records and left collection inactive. APK
  upgrade preserved the existing database. Final cold launch returned Status ok,
  392ms, one observation rather than a performance benchmark.
- Actual Android SAF picker cancellation showed Export cancelled; no document
  written. First local export parsed with 93 records; final export parsed with
  234 records, matching recordCount, 68 null values, zero interface identifiers
  and zero populated metadata objects. Final export exercised two database pages.
  Final local destination: Downloads/atlas-rf-1793546160410.json.
- Lint: zero errors, 16 warnings (dependency-version notices and original template
  resources/theme). A new static-context warning was resolved by application
  ownership; version declarations follow the existing catalog. No warning/test
  suppression was added. Gradle also emits existing JDK/protobuf Unsafe warnings;
  initial dependency packaging reported unstripped DataStore native support.
- Final sampled app-process Logcat contained no matching FATAL EXCEPTION,
  AndroidRuntime or error lines; this is a bounded sample, not comprehensive proof.
- git diff --check passed (Windows LF/CRLF normalization notices only).

Observed failure and correction:

The first device regression had one failure: the existing empty-history message
was below the viewport after adding filters. Moved it above filters without
weakening the assertion; the complete suite passed afterward. The initial result
is preserved in evidence/phase6/initial-regression-device.xml.

Not run / not verified:

- NFC reading or persistence from physical tags; Phase 5 remains pending.
- New real BLE/Wi-Fi-to-persistence runs. Their normalizers have focused tests;
  previously verified acquisition is unchanged. GATT payload persistence is not
  part of this implementation.
- Actual provider failure, full/unredacted SAF export, other document providers,
  full 50,000-row stress/latency tests, OS process kill during a partial provider
  write, release/R8 build or multi-device behavior. Writer failures/rollback are
  covered by focused tests; they do not prove all real provider failure behavior.
- Encryption beyond Android's application sandbox, forensic erasure or any
  security/compliance certification is not claimed.

## Reproduction and evidence

Use JAVA_HOME=C:/Program Files/Android/Android Studio/jbr.

For disposable clean installations:

    ./gradlew.bat :app:connectedDebugAndroidTest

That runner can uninstall the application and erase saved telemetry. To retain an
existing installed database, build the two APKs, use adb install -r on each, then:

    adb shell am instrument -w -e class com.example.atlas.CapabilityInstrumentedTest,com.example.atlas.DashboardInstrumentedTest,com.example.atlas.ExampleInstrumentedTest,com.example.atlas.TelemetryStorageInstrumentedTest com.example.atlas.test/androidx.test.runner.AndroidJUnitRunner

Separate authorized real cellular/storage validation:

    adb shell am instrument -w -e class com.example.atlas.CellularPhysicalInstrumentedTest -e atlas.cellularHardware true -e atlas.storageHardware true com.example.atlas.test/androidx.test.runner.AndroidJUnitRunner

This uses ordinary permission dialogs, already-enabled location and available
service. It does not change Wi-Fi, mobile data, SIM/network modes or security
settings. It adds real collected records to local history. Device interaction
stopped when the foreground changed after final export verification; no screenshot
from the later foreground was retained.

Evidence in docs/evidence/phase6:

- telemetry-unit.xml, regression-device.xml, final-device-result.txt,
  final-physical-result.txt and physical-storage-result.txt.
- physical-observations.txt: aggregate real RF/save/cleanup logs, no identity values.
- manual-storage-checks.txt: process restart, upgrade, SAF cancellation/export and
  parsed-export checks, with APK hash.
- physical-redacted-export.json and final-redacted-export.json: actual redacted
  documents (93 and 234 records respectively); signal patterns/timestamps remain.
- lint-results-debug.txt and initial-regression-device.xml.

Official dependency/API references consulted:
https://developer.android.com/jetpack/androidx/releases/room
https://developer.android.com/jetpack/androidx/releases/datastore
https://github.com/google/ksp/releases/tag/2.3.13
https://developer.android.com/training/data-storage/shared/documents-files
