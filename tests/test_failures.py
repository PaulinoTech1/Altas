"""Failure contracts are tested through real HTTP and injected dependency faults."""

import errno
import http.client
import json
import sqlite3
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch
from http.server import ThreadingHTTPServer
from atlas import Atlas, handler
from atlaslib import engine
from atlaslib.errors import AtlasError, CATALOG, normalize

SECRET = "sentinel-private-credential"


class NativeBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.path = MagicMock()
        self.path.is_file.return_value = True
        self.native = patch.object(engine, "NATIVE", self.path)
        self.native.start()
        self.slots = MagicMock()
        self.slots.acquire.return_value = True
        self.capacity = patch.object(engine, "ENGINE_SLOTS", self.slots)
        self.capacity.start()

    def tearDown(self):
        self.native.stop()
        self.capacity.stop()

    def failure(self, code, call):
        with self.assertRaises(AtlasError) as caught:
            call()
        self.assertEqual(caught.exception.code, code)
        self.assertNotIn(SECRET, json.dumps(caught.exception.failure()))
        return caught.exception

    def test_missing_and_saturated_engine(self):
        self.path.is_file.return_value = False
        self.failure("ATLAS_ENGINE_MISSING", lambda: engine.invoke({}))
        self.path.is_file.return_value = True
        self.slots.acquire.return_value = False
        self.failure("ATLAS_ENGINE_BUSY", lambda: engine.invoke({}))
        self.slots.release.assert_not_called()

    def test_timeout_releases_capacity(self):
        with patch.object(
            engine.subprocess,
            "run",
            side_effect=subprocess.TimeoutExpired(SECRET, 20, stderr=SECRET),
        ):
            failure = self.failure(
                "ATLAS_ENGINE_TIMEOUT", lambda: engine.invoke({"token": SECRET})
            )
        self.assertTrue(failure.spec["retryable"])
        self.slots.release.assert_called_once()

    def test_start_failure_releases_capacity(self):
        with patch.object(
            engine.subprocess, "run", side_effect=PermissionError(errno.EACCES, SECRET)
        ):
            self.failure("ATLAS_ENGINE_START_FAILED", lambda: engine.invoke({}))
        self.slots.release.assert_called_once()

    def test_native_typed_auth_failure_is_preserved(self):
        failure = AtlasError("ATLAS_AUTH_INVALID").failure()
        result = SimpleNamespace(
            returncode=1, stderr=json.dumps({"failure": failure}), stdout=""
        )
        with patch.object(engine.subprocess, "run", return_value=result):
            self.assertEqual(
                self.failure("ATLAS_AUTH_INVALID", lambda: engine.invoke({})).status,
                401,
            )

    def test_crashes_and_invalid_responses_do_not_expose_stderr(self):
        for result, code in [
            (
                SimpleNamespace(returncode=-11, stderr=SECRET, stdout=""),
                "ATLAS_ENGINE_CRASHED",
            ),
            (
                SimpleNamespace(returncode=0, stderr="", stdout=SECRET),
                "ATLAS_ENGINE_PROTOCOL",
            ),
            (
                SimpleNamespace(returncode=0, stderr="", stdout='"invalid scalar"'),
                "ATLAS_ENGINE_PROTOCOL",
            ),
        ]:
            with self.subTest(code=code), patch.object(
                engine.subprocess, "run", return_value=result
            ):
                self.failure(code, lambda: engine.invoke({}))


class HttpFailureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Atlas(Path(self.tmp.name) / "db")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler(self.app))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.conn = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_address[1]
        )
        self.logs = self.assertLogs("atlas", level="WARNING")
        self.captured = self.logs.__enter__()

    def tearDown(self):
        # Each test intentionally exercises a failing request.
        self.conn.close()
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()
        self.logs.__exit__(None, None, None)

    def request(self, method, path, body=None, headers=None):
        headers = {"X-Atlas-Control": self.app.control_token, **(headers or {})}
        self.conn.request(method, path, body, headers)
        r = self.conn.getresponse()
        return r.status, dict(r.getheaders()), json.loads(r.read())

    def check(self, result, status, code):
        actual, headers, body = result
        self.assertEqual(actual, status)
        self.assertEqual(body["error_code"], code)
        self.assertEqual(body["failure"]["code"], code)
        self.assertEqual(body["failure"]["status"], status)
        self.assertEqual(body["request_id"], headers["X-Request-ID"])
        self.assertIsInstance(body["error"], str)
        self.assertEqual(body["failure"]["retryable"], CATALOG[code]["retryable"])
        self.assertTrue(body["failure"]["action"])
        self.assertNotIn(SECRET, json.dumps(body))
        return body

    def post(self, path, data):
        return self.request(
            "POST", path, json.dumps(data), {"Content-Type": "application/json"}
        )

    def test_protocol_errors(self):
        cases = [
            (
                "POST",
                "/api/targets",
                b"{",
                {"Content-Type": "application/json"},
                400,
                "ATLAS_JSON_INVALID",
            ),
            (
                "POST",
                "/api/targets",
                b"\xff",
                {"Content-Type": "application/json"},
                400,
                "ATLAS_JSON_INVALID",
            ),
            (
                "POST",
                "/api/targets",
                "{}",
                {"Content-Type": "text/plain"},
                415,
                "ATLAS_MEDIA_UNSUPPORTED",
            ),
            (
                "POST",
                "/api/targets",
                "{}",
                {
                    "Content-Type": "application/json",
                    "Origin": "http://foreign.example",
                },
                403,
                "ATLAS_ORIGIN_REJECTED",
            ),
            (
                "POST",
                "/api/targets",
                "{}",
                {"Content-Type": "application/json", "Content-Length": "1100001"},
                413,
                "ATLAS_BODY_TOO_LARGE",
            ),
            ("GET", "/not-a-route", None, {}, 404, "ATLAS_NOT_FOUND"),
            ("GET", "/api/v2/sensor_create", None, {}, 405, "ATLAS_METHOD_NOT_ALLOWED"),
        ]
        for method, path, body, headers, status, code in cases:
            with self.subTest(code=code):
                self.check(self.request(method, path, body, headers), status, code)

    def test_native_auth_and_empty_capture(self):
        self.check(self.post("/api/v2/ingest", {}), 401, "ATLAS_AUTH_INVALID")
        self.check(
            self.post("/api/v2/import", {"text": "unsupported", "source": "test"}),
            422,
            "ATLAS_CAPTURE_EMPTY",
        )

    def test_strict_integer_validation_and_not_found(self):
        for value in (True, 1.5, "not-a-number"):
            with self.subTest(value=value):
                self.check(
                    self.post(
                        "/api/v2/import", {"text": "unsupported", "limit": value}
                    ),
                    400,
                    "ATLAS_INPUT_INVALID",
                )
        self.check(
            self.request("GET", "/api/v2/replay?session_id=999"), 404, "ATLAS_NOT_FOUND"
        )
        self.check(self.post("/api/v2/ack", {"id": 999}), 404, "ATLAS_NOT_FOUND")

    def test_internal_errors_are_not_input_errors_or_secret_leaks(self):
        with patch.object(self.app, "state", side_effect=RuntimeError(SECRET)):
            self.check(self.request("GET", "/api/state"), 500, "ATLAS_INTERNAL")
        self.assertNotIn(SECRET, "\n".join(self.captured.output))
        record = json.loads(self.captured.records[-1].getMessage())
        self.assertTrue(record["frames"])
        self.assertTrue(record["request_id"])

    def test_storage_busy_is_retryable(self):
        fault = sqlite3.OperationalError(SECRET)
        fault.sqlite_errorcode = sqlite3.SQLITE_BUSY
        with patch.object(self.app, "state", side_effect=fault):
            response = self.request("GET", "/api/state")
            self.check(response, 503, "ATLAS_STORAGE_BUSY")
            self.assertEqual(response[1]["Retry-After"], "3")

    def test_duplicate_target_and_job_busy(self):
        self.app.add("127.0.0.1", "fixture", [80])
        self.check(
            self.post("/api/targets", {"host": "127.0.0.1", "ports": [80]}),
            409,
            "ATLAS_CONFLICT",
        )
        with patch.object(self.app, "start_scan", return_value=False):
            self.check(self.post("/api/scan", {}), 409, "ATLAS_JOB_BUSY")


class CollectorFailureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Atlas(Path(self.tmp.name) / "db")

    def tearDown(self):
        self.tmp.cleanup()

    def test_metric_failure_cannot_poison_scan_lock(self):
        with patch.object(
            self.app, "metric", side_effect=sqlite3.OperationalError(SECRET)
        ), self.assertLogs("atlas"):
            self.assertTrue(self.app.scan())
            self.assertFalse(self.app.scanning)
            self.assertEqual(self.app.metrics_failure["code"], "ATLAS_STORAGE_FAILURE")
            self.assertTrue(self.app.lock.acquire(blocking=False))
            self.app.lock.release()

    def test_failed_scan_and_failed_metrics_preserve_cleanup(self):
        self.app.add("127.0.0.1", "fixture", [80])
        with patch.object(
            self.app, "scan_target", side_effect=RuntimeError(SECRET)
        ), patch.object(
            self.app, "metric", side_effect=sqlite3.OperationalError(SECRET)
        ), self.assertLogs(
            "atlas"
        ):
            self.app.scan()
        self.assertEqual(self.app.last_failure["code"], "ATLAS_COLLECTOR_FAILED")
        self.assertFalse(self.app.scanning)
        self.assertTrue(self.app.lock.acquire(blocking=False))
        self.app.lock.release()

    def test_discovery_and_enrichment_metric_failures_release_locks(self):
        with patch.object(
            self.app, "metric", side_effect=sqlite3.OperationalError(SECRET)
        ), patch.object(self.app, "scan", return_value=False), self.assertLogs("atlas"):
            self.app.discovery_lock.acquire()
            self.app.discover([])
        self.assertFalse(self.app.discovery["running"])
        self.assertTrue(self.app.discovery_lock.acquire(blocking=False))
        self.app.discovery_lock.release()
        with patch.object(self.app, "wifi_network", return_value=None), patch(
            "atlaslib.service.shutil.which", return_value=None
        ), patch.object(
            self.app, "metric", side_effect=sqlite3.OperationalError(SECRET)
        ), self.assertLogs(
            "atlas"
        ):
            self.app.enrichment_lock.acquire()
            self.app.enrich()
        self.assertFalse(self.app.enrichment["running"])
        self.assertTrue(self.app.enrichment_lock.acquire(blocking=False))
        self.app.enrichment_lock.release()

    def test_worker_start_failure_releases_scan_lock(self):
        with patch(
            "atlaslib.service.threading.Thread.start", side_effect=RuntimeError(SECRET)
        ):
            with self.assertRaises(AtlasError) as error:
                self.app.start_scan()
        self.assertEqual(error.exception.code, "ATLAS_WORKER_START_FAILED")
        self.assertFalse(self.app.scanning)
        self.assertTrue(self.app.lock.acquire(blocking=False))
        self.app.lock.release()

    def test_observation_failure_is_not_an_application_outage(self):
        with patch(
            "atlaslib.collectors.socket.create_connection",
            side_effect=ConnectionRefusedError(errno.ECONNREFUSED, SECRET),
        ):
            result = self.app.probe("127.0.0.1", 80)
        self.assertEqual(result["state"], "unreachable")
        self.assertEqual(result["failure"]["code"], "ATLAS_TCP_REFUSED")
        with patch("atlaslib.service.socket.getaddrinfo", side_effect=OSError(SECRET)):
            result = self.app.scan_target({"host": "missing.invalid", "ports": "[80]"})
        self.assertEqual(result["status"], "unknown")
        self.assertEqual(result["failure"]["code"], "ATLAS_DNS_FAILED")


class CatalogTests(unittest.TestCase):
    def test_sqlite_primary_codes(self):
        for primary, code in [
            (sqlite3.SQLITE_LOCKED, "ATLAS_STORAGE_BUSY"),
            (sqlite3.SQLITE_FULL, "ATLAS_STORAGE_FULL"),
            (sqlite3.SQLITE_CORRUPT, "ATLAS_STORAGE_CORRUPT"),
            (sqlite3.SQLITE_NOTADB, "ATLAS_STORAGE_CORRUPT"),
        ]:
            fault = sqlite3.OperationalError(SECRET)
            fault.sqlite_errorcode = primary
            self.assertEqual(normalize(fault).code, code)


class ContractDocumentationTests(unittest.TestCase):
    def test_every_code_is_documented_and_has_recovery_policy(self):
        from atlaslib.config import ROOT

        document = (ROOT / "docs/failures.md").read_text()
        for code, spec in CATALOG.items():
            with self.subTest(code=code):
                self.assertIn("`" + code + "`", document)
                self.assertIsInstance(spec["retryable"], bool)
                self.assertTrue(spec["action"])
                self.assertTrue(spec["summary"])


class JobOwnershipTests(unittest.TestCase):
    def test_start_scan_claims_ownership_before_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = Atlas(Path(tmp) / "db")
            app.add("127.0.0.1", "fixture", [80])
            entered = threading.Event()
            release = threading.Event()
            finished = threading.Event()

            def probe(target):
                entered.set()
                release.wait(3)
                return {"status": "no_response", "services": []}

            original = app._scan_locked

            def complete():
                try:
                    return original()
                finally:
                    finished.set()

            with patch.object(app, "scan_target", side_effect=probe), patch.object(
                app, "metric"
            ), patch.object(app, "_scan_locked", side_effect=complete):
                self.assertTrue(app.start_scan())
                self.assertTrue(entered.wait(2))
                self.assertFalse(app.start_scan())
                release.set()
                self.assertTrue(finished.wait(2))
            self.assertFalse(app.scanning)
