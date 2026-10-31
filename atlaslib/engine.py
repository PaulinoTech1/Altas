"""Bounded, typed subprocess boundary for the Rust engine."""

import json
import subprocess
import threading
from .config import ROOT, ENGINE_TIMEOUT_SECONDS, ENGINE_WAIT_SECONDS, ENGINE_WORKERS
from .errors import AtlasError, CATALOG

NATIVE = ROOT / "native/target/release/atlas-capture"
ENGINE_SLOTS = threading.BoundedSemaphore(ENGINE_WORKERS)


def invoke(request):
    if not NATIVE.is_file():
        raise AtlasError("ATLAS_ENGINE_MISSING")
    if not ENGINE_SLOTS.acquire(timeout=ENGINE_WAIT_SECONDS):
        raise AtlasError("ATLAS_ENGINE_BUSY")
    try:
        try:
            result = subprocess.run(
                [str(NATIVE)],
                input=json.dumps(request),
                capture_output=True,
                text=True,
                timeout=ENGINE_TIMEOUT_SECONDS,
            )
        except subprocess.TimeoutExpired as error:
            raise AtlasError(
                "ATLAS_ENGINE_TIMEOUT",
                details={
                    "deadline_seconds": ENGINE_TIMEOUT_SECONDS,
                    "outcome": "unknown",
                },
            ) from error
        except OSError as error:
            raise AtlasError(
                "ATLAS_ENGINE_START_FAILED", details={"errno": error.errno}
            ) from error
    finally:
        ENGINE_SLOTS.release()
    if result.returncode:
        try:
            failure = json.loads(result.stderr)["failure"]
            code = failure["code"]
            if code not in CATALOG or not isinstance(failure.get("message"), str):
                raise ValueError("Unknown native failure")
        except (ValueError, TypeError, KeyError):
            raise AtlasError(
                "ATLAS_ENGINE_CRASHED",
                details={"exit_code": result.returncode, "outcome": "unknown"},
            )
        # Rust selects a catalog message; arbitrary native stderr never reaches HTTP.
        raise AtlasError(code, failure["message"], details=failure.get("details") or {})
    try:
        value = json.loads(result.stdout)
        if not isinstance(value, (dict, list)):
            raise ValueError("Native result must be an object or array")
        return value
    except (ValueError, TypeError) as error:
        raise AtlasError(
            "ATLAS_ENGINE_PROTOCOL", details={"outcome": "unknown"}
        ) from error
