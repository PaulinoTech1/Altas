import datetime as dt
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from atlas import Atlas
from atlaslib.wifi_context import read_context
from ble_bridge import investigate
from atlaslib.errors import AtlasError


class EvidenceExtensionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Atlas(Path(self.tmp.name) / "db")
        investigate(self.app, "init")
        self.visible = dict(address="192.168.1.2", cidr="192.168.1.0/24")
        self.app.network.observe(self.visible)
        self.app.add("192.168.1.3", "fixture", [80])

    def tearDown(self):
        self.tmp.cleanup()

    def test_bundle_contract_catalog_scope_and_pagination(self):
        with patch.object(
            self.app,
            "scan_target",
            return_value={"status": "responding", "services": []},
        ):
            self.app.scan()
            self.app.scan()
        scope = self.app.network.id
        first = investigate(self.app, "evidence_bundle", network_id=scope, limit=1)[
            "archive"
        ]
        self.assertTrue(first["truncation"]["observations"])
        self.assertEqual(
            first["network_fingerprint"], self.app.network.snapshot()["fingerprint"]
        )
        self.assertIn(
            "ATLAS_CONTROL_AUTH_INVALID",
            first["collection_batches"][0]["failure_catalog"],
        )
        second = investigate(
            self.app,
            "evidence_bundle",
            network_id=scope,
            limit=1,
            cursor=first["next_cursor"],
        )["archive"]
        self.assertFalse(second["truncation"]["observations"])
        self.assertNotEqual(
            first["observations"][0]["id"], second["observations"][0]["id"]
        )
        self.assertTrue(second["truncation"]["earlier_observation_pages_omitted"])
        with self.assertRaises(AtlasError):
            investigate(self.app, "evidence_bundle", network_id="unknown")
        with self.app.db() as c:
            c.execute(
                "update observations set data=?",
                (json.dumps({"text": "x" * 8_000_001}),),
            )
        with self.assertRaises(AtlasError) as caught:
            investigate(self.app, "evidence_bundle", network_id=scope, limit=1)
        self.assertEqual(caught.exception.code, "ATLAS_EXPORT_LIMIT")

    def test_gap_api_and_report_embeds_full_manifest_and_truncation(self):
        o = self.app.observer
        with patch("atlaslib.observer.time.time", return_value=o.wall + 400), patch(
            "atlaslib.observer.time.monotonic", return_value=o.mono + 400
        ):
            o.tick()
        gaps = investigate(self.app, "gaps", network_id=self.app.network.id)
        self.assertEqual(gaps["gaps"][0]["reason"], "observer_delay")
        sid = investigate(
            self.app,
            "import",
            text="\n".join(
                f"12:00:00 AA:BB:CC:DD:00:{i:02X} -40 020106" for i in range(65)
            ),
        )["session_id"]
        report = investigate(self.app, "research_report", session_id=sid)["html"]
        for key in (
            "source_hashes",
            "kernel_version",
            "packet_limit_reached",
            "device_limit_reached",
            "collection_contract",
        ):
            self.assertIn(key, report)
        self.assertIn("atlas-kernels/1", report)

    def hint(self, **overrides):
        data = {
            **self.visible,
            "observed_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "ssid": "Example",
            "bssid": "aa:bb:cc:dd:ee:ff",
            **overrides,
        }
        path = Path(self.app.wifi_context_path)
        path.write_text(json.dumps(data))
        return path

    def test_wifi_hints_fresh_matching_only_and_fingerprint_changes(self):
        path = self.hint()
        result, failure = read_context(path, self.visible, time.time())
        self.assertIsNone(failure)
        self.assertEqual(result["bssid"], "AA:BB:CC:DD:EE:FF")
        original = self.app.network.id
        self.app.network.observe(result)
        self.assertNotEqual(original, self.app.network.id)
        for overrides in (
            {"address": "10.0.0.2"},
            {"observed_at": "2000-01-01T00:00:00Z"},
            {"observed_at": "bad"},
            {"ssid": 123},
            {"bssid": "invalid"},
            {"ssid": "x" * 33},
        ):
            with self.subTest(overrides=overrides):
                result, failure = read_context(
                    self.hint(**overrides), self.visible, time.time()
                )
                self.assertEqual(result, self.visible)
                self.assertEqual(failure["code"], "ATLAS_WIFI_CONTEXT_INVALID")
        path.write_text("[")
        self.assertEqual(
            read_context(path, self.visible, time.time())[1]["code"],
            "ATLAS_WIFI_CONTEXT_INVALID",
        )
        path.write_text("x" * 4097)
        self.assertEqual(
            read_context(path, self.visible, time.time())[1]["code"],
            "ATLAS_WIFI_CONTEXT_INVALID",
        )
        path.unlink()
        self.assertEqual(
            read_context(path, self.visible, time.time())[1]["code"],
            "ATLAS_WIFI_CONTEXT_MISSING",
        )
        self.assertEqual(read_context(path, None, time.time()), (None, None))
