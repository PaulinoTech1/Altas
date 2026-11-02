# Phase 5 — foreground NFC reader

Implementation date: 2026-11-01. This extends the existing native app and keeps its package name and Room schema. It does not access a secure element, payment credential, or privileged Android interface.

## Contract

- The user starts reader mode from the NFC screen. Reader mode stops on user action, after 60 seconds, on navigation away, and when the activity stops. No NFC collection runs at startup or in the background.
- `NfcAdapter.ReaderCallback` supplies a `Tag`. Metadata-only mode is the default and uses `FLAG_READER_SKIP_NDEF_CHECK`; this prevents an NFC security key from generating an NDEF one-time credential during a capability check. The app reports technology list, tag ID when Android provides one, and ISO-DEP capability. NDEF reading is a separate confirmed mode for authorized ordinary tags; it runs on an I/O dispatcher and reports type/capacity/writability, records, errors and tag loss.
- NDEF content is shown as bounded hexadecimal payloads. Up to 32 records and 512 payload bytes per record are displayed. No URI is opened and no tag content is executed.
- ISO-DEP commands are user-entered hex, 4–261 bytes, and require confirmation for each send. The operation runs off the main thread with a five-second transceive timeout and closes the connection on completion, failure or reader stop. There are no payment or credential presets. A command can still alter an authorized tag, so users must choose commands carefully.
- In-memory history is bounded to 32 tag reports and 20 APDU exchanges. History Save stores only a tag-discovery event and technical summary; it excludes NDEF and APDU payloads. Redacted JSON export removes the tag ID and summary metadata. The existing Room schema is unchanged.

## Verification

- Debug and release APK compilation and 58 unit tests: passed after an initial test assertion incorrectly matched `NfcAdapter` as if it were the `NfcA` technology. The assertion now checks the removed metadata field directly.
- Physical NFC reader start/stop on the Pixel 10 Pro XL: passed in one opt-in instrumentation test. An initial test selector could not find the lazy dashboard card; switching to the list's `performScrollToNode` resolved the test navigation error. The reader then started explicitly and stopped when History was opened.
- Full-export confirmation and cancel path: passed in a separate physical instrumentation test; no document picker was opened.
- **Physical tag detection PASSED:** after the YubiKey 5C NFC was unplugged from the computer and held against the Pixel's upper back NFC antenna, Atlas received a tag report in a 3.631-second opt-in instrumentation run. Android exposed `NfcA` and `IsoDep`; the reader stopped on navigation. The log contains technology names and booleans only, with no tag ID or payload.
- **NDEF content and ISO-DEP APDU remain NOT VERIFIED.** Metadata-only mode intentionally uses `FLAG_READER_SKIP_NDEF_CHECK`, so `ndef_supported=false` in this run is not evidence that the key lacks NDEF. No APDU was sent to the security key.
- Two earlier attempts timed out after 45 and 55 seconds while the YubiKey and Pixel were separated by their computer USB connections. The 55-second run confirmed reader mode active with no callback or error. The successful physical placement explains the earlier misses; no software fix or phone security-setting change was needed.
- API 31–36 physical behavior: **NOT VERIFIED**.

References: [NfcAdapter reader mode](https://developer.android.com/reference/android/nfc/NfcAdapter), [Ndef](https://developer.android.com/reference/android/nfc/tech/Ndef), [IsoDep](https://developer.android.com/reference/android/nfc/tech/IsoDep).
