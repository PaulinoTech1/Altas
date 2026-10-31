"""Localhost HTTP adapter. Business logic lives in service/Rust modules."""

import json
import re
import secrets
import sqlite3
import tempfile
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit
import ble_bridge
from .config import ROOT, DEFAULT_PORTS, MAX_REQUEST_BYTES
from .errors import AtlasError, log_failure

READS = frozenset(
    (
        "overview",
        "sessions",
        "packets",
        "replay",
        "research",
        "research_sources",
        "research_report",
        "compare",
        "detail",
        "health",
        "collection_contract",
        "alerts",
        "sensors",
        "source",
        "report",
    )
)
WRITES = frozenset(
    (
        "import",
        "ack",
        "rules",
        "sensor_create",
        "sensor_revoke",
        "ingest",
        "research_benchmark",
        "purge",
    )
)
ASSETS = {
    "/": "index.html",
    "/app.js": "app.js",
    "/investigate.js": "investigate.js",
    "/style.css": "style.css",
    "/api-client.js": "api-client.js",
}
INTEGER_FIELDS = (
    "session_id",
    "cursor",
    "limit",
    "rssi_min",
    "rssi_max",
    "a",
    "b",
    "window",
    "threshold_db",
    "target_id",
    "port",
    "threshold_ms",
    "through_id",
    "age_seconds",
)


