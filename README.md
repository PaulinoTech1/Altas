# Atlas RF

Native Kotlin / Jetpack Compose Android diagnostics foundation in the original
Android Studio project. Minimum API 31; compile/target API 37.

Phase 2 adds explicit foreground BLE surveying with advertisement decoding,
RSSI/source timestamps, bounded in-memory history and start/stop controls.
Authorized GATT service enumeration, reads and notifications are implemented;
their physical verification status is recorded separately in the Phase 2 report.
The app also inspects NFC, Wi-Fi and cellular hardware declarations and accessible
state. NFC reading, Wi-Fi/cellular acquisition, persistent storage and export are
not implemented. Permissions and collection are initiated by user action.

- [Audit, inventories, permission/feasibility matrices and migration plan](docs/MIGRATION_REPORT.md)
- [Physical validation and remaining gates](docs/PHASE1_VALIDATION.md)
- [Phase 2 Bluetooth contract and validation](docs/PHASE2_BLUETOOTH.md)

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
`installDebug` to leave the application installed.

BLE acquisition tests are opt-in, require Nearby devices/precise location grants
through normal system dialogs and already-enabled Bluetooth/location services:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.atlas.BlePhysicalInstrumentedTest' '-Pandroid.testInstrumentationRunnerArguments.atlas.bleHardware=true'
```

This test passively observes advertisements and checks cleanup; it never connects
to a peripheral. Regular regression runs skip this opt-in physical test.

APK: `app/build/outputs/apk/debug/app-debug.apk` (development/debug signing).
The template is preserved in local Git commit `dcb39bb` and validated Phase 1 in
`185c3c6`; no remote push was made.
