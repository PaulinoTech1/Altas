# Atlas RF app review — 2026-11-01

Target: connected stock Pixel 10 Pro XL, Android 17, installed Atlas RF 0.7.0-preview (version code 3). This is a debug build review, not release certification. No root, system setting change, app-data clear, or uninstall was used. Both APKs were upgraded with `adb install -r`.

## Assessment

| Feature | Current direct result | Scope limit |
| --- | --- | --- |
| Build and launch | Debug/test APK build succeeded; cold Pixel launch succeeded. | Release signing and API 31–36 runtime not checked here. |
| Dashboard and navigation | 12 on-device capability, navigation, storage-fixture and export-confirmation tests passed. Four hardware cards appeared. Cold relaunch showed 306 saved records and collection inactive. | Permission-denial test branches returned early because permissions were already granted. |
| BLE | Live test passed: initial 10-second scan, 160 observations from 11 reported addresses, RSSI -97 to -39 dBm, 160 source timestamps, zero malformed flags. Manual stop, time limit, restart cooldown, navigation and background cleanup checks passed. | Address count is not a physical-device count. No GATT connection. Separate same-day AirPods probe found no advertised AirPods name, so AirPods-specific BLE identity remains unverified. |
| Wi-Fi | Live survey passed: 27 AP rows, 60 initial source samples, RSSI -91 to -45 dBm, 2.4/5 GHz, one connected snapshot; later 87 history samples and two updated broadcasts. Local rate guard, manual/navigation/background stop checks passed. | Android throttling still applies; these are environment-specific values, not calibration. |
| Cellular | Live test passed: one active slot, NR base type, five cell rows (one registered, four reported nonregistered), 13 available NR metrics. Six SS-RSRP values -110 to -99 dBm, six SS-RSRQ values -16 to -11 dB, one SS-SINR value 5 dB. Manual/navigation/background cleanup passed. | No LTE RSRP/RSRQ/RSSNR supplied. Dual-SIM and modem diagnostic completeness not established. |
| NFC | Reader start and navigation stop passed on the Pixel. | No tag was presented in this review. Earlier same-day YubiKey 5C NFC metadata-only detection succeeded for NfcA/IsoDep. NDEF content and APDU remain untested. |
| Local storage | Separate real cellular/storage test passed: 306 private saved records, 88 unavailable values; repeat save added zero duplicates. Activity recreation preserved count; cold relaunch showed the same count. | The test added real private telemetry to the app's database; no new SAF file was exported in this review. |
| JSON export | Export confirmation/cancel UI test passed; isolated Room/DataStore tests passed. Prior Phase 6 SAF redacted export parsed successfully with 234 records and no populated interface IDs/metadata. | SAF document creation and parsing were not repeated against the current 306-record database. |

## Verification

- `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain`: passed. Gradle marked unit tests up to date; the existing XML contains 58 tests, zero failures/errors. Lint report: 0 errors, 15 warnings.
- Direct `adb shell am instrument -w` selected suite: 12 passed in 8.87 s.
- Direct physical tests: Wi-Fi passed in 46.092 s; cellular passed in 7.593 s; NFC reader lifecycle passed in 1.72 s; BLE passed in 38.209 s; cellular plus private storage passed in 8.995 s.
- `adb shell am force-stop` then `am start -W`: cold launch status `ok`; UI hierarchy reported 306 records and inactive collection. No Atlas fatal exception appeared in the bounded AndroidRuntime log inspected after launch.
- `git diff --check`: passed. Existing unrelated and prior-stage working-tree changes were preserved.

## Remaining gates

Physical GATT service discovery/read/notifications need an authorized device that exposes suitable GATT services; the connected AirPods audio profile does not verify those paths. NFC NDEF reading needs an appropriate authorized non-security tag, and APDU remains untested. A fresh redacted SAF export would verify the current database's export path. The original Linux/Python/Rust/Node sources are absent from this checkout, so migration parity cannot be assessed. This review does not establish long-run battery behavior, API 31–36 compatibility, release distribution readiness, or that all scan/revocation edge cases are handled.
