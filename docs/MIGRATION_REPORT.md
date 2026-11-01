# Atlas RF migration audit and Phase 1 contract

Audit date: 2026-11-01. Scope: this Android Studio workspace, including source,
resources, tests, build configuration, and wrapper. Generated Gradle/IDE caches
are not application source. Original template preserved in Git commit `dcb39bb`.

## Assessment and assumption register

High confidence: this checkout is a single-module Android Compose template, not
the previous Linux Atlas RF implementation. No collectors, processing algorithms,
schemas, cellular/SIM exporters, network scanners, Python, Rust, Node, JNI,
Debian, or Bubblewrap components are present. No original behavior is verified.
The legacy implementation's location and contents are unknown; its migration
cannot be audited from this checkout. Do not delete or replace that external source.

| Assumption | Status | Impact if wrong | Validation |
|---|---|---|---|
| This workspace is the intended native app | User specified | Wrong migration target | Existing Android Studio project inspected |
| Legacy source must be supplied separately | Source absent here | Reuse inventory incomplete | Re-audit when source becomes available |
| Installed API 37 is a release SDK | Verified locally | Compatibility/preview risk | source.properties: PreviewSdkInt=0, empty code name, revision 2 |
| Phase 1 must collect no RF observations | User specified | Privacy and scope violation | No scan, cell, tag reader, or GATT acquisition calls; instrumentation checks |

## Source inventory and disposition

| Component | Existing behavior | Classification / action |
|---|---|---|
| `MainActivity.kt` | Compose Hello Android | PORT: replace greeting with native capability dashboard |
| `ui/theme/*.kt` | Material 3 theme / typography | REUSE: native theme remains |
| Manifest / resources / launcher icons | Single exported launcher; backup template | REUSE with permission, optional hardware and backup hardening |
| Gradle Kotlin DSL / TOML catalog / wrapper | AGP 9.5.0, Gradle 9.6.0, built-in Kotlin | REUSE; lower minimum SDK from 36 to required 31, retain compile/target 37 |
| Unit and instrumentation examples | Arithmetic and package-name tests | REUSE; add meaningful foundation coverage |
| IDE configuration / local.properties | Local project/toolchain metadata | REUSE; local SDK path ignored by Git |
| Old Python / Rust / JavaScript / Debian modules | Not supplied | BLOCKED for audit; no invented reuse claims |
| Public Android radio acquisition | Absent | PORT: implement incrementally in Kotlin in later phases |
| Raw HCI, Wi-Fi monitor/injection, privileged modem / protected identifiers | Absent; outside public API scope | BLOCKED; do not implement |
| Future Linux instruments / Python processing | Source absent | RETAIN EXTERNALLY if useful after actual audit |
| Rust JNI | No candidate or benchmark | BRIDGE only after reuse/performance justification; none now |
| Obsolete legacy code | No evidence | REMOVE nothing from an unseen project |

Languages present: Kotlin (`.kt`), Kotlin DSL (`.kts`), XML resources/manifest,
TOML catalog, Java properties, Gradle POSIX shell and Windows batch launchers.
No Java application sources, Python, Rust, JavaScript, native C/C++ or assembly.
Binary resources consist of launcher WebP icons and Gradle wrapper JAR.

## Dependency inventory

Direct template dependencies (versions retained unless noted): Android Gradle
Plugin 9.5.0 (built-in Kotlin), Compose compiler plugin 2.2.10, Gradle 9.6.0
(distribution SHA pinned), Foojay resolver 1.0.0 / daemon JDK 25,
Compose BOM 2026.02.01 (UI, graphics, Material 3, previews, tooling, UI tests),
Activity Compose 1.8.0, Core KTX 1.10.1, Lifecycle Runtime KTX 2.6.1,
JUnit 4.13.2, AndroidX Test JUnit 1.1.5, Espresso 3.5.1.
Phase 1 adds Lifecycle Runtime Compose and ViewModel Compose and updates the
lifecycle family to stable 2.11.0: both the template's 2.6.1 and an intermediate
2.9.4 trial had a lint plugin incompatible with the installed toolchain.
Releases are documented at https://developer.android.com/jetpack/androidx/releases/lifecycle .
Explicit Coroutines Android/Test 1.10.2 and UI Automator 2.3.0 (test only) are added.
AndroidX Test JUnit is updated to 1.3.0 and Espresso to 3.7.0 after the template's
Espresso 3.5.1 failed on API 37 with `InputManager.getInstance` missing. These
stable releases are listed at https://developer.android.com/jetpack/androidx/releases/test .
Google Maven, Maven Central and Gradle Plugin Portal resolve build dependencies.
These are build-time network dependencies; the APK has no INTERNET permission.
Room, DataStore and serialization are deliberately deferred to Phase 6.
Use `:app:dependencies --configuration debugRuntimeClasspath` to inspect the
resolved transitive dependency graph (recorded with validation artifacts).

## Hardware API feasibility

