# Assembly research lab · method version atlas-research/1

This is a bounded, offline descriptive toolkit for recorded observations. It does not start network probes, obtain radio permissions, infer location, merge rotating addresses, or turn threshold crossings into proof of causation.

Open **Research lab** on the localhost dashboard. Choose recorded TCP connect timings or an imported BLE session. Historical network contexts are available for TCP research. Source files and measurements are never replaced with demonstration data.

## Assembly tools

The AAPCS64 implementations are in `native/src/research.S`. Safe wrappers and portable Rust references are in `native/src/kernels.rs`.

| Kernel | Contract | Implementation |
|---|---|---|
| `atlas_moments_i16` | Signed i16 values; sum, sum of squares, min, max, absolute successive differences | Eight-value NEON blocks, scalar tail; 64-bit accumulators |
| `atlas_histogram_rssi` | 148 u64 bins, -127 through +20 dBm; out-of-range values ignored | Range-checked assembly histogram |
| `atlas_dot_i16` | Equal-length signed i16 arrays; signed i64 dot product | NEON widening multiplication, 64-bit vector accumulation, scalar tail |
| `atlas_hamming_bytes` | Equal-length byte arrays; differing bit count | NEON XOR/popcount blocks and bounded byte tail |
| `atlas_window_sums_i16` | Sliding sum for a valid window; n-w+1 signed i64 outputs | Linear-time assembly recurrence |

Only caller-saved registers are modified. Wrappers bound statistical arrays to 10,000 elements and payloads to 1,650 bytes. Vector loads occur only with a full block remaining. Empty arrays, odd/tail lengths, signed extrema, unaligned payload slices and histogram output canaries are exercised against independent references. The assembly uses ordinary process memory, with no privileged instructions or hardware device access.

For 10,000 signed i16 values, sums and squared sums fit in 64 bits. Sample variance uses the exact i128 numerator `n * sum_squares - sum * sum`, divided by `n*(n-1)` after conversion to f64. This avoids floating-point cancellation in nearly constant signals. Correlation likewise forms exact scaled integer covariance/variance numerators before floating-point normalization.

## BLE analysis

Measurements are grouped by address within one immutable capture session:

- Mean, sample standard deviation, nearest-rank 5th/50th/95th percentiles, min/max.
- Mean absolute successive RSSI change and lag-one packet-order correlation. Correlation is undefined for constant series and insufficient pairs.
- Shannon entropy of the **observed quantized RSSI distribution**, not cryptographic entropy.
- Adjacent, equal-length payload bit distances, unchanged-payload counts, unique payload counts and length-change counts. Different-length payloads are counted separately and are not padded into a false bit-distance comparison.
- Observed interarrival intervals, duplicate/backward clock observations and descriptive large-gap counts. A large gap exceeds the greater of 1,000 ms or three times the nearest-rank median positive interval. This does not estimate radio packet loss.
- Candidate signal shifts from two adjacent equal-size windows. Default: 16 samples per window and a 6 dB absolute mean difference. Candidates are ranked by effect magnitude, greedily separated by at least one window, capped at 20, and returned in sample order.

Limits: first 10,000 matching packets by capture sequence, up to 64 addresses in lexicographic order, and up to 500 plot points per address. Select an exact address to override the multi-address display limit. `eligible_packets` counts all valid sampled packets; `analyzed_packets` counts only displayed/analyzed address groups. Output flags identify packet/address truncation and invalid stored RSSI values.

RSSI is not a calibrated distance measurement. Observations need not be uniformly sampled or independent. There are no p-values, calibrated confidence intervals, stationarity guarantees or automatic device-identity conclusions. Absolute clock dates may be unknown; the capture importer supplies its documented rollover heuristic. Candidate indices are zero-based within the address's retained sequence.

## TCP timing analysis

Choose one target and one TCP port. The tool reads up to the latest 10,000 observations for that target and selects successful connections with recorded `latency_ms` for that port. Failed connections and absent timing are reported as excluded/missing categories, never assigned zero latency.

Accepted timing is quantized to 0.1 ms in signed i16 units. Values outside 0..3276.7 ms and invalid timestamps are excluded, not clipped. Zero is an eligible recorded/rounded timing, not an inferred failure. The same assembly moments and sliding-window kernels provide descriptive statistics and candidate timing shifts. Default shift threshold: 10 ms, with 16 samples in each adjacent window.

Sample standard deviation uses n-1; quantiles use nearest rank. Source timestamps retain the precision of the stored observations. Device scheduling, phone scheduling, routing, power state and measurement noise can all affect the result. TCP connect timing is not throughput, one-way delay, radio signal strength or physical distance. Selecting successful connections introduces a selection bias.

A response includes a hash of the loaded observation evidence and its observation IDs. `through_id` pins the upper observation ID for reproduction as new scans append data. The dashboard pins notebook exports to the analyzed range, including the empty-range case. Label/notes edits may change descriptive metadata, but fixed observation rows determine the numerical results.

## Reproducibility and reports

JSON and standalone HTML notebooks include the method version, configuration, data provenance and kernel build manifest:

- Embedded source SHA-256 over length-delimited research kernel/wrapper/analysis source files.
- Cargo.lock SHA-256, compiler version, build profile and architecture.
- BLE capture digest, session ID, sensor, source and import time; original logs remain downloadable from Capture lab.
- TCP loaded evidence hash and fixed observation bounds.

HTML reports escape all device-provided evidence and use local SVG/styles. No external assets or executable report scripts are needed. Graphs are descriptive displays of sampled observations; downsampled plots do not replace the full statistics.

The manifest identifies software/evidence inputs, not an independent attestation that the measurements reflect physical ground truth. Legacy imports can lack original source files or meaningful source digests.

## On-device performance experiments

Click **Benchmark kernels**, or run:

```sh
native/target/release/atlas-capture --research-benchmark
```

Each kernel is compared with an optimized, same-complexity Rust reference using five warmed batches of 100 calls. Statistical kernels use 10,000 values; payload bit distance uses 1,650 bytes. The median batch time is reported for both, including output allocation for sliding windows. `reference_over_native < 1` means assembly was slower. Clock noise, CPU scheduling and thermal state affect measurements; this does not benchmark the whole HTTP/database pipeline. The benchmark is bounded and runs only when explicitly requested.

## API

- `GET /api/v2/research_sources?network_id=...` — bounded contexts, targets and sessions.
- `GET /api/v2/research?dataset=ble&session_id=ID&window=16&threshold_db=6&address=...`
- `GET /api/v2/research?dataset=network&target_id=ID&port=80&window=16&threshold_ms=10&through_id=...`
- `GET /api/v2/research_report?...` — HTML notebook packaged in the existing JSON download envelope.
- `POST /api/v2/research_benchmark` with `{}` — fixed-budget benchmark.

Requests use the existing typed failure contract and two-slot native process pool. Unsupported settings return `ATLAS_INPUT_INVALID`; unknown sources return `ATLAS_NOT_FOUND`. Arrays are bounded and operations retain the native execution deadline. Research methods do not persist fabricated observations or modify collection scope.

## Verification

```sh
cargo test --manifest-path native/Cargo.toml --locked
cargo build --manifest-path native/Cargo.toml --release --locked
python3 -m unittest discover -s tests -v
# With the isolated GUI fixture and optional jsdom tooling described in README:
NODE_PATH=/tmp/atlas-gui-check/node_modules node tests/gui_research.cjs
```

Numerical integration tests compare real assembly-backed API outputs with Python statistics, exercise known step signals, check pinned reproduction, validate sparse/constant/out-of-range series and test escaped reports. Synthetic data stays in isolated temporary databases.
