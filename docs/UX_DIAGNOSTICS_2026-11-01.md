# Atlas RF UI and diagnostic codes — 2026-11-01

## Scope and assumptions

The existing Compose navigation, radio controls, and saved telemetry schema remain in place. The overview now labels each radio's readiness before its technical fields, and the session banner distinguishes idle from active collection. Every existing UI error surface for capability checks, BLE, GATT, Wi-Fi, cellular, NFC, and storage now presents a diagnostic card with a visible Atlas code, a recovery step, and expandable reported detail. Wi-Fi and cellular request statuses that indicate a delay or failure also use cards. Codes do not imply that Android supplied a numeric status unless the code contains `OS-<number>`.

Material assumption: existing error strings are app-generated and do not include identifiers or tag payloads. The inspected current call sites support this, but future error producers must preserve that boundary. The details panel caps the displayed message at 500 characters and does not print stack traces.

## Code policy

| Area | Examples | Meaning |
| --- | --- | --- |
| BLE | `ATLAS-BLE-PERMISSION`, `ATLAS-BLE-COOLDOWN`, `ATLAS-BLE-OS-6` | Access, local restart guard, or Android's reported scan failure integer |
| GATT | `ATLAS-GATT-TIMEOUT`, `ATLAS-GATT-OS-5` | Connection/operation timeout or an Android GATT status integer |
| Wi-Fi | `ATLAS-WIFI-CACHED`, `ATLAS-WIFI-LOCAL-RATE`, `ATLAS-WIFI-OS-REJECTED` | Stale result warning or scan request limits |
| Cellular | `ATLAS-CELL-LOCAL-RATE`, `ATLAS-CELL-UPDATE-TIMEOUT`, `ATLAS-CELL-OS-2` | Update request delay, timeout, or reported platform integer |
| NFC | `ATLAS-NFC-APDU-INPUT`, `ATLAS-NFC-ISO-DEP`, `ATLAS-NFC-NDEF-IO` | Invalid command input, tag compatibility, or NDEF I/O failure |
| Storage | `ATLAS-STORAGE-SAVE`, `ATLAS-STORAGE-EXPORT`, `ATLAS-STORAGE-RETENTION` | Operation-specific failure context |

Unknown failures receive a stable area-level `OPERATION` code. The numeric OS/GATT codes are copied from platform responses; the UI does not claim a unique root cause for a number. Classification currently uses bounded app error text and request status names. If source wording changes, a specific code may fall back to its area-level code; future acquisition work should carry structured diagnostic codes directly from the source.

## Verification

- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`: passed. 60 unit tests, zero failures; lint 0 errors and 15 warnings.
- Debug APK upgraded on the Pixel with `adb install -r`; existing app data preserved. Navigation/dashboard instrumentation passed (2 tests) after the UI change. A physical BLE test passed (1 test, 38.236 s): the app displayed `ATLAS-BLE-COOLDOWN` after an immediate restart attempt, while scan/stop/navigation/background assertions passed. Initial 10-second scan in that run received 74 observations from 8 reported addresses; no GATT connection.
- Final small idle-copy revision rebuilt, upgraded, and passed one on-device navigation test. Cold launch returned `Status: ok`; UI hierarchy showed `All collectors idle` and 306 saved records. The rendered overview was visually inspected on the Pixel in dark theme.
- `git diff --check`: passed. No app-data reset, permission change, network service, or destructive storage action.

## Limits

The diagnostic card was exercised on device for a BLE cooldown. Other failure cards are covered by focused catalog unit tests and compilation, not by forced permission revocation, OS failures, GATT hardware, or NFC tag errors. Light theme, TalkBack, large-font/landscape layouts, and API 31–36 runtime remain unverified. The app does not retain a separate diagnostic event log; existing telemetry diagnostic saves still require the user to choose Save in History.
