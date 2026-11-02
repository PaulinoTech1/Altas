# Phase 8 — validation status

Date: 2026-11-01. Target: stock Pixel 10 Pro XL, Android 17. The original request has phases 0–8 and **13** numbered acceptance criteria. This report retains all 13 so a missing criterion is not silently counted as complete.

## Current build and physical evidence

- Native package `com.example.atlas`, version code 3 / `0.7.0-preview`, min API 31, target/compile API 37. Debug APK installed with `adb install -r`; no uninstall, root, bootloader or system-setting change. The release/R8 build passes but is unsigned and was not installed.
- `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug`: passed, 58 unit tests, zero failures, lint 0 errors / 15 warnings. Warnings are dependency-age and original unused-resource notices. API 31–36 execution is not verified.
- Direct on-device instrumentation after the upgrade: 10 capability/navigation/storage/export/NFC reader tests passed. Separate current Wi-Fi and cellular acquisition tests passed. After the user granted Nearby devices in Android settings, a fresh BLE physical test and a focused passive AirPods probe both passed. The BLE test observed 253 advertisements from 14 reported addresses and passed timed stop, manual stop, cooldown, navigation, and background cleanup checks. The separate 20-second probe observed 338 advertisements from 14 reported addresses, but none carried an advertised name containing `AirPods`. Sixteen observations carried manufacturer code `0x004C`, which does not identify the user's AirPods by itself. Android reported the AirPods connected for BR/EDR audio; Atlas does not implement Classic audio-profile diagnostics. GATT with the AirPods was not attempted.
- Current Wi-Fi acquisition: 26 visible AP rows, 80 source samples at initial report, two platform update broadcasts during the session, 2.4/5 GHz bands, RSSI range −88 to −48 dBm, one connected snapshot. Manual, navigation and background stop checks passed. These observations are environment-specific, not an RF accuracy benchmark.
- Current cellular acquisition: one active subscription, reported base technology NR, five reported cells with one registered and four nonregistered, source timestamps on five rows. Six NR SS-RSRP and six NR SS-RSRQ values were available; one NR SS-SINR value was available. **LTE RSRP/RSRQ/RSSNR were not supplied** and remain not verified on this network. Stop and callback cleanup checks passed. A nonregistered row is not proof of a complete neighbor survey.
- NFC foreground reader mode started only by the UI and stopped on navigation. A physical YubiKey 5C NFC was detected as `NfcA` and `IsoDep` after correct antenna placement. NDEF was deliberately skipped, and no APDU was sent.

Sanitized current observations and timing are retained in `docs/evidence/phase8/current-observations.txt`. Wi-Fi/cellular acquisition tests preceded the last NFC-only timestamp/cancellation edit; the final installed build passed the 10-test UI/storage/NFC suite afterward.

## Acceptance criteria

| # | Criterion | Current evidence/status |
| --- | --- | --- |
| 1 | Native Kotlin APK builds | **PASSED** — debug and optimized release builds |
| 2 | APK installs and launches on Pixel | **PASSED** — debug upgrade and on-device UI tests |
| 3 | Four interfaces identified | **PASSED** — capability instrumentation, hardware declarations/state |
| 4 | Permissions requested correctly | **PARTIAL** — scoped flows and denial paths tested; all revocation states and API 31–36 not covered |
| 5 | Actual BLE peripheral discovered | **PASSED** — fresh physical BLE scan received 253 advertisements; AirPods-specific BLE identification remains unverified |
| 6 | Real Wi-Fi measurements | **PASSED** — current physical survey above |
| 7 | Available real cellular metrics | **PASSED FOR NR** — current physical report; LTE values unavailable |
| 8 | NFC supported tag detected/read | **PARTIAL** — real YubiKey tag and technology metadata read; NDEF content and APDU untested |
| 9 | Unsupported capabilities identified | **PASSED IN SOURCE/UI** — raw HCI, monitor mode, privileged modem data excluded; hardware-specific absences remain conditional |
| 10 | Collection stops safely | **PASSED FOR OBSERVED PATHS** — fresh BLE stop/navigation/background checks plus Wi-Fi/cellular/NFC; physical GATT cleanup remains unverified |
| 11 | Local save and export | **PASSED EARLIER** — Phase 6 Room/SAF evidence; current UI confirmation tested, NFC-tag save awaits tag |
| 12 | No root or bootloader changes | **PASSED FOR THIS WORK** — public APIs and data-preserving ADB upgrade only |
| 13 | Useful legacy code preserved or replacement documented | **BLOCKED** — legacy source absent; native-template baseline preserved, no reuse parity can be established |

## Remaining gates

The full migration cannot be declared complete until NDEF content is read from an appropriate non-security tag, the original legacy source is supplied and audited, and physical GATT interactions are exercised with an authorized peripheral. LTE and dual-SIM coverage require the corresponding network/SIM conditions; missing values are displayed as unavailable rather than filled in. A signed release and broad device/API compatibility checks remain separate distribution gates.

The native app is usable on the Pixel today for the measurements the OS supplies. The explicit NOT VERIFIED and BLOCKED labels above are intentional acceptance outcomes, not passing tests.
