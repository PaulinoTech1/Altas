"""Independent numerical/reference and end-to-end research API checks."""

import datetime as dt
import http.client
import json
import statistics
import tempfile
import threading
import unittest
from pathlib import Path
from http.server import ThreadingHTTPServer
from atlas import Atlas, handler
from atlaslib.errors import AtlasError
from ble_bridge import investigate


class ResearchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Atlas(Path(self.tmp.name) / "db")

    def tearDown(self):
        self.tmp.cleanup()

    def call(self, op, **data):
        return investigate(self.app, op, **data)

    def capture(self):
        lines = [
            f"12:{i//60:02}:{i%60:02}.000 AA:BB:CC:DD:EE:FF {-80 if i<32 else -40} 05095465737404ff4c00{i:02x}"
            for i in range(64)
        ]
        return self.call(
            "import",
            source="synthetic fixture",
            name="<script>fixture</script>",
            text="\n".join(lines),
        )["session_id"]

    def timings(self):
        self.app.add("127.0.0.1", "<script>fixture</script>", [80])
        values = [2.0] * 32 + [22.0] * 32
        start = dt.datetime(2026, 1, 1, tzinfo=dt.timezone.utc)
        with self.app.db() as c:
            c.executemany(
                "INSERT INTO observations(target_id,at,data) VALUES(1,?,?)",
                [
                    (
                        (start + dt.timedelta(seconds=i)).isoformat(),
                        json.dumps(
                            {
                                "status": "responding",
                                "services": [
                                    {"port": 80, "state": "open", "latency_ms": v}
                                ],
                            }
                        ),
                    )
                    for i, v in enumerate(values)
                ],
            )
        return values

    def test_ble_statistics_match_python_reference(self):
        sid = self.capture()
        r = self.call(
            "research", dataset="ble", session_id=sid, window=8, threshold_db=20
        )
        self.assertIn("ARM64", r["backend"])
        self.assertEqual(r["method_version"], "atlas-research/1")
        d = r["devices"][0]
        s = d["signal"]
        values = [-80] * 32 + [-40] * 32
        self.assertEqual(s["samples"], 64)
        self.assertEqual(s["mean_dbm"], statistics.mean(values))
        self.assertAlmostEqual(
            s["sample_stddev_db"], statistics.stdev(values), places=10
        )
        self.assertEqual(s["rssi_entropy_bits"], 1.0)
        self.assertEqual(s["median_dbm"], -80.0)
        self.assertEqual(
            d["payload"]["differing_bits"],
            sum((i ^ (i - 1)).bit_count() for i in range(1, 64)),
        )
        self.assertTrue(
            any(
                s["sample_index"] == 32 and s["delta_db"] == 40
                for s in d["candidate_signal_shifts"]
            )
        )

    def test_tcp_statistics_and_pinned_reproduction(self):
        values = self.timings()
        r = self.call(
            "research",
            dataset="network",
            target_id=1,
            port=80,
            window=8,
            threshold_ms=10,
        )
        self.assertEqual(r["signal"]["mean_ms"], statistics.mean(values))
        self.assertAlmostEqual(
            r["signal"]["sample_stddev_ms"], statistics.stdev(values), places=10
        )
        self.assertTrue(
            any(
                s["sample_index"] == 32 and s["delta_ms"] == 20
                for s in r["candidate_timing_shifts"]
            )
        )
        through = r["provenance"]["through_observation_id"]
        self.app.record(
            1,
            {
                "status": "responding",
                "services": [{"port": 80, "state": "open", "latency_ms": 100}],
            },
        )
        pinned = self.call(
            "research",
            dataset="network",
            target_id=1,
            port=80,
            window=8,
            threshold_ms=10,
            through_id=through,
        )
        self.assertEqual(pinned["signal"], r["signal"])
        self.assertEqual(pinned["provenance"], r["provenance"])
        self.assertEqual(
            self.call("research", dataset="network", target_id=1, port=80)["signal"][
                "samples"
            ],
            65,
        )

    def test_sparse_empty_and_out_of_range_are_explicit(self):
        self.app.add("127.0.0.1", "fixture", [80])
        r = self.call("research", dataset="network", target_id=1, port=80)
        self.assertEqual(r["signal"]["samples"], 0)
        self.assertIsNone(r["signal"]["mean_ms"])
        self.assertTrue(r["quality"])
        for latency in [-1, 4000, 0]:
            self.app.record(
                1,
                {
                    "status": "responding",
                    "services": [{"port": 80, "state": "open", "latency_ms": latency}],
                },
            )
        r = self.call("research", dataset="network", target_id=1, port=80)
        self.assertEqual(r["excluded_samples"], 2)
        self.assertEqual(r["signal"]["samples"], 1)
        self.assertIsNone(r["signal"]["sample_stddev_ms"])

    def test_reports_escape_evidence_and_include_charts(self):
        sid = self.capture()
        r = self.call("research_report", session_id=sid)
        self.assertIn("<svg", r["html"])
        self.assertIn("&lt;script&gt;", r["html"])
        self.assertNotIn("<script>", r["html"])
        self.timings()
        r = self.call("research_report", dataset="network", target_id=1, port=80)
        self.assertIn("<svg", r["html"])
        self.assertNotIn("<script>", r["html"])

    def test_validation_and_dataset_selection(self):
        sid = self.capture()
        for args in [
            {"session_id": sid, "window": 3},
            {"session_id": sid, "threshold_db": 0},
            {"dataset": "unknown", "session_id": sid},
            {"dataset": "network", "target_id": 999, "port": 80},
        ]:
            with self.subTest(args=args), self.assertRaises(AtlasError):
                self.call("research", **args)

    def test_device_limit_counts_only_analyzed_devices(self):
        lines = [f"12:00:00 AA:BB:CC:DD:00:{i:02X} -45 020106" for i in range(65)]
        sid = self.call("import", source="fixture", text="\n".join(lines))["session_id"]
        r = self.call("research", session_id=sid)
        self.assertTrue(r["device_limit_reached"])
        self.assertEqual(r["eligible_packets"], 65)
        self.assertEqual(r["analyzed_packets"], 64)
        self.assertEqual(len(r["devices"]), 64)


