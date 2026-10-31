"""Regression: network changes must change collection scope, not just redraw history."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from atlas import Atlas
from ble_bridge import investigate

A = {"address": "192.168.1.10", "cidr": "192.168.1.0/24"}
B = {"address": "10.0.0.10", "cidr": "10.0.0.0/24"}
SAME_SUBNET = {"address": "192.168.1.11", "cidr": "192.168.1.0/24"}


class NetworkChangeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Atlas(Path(self.tmp.name) / "db")

    def tearDown(self):
        self.tmp.cleanup()

    def switch(self, wifi, force=False):
        with patch.object(self.app, "wifi_network", return_value=wifi):
            self.app.refresh_network(auto_discover=False, force=force)
        return self.app.network.snapshot()["id"]

    def test_same_ip_on_two_networks_has_separate_identity_and_history(self):
        old = self.switch(A)
        self.app.add("192.168.1.20", "Old device", [80])
        self.app.save_identity("192.168.1.20", {"model": "Old"})
        self.app.record(1, {"status": "responding", "services": []})
        current = self.switch(SAME_SUBNET)
        self.assertNotEqual(old, current)
        self.app.add("192.168.1.20", "New device", [443])
        self.assertIsNone(self.app.identity("192.168.1.20"))
        state = self.app.state()
        self.assertEqual([t["label"] for t in state["targets"]], ["New device"])
        with self.app.db() as c:
            self.assertEqual(
                c.execute(
                    "SELECT count(*) FROM observations WHERE target_id=1"
                ).fetchone()[0],
                1,
            )

    def test_scan_only_monitors_current_network(self):
        self.switch(A)
        self.app.add("192.168.1.20", "Old", [80])
        self.switch(B)
        self.app.add("10.0.0.20", "New", [80])
        seen = []

        def probe(target):
            seen.append(target["host"])
            return {"status": "no_response", "services": []}

        with patch.object(self.app, "scan_target", side_effect=probe), patch.object(
            self.app, "metric"
        ):
            self.app.scan()
        self.assertEqual(seen, ["10.0.0.20"])

    def test_switch_during_scan_discards_old_results(self):
        self.switch(A)
        self.app.add("192.168.1.20", "Old", [80])

        def probe(target):
            self.switch(B)
            return {"status": "responding", "services": []}

        with patch.object(self.app, "scan_target", side_effect=probe), patch.object(
            self.app, "metric"
        ):
            self.app.scan()
        with self.app.db() as c:
            self.assertEqual(
                c.execute("SELECT count(*) FROM observations").fetchone()[0], 0
            )

    def test_map_defaults_to_current_and_can_select_history(self):
        old = self.switch(A)
        self.app.add("192.168.1.20", "Old", [80])
        self.switch(B)
        self.app.add("10.0.0.20", "New", [80])
        overview = investigate(self.app, "overview")
        self.assertEqual(
            [n["host"] for n in overview["nodes"] if n["id"].startswith("ip:")],
            ["10.0.0.20"],
        )
        historical = investigate(self.app, "overview", network_id=old)
        self.assertEqual(
            [n["host"] for n in historical["nodes"] if n["id"].startswith("ip:")],
            ["192.168.1.20"],
        )

    def test_disconnect_hides_old_network_and_force_handles_identical_visible_fields(
        self,
    ):
        old = self.switch(A)
        self.app.add("192.168.1.20", "Old", [80])
        self.switch(None)
        self.assertEqual(self.app.state()["targets"], [])
        first = self.switch(A)
        self.assertNotEqual(first, old)
        second = self.switch(A, force=True)
        self.assertNotEqual(first, second)

    def test_unchanged_network_does_not_create_contexts_or_repeat_discovery(self):
        with patch.object(self.app, "wifi_network", return_value=A), patch.object(
            self.app, "start_discovery", return_value=True
        ) as start:
            self.app.refresh_network()
            self.app.refresh_network()
        self.assertEqual(start.call_count, 1)

    def test_discovery_switch_discards_stale_results(self):
        old = self.switch(A)

        def result(host):
            self.switch(B)
            return {"host": host, "open_ports": [80]}

        with patch.object(self.app, "discover_host", side_effect=result), patch.object(
            self.app, "metric"
        ):
            self.app.discovery_lock.acquire()
            self.app.discover(["192.168.1.20"], old)
        self.assertTrue(self.app.discovery["cancelled"])
        with self.app.db() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM targets").fetchone()[0], 0)

    def test_previous_network_capacity_does_not_block_new_network(self):
        old = self.switch(A)
        with self.app.db() as c:
            c.executemany(
                "INSERT INTO targets(host,label,ports,network_id) VALUES(?,?,?,?)",
                [(f"192.168.1.{i}", str(i), "[80]", old) for i in range(1, 65)],
            )
        self.switch(B)
        self.app.add("10.0.0.20", "New", [80])
        self.assertEqual(len(self.app.state()["targets"]), 1)

    def test_old_schema_migration_keeps_ids_notes_and_observations(self):
        import sqlite3

        path = Path(self.tmp.name) / "legacy"
        c = sqlite3.connect(path)
        c.executescript(
            "CREATE TABLE targets(id INTEGER PRIMARY KEY,host TEXT UNIQUE,label TEXT,ports TEXT,notes TEXT,paused INTEGER);CREATE TABLE identities(host TEXT PRIMARY KEY,data TEXT,at TEXT);CREATE TABLE observations(id INTEGER PRIMARY KEY,target_id INTEGER,at TEXT,data TEXT);"
        )
        c.execute(
            "INSERT INTO targets VALUES(7,'192.168.1.20','Old','[80]','Retain notes',1)"
        )
        c.execute("INSERT INTO identities VALUES('192.168.1.20','{}','2026-01-01')")
        c.execute("INSERT INTO observations VALUES(9,7,'2026-01-01','{}')")
        c.commit()
        c.close()
        migrated = Atlas(path)
        with migrated.db() as c:
            row = c.execute("SELECT * FROM targets WHERE id=7").fetchone()
            self.assertEqual(
                (row["network_id"], row["notes"], row["paused"]),
                ("legacy", "Retain notes", 1),
            )
            self.assertEqual(
                c.execute("SELECT target_id FROM observations WHERE id=9").fetchone()[
                    0
                ],
                7,
            )
            self.assertEqual(
                c.execute("SELECT network_id FROM identities").fetchone()[0], "legacy"
            )

    def test_new_switch_during_discovery_launch_stays_pending(self):
        def starting():
            old = self.app.network.id
            self.switch(B)
            self.app.discovery["network_id"] = old
            return True

        with patch.object(self.app, "wifi_network", return_value=A), patch.object(
            self.app, "start_discovery", side_effect=starting
        ):
            self.app.refresh_network()
        self.assertTrue(self.app.network_pending)
        self.assertEqual(self.app.network.snapshot()["cidr"], B["cidr"])

    def test_periodic_discovery_runs_only_when_due(self):
        self.switch(A)
        self.app.network_pending = False
        self.app.network_tracking = True
        self.app.discovery_next_due = 200
        with patch.object(self.app, "wifi_network", return_value=A), patch.object(
            self.app, "start_discovery", return_value=True
        ) as start, patch("atlaslib.service.time.monotonic", return_value=100):
            self.app.refresh_network()
            start.assert_not_called()
        with patch.object(self.app, "wifi_network", return_value=A), patch.object(
            self.app, "start_discovery", return_value=True
        ) as start, patch("atlaslib.service.time.monotonic", return_value=201):
            self.app.refresh_network()
            start.assert_called_once()
