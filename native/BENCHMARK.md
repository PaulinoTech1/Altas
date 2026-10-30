# Pixel capture-engine measurements

Measured in Debian PRoot on the Pixel 10 Pro XL, release build, during implementation.

- Five-run median, 20,000 decodes per run, 1,664 ASCII hex characters per decode:
  - ARM64 NEON assembly: 0.03933 seconds
  - Portable Rust reference: 0.06346 seconds
  - Reference/native ratio: 1.61
- 10,000 synthetic Nordic scan lines: 0.881 seconds for process startup, Rust parsing, output serialization and Python JSON decoding. Excludes SQLite insertion and HTTP transport.

These are local microbenchmarks, not guarantees for real captures or battery usage. The initial scalar assembly implementation was slower than Rust; the final implementation uses 16-character NEON blocks with a scalar tail. Tests check vector boundaries and invalid characters as well as exhaustive two-byte UTF-8 input comparisons.

Reproduce the hex comparison with `cargo build --release --locked` and `./target/release/atlas-capture --benchmark`. Run several times to account for scheduling and phone thermal state.
