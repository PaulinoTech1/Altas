# Phase 1 validation — 2026-11-01

**Assessment: High confidence within Phase 1 scope.** Native debug APK builds,
installs and launches on the physical Google Pixel 10 Pro XL, Android 17 / API 37.
Android reports Bluetooth (including BLE), NFC, Wi-Fi and telephony features.
This establishes capability reporting, not successful RF acquisition.

## Implemented and changed

- `app/build.gradle.kts`: minimum API 31, retained compile/target 37; lifecycle,
  coroutine and focused test dependencies.
- `gradle/libs.versions.toml`: Runtime/ViewModel Compose, Coroutines Android/Test,
  UI Automator; Lifecycle 2.11.0 and compatible AndroidX test updates.
- `app/src/main/AndroidManifest.xml`: optional radio features, four necessary
  Phase 1 permissions, backups and cleartext disabled. No INTERNET, location,
  scan, phone-state, advertising or background collection permissions.
- `app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`: exclude app
  data from backup and device transfer.
- `app/src/main/res/values/strings.xml`: Atlas RF launcher name.
- `app/src/main/java/com/example/atlas/MainActivity.kt`: permission launcher,
  manual dependency construction, resume refresh and stop cancellation.
- `model/RadioCapability.kt`: availability, permission, state, collection and
  nullable last-measurement contracts.
- `permissions/RadioPermissionManager.kt`: central state-access policy and
  current OS grant checks.
- `data/{CapabilityRepository,AndroidCapabilityRepository}.kt`: interface and
  public Android feature/state inspection, off-main execution and guarded errors.
- `ui/{DashboardViewModel,AtlasApp}.kt`: StateFlow, cancellable refresh, Compose
  dashboard/module details, permission settings link, honest history/settings.
  Theme remains the existing native Material 3 theme.
- `app/src/test/java/com/example/atlas/FoundationTest.kt`: six focused tests.
- `app/src/androidTest/java/com/example/atlas/{CapabilityInstrumentedTest,DashboardInstrumentedTest}.kt`:
  four focused physical-device tests; original template tests retained.
- `.gitignore`, `README.md`, `docs/*`: local log exclusion, instructions, audit,
  inventories and validation evidence. Implementation remains reviewable in the
  working tree; original template committed locally as `dcb39bb`. No push.

Package paths above are relative to `app/src/main/java/com/example/atlas` unless
fully specified. A full original file list is in `evidence/original-file-inventory.txt`.

## Passed

Command (Android Studio bundled JDK 25.0.4):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --console=plain
./gradlew.bat :app:installDebug :app:dependencies --configuration debugRuntimeClasspath --console=plain
```

- Gradle final verification: **BUILD SUCCESSFUL**, 44 seconds. Seven unit tests
  passed (six foundation tests and one retained arithmetic example).
- Five instrumentation tests passed, zero failures/errors/skips (four focused
  checks plus the retained package-name example). Verified capability snapshots,
  no produced measurements, manifest restrictions, all module navigation and
  history empty state, user-triggered OS Nearby devices dialog and safe denial.
- Lint: **0 errors, 15 warnings**. No checks were suppressed. Remaining warnings
  are available dependency updates, the template's redundant API-31 check and
  unused template color resources. The obsolete Lifecycle lint warning is absent
  from the final report.
- Explicit final installation: **Installed on 1 device**. `am start -W` returned
  `Status: ok`, `LaunchState: COLD`, `TotalTime: 394 ms`; process confirmed running.
  This launch timing is a single observation, not a performance benchmark.
- Inspected app-process Logcat after launch; no fatal exception observed in that
  bounded sample. Inspected the dashboard screenshot. This is not a soak test.
- `git diff --check` passed.

## Physical observations

| Interface | Android feature | Accessible state | Acquisition |
|---|---|---|---|
| Bluetooth | Available; BLE reported | Permission required; Nearby devices denial tested | NOT VERIFIED |
| NFC | Available | Enabled | NOT VERIFIED: no tag read |
| Wi-Fi | Available | Enabled | NOT VERIFIED: no survey/SSID/RSSI read |
| Cellular | Telephony available | Modem/registration not queried; active default network did not report cellular transport | NOT VERIFIED: no LTE/NR metrics |

No startup permission prompt, scan, reader mode or cellular acquisition was
implemented. Permission denial does not imply Bluetooth is disabled. A network
transport observation does not imply cellular radio power or registration state.
Last measurement is null/None throughout. No hardware settings were toggled.
Permission tests denied Nearby devices through the normal OS prompt; subsequent
requests may require the provided app-settings link after repeated denial.

## Failures encountered and corrected

1. Initial instrumentation compilation failed because requestedPermissions is
   nullable in the SDK. Corrected the test to use `orEmpty()` and recompiled.
2. Espresso 3.5.1 from the template failed inside its input injection setup with
   `NoSuchMethodException: android.hardware.input.InputManager.getInstance` on
   API 37. Updated Espresso to 3.7.0 and AndroidX Test JUnit to 1.3.0.
3. Lifecycle 2.6.1 and an intermediate 2.9.4 trial could not run two custom lint
   checks. Lifecycle 2.11.0 resolved that warning in the final full lint report.
4. The first working UI run could not assert that Open Cellular was displayed
   after child-level scrolling. The revised test scrolls the lazy list to each
   target and actually opens all four module screens, then verifies their hardware
   reports and returns to the dashboard. Final run passed; no assertion removed.

Toolchain warnings retained: first packaging could not strip the AndroidX
graphics path shared library, and the Gradle device-test tooling's protobuf uses
a deprecated JDK Unsafe method. Neither was suppressed or attributed to RF code.

## Artifacts and residual gates

- APK: `app/build/outputs/apk/debug/app-debug.apk`, development signing only.
- APK SHA-256: `6A2A0A745285B5AA6E5A517E6503127706F6AC8D5199370D82D94E829D2BA166`.
- `evidence/pixel-capabilities.txt`: filtered test observations without device IDs.
- `evidence/pixel-instrumentation.xml`: final results, device serial redacted.
- `evidence/foundation-unit-tests.xml`: six focused unit test results.
- `evidence/lint-results-debug.txt`: final complete lint findings.
- `evidence/runtime-dependencies.txt`: resolved runtime dependency inventory.
- `evidence/pixel-launch.txt`, `evidence/pixel-dashboard.png`: final launch evidence.
- Generated HTML reports: `app/build/reports/tests/testDebugUnitTest/index.html`,
  `app/build/reports/androidTests/connected/debug/index.html`,
  `app/build/reports/lint-results-debug.html`.
- Local build logs: `phase1-checks.log`, `phase1-validation.log`,
  `phase1-device-retest.log`, `phase1-final-checks.log`, `phase1-install.log`.
  They preserve earlier failures and are ignored by Git. App-process launch
  Logcat sample is `app/build/phase1-app-logcat.txt`.

**NOT VERIFIED / not implemented:** actual BLE peripheral discovery/GATT, Wi-Fi
surveys and throttling, LTE/NR metrics/dual-SIM, NFC tag/NDEF/APDU, persistence,
retention and SAF exports. No Rust/Python/legacy integration is possible to audit
without the original source. Bluetooth granted-state and revocation paths,
missing-hardware devices, API 31 execution, font-scale/landscape accessibility,
TalkBack, long-run stability, and release-signed optimization are not verified.

Next phase is BLE scanning with precise operation-specific permissions, bounded
history and explicit lifecycle cancellation, followed by a real authorized
peripheral test. Do not call the full migration complete based on Phase 1.