def handler(app):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, value, status=200):
            body = json.dumps(value).encode()
            self.send_bytes(body, "application/json", status)

        def send_bytes(self, body, content_type, status=200, filename=None):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Request-ID", self.request_id)
            if filename:
                self.send_header(
                    "Content-Disposition", 'attachment; filename="' + filename + '"'
                )
            if status == 401:
                self.send_header("WWW-Authenticate", "Bearer")
            if status == 405:
                self.send_header(
                    "Allow",
                    (
                        "GET"
                        if self.operation in READS
                        else "POST" if self.operation in WRITES else "GET, POST"
                    ),
                )
            if getattr(self, "retry_after", None):
                self.send_header("Retry-After", str(self.retry_after))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def execute(self, callback):
            self.request_id = secrets.token_hex(12)
            self.operation = "unknown"
            self.retry_after = None
            try:
                return callback()
            except (BrokenPipeError, ConnectionResetError):
                return  # The client disconnected; do not try writing a second response.
            except Exception as error:
                failure = log_failure(error, self.request_id, self.operation)
                self.retry_after = 3 if failure.spec["retryable"] else None
                try:
                    return self.reply(failure.response(self.request_id), failure.status)
                except (BrokenPipeError, ConnectionResetError):
                    return

        def do_GET(self):
            self.execute(self.get)

        def do_POST(self):
            self.execute(self.post)

        def unsupported(self):
            raise AtlasError("ATLAS_METHOD_NOT_ALLOWED")

        def do_PUT(self):
            self.execute(self.unsupported)

        def do_PATCH(self):
            self.execute(self.unsupported)

        def do_DELETE(self):
            self.execute(self.unsupported)

        def do_HEAD(self):
            self.execute(self.unsupported)

        def do_OPTIONS(self):
            self.execute(self.unsupported)

        def send_error(self, code, message=None, explain=None):
            # BaseHTTPRequestHandler protocol errors must not fall back to HTML.
            self.request_id = secrets.token_hex(12)
            self.operation = "unknown"
            failure = AtlasError(
                "ATLAS_METHOD_NOT_ALLOWED" if code == 501 else "ATLAS_INPUT_INVALID"
            )
            self.reply(failure.response(self.request_id), failure.status)

        def v2(self, path, data, write=False):
            name = path.rsplit("/", 1)[-1]
            self.operation = name if name in READS | WRITES else "unknown"
            if name not in READS | WRITES:
                raise AtlasError("ATLAS_NOT_FOUND")
            if name not in (WRITES if write else READS):
                raise AtlasError("ATLAS_METHOD_NOT_ALLOWED")
            data = {
                k: v
                for k, v in data.items()
                if k not in ("database", "mode", "op", "token")
            }
            if name == "ingest":
                authorization = self.headers.get("Authorization", "")
                data["token"] = (
                    authorization[7:] if authorization.startswith("Bearer ") else ""
                )
            for key in INTEGER_FIELDS:
                if key in data:
                    try:
                        if isinstance(data[key], bool):
                            raise ValueError("Boolean is not an integer")
                        value = data[key]
                        if not (
                            type(value) is int
                            or isinstance(value, str)
                            and re.fullmatch(r"-?[0-9]+", value)
                        ):
                            raise ValueError("Expected an integer")
                        data[key] = int(value)
                    except (ValueError, TypeError, OverflowError) as error:
                        raise AtlasError(
                            "ATLAS_INPUT_INVALID",
                            "An integer field is invalid.",
                            details={"field": key},
                        ) from error
            result = ble_bridge.investigate(app, name, **data)
            if name in ("health", "overview"):
                result["runtime"] = app.runtime()
            return self.reply(result)

        def authorize(self):
            host = self.headers.get("Host", "")
            port = self.server.server_address[1]
            if host not in (f"127.0.0.1:{port}", f"localhost:{port}"):
                raise AtlasError("ATLAS_ORIGIN_REJECTED")
            origin = self.headers.get("Origin")
            if origin and origin != "http://" + host:
                raise AtlasError("ATLAS_ORIGIN_REJECTED")
            supplied = self.headers.get("X-Atlas-Control", "")
            if not secrets.compare_digest(
                supplied.encode(), app.control_token.encode()
            ):
                raise AtlasError("ATLAS_CONTROL_AUTH_INVALID")

        def get(self):
            path = urlsplit(self.path).path
            if path.startswith("/api/"):
                self.authorize()
            if path.startswith("/api/v2/"):
                return self.v2(
                    path,
                    {
                        k: v[-1]
                        for k, v in parse_qs(
                            urlsplit(self.path).query, keep_blank_values=True
                        ).items()
                    },
                )
            if path.startswith("/api/ble/history/"):
                self.operation = "ble_history"
                return self.reply(
                    ble_bridge.history(app, unquote(path.rsplit("/", 1)[1]))
                )
            if path == "/api/backup":
                self.operation = "backup"
                with tempfile.TemporaryDirectory() as folder:
                    backup = Path(folder) / "atlas.sqlite3"
                    with app.db() as source:
                        destination = sqlite3.connect(backup)
                        try:
                            source.backup(destination)
                        finally:
                            destination.close()
                    body = backup.read_bytes()
                return self.send_bytes(
                    body, "application/octet-stream", filename="atlas-backup.sqlite3"
                )
            if path == "/api/state":
                self.operation = "state"
                return self.reply(app.state())
            if path.startswith("/api/history/"):
                self.operation = "history"
                try:
                    target_id = int(path.rsplit("/", 1)[1])
                except ValueError as error:
                    raise AtlasError(
                        "ATLAS_INPUT_INVALID", "Target ID must be an integer."
                    ) from error
                with app.db() as connection:
                    if not connection.execute(
                        "SELECT 1 FROM targets WHERE id=?", (target_id,)
                    ).fetchone():
                        raise AtlasError("ATLAS_NOT_FOUND")
                    rows = connection.execute(
                        "SELECT at,data FROM observations WHERE target_id=? ORDER BY id DESC LIMIT 200",
                        (target_id,),
                    ).fetchall()
                return self.reply(
                    [dict(at=row["at"], **json.loads(row["data"])) for row in rows]
                )
            name = ASSETS.get(path)
            if not name:
                raise AtlasError("ATLAS_NOT_FOUND")
            self.operation = "asset"
            content_type = {
                "html": "text/html",
                "js": "text/javascript",
                "css": "text/css",
            }[name.rsplit(".", 1)[1]]
            return self.send_bytes((ROOT / "static" / name).read_bytes(), content_type)

        def read_object(self):
            if (
                self.headers.get("Content-Type", "").split(";")[0].strip()
                != "application/json"
            ):
                raise AtlasError("ATLAS_MEDIA_UNSUPPORTED")
            origin = self.headers.get("Origin")
            if origin and origin != "http://" + self.headers.get("Host", ""):
                raise AtlasError("ATLAS_ORIGIN_REJECTED")
            if self.headers.get("Transfer-Encoding"):
                raise AtlasError(
                    "ATLAS_INPUT_INVALID", "Chunked request bodies are not supported."
                )
            raw_length = self.headers.get("Content-Length")
            if raw_length is None:
                raise AtlasError("ATLAS_LENGTH_REQUIRED")
            try:
                length = int(raw_length)
            except ValueError as error:
                raise AtlasError(
                    "ATLAS_INPUT_INVALID", "Content-Length must be an integer."
                ) from error
            if length > MAX_REQUEST_BYTES:
                raise AtlasError(
                    "ATLAS_BODY_TOO_LARGE", details={"max_bytes": MAX_REQUEST_BYTES}
                )
            if length <= 0:
                raise AtlasError("ATLAS_JSON_INVALID")
            self.connection.settimeout(10)
            try:
                body = self.rfile.read(length)
            except TimeoutError as error:
                raise AtlasError(
                    "ATLAS_JSON_INVALID",
                    "The request body was not received before the read deadline.",
                ) from error
            if len(body) != length:
                raise AtlasError(
                    "ATLAS_JSON_INVALID", "The request body is incomplete."
                )
            try:
                data = json.loads(body)
            except (ValueError, UnicodeError, RecursionError) as error:
                raise AtlasError("ATLAS_JSON_INVALID") from error
            if not isinstance(data, dict):
                raise AtlasError(
                    "ATLAS_JSON_INVALID", "The JSON request must be an object."
                )
            return data

        def post(self):
            self.authorize()
            data = self.read_object()
            path = urlsplit(self.path).path
            if path == "/api/control/unlock":
                return self.reply({"ok": True})
            if path.startswith("/api/v2/"):
                return self.v2(path, data, True)
            if path == "/api/ble/import":
                self.operation = "import"
                return self.reply(
                    ble_bridge.import_text(
                        app,
                        data.get("text"),
                        data.get("source", "browser import"),
                        name=data.get("name", ""),
                        session_key=data.get("session_key", ""),
                    )
                )
            if path == "/api/ble/folder":
                self.operation = "folder"
                return self.reply(ble_bridge.import_folder(app))
            if path == "/api/update":
                self.operation = "update"
                app.update_target(data)
                return self.reply({"ok": True})
            if path == "/api/targets":
                self.operation = "targets"
                app.add(
                    data.get("host", ""),
                    data.get("label", ""),
                    data.get("ports", DEFAULT_PORTS),
                )
                return self.reply({"ok": True}, 201)
            if path == "/api/network/reset":
                self.operation = "network_reset"
                app.refresh_network(force=True)
                return self.reply(
                    {"started": True, "network": app.network.snapshot()}, 202
                )
            jobs = {
                "/api/enrich": app.start_enrichment,
                "/api/discover": app.start_discovery,
                "/api/scan": app.start_scan,
            }
            if path in jobs:
                self.operation = path.rsplit("/", 1)[-1]
                if not jobs[path]():
                    raise AtlasError("ATLAS_JOB_BUSY")
                return self.reply({"started": True, "ok": True}, 202)
            raise AtlasError("ATLAS_NOT_FOUND")

        def log_message(self, *args):
            pass  # Raw URLs may contain secrets; failures use structured logging.

    return Handler
