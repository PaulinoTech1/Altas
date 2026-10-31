"""Durable collection contracts and scoped evidence maintenance."""

import hashlib
import json
from .config import ROOT, DEFAULT_PORTS

DISCOVERY_PORTS = sorted(
    set(DEFAULT_PORTS + [139, 445, 554, 631, 5000, 7000, 8008, 8009, 9100])
)


def begin_batch(app, kind, context, targets=()):
    # Capture the exact immutable parameters supplied to workers, before they run.
    with app.db() as c:
        row = c.execute(
            "SELECT * FROM network_contexts WHERE id=?", (context,)
        ).fetchone()
        network = dict(row) if row else {"id": context, "fingerprint": None}
        sources = hashlib.sha256()
        for name in ("service.py", "collectors.py", "networks.py", "evidence.py"):
            content = (ROOT / "atlaslib" / name).read_bytes()
            sources.update(
                name.encode() + b"\0" + len(content).to_bytes(8, "big") + content
            )
        contract = dict(
            version="atlas-collection/1",
            collector_sha256=sources.hexdigest(),
            kind=kind,
            provenance="local scan",
            network=network,
            interval_seconds=app.interval,
            timeout_seconds=0.3 if kind == "discovery" else app.timeout,
            host_workers=32 if kind == "discovery" else 4,
            port_workers=1 if kind == "discovery" else 8,
            max_hosts=256,
            max_targets=64,
            max_ports=32,
            targets=[
                dict(id=t["id"], host=t["host"], ports=json.loads(t["ports"]))
                for t in targets
            ],
            discovery_ports=DISCOVERY_PORTS if kind == "discovery" else [],
            metadata={
                "tls_certificate_trust": "not verified",
                "ssdp_wait_seconds": 4,
                "ssdp_socket_timeout_seconds": 0.4,
                "reverse_dns_timeout_seconds": 2,
                "wifi_api_timeout_seconds": 6,
                "ssdp_description_timeout_seconds": 2,
                "ssdp_description_max_bytes": 65536,
            },
        )
        c.execute(
            "INSERT INTO collection_batches(network_id,started_at,kind,contract,failure_catalog) VALUES(?,strftime('%Y-%m-%dT%H:%M:%fZ','now'),?,?,?)",
            (
                context,
                kind,
                json.dumps(contract, sort_keys=True),
                (ROOT / "failure_codes.json").read_text(),
            ),
        )
        return c.execute("SELECT last_insert_rowid()").fetchone()[0]
