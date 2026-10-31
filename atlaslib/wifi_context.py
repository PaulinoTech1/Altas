"""Optional user-written hints. No Android permission bridge or writer is provided."""

import datetime as dt
import json
import re
from pathlib import Path
from .errors import AtlasError


def read_context(path, visible, wall):
    if not visible:
        return visible, None
    try:
        with Path(path).open("rb") as source:
            raw = source.read(4097)
    except FileNotFoundError:
        return visible, AtlasError("ATLAS_WIFI_CONTEXT_MISSING").failure()
    except OSError:
        return visible, AtlasError("ATLAS_WIFI_CONTEXT_INVALID").failure()
    try:
        if len(raw) > 4096:
            raise ValueError()
        data = json.loads(raw)
        if not isinstance(data, dict):
            raise ValueError()
        if any(data.get(k) != visible.get(k) for k in ("address", "cidr")):
            raise ValueError()
        at = dt.datetime.fromisoformat(data["observed_at"].replace("Z", "+00:00"))
        if at.tzinfo is None or not -5 <= wall - at.timestamp() <= 120:
            raise ValueError()
        hints = {}
        for key in ("ssid", "bssid", "network_token"):
            if key not in data:
                continue
            value = data[key]
            if (
                not isinstance(value, str)
                or not value
                or len(value.encode()) > (32 if key == "ssid" else 128)
            ):
                raise ValueError()
            if any(ord(c) < 32 for c in value):
                raise ValueError()
            if key == "bssid":
                if not re.fullmatch(r"(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}", value):
                    raise ValueError()
                value = value.upper()
            hints[key] = value
        if not hints:
            raise ValueError()
        return {
            **visible,
            **hints,
            "context_hint_provenance": {
                "method": "user-supplied status file",
                "observed_at": data["observed_at"],
                "verified": False,
            },
        }, None
    except (
        ValueError,
        KeyError,
        TypeError,
        AttributeError,
        OverflowError,
        RecursionError,
    ):
        return visible, AtlasError("ATLAS_WIFI_CONTEXT_INVALID").failure()
