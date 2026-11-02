# WiFiman cellular comparison - 2026-11-01

## Scope and evidence

Read-only inspection of the installed WiFiman 2.14.0 (com.ubnt.usurvey,
versionCode 214000007, target SDK 36), Android package permission state,
its base APK DEX method references and targeted disassembly, and Atlas source.
Base APK SHA-256: 17A4DC300B16D4BD7B1F38FAED09462752901078E207F184BA1B8DA0CF46E8C3.
Raw APK, disassembly, UI XML and screenshots remain in ignored app/build.
No third-party implementation was copied into Atlas.

## Verified observations (high confidence)

- WiFiman's Speed overview displayed Cellular, Standard 5G and Signal -104 dBm.
  This was a single UI observation, not a simultaneous comparison with Atlas.
- Installed permission state showed precise/coarse location, READ_PHONE_STATE,
  ACCESS_NETWORK_STATE and INTERNET granted. Permission possession alone does
  not prove a particular screen needs each permission.
- In classes3.dex, obfuscated class cv1 implements onSignalStrengthsChanged
  and onDisplayInfoChanged through PhoneStateListener. The signal handler calls
  SignalStrength.getCellSignalStrengths(), searches for CellSignalStrengthNr
  first, reads getDbm(), and falls back to CellSignalStrengthLte.getDbm() when
  no NR instance is found, then other technologies. It emits a nullable integer.
  This establishes compiled behavior; it is not a runtime method trace proving
  the source of the particular -104 reading.
- The inspected code invokes TelephonyManager.listen(PhoneStateListener, int).
  Method references also include createForSubscriptionId, subscription selection,
  getNetworkType and getOverrideNetworkType.
- Android documents NR getDbm() as SS-RSRP. A generic cellular dBm label should
  not be described as Wi-Fi-style RSSI or interpreted as a throughput test.
- Atlas already registers TelephonyCallback.SignalStrengthsListener and maps
  SignalStrength.cellSignalStrengths into separate LTE and NR reports. Its NR
  ssRsrp is the same measurement quantity returned by NR getDbm(). Atlas also
  reads NR ssRsrq/ssSinr and LTE rsrp/rsrq/rssnr when supplied.

## Interpretation and limits

High confidence: WiFiman contains a standard public-API path for cellular signal
reporting; no special modem access is needed for the observed kind of signal
reading. Atlas has the corresponding modern callback path already. Retain
TelephonyCallback: PhoneStateListener is deprecated from API 31.

WiFiman selects a single headline signal, preferring NR over LTE in the inspected
handler. That presentation can hide the distinction between individual RAT
reports; Atlas deliberately keeps those measurements separate. This inspection
is not evidence that WiFiman obtains missing LTE values while NR is reported.

Detailed Signal -> Cellular UI inspection was interrupted by foreground-app
changes; further taps stopped. Full detailed cellular screen contents are NOT
VERIFIED. No speed test was run, VPN enabled, permission grant changed, or radio
setting changed. Previously displayed speed results were historical and are not
new test evidence. Atlas application code/APK was not changed by this comparison.
No new build or tests were required for this documentation-only change.

## References

- https://developer.android.com/reference/android/telephony/CellSignalStrengthNr#getDbm()
- https://developer.android.com/reference/android/telephony/PhoneStateListener
- https://help.ui.com/hc/en-us/articles/205204150-Using-WiFiman

The Ubiquiti guide distinguishes cellular/Wi-Fi speed tests from signal views;
it does not document WiFiman's internal telephony implementation. Conclusions
about that implementation above come from the installed APK, not marketing.

## Migration decision

Phase 6 retains Atlas's TelephonyCallback-based acquisition. The WiFiman inspection did not establish a missing privileged signal path or additional LTE visibility. Storage and export are documented in PHASE6_STORAGE.md; NFC Phase 5 remains pending.
