#!/usr/bin/env python3
"""Atlas localhost CLI and backward-compatible public imports."""
import argparse
import http.client
import json
import shutil
import signal
import subprocess
import sys
import threading
from http.server import ThreadingHTTPServer
import ble_bridge
from atlaslib.config import ROOT, DEFAULT_PORTS
from atlaslib.service import Atlas, now
from atlaslib.http import handler
from atlaslib.errors import AtlasError, log_failure

__all__ = ["Atlas", "handler", "now", "DEFAULT_PORTS", "shutil", "subprocess", "http"]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--interval", type=int, default=300)
    p.add_argument(
        "--discovery-interval",
        type=int,
        default=300,
        help="Seconds between local discovery sweeps (minimum 60)",
    )
    p.add_argument("--database", default=str(ROOT / "atlas.sqlite3"))
    args = p.parse_args()
    if args.interval < 10:
        p.error("interval must be at least 10 seconds")
    if args.discovery_interval < 60:
        p.error("discovery interval must be at least 60 seconds")
    app = Atlas(
        args.database, args.interval, discovery_interval=args.discovery_interval
    )
    ble_bridge.investigate(app, "init")
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), handler(app))
    except OSError as error:
        raise AtlasError(
            "ATLAS_BIND_FAILED", details={"port": args.port, "errno": error.errno}
        ) from error
    app.refresh_network(auto_discover=False)
    app.network_pending = bool(app.network.snapshot().get("connected"))
    threading.Thread(target=app.network_observer, daemon=True).start()
    threading.Thread(target=app.scheduler, daemon=True).start()
    print(f"Atlas control token (shown once): {app.control_token}", flush=True)
    print(f"Atlas ready: http://127.0.0.1:{args.port}", flush=True)

    def terminate(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, terminate)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        app.stop.set()
        app.observer.close()
        server.server_close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        failure = log_failure(error, operation="startup")
        print(json.dumps(failure.response("startup")), file=sys.stderr)
        raise SystemExit(1)
