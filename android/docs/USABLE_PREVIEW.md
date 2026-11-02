# Atlas RF usable preview - 0.6.1-preview

Status: installed and launched on the Pixel 10 Pro XL. High confidence for the
observed preview delivery/navigation/storage behavior; remaining radio validation
limits are explicitly shown in Project. This is a development/debug build, not
release-distribution acceptance or completion of the full migration.

## User workflow

Open Atlas RF from the Android app launcher (new white Atlas A/radio mark on a
teal background). Overview opens the Bluetooth, Wi-Fi and Cellular tools, followed
by NFC hardware/status information. Review the corresponding permission, start a
session, stop it, then use History -> Save session measurements. Settings exposes
redacted JSON export first, followed by retention controls. Project lists unfinished
NFC, GATT/LTE/dual-SIM validation and external instruments without pretending these
are verified capabilities.

Nearby devices access is currently denied by Android, including its USER_FIXED
state for BLUETOOTH_CONNECT after repeat denial testing. The Bluetooth screen's
App permission settings button is the normal user recovery path. Atlas displays
explicit instructions when Android no longer offers a prompt. No shell permission
grant, denial-flag reset, app-data clearing or security-setting change was used.

## Scope and invariants

- Persistent Overview, History, Settings and Project navigation. Radio subpages
  select Overview as their parent. Back returns to the overview; switching away
  from a radio retains the existing stop/disconnect behavior.
- Compact dashboard cards keep hardware, permission, collection and session-result
  information. Missing storage state shows loading/unavailable rather than a
  misleading zero count. Saved history is distinct from this process's session.
- Filters and retention selections use wrapping chips. Export is placed before
  retention editing; device/debug details are expandable. Existing source details,
  timestamp/freshness distinctions and diagnostic controls are preserved.
- Native light/dark teal palette follows the system appearance; the Pixel's
  existing 1.3 font scale was retained and visually inspected. No display or
  accessibility setting was reduced to fit the UI.
- Application ID com.example.atlas, signing identity, Room schema 1, saved data,
  acquisition repositories and public-API security constraints are unchanged.
- Version code 2 / name 0.6.1-preview. Build version resource generation is explicitly
  enabled. Future code can extend the section list and independent acquisition
  modules; no navigation framework, cloud service or speculative instrument
  implementation was introduced.

## Changes in this delivery

- ui/AtlasShell.kt: app header, persistent navigation, small vector navigation
  glyphs, Project content and extension points.
- ui/AtlasApp.kt: connect shell, compact/reorder cards, clear pending NFC status,
  storage loading/error state and expandable device details.
- ui/TelemetryScreen.kt: wrapping filter/retention chips and export-first layout.
- ui/PermissionGuidance.kt, BluetoothScreen.kt, WifiScreen.kt, CellularScreen.kt,
  res/values/strings.xml: denial/settings guidance, conditional on missing access.
- ui/theme/Color.kt and Theme.kt: Atlas palette for both system themes.
- res/drawable/ic_launcher_foreground.xml and ic_launcher_background.xml: adaptive
  launcher mark, including the existing monochrome/themed-icon integration.
- app/build.gradle.kts: version metadata and resource generation only; no new
  runtime/build dependencies in this preview delivery.
- DashboardInstrumentedTest.kt: persistent tab visibility, page restoration,
  history/settings reachability and idle collectors. Distinguishes an ordinary
  permission prompt from Android's repeated-denial state rather than assuming a
  prompt always exists. Denied access remains denied in both branches.
- CellularPhysicalInstrumentedTest.kt: optional atlas.previewNavigation=true
  exercises switching to History while cellular collection is running.
- README.md, .gitignore, this report and selected evidence.

Original/foundation/Phase 2/Phase 3 commits remain preserved. Previous Phase 4/6
working-tree edits and unrelated .idea/vcs.xml were retained. No remote push,
unrelated-project mutation or destructive migration was performed.

## Verification

Final APK: app/build/outputs/apk/debug/app-debug.apk.
SHA-256: 565AAAEAB94CEC617E6A6BF874A07266CB6CB30300A3C82D22D7CC47849D21D9.
Installed package reports versionCode=2, versionName=0.6.1-preview, minimum API 31,
target API 37. Pixel runs Android 17. Final cold launch returned Status ok,
473 ms (one observation, not a benchmark). Overview showed collection inactive
and 234 saved records after upgrade. No application uninstall was used.

Passed:

- Gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
  :app:lintDebug with the installed Android Studio JDK. Final log:
  preview-accepted-build.log. 56 unit tests; zero failures.
- Final direct instrumentation: 12 passed (capability, dashboard/navigation,
  application context, isolated Room/DataStore tests). Persisted page restoration,
  reachable controls and absence of automatic collection were asserted.
- Separate real cellular test with atlas.previewNavigation=true passed. Switching
  to History stopped collection; report count stayed unchanged over 1.5 seconds.
  Existing manual stop, background stop and no-auto-resume checks also passed.
  This run did not save extra records or change Wi-Fi/cellular modes.
- Upgrade preserved the 234 records from Phase 6. Actual final Overview screenshot
  was inspected at the existing font scale; tabs remain visible below scrolling
  content and interface text is legible in the observed dark system theme.
- Lint: zero errors, 15 warnings (dependency version notices and original unused
  template resources). No new suppression; no release/R8 check was run.
- git diff --check passed, with Windows LF/CRLF normalization notices.

Observed failures and corrections:

1. Initial Gradle configuration failed because generated custom resources were
   disabled. Enabled buildFeatures.resValues; subsequent builds passed.
2. An intermediate 12-test repeat had one failure expecting a Nearby devices
   dialog. Read-only package inspection showed USER_FIXED denial; Android
   correctly did not show another prompt. Added app guidance and a distinct test
   branch verifying no unresolved OS dialog, denied state and reachable settings.
   Final 12-test run passed. The ordinary denial-dialog branch was NOT RUN in
   that final run; it ran successfully in the earlier preview regression.

Not verified in this delivery: new GATT operations, NFC tag reading/APDU, LTE or
dual-SIM metrics, external instruments, release/R8 distribution, all screen sizes
or formal accessibility audit. The system light palette is implemented but the
physical visual check used the phone's existing dark mode. No radio feature was
marked complete based on a UI build. Earlier Phase 6 export and storage evidence
remains separately documented; no fresh export was required for layout changes.

## Reproduction and evidence

Build using JAVA_HOME=C:/Program Files/Android/Android Studio/jbr. Upgrade the app
and test APK with adb install -r; do not use a runner that uninstalls saved data.

Final regression command:

    adb shell am instrument -w -e class com.example.atlas.CapabilityInstrumentedTest,com.example.atlas.DashboardInstrumentedTest,com.example.atlas.ExampleInstrumentedTest,com.example.atlas.TelemetryStorageInstrumentedTest com.example.atlas.test/androidx.test.runner.AndroidJUnitRunner

Explicit real navigation/cleanup test:

    adb shell am instrument -w -e class com.example.atlas.CellularPhysicalInstrumentedTest -e atlas.cellularHardware true -e atlas.previewNavigation true com.example.atlas.test/androidx.test.runner.AndroidJUnitRunner

The physical tests require an idle phone, ordinary permission access and already
available service/location. They preserve settings and do not force network modes.

Evidence: docs/evidence/preview/overview.png, device-result.txt,
navigation-radio-result.txt, repeated-denial-failure.txt and lint-results-debug.txt.
Build/test full logs are ignored preview-*.log files at the project root. The final
app is installed and was left open on Overview with collection inactive.
