# Atlas RF security review — 2026-11-01

## Scope and evidence

This was a targeted static review of the current Android app, including its manifest, backup rules, radio receivers, BLE advertisement parser and GATT client, telemetry database and export, UI entry points, Gradle configuration, and repository evidence files. An independent read-only pass found no confirmed exploitable source-to-sink vulnerability. This is not a penetration test or a complete dependency advisory audit.

| Material assumption | Status | Impact if wrong | Check |
| --- | --- | --- | --- |
| The installed app uses the manifest and Android 17 behavior reviewed here | Unverified this turn; ADB reported no device | Runtime behavior could differ | Inspect the merged manifest and repeat device checks when connected |
| Full export is started only by the in-app UI | Verified in current source | Another exported entry point could disclose telemetry | Recheck if intents, services, or content providers are added |

## Results

- **No confirmed exploitable code vulnerability (medium confidence).** In the merged release manifest, the launcher is the only exported component without a permission requirement, and it does not consume incoming intent data. A profile-installer receiver requires Android's `DUMP` permission. The merged debug manifest additionally exports tooling activities; a debug APK should remain a development build, not a distribution artifact. There is no `INTERNET` permission or remote listener. BLE data is bounded before parsing and history is bounded. Room queries use bound parameters. These facts do not establish that the whole app is vulnerability free.
- **Backup controls (high confidence).** `allowBackup=false`, and both cloud and device-transfer rules exclude app files, databases, and preferences. Sensitive telemetry remains in the app sandbox unless the user exports it. The SQLite database is not separately encrypted; a compromised or debug-accessible device is outside this control's protection.
- **Radio broadcast trust (high confidence for stock Android 17).** The Android 17 platform manifest protects the Wi-Fi scan, Wi-Fi state, Bluetooth state, and location-mode actions used by the dynamic receivers. The receivers do not trust a broadcast for measurements: they reread Android services. This review therefore did not treat their `RECEIVER_EXPORTED` flags as a confirmed spoofing vulnerability. Reevaluate this boundary if actions or device firmware change.
- **Export privacy (high confidence).** The redacted JSON serializer removes interface IDs and all metadata, and its existing unit tests cover nested metadata. It retains timestamps and signal values, so redaction is not anonymization; the settings screen says this. A full export now requires a second explicit confirmation before the Storage Access Framework picker opens.
- **Repository evidence (high confidence).** Two locally generated, redacted JSON exports under `docs/evidence/phase6/` contain timing and signal data. They remain on disk but are now ignored by Git to reduce accidental inclusion in future commits. Existing tracked evidence was not removed.

## Verification and limits

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: passed; 56 unit tests, zero failures. Lint retained dependency-age and unused-resource warnings.
- `:app:processReleaseManifest`: passed; merged release component export flags were inspected.
- `git check-ignore -v` confirmed both local Phase 6 JSON exports are ignored.
- `git diff --check` reported only line-ending conversion warnings.
- Physical install, UI confirmation, and radio regression were **not run**: ADB reported no connected devices. The existing installed app and saved telemetry were not touched.
- No dependency vulnerability database, dynamic fuzzing, or NFC implementation was assessed. New external components and the eventual release build need separate review.

References: [Android broadcast receiver security](https://developer.android.com/develop/background-work/background-tasks/broadcasts), [Android 17 protected broadcast declarations](https://android.googlesource.com/platform/frameworks/base/+/android17-release/core/res/AndroidManifest.xml), [Android backup and transfer rules](https://developer.android.com/identity/data/autobackup).
