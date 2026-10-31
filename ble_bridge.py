"""Import Nordic automated scan lines; no Bluetooth privileges required."""

import json
import re
from pathlib import Path

SCAN = re.compile(
    r"(?P<time>\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+(?P<address>(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})\s+(?P<rssi>-?\d{1,3})\s+(?P<raw>[0-9A-Fa-f]{2,3300})(?=\s|$)"
)


def decode_advertisement(raw):
    data = bytes.fromhex(raw)
    result = {"records": []}
    offset = 0
    while offset < len(data):
        length = data[offset]
        offset += 1
        if length == 0:
            break
        if offset + length > len(data):
            result["warning"] = "Truncated advertisement record"
            break
        kind = data[offset]
        value = data[offset + 1 : offset + length]
        offset += length
        result["records"].append({"type": kind, "hex": value.hex()})
        if kind in (8, 9):
            result["name"] = value.decode("utf-8", "replace")
        if kind in (2, 3):
            result.setdefault("service_uuids", []).extend(
                f'{int.from_bytes(value[i:i+2],"little"):04x}'
                for i in range(0, len(value) - 1, 2)
            )
        if kind in (6, 7):
            result.setdefault("service_uuids", []).extend(
                value[i : i + 16][::-1].hex() for i in range(0, len(value) - 15, 16)
            )
        if kind == 255 and len(value) >= 2:
            result["manufacturer_id"] = int.from_bytes(value[:2], "little")
            result["manufacturer_data"] = value[2:].hex()
    return result


def investigate(app, op, **data):
    return native(dict(data, mode="investigate", op=op, database=app.database))


def import_text(app, text, source, **options):
    if not isinstance(text, str) or len(text.encode()) > 1_000_000:
        raise AtlasError(
            "ATLAS_BODY_TOO_LARGE" if isinstance(text, str) else "ATLAS_INPUT_INVALID",
            "Import must be text under 1 MB",
        )
    return investigate(app, "import", text=text, source=str(source)[:160], **options)


from atlaslib.engine import invoke as native, NATIVE
from atlaslib.errors import AtlasError, normalize


def state(app):
    with app.db() as c:
        rows = c.execute(
            """SELECT b.*,x.samples,x.first_import,x.last_import,x.rssi_min,x.rssi_max
        FROM ble_samples b JOIN (SELECT address,max(id) AS latest,count(*) AS samples,min(imported_at) AS first_import,
        max(imported_at) AS last_import,min(json_extract(data,'$.rssi')) AS rssi_min,max(json_extract(data,'$.rssi')) AS rssi_max
        FROM ble_samples GROUP BY address) x ON b.id=x.latest ORDER BY b.id DESC LIMIT 256"""
        ).fetchall()
        devices = [
            dict(
                json.loads(r["data"]),
                imported_at=r["imported_at"],
                **{
                    k: r[k]
                    for k in (
                        "samples",
                        "first_import",
                        "last_import",
                        "rssi_min",
                        "rssi_max",
                    )
                },
            )
            for r in rows
        ]
    return {
        "devices": devices,
        "engine": (
            "Rust + ARM64 assembly" if NATIVE.is_file() else "Rust engine unavailable"
        ),
        "mode": "Imported observations; not a live Bluetooth scan",
        "folder": "/sdcard/Download/AtlasBridge",
    }


def history(app, address):
    if not re.fullmatch(r"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}", address):
        raise AtlasError("ATLAS_INPUT_INVALID", "Invalid Bluetooth address.")
    with app.db() as c:
        rows = c.execute(
            "SELECT id,data FROM ble_samples WHERE address=? ORDER BY id DESC LIMIT 2000",
            (address.upper(),),
        ).fetchall()
    records = []
    for row in reversed(rows):
        r = json.loads(row["data"])
        if "time_ms" not in r:
            h, m, sec = r["time"].split(":")
            r["time_ms"] = round((int(h) * 3600 + int(m) * 60 + float(sec)) * 1000)
        records.append(
            {k: r.get(k) for k in ("time_ms", "rssi", "address", "advertisement")}
        )
        records[-1]["advertisement"] = {"name": r.get("advertisement", {}).get("name")}
    return native({"mode": "analyze", "records": records})


def import_folder(app):
    folder = Path("/sdcard/Download/AtlasBridge")
    results = []
    for path in sorted(folder.glob("*"))[:50]:
        if (
            path.suffix.lower() not in (".txt", ".log", ".csv")
            or path.is_symlink()
            or not path.is_file()
        ):
            continue
        try:
            if path.stat().st_size > 1_000_000:
                raise AtlasError(
                    "ATLAS_BODY_TOO_LARGE", details={"max_bytes": 1_000_000}
                )
            results.append(
                {
                    "file": path.name,
                    **import_text(app, path.read_text(errors="replace"), path.name),
                }
            )
        except (ValueError, OSError) as e:
            results.append(
                dict(
                    file=path.name,
                    error=normalize(e).message,
                    failure=normalize(e).failure(),
                )
            )
    return {"files": results}
