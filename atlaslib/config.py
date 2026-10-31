"""Shared Python limits. Rust owns capture/query limits in native/src/."""

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PORTS = [22, 80, 443, 1883, 8000, 8080, 8443]
MAX_REQUEST_BYTES = 1_100_000
MAX_CAPTURE_BYTES = 1_000_000
ENGINE_TIMEOUT_SECONDS = 20
ENGINE_WAIT_SECONDS = 3
ENGINE_WORKERS = 2
