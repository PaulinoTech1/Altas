import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from atlas import Atlas
from ble_bridge import investigate


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Atlas(Path(self.tmp.name) / "db")
        investigate(self.app, "init")
        self.app.add("127.0.0.1", "fixture", [80, 443])

    def tearDown(self):
        self.tmp.cleanup()

    def test_scan_contract_survives_parameter_changes_and_alerts(self):
        with patch.object(
            self.app,
            "scan_target",
            return_value={
                "status": "responding",
                "services": [{"port": 80, "state": "open", "latency_ms": 1.2}],
            },
        ):
            self.app.scan()
        self.app.timeout = 9
        self.app.update_target({"id": 1, "ports": [22]})
        with self.app.db() as c:
            batch = c.execute("select batch_id from observations").fetchone()[0]
            alert = c.execute("select batch_id from local_alerts").fetchone()[0]
        contract = investigate(self.app, "collection_contract", id=batch)
        self.assertEqual(contract["contract"]["targets"][0]["ports"], [80, 443])
        self.assertEqual(contract["contract"]["timeout_seconds"], 1.5)
        self.assertEqual(batch, alert)
        self.assertIn("ATLAS_CONTROL_AUTH_INVALID", contract["failure_catalog"])
        analysis = investigate(
            self.app, "research", dataset="network", target_id=1, port=80
        )
        self.assertEqual(analysis["collection_contracts"][0]["id"], batch)

    def test_import_contract_is_not_a_local_scan(self):
        capture = investigate(
            self.app,
            "import",
            source="test",
            text="12:00:00 AA:BB:CC:DD:EE:FF -40 020106",
        )
        result = investigate(self.app, "research", session_id=capture["session_id"])
        self.assertEqual(
            result["collection_contract"]["contract"]["provenance"], "import"
        )
        self.assertIsNone(
            result["collection_contract"]["contract"]["network_fingerprint"]
        )

    def test_failed_batch_and_stale_context_leave_no_observations(self):
        with patch.object(self.app, "scan_target", side_effect=RuntimeError("secret")):
            self.app.scan()
        with self.app.db() as c:
            self.assertEqual(
                c.execute("select count(*) from observations").fetchone()[0], 0
            )
            self.assertIsNotNone(
                c.execute("select batch_id from collection_runs").fetchone()[0]
            )
        self.assertFalse(self.app.scanning)
        self.assertTrue(self.app.lock.acquire(blocking=False))
        self.app.lock.release()
