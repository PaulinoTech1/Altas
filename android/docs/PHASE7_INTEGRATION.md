# Phase 7 — legacy integration boundary

Status: **BLOCKED ON SOURCE**, 2026-11-01.

The original Atlas RF Python/Rust/Node/Debian implementation is not in this Android Studio checkout. The Phase 0 inventory and a current source search found only the native Android module, Gradle files, XML resources and test/docs artifacts. No claim of algorithm reuse or migration equivalence is possible without the legacy files. The original Android template is preserved in Git commit `dcb39bb`; no unseen external source has been removed or overwritten.

The native app already has separate Bluetooth, Wi-Fi, cellular and NFC acquisition packages and a versioned Room/JSON telemetry schema. Those are concrete integration points for any future reusable algorithm. Rust JNI remains deferred until a supplied algorithm and a representative benchmark justify it. Python processing remains external; Debian, Bubblewrap, Node and Linux command-line tools are not bundled in the APK.

Required next input: the path or archive of the original Atlas RF source. Then inspect its licenses, data formats, trust boundaries, algorithms, and tests; classify each component REUSE/PORT/BRIDGE/RETAIN EXTERNALLY/REMOVE/BLOCKED; and port only demonstrated useful behavior with parity tests. No external instrument service or port forwarding should be enabled as a substitute.