| Module | Public API path | Feasible scope | Limit / verification gate |
|---|---|---|---|
| Bluetooth | BluetoothManager/Adapter, LE scanner, GATT | Feature/state now; BLE ads, RSSI, services and authorized GATT later | No raw HCI; real peripheral/GATT test required |
| NFC | NfcManager/Adapter, Tag, Ndef, IsoDep | Feature/state now; foreground reader/NDEF/APDU later | Physical authorized tag required; no protected credentials |
| Wi-Fi | WifiManager, ScanResult, ConnectivityManager | Feature/state now; permitted survey/current network later | Location, throttling, cached/redacted results; no monitor mode |
| Cellular | PackageManager telephony features, ConnectivityManager; later TelephonyManager/Callback, CellInfo/SubscriptionManager | Feature and active transport now; permitted LTE/NR cells later | Active transport is not modem power/registration; carrier/OEM may omit NR/neighbors |

Feature availability is a platform declaration, not proof of RF functionality.
Dual-SIM/per-subscription callbacks, NSA/SA interpretation and unavailable
sentinels require explicit testing in Phase 4. Never substitute zero for unknown.

## Permission matrix

| Permission | Phase / purpose | Runtime / conditions |
|---|---|---|
| BLUETOOTH_CONNECT | 1: Adapter state; later GATT | Request only on Bluetooth screen action, API 31+ |
| ACCESS_WIFI_STATE | 1: Wi-Fi enabled state | Normal, no runtime dialog |
| NFC | 1: NFC state; later reader mode | Normal |
| ACCESS_NETWORK_STATE | 1: active cellular transport only | Normal |
| BLUETOOTH_SCAN | 2: BLE discovery | Runtime; no neverForLocation assertion for survey app |
| COARSE + FINE_LOCATION | 2/3/4 when location-derived observations used | Request together; precise grant and location-services status checked |
| CHANGE_WIFI_STATE | 3: request Wi-Fi survey scan | Normal; obey startScan result and scan throttling |
| NEARBY_WIFI_DEVICES | Only if chosen API requires it | Not a substitute for fine location on startScan/getScanResults |
| READ_PHONE_STATE | 4: specific callbacks/subscription APIs requiring it | Runtime; add only with concrete call sites |
| BLUETOOTH_ADVERTISE / INTERNET / background location | None in Phase 1 | Omitted; no advertising, networking or background collection |

Central manager rechecks actual OS grants on refresh/resume and handles denials;
never treats an earlier grant as permanent. Adapter calls catch SecurityException
and UnsupportedOperationException. Permanently denied users can open app settings.
No permissions requested at launch; no shell-granted permissions for normal UX.

References checked before implementation:
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/reference/android/bluetooth/BluetoothAdapter
- https://developer.android.com/develop/connectivity/wifi/wifi-scan
- https://developer.android.com/reference/android/telephony/TelephonyManager
- https://developer.android.com/reference/android/nfc/NfcAdapter

## Kotlin strategy and package structure

Keep applicationId/namespace `com.example.atlas` for continuity. One app module,
simple constructor injection, no DI framework or JNI. Structure:

```
com.example.atlas
  MainActivity                  Activity permission launcher, lifecycle refresh
  model/                        Radio state contracts (no fabricated telemetry)
  permissions/                  Central operation-specific permission policy
  data/                         CapabilityRepository + Android implementation
  ui/                           DashboardViewModel, dashboard/detail/settings/history
  ui/theme/                     Existing Material 3 theme
  acquisition/{bluetooth,wifi,cellular,nfc}/  Later, independent cancellable collectors
  telemetry/{storage,export}/    Later, Room, normalized events, SAF JSON
```

Repository snapshots flow through a ViewModel StateFlow to lifecycle-aware Compose.
Refresh runs off the UI thread, with one cancellable job; no polling or background
service. Phase 1 reads hardware/state only, stores no identifiers or observations,
and shows collection as not implemented and last measurement as none.
Later collectors expose explicit start/stop and bounded streams. Phase 6 schema
will retain event UUID, radio, wall/source/elapsed times, optional identifier,
metric/value/unit, acquisition/permission status, source API, freshness and metadata;
null values encode unavailable data. Room writes and retention must be bounded;
SAF exports require a user-selected destination and identifier redaction.

## Prioritized plan / acceptance

1. Phase 0: audit and preserve template (done before implementation).
2. Phase 1: native dashboard, optional radio feature declarations, capability
   repository, permission manager, ViewModel + lifecycle state, errors, useful
   module details. Build, unit/lint checks, install/launch and physical capability
   instrumentation. No RF collection. Disable backups for future private telemetry.
3. Phase 2: BLE advertisements, bounded history, scan cancellation, then GATT.
4. Phase 3: Wi-Fi surveying, valid frequency/channel mapping, freshness/throttling.
5. Phase 4: permission-gated per-subscription LTE/NR callbacks and missing values.
6. Phase 5: foreground NFC reader, authorized NDEF and explicit APDU interactions.
7. Phase 6: normalized Room storage, retention/DataStore, history, SAF export.
8. Phase 7: audit supplied legacy source and port only demonstrated reusable parts.
9. Phase 8: integrate real radio tests, denial/revocation/lifecycle testing and Logcat.

Only Phase 1 is implemented in this iteration. Phase 1 success requires a native
APK installed/launched on the Pixel, four correct feature reports, safe permission
states, no scans/telemetry at launch and no crash. It does not satisfy the full
migration acceptance criteria. API 31 compatibility requires a separate device or
emulator test; build minSdk configuration alone is insufficient.
