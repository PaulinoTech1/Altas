import http.client
import json
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from atlas import Atlas, handler
from atlaslib.errors import AtlasError


class ControlAuthTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Atlas(Path(self.tmp.name) / "db")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler(self.app))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, path, method="GET", token=None, extra=None):
        c = http.client.HTTPConnection(*self.server.server_address)
        headers = {"Content-Type": "application/json", **(extra or {})}
        if token is not None:
            headers["X-Atlas-Control"] = token
        c.request(method, path, "{}" if method == "POST" else None, headers)
        r = c.getresponse()
        body = r.read()
        status = r.status
        c.close()
        return status, body

    def test_assets_open_all_apis_locked_and_token_not_embedded(self):
        for path in ("/", "/api-client.js", "/investigate.js"):
            status, body = self.request(path)
            self.assertEqual(status, 200)
            self.assertNotIn(self.app.control_token.encode(), body)
        for path, method in [
            ("/api/scan", "POST"),
            ("/api/targets", "POST"),
            ("/api/backup", "GET"),
            ("/api/v2/source?session_id=1", "GET"),
            ("/api/v2/report", "GET"),
            ("/api/v2/research_report", "GET"),
            ("/api/v2/purge", "POST"),
            ("/api/v2/overview", "GET"),
        ]:
            for token in (None, "wrong"):
                with self.subTest(path=path, token=token):
                    status, body = self.request(path, method, token)
                    self.assertEqual(status, 401)
                    self.assertEqual(
                        json.loads(body)["error_code"], "ATLAS_CONTROL_AUTH_INVALID"
                    )
        with self.app.db() as c:
            self.assertEqual(c.execute("select count(*) from targets").fetchone()[0], 0)

    def test_valid_unlock_origin_checks_and_sensor_separation(self):
        token = self.app.control_token
        self.assertEqual(self.request("/api/control/unlock", "POST", token)[0], 200)
        status, body = self.request(
            "/api/scan", "POST", token, {"Origin": "http://foreign.example"}
        )
        self.assertEqual(status, 403)
        status, body = self.request(
            "/api/state", token=token, extra={"Host": "evil.example"}
        )
        self.assertEqual(status, 403)
        status, body = self.request(
            "/api/v2/ingest", "POST", token, {"Authorization": "Bearer " + token}
        )
        self.assertEqual(status, 401)
        self.assertEqual(json.loads(body)["error_code"], "ATLAS_AUTH_INVALID")

    def test_token_rotation_and_environment_validation(self):
        other = Atlas(Path(self.tmp.name) / "other")
        self.assertEqual(len(other.control_token), 64)
        self.assertNotEqual(other.control_token, self.app.control_token)
        with patch.dict(os.environ, {"ATLAS_CONTROL_TOKEN": "a" * 64}):
            self.assertEqual(
                Atlas(Path(self.tmp.name) / "configured").control_token, "a" * 64
            )
        with patch.dict(
            os.environ, {"ATLAS_CONTROL_TOKEN": "secret"}
        ), self.assertRaises(AtlasError) as error:
            Atlas(Path(self.tmp.name) / "invalid")
        self.assertEqual(error.exception.code, "ATLAS_CONTROL_TOKEN_INVALID")
