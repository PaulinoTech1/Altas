# BLE orientation signature and Flipper hints

## Contract and assumptions

The 3D sphere plots the **phone's facing direction when a BLE advertisement was received**. Dot size is RSSI, color distinguishes the observed address, and opacity shows the phone-orientation depth. The game rotation vector is relative and can drift. Points from different scan sessions are never combined into one sphere.

The interactive view uses the existing 2D radar's -30 to -100 dBm strength scale for concentric 3D signal shells. Stronger samples sit closer to the center, with a small inner radius so they remain selectable. Dragging orbits the view; pinching zooms it up to 2.5x. Taps use a 28 dp hit radius, and Older/Newer controls step through overlapping observations while showing the exact RSSI, address/name, and timestamp. These controls transform the display only; they do not change collected measurements or start a scan.

The plot now draws colored +X, +Y, and +Z **phone-orientation** axes in an oblique default view. Each axis uses the same -50, -70, -90, and -100 dBm tick positions as the signal shells; numeric labels along X explain the shared scale, and each positive axis endpoint is marked at -100 dBm. Axes rotate with the samples. They are reference directions for handset orientation, not coordinates of nearby devices.

| Assumption | Status | Impact if wrong | Validation |
| --- | --- | --- | --- |
| Game rotation vector is present on the target Pixel | Verified by `dumpsys sensorservice` and a callback instrumentation test | No orientation points | The UI displays an empty-state explanation |
| A foreground scan can pair a BLE advertisement with the rotation sensor | Verified on Pixel 10 Pro XL; one sample had -87 dBm RSSI and a 10 ms orientation age | Without an advertiser the plot stays empty | Repeat with a known peripheral for device-specific validation |
| Advertised names and service UUIDs are truthful | Intentionally untrusted | Flipper hint may be spoofed | Never treat the hint as identity or a security decision |

The phone's gyroscope and rotation sensor cannot establish a BLE device's bearing, range, or 3D physical location. RSSI is also affected by orientation, obstruction, multipath, and transmit behavior. The plot makes no location or distance claim.

## Signal locator

Select an observed BLE address from the 3D plot or a device row, then press **Start 20s focused search**. This runs one explicit high-intensity BLE scan with the existing stop button and Android scan cooldown. It never restarts automatically. The locator shows the latest dBm, strongest retained dBm, sample count, and whether the signal is live, stale, absent, or from an ended scan. A live reading must be no more than three seconds old. Trend compares the median of the newest three readings with the prior three, all within ten seconds; changes smaller than 6 dB are called unclear. A trend describes RSSI only, not movement toward or away from the transmitter.

This is a **nearby signal search**, not Apple's Find My network or Precision Finding. The Pixel reports UWB hardware, but the observed BLE advertiser does not automatically support UWB ranging. Flipper Zero's published hardware specification lists BLE but no UWB capability. Atlas does not show a distance, direction arrow, map coordinate, or remote ring control from a generic BLE advertisement. BLE addresses may rotate or be spoofed, so the selected address is a temporary observation key, not an authenticated device identity.

Flipper hints have three useful combinations: advertised name resembling `Flipper`, the firmware BLE serial service UUID, and both together with repeated observations. The last combination is labeled **Heightened Flipper Zero possibility**. It is a qualitative label, not a calibrated probability or proof. Generic HID UUIDs and names such as `Control` are intentionally excluded because they are shared by many devices. The app makes no automatic GATT connection.

## Pixel GPS origin and signature isolation (proposed branch)

During an explicit BLE scan, Atlas can request foreground fixes from Android's GPS provider. A BLE observation receives the **Pixel's** latitude, longitude, and reported horizontal accuracy only when a prior GNSS fix is at most ten seconds old on the same monotonic clock. If GPS is unavailable, disabled, denied, or too slow for the bounded scan, the observation has no position. These coordinates are not the advertiser's coordinates. The app does not infer a 3D transmitter location from GPS, phone orientation, or RSSI.

Selecting an address and pressing **Isolate selected signature** hides other addresses from the radar, 3D plot, and device list. Collection and the bounded in-memory history continue for all addresses so the user can restore context. Isolation is by the observed BLE address, not a verified physical identity; a rotating or spoofed address can break continuity. The selected-address history and focused RSSI locator already use that address.