class ResearchHttpTests(unittest.TestCase):
    def test_numeric_queries_and_benchmark_method(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = Atlas(Path(tmp) / "db")
            app.add("127.0.0.1", "fixture", [80])
            server = ThreadingHTTPServer(("127.0.0.1", 0), handler(app))
            threading.Thread(target=server.serve_forever, daemon=True).start()
            c = http.client.HTTPConnection("127.0.0.1", server.server_address[1])
            try:
                c.request(
                    "GET",
                    "/api/v2/research?dataset=network&target_id=1&port=80&window=8&threshold_ms=10",
                    headers={"X-Atlas-Control": app.control_token},
                )
                r = c.getresponse()
                body = json.loads(r.read())
                self.assertEqual(r.status, 200)
                self.assertEqual(body["signal"]["samples"], 0)
                c.request(
                    "GET",
                    "/api/v2/research_benchmark",
                    headers={"X-Atlas-Control": app.control_token},
                )
                r = c.getresponse()
                r.read()
                self.assertEqual(r.status, 405)
                c.request(
                    "POST",
                    "/api/v2/research_benchmark",
                    "{}",
                    {
                        "Content-Type": "application/json",
                        "X-Atlas-Control": app.control_token,
                    },
                )
                r = c.getresponse()
                body = json.loads(r.read())
                self.assertEqual(r.status, 200)
                self.assertEqual(len(body["results"]), 5)
                self.assertTrue(
                    all(x["native_median_ms"] >= 0 for x in body["results"])
                )
            finally:
                c.close()
                server.shutdown()
                server.server_close()
