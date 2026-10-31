"""Wi-Fi observation contexts. Visible link fields are hints, not SSID proof."""

import hashlib
import json
import threading
import uuid
from datetime import datetime, timezone


class Networks:
    def __init__(self, store):
        self.store = store
        self.lock = threading.RLock()
        with store.connection() as c:
            row = c.execute("SELECT active_id FROM network_state WHERE id=1").fetchone()
            self.id = row["active_id"] if row else ""
            previous = c.execute(
                "SELECT * FROM network_contexts WHERE id=?", (self.id,)
            ).fetchone()
            self.current = (
                dict(previous)
                if previous
                else dict(id="", label="Unscoped observations", connected=False)
            )
        self.observed = False

    @staticmethod
    def fingerprint(wifi):
        if not wifi:
            return None
        # Android may withhold SSID/BSSID. Never infer them from a shared subnet.
        visible = {
            k: wifi.get(k)
            for k in ("address", "cidr", "ssid", "bssid", "network_token")
            if wifi.get(k) is not None
        }
        return hashlib.sha256(json.dumps(visible, sort_keys=True).encode()).hexdigest()

    def observe(self, wifi, force=False):
        fingerprint = self.fingerprint(wifi)
        with self.lock:
            initial_unscoped = not self.observed and self.id == ""
            self.observed = True
            if (
                not force
                and not initial_unscoped
                and self.current.get("fingerprint") == fingerprint
                and self.current.get("connected") == bool(wifi)
            ):
                return False
            at = datetime.now(timezone.utc).isoformat(timespec="seconds")
            context = dict(
                id=uuid.uuid4().hex if wifi else "offline",
                label=(
                    (wifi.get("ssid") or wifi["cidr"]) if wifi else "Wi-Fi disconnected"
                ),
                address=wifi["address"] if wifi else None,
                cidr=wifi["cidr"] if wifi else None,
                fingerprint=fingerprint,
                created_at=at,
                connected=bool(wifi),
                visible_evidence=json.dumps(wifi or {}, sort_keys=True),
            )
            with self.store.connection() as c:
                c.execute(
                    "INSERT OR REPLACE INTO network_contexts(id,label,address,cidr,fingerprint,created_at,connected,visible_evidence) VALUES(:id,:label,:address,:cidr,:fingerprint,:created_at,:connected,:visible_evidence)",
                    context,
                )
                c.execute(
                    "UPDATE network_state SET active_id=? WHERE id=1", (context["id"],)
                )
            self.current = context
            self.id = context["id"]
            return True

    def is_current(self, network_id):
        with self.lock:
            return self.id == network_id

    def snapshot(self):
        with self.lock:
            return dict(self.current)
