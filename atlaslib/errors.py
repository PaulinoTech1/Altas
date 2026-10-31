"""Stable failures shared with Rust through failure_codes.json.

Messages describe failures; codes, categories and HTTP statuses are the contract.
Never put request bodies, credentials, SQL or subprocess stderr in public details.
"""

import errno
import json
import logging
import sqlite3
import traceback
from pathlib import Path
from .config import ROOT

CATALOG = json.loads((ROOT / "failure_codes.json").read_text())
LOGGER = logging.getLogger("atlas")


class AtlasError(ValueError):
    def __init__(self, code, message=None, *, details=None):
        if code not in CATALOG:
            raise RuntimeError("Unregistered Atlas error code")
        self.code = code
        self.spec = CATALOG[code]
        self.message = message or self.spec["summary"]
        self.details = details or {}
        super().__init__(self.message)

    @property
    def status(self):
        return self.spec["status"]

    def failure(self):
        return dict(
            code=self.code,
            status=self.status,
            message=self.message,
            category=self.spec["category"],
            retryable=self.spec["retryable"],
            action=self.spec["action"],
            details=self.details,
        )

    def response(self, request_id):
        return dict(
            error=self.message,
            error_code=self.code,
            failure=self.failure(),
            request_id=request_id,
        )


def normalize(error, fallback="ATLAS_INTERNAL"):
    if isinstance(error, AtlasError):
        return error
    if isinstance(error, sqlite3.IntegrityError):
        return AtlasError("ATLAS_CONFLICT")
    if isinstance(error, sqlite3.Error):
        primary = getattr(error, "sqlite_errorcode", 0) & 255
        if primary in (sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED):
            code = "ATLAS_STORAGE_BUSY"
        elif primary == sqlite3.SQLITE_FULL:
            code = "ATLAS_STORAGE_FULL"
        elif primary in (sqlite3.SQLITE_CORRUPT, sqlite3.SQLITE_NOTADB):
            code = "ATLAS_STORAGE_CORRUPT"
        else:
            code = "ATLAS_STORAGE_FAILURE"
        return AtlasError(code)
    if isinstance(error, OSError):
        if error.errno == errno.ENOSPC:
            return AtlasError("ATLAS_STORAGE_FULL")
        return AtlasError("ATLAS_IO_FAILED", details={"errno": error.errno})
    return AtlasError(fallback)


def log_failure(error, request_id=None, operation=None, fallback="ATLAS_INTERNAL"):
    """Log correlation metadata only; raw exception text may contain secrets."""
    failure = normalize(error, fallback)
    frames = [
        dict(
            module=Path(frame.filename).name,
            function=frame.name,
            line=frame.lineno,
        )
        for frame in traceback.extract_tb(error.__traceback__)
    ]
    LOGGER.warning(
        json.dumps(
            dict(
                event="atlas.failure",
                code=failure.code,
                category=failure.spec["category"],
                request_id=request_id,
                operation=operation,
                exception_type=type(error).__name__,
                frames=frames,
            ),
            sort_keys=True,
        )
    )
    return failure
