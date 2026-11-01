# Atlas RF

Native Kotlin / Jetpack Compose Android diagnostics foundation in the original
Android Studio project. Minimum API 31; compile/target API 37.

Phase 1 inspects Bluetooth, NFC, Wi-Fi and cellular hardware declarations and
accessible state. It does **not** scan, read tags, connect to peripherals, collect
cell measurements, save telemetry or export data. Module screens explicitly show
these boundaries. Bluetooth state permission is requested only on user action.

- [Audit, inventories, permission/feasibility matrices and migration plan](docs/MIGRATION_REPORT.md)
- [Physical validation and remaining gates](docs/PHASE1_VALIDATION.md)

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

APK: `app/build/outputs/apk/debug/app-debug.apk` (development/debug signing).
The template is preserved in local Git commit `dcb39bb`; no remote push was made.