Generic BLE advertisements do not establish a two-way ranging session. Android's Ranging module requires supported local and peer capabilities plus a participating peer and out-of-band negotiation. Atlas will not label a passive RSSI response as validated distance or location. A future cooperative-ranging feature must display the ranging technology, measured distance/angle availability, uncertainty, peer identity basis, and failure state separately from scan-only observations.

This branch's GNSS and isolation changes have not yet been built or exercised on the Pixel. The verification results below apply to the prior main-branch implementation.

## Resource and privacy boundaries

The game rotation listener and the optional GPS listener are registered only during an explicit bounded BLE scan and unregistered when the flow closes, including stop, timeout, and failure. An orientation is attached only when its monotonic timestamp is within 250 ms of the BLE source timestamp. The existing 1,024-event BLE history cap applies. Orientation and matched GPS fixes are currently in memory only; they are not written to Room or JSON export. No new permission, background service, network access, or external dependency was added.

## Verification on the connected Pixel

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: passed in the latest validation; 140 unit tests, zero failures. Lint completed with existing warnings.
- `:app:assembleDebugAndroidTest`: passed.
- APK install with `adb install -r` and launch: passed; existing app data was preserved.
- `BleOrientationInstrumentedTest#gameRotationSensorDeliversEventsAndCanStop`: passed on Pixel 10 Pro XL.
- `dumpsys sensorservice` recorded game rotation listener registration and removal for both bounded BLE scan attempts and the focused sensor test. This supports cleanup for those runs; it is not a general leak proof.
- `DashboardInstrumentedTest#persistentNavigationPreservesPageAndLeavesCollectorsIdle`: passed on Pixel.
- `BleOrientationInstrumentedTest#liveBleEventHasFreshPhoneOrientation`: passed with Atlas in the foreground. The diagnostic recorded one real BLE advertisement at -87 dBm paired to a rotation sample 10 ms old, without logging identifiers. Earlier background probe attempts received zero advertisements; that test setup did not establish a BLE pairing failure. Flipper detection is **NOT VERIFIED** because no Flipper Zero was available.
- `BleOrientationGlobeUiTest#tapAndTraversalExposeExactDbm`: passed on Pixel using synthetic observations. It validates tap selection, Older/Newer dBm display, and drag orbit state. It does not validate RF acquisition or pinch gestures.
- `OrientationGlobeMathTest`: passed unit coverage for dBm shells, orbit projection, touch radius, and missing measurements.
- The XYZ axis reference uses the same projection math as samples at each dBm shell; the matching-axis unit test passed. A Pixel screenshot was inspected in the final build with the +X, +Y, +Z labels and tick marks visible. Axis shortening during orbit is perspective foreshortening of the phone-orientation reference.
- `BleLocatorMathTest`: passed unit coverage for selected-address isolation, stale and unavailable signal handling, and median-based trend.
- `BleSignalLocatorUiTest#searchNeedsTargetAndExplicitStartAndStop`: passed on Pixel with synthetic data; selecting a target alone did not start a scan.
- `BleLocatorPhysicalInstrumentedTest#foregroundSearchTracksRealRssiAndStops`: passed on Pixel with one real -85 dBm reading; status changed from live to ended after Stop. No peripheral identity was logged. `dumpsys sensorservice` showed the rotation listener was removed. Direction, range, and Flipper-specific matching remain **NOT VERIFIED / unavailable** for this test.

Primary references: [Android game rotation vector](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position), [Android BLE ScanResult](https://developer.android.com/reference/android/bluetooth/le/ScanResult), [Android UWB ranging](https://developer.android.com/develop/connectivity/uwb), [Apple Find My capabilities](https://support.apple.com/en-us/104978), [Flipper hardware specification](https://docs.flipper.net/zero/development/hardware/tech-specs), [Flipper firmware serial service UUID](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/f7/ble_glue/services/serial_service_uuid.inc), [Flipper HID advertising profile](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/lib/ble_profile/extra_profiles/hid_profile.c).
