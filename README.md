# Atlas RF

Native Kotlin / Jetpack Compose Android diagnostics foundation in the original
Android Studio project. Minimum API 31; compile/target API 37.

The current source is version **0.7.0-preview**. Persistent **Overview**, **History**,
**Settings** and **Project** tabs keep the current tools accessible while showing
future work honestly. The Atlas launcher icon replaces the Android template icon.
See [preview delivery and validation](docs/USABLE_PREVIEW.md).

Phase 6 adds explicit local saves to Room, searchable/paged history, DataStore
retention preferences and versioned JSON exports through Android's file picker.
Collect on a radio screen, stop, then choose **History → Save session measurements**.
Choose **Settings → Export redacted JSON** to export. Device/network
identifiers and all metadata are removed by default; full export is explicit.
Unsaved histories remain temporary. Defaults are seven days and 10,000 measurement
records; retention also runs on storage initialization, history/settings entry,
save and export. No background collector or purge service is introduced.

Phase 4 adds explicit foreground cellular collection: LTE/NR signal values where
reported, cells and source ages, service/registration, subscription selection and
bounded report history. Physical coverage and unavailable metrics are recorded
in the Phase 4 report; a carrier 5G icon is not treated as an NR measurement.
The earlier Phase 3 adds Wi-Fi surveying with AP RSSI/frequency/channel/security,
source-age and cache labels, connection snapshots, bounded history and signal
charts. It honors Android scan restrictions and has explicit start/stop controls.
The earlier Phase 2 adds foreground BLE surveying with advertisement decoding,
RSSI/source timestamps, bounded in-memory history and start/stop controls.
Authorized GATT service enumeration, reads and notifications are implemented;
their physical verification status is recorded separately in the Phase 2 report.
Phase 5 adds explicit foreground NFC reader sessions, technical tag and NDEF
inspection, and user-entered ISO-DEP APDUs with a confirmation step. The Pixel
physically detected an authorized YubiKey 5C NFC in metadata-only mode. NDEF
content reading and ISO-DEP command exchange remain **NOT VERIFIED**.
Permissions, collection, saves and exports are initiated by user action.

- [Audit, inventories, permission/feasibility matrices and migration plan](docs/MIGRATION_REPORT.md)
- [Physical validation and remaining gates](docs/PHASE1_VALIDATION.md)
- [Phase 2 Bluetooth contract and validation](docs/PHASE2_BLUETOOTH.md)
- [Phase 3 Wi-Fi contract and validation](docs/PHASE3_WIFI.md)
- [Phase 4 cellular contract and validation](docs/PHASE4_CELLULAR.md)
- [Phase 5 NFC implementation and validation status](docs/PHASE5_NFC.md)
- [Phase 7 legacy integration boundary](docs/PHASE7_INTEGRATION.md)
- [Phase 8 validation and acceptance matrix](docs/PHASE8_VALIDATION.md)
- [Phase 6 storage/export contract, schema and validation](docs/PHASE6_STORAGE.md)

Use the installed Android Studio JDK 25 and SDK 37. From PowerShell:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew.bat :app:connectedDebugAndroidTest
./gradlew.bat :app:installDebug
```

Connected tests require an ADB-authorized, unlocked physical device. The permission
test denies the Nearby devices prompt on an installation without an existing
grant; it preserves a pre-existing grant. It does not change system radio settings.
Gradle's connected-test runner may remove test installations afterward; use
`installDebug` to leave the application installed. Do not use that runner against
an installation with saved telemetry you want to retain; install both APKs with
`adb install -r` and run `adb shell am instrument -w` directly instead. Storage
fixture tests use separate database/preferences files and preserve saved telemetry.

BLE acquisition tests are opt-in, require Nearby devices/precise location grants
through normal system dialogs and already-enabled Bluetooth/location services:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.atlas.BlePhysicalInstrumentedTest' '-Pandroid.testInstrumentationRunnerArguments.atlas.bleHardware=true'
```

This test passively observes advertisements and checks cleanup; it never connects
to a peripheral. Regular regression runs skip this opt-in physical test.

The Wi-Fi physical test similarly requires explicit opt-in and already-enabled
Wi-Fi/location services. It uses normal app permission dialogs and two spaced
scan requests, without changing connections or disabling throttling:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.atlas.WifiPhysicalInstrumentedTest' '-Pandroid.testInstrumentationRunnerArguments.atlas.wifiHardware=true'
```

Cellular physical testing is separately opt-in, using normal phone-status and
precise-location permission dialogs. It does not change network/SIM/radio modes:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.atlas.CellularPhysicalInstrumentedTest' '-Pandroid.testInstrumentationRunnerArguments.atlas.cellularHardware=true'
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (development/debug signing).
The template is preserved in local Git commit `dcb39bb` and validated Phase 1 in
`185c3c6`, Phase 2 in `2d6d6c9`, and Phase 3 in `e29827e`; no remote push was made.
