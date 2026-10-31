"""Inventory and bounded collection orchestration."""

import ble_bridge
import concurrent.futures
import datetime as dt
import errno
import http.client
import ipaddress
import json
import os
import secrets
import re
import socket
import sqlite3
import subprocess
import shutil
import xml.etree.ElementTree as ET
import threading
import time
from .config import DEFAULT_PORTS
from .errors import AtlasError, log_failure
from .storage import Store
from .networks import Networks
from .observer import Observer
from .wifi_context import read_context
from .evidence import begin_batch, DISCOVERY_PORTS, freeze_collector
from . import collectors


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


class Atlas:

    def __init__(self, database, interval=300, timeout=1.5, discovery_interval=300):
        configured = os.environ.get("ATLAS_CONTROL_TOKEN")
        if configured is not None and not re.fullmatch(r"[0-9a-fA-F]{64}", configured):
            raise AtlasError("ATLAS_CONTROL_TOKEN_INVALID")
        self.control_token = (
            configured if configured is not None else secrets.token_hex(32)
        )
        self.database, self.interval, self.timeout = (str(database), interval, timeout)
        self.lock = threading.Lock()
        self.discovery_lock = threading.Lock()
        self.discovery = {
            "running": False,
            "checked": 0,
            "total": 0,
            "found": [],
            "error": None,
        }
        self.enrichment = {
            "running": False,
            "error": None,
            "ssdp": [],
            "wifi": {"available": False},
        }
        self.enrichment_lock = threading.Lock()
        self.scanning = False
        self.last_error = None
        self.last_failure = None
        self.metrics_failure = None
        self.investigation_failure = None
        self.started_at = now()
        self.investigation_error = None
        self.stop = threading.Event()
        freeze_collector(self)
        self.store = Store(self.database)
        self.store.initialize()
        self.network = Networks(self.store)
        self.observer = Observer(self.store, self.interval, self.network)
        self.wifi_context_failure = None
        self.wifi_context_path = os.environ.get(
            "ATLAS_WIFI_CONTEXT_FILE", self.database + ".wifi-context.json"
        )
        self.network_tracking = False
        self.network_pending = False
        self.network_failure = None
        self.discovery_interval = max(60, int(discovery_interval))
        self.discovery_next_due = time.monotonic() + self.discovery_interval

    def db(self):
        return self.store.connection()

    def metric(self, kind, started, clock, items, error=None, batch_id=None):
        with self.db() as connection:
            connection.execute(
                "INSERT INTO collection_runs(kind,started_at,finished_at,duration_ms,items,error,batch_id) VALUES(?,?,?,?,?,?,?)",
                (
                    kind,
                    started,
                    now(),
                    round((time.monotonic() - clock) * 1000),
                    items,
                    error,
                    batch_id,
                ),
            )
        try:
            ble_bridge.investigate(self, "sync")
            self.investigation_error = None
            self.investigation_failure = None
        except Exception as error:
            failure = log_failure(error, operation="investigation_sync")
            self.investigation_error = failure.message
            self.investigation_failure = failure.failure()

    def finish_run(self, kind, started, clock, items, error=None, batch_id=None):
        try:
            self.metric(kind, started, clock, items, error, batch_id=batch_id)
            self.metrics_failure = None
        except Exception as failure:
            self.metrics_failure = log_failure(
                failure, operation="collector_metrics"
            ).failure()

        finally:
            try:
                self.observer.completed(error is None and self.metrics_failure is None)
            except Exception as failure:
                self.observer.failure = log_failure(
                    failure, operation="observer_checkpoint"
                ).failure()

    def refresh_network(self, auto_discover=True, force=False):
        wifi = self.wifi_network()
        with self.network.lock:
            changed = self.network.observe(wifi, force=force)
            context = self.network.id
            if changed:
                self.network_pending = bool(wifi)
                self.network_failure = None
            if (
                self.network_tracking
                and self.network.current.get("connected")
                and time.monotonic() >= self.discovery_next_due
                and not self.discovery["running"]
            ):
                self.network_pending = True
            pending = self.network_pending
        if auto_discover and pending and not self.discovery["running"]:
            try:
                if self.start_discovery():
                    started_context = self.discovery.get("network_id", context)
                    with self.network.lock:
                        if self.network.id == started_context:
                            self.network_pending = False
            except AtlasError as error:
                with self.network.lock:
                    if self.network.id == context:
                        self.network_failure = error.failure()
                        self.network_pending = False
        return changed

    def network_observer(self):
        self.network_tracking = True
        while not self.stop.is_set():
            try:
                self.observer.tick()
                self.refresh_network()
            except Exception as error:
                self.network_failure = log_failure(
                    error, operation="network_detection"
                ).failure()
            self.stop.wait(2)

    def start_scan(self):
        if self.network_tracking:
            self.refresh_network()
        if not self.lock.acquire(blocking=False):
            return False
        self.scanning = True
        try:
            threading.Thread(target=self._scan_locked, daemon=True).start()
        except Exception as error:
            self.scanning = False
            self.lock.release()
            raise AtlasError("ATLAS_WORKER_START_FAILED") from error
        return True

    def runtime(self):
        return dict(
            network=self.network.snapshot(),
            network_pending=self.network_pending,
            network_failure=self.network_failure,
            wifi_context_failure=self.wifi_context_failure,
            discovery_interval=self.discovery_interval,
            next_discovery_in_seconds=max(
                0, round(self.discovery_next_due - time.monotonic())
            ),
            observer=self.observer.snapshot(),
            started_at=self.started_at,
            scanning=self.scanning,
            discovery=self.discovery.copy(),
            enrichment=self.enrichment.copy(),
            last_error=self.last_error,
            last_failure=self.last_failure,
            metrics_failure=self.metrics_failure,
            investigation_error=self.investigation_error,
            investigation_failure=self.investigation_failure,
            interval=self.interval,
        )

    def add(self, host, label, ports, network_id=None):
        network_id = self.network.id if network_id is None else network_id
        host = str(host).strip()
        try:
            ipaddress.ip_address(host)
        except ValueError:
            if not re.fullmatch(
                "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", host
            ):
                raise AtlasError(
                    "ATLAS_INPUT_INVALID",
                    "Enter an IP address or hostname, without a URL or port.",
                )
        if (
            not isinstance(ports, list)
            or not 1 <= len(ports) <= 32
            or any((type(p) is not int or not 1 <= p <= 65535 for p in ports))
        ):
            raise AtlasError(
                "ATLAS_INPUT_INVALID", "Choose 1–32 TCP ports between 1 and 65535."
            )
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            if (
                c.execute("SELECT active_id FROM network_state WHERE id=1").fetchone()[
                    "active_id"
                ]
                != network_id
            ):
                raise AtlasError("ATLAS_NETWORK_CHANGED")
            if c.execute(
                "SELECT 1 FROM targets WHERE host=? AND network_id=?",
                (host, network_id),
            ).fetchone():
                raise AtlasError("ATLAS_CONFLICT", "This target already exists.")
            if (
                c.execute(
                    "SELECT count(*) FROM targets WHERE network_id=?", (network_id,)
                ).fetchone()[0]
                >= 64
            ):
                raise AtlasError(
                    "ATLAS_LIMIT_REACHED", "Maximum 64 targets.", details={"limit": 64}
                )
            c.execute(
                "INSERT INTO targets(host,label,ports,network_id) VALUES(?,?,?,?)",
                (
                    host,
                    str(label).strip()[:100] or host,
                    json.dumps(sorted(set(ports))),
                    network_id,
                ),
            )

    def update_target(self, data):
        try:
            if isinstance(data.get("id"), bool):
                raise ValueError("Expected integer")
            target_id = int(data["id"])
        except (ValueError, TypeError, KeyError, OverflowError) as error:
            raise AtlasError(
                "ATLAS_INPUT_INVALID",
                "Target ID must be an integer.",
                details={"field": "id"},
            ) from error
        with self.db() as c:
            row = c.execute("SELECT * FROM targets WHERE id=?", (target_id,)).fetchone()
            if not row:
                raise AtlasError("ATLAS_NOT_FOUND", "Unknown device")
            label = str(data.get("label", row["label"])).strip()[:100]
            ports = data.get("ports", json.loads(row["ports"]))
            if not label:
                raise AtlasError("ATLAS_INPUT_INVALID", "Name is required")
            if (
                not isinstance(ports, list)
                or not 1 <= len(ports) <= 32
                or any((type(p) is not int or not 1 <= p <= 65535 for p in ports))
            ):
                raise AtlasError("ATLAS_INPUT_INVALID", "Choose 1–32 valid TCP ports")
            paused = data.get("paused", bool(row["paused"]))
            if type(paused) is not bool:
                raise AtlasError("ATLAS_INPUT_INVALID", "paused must be boolean")
            c.execute(
                "UPDATE targets SET label=?,ports=?,notes=?,paused=? WHERE id=?",
                (
                    label,
                    json.dumps(sorted(set(ports))),
                    str(data.get("notes", row["notes"]))[:2000],
                    int(paused),
                    target_id,
                ),
            )

    def identity(self, host, network_id=None):
        network_id = self.network.id if network_id is None else network_id
        with self.db() as c:
            row = c.execute(
                "SELECT data,at FROM identities WHERE host=? AND network_id=?",
                (host, network_id),
            ).fetchone()
        return dict(at=row["at"], **json.loads(row["data"])) if row else None

    def save_identity(self, host, data, network_id=None, batch_id=None):
        network_id = self.network.id if network_id is None else network_id
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            if (
                c.execute("SELECT active_id FROM network_state WHERE id=1").fetchone()[
                    "active_id"
                ]
                != network_id
            ):
                return False
            c.execute(
                "INSERT OR REPLACE INTO identities(host,data,at,network_id,batch_id) VALUES(?,?,?,?,?)",
                (host, json.dumps(data), now(), network_id, batch_id),
            )

    def start_enrichment(self):
        if not self.enrichment_lock.acquire(blocking=False):
            return False
        self.enrichment = {
            "running": True,
            "error": None,
            "ssdp": [],
            "wifi": {"available": False, "reason": "Termux Wi-Fi API is unavailable."},
        }
        try:
            threading.Thread(target=self.enrich, daemon=True).start()
        except Exception as error:
            self.enrichment["running"] = False
            self.enrichment_lock.release()
            raise AtlasError("ATLAS_WORKER_START_FAILED") from error
        return True

    def enrich(self):
        context = self.network.id
        started, clock = (now(), time.monotonic())
        batch_id = None
        try:
            batch_id = begin_batch(self, "enrichment", context)
            wifi_cmd = shutil.which("termux-wifi-connectioninfo")
            if wifi_cmd:
                try:
                    r = subprocess.run(
                        [wifi_cmd], capture_output=True, text=True, timeout=6
                    )
                    value = json.loads(r.stdout)
                    self.enrichment["wifi"] = {
                        "available": True,
                        "data": value,
                        "scope": "This phone only; does not identify other devices SSIDs.",
                    }
                except (ValueError, OSError, subprocess.TimeoutExpired):
                    self.enrichment["wifi"] = {
                        "available": False,
                        "reason": "Android Wi-Fi API did not return usable information.",
                        "failure": AtlasError("ATLAS_WIFI_API_UNAVAILABLE").failure(),
                    }
            wifi = self.wifi_network()
            if wifi:
                net = ipaddress.ip_network(wifi["cidr"])
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                    sock.setsockopt(
                        socket.IPPROTO_IP,
                        socket.IP_MULTICAST_IF,
                        socket.inet_aton(wifi["address"]),
                    )
                    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
                    sock.bind((wifi["address"], 0))
                    sock.settimeout(0.4)
                    sock.sendto(
                        b'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n',
                        ("239.255.255.250", 1900),
                    )
                    deadline = time.monotonic() + 4
                    found = {}
                    while time.monotonic() < deadline:
                        try:
                            packet, peer = sock.recvfrom(8192)
                        except socket.timeout:
                            continue
                        if ipaddress.ip_address(peer[0]) not in net:
                            continue
                        headers = {}
                        for line in packet.decode("utf-8", "replace").split("\r\n")[1:]:
                            key, sep, value = line.partition(":")
                            if sep and key.lower() in (
                                "server",
                                "st",
                                "usn",
                                "location",
                                "cache-control",
                            ):
                                headers[key.lower()] = value.strip()[:500]
                        if headers:
                            found[
                                peer[0], headers.get("usn", ""), headers.get("st", "")
                            ] = {"host": peer[0], **headers}
                        if len(found) >= 128:
                            break
                    self.enrichment["ssdp"] = list(found.values())
                    for host in {r["host"] for r in found.values()}:
                        if not self.network.is_current(context):
                            break
                        identity = self.identity(host, context) or {}
                        identity.pop("at", None)
                        identity["ssdp"] = [
                            r for r in found.values() if r["host"] == host
                        ]
                        location = next(
                            (
                                r.get("location")
                                for r in identity["ssdp"]
                                if r.get("location")
                            ),
                            None,
                        )
                        if location:
                            try:
                                identity["description"] = self.device_description(
                                    host, location
                                )
                            except (
                                ValueError,
                                OSError,
                                http.client.HTTPException,
                                ET.ParseError,
                            ):
                                failure = AtlasError(
                                    "ATLAS_METADATA_FAILED",
                                    details={"stage": "ssdp_description"},
                                )
                                identity["description_error"] = failure.message
                                identity["description_failure"] = failure.failure()
                        if not self.network.is_current(context):
                            break
                        self.save_identity(host, identity, context, batch_id=batch_id)
                        try:
                            self.add(host, host, DEFAULT_PORTS, network_id=context)
                        except (ValueError, sqlite3.IntegrityError):
                            pass
            with self.db() as c:
                hosts = [
                    r["host"]
                    for r in c.execute(
                        "SELECT host FROM targets WHERE network_id=?", (context,)
                    )
                ]

            def resolve(host):
                if not self.network.is_current(context):
                    return
                identity = self.identity(host, context) or {}
                identity.pop("at", None)
                try:
                    ipaddress.ip_address(host)
                    script = "import socket,sys,json; print(json.dumps(socket.gethostbyaddr(sys.argv[1])[0]))"
                    r = subprocess.run(
                        ["python3", "-c", script, host],
                        capture_output=True,
                        text=True,
                        timeout=2,
                    )
                    if r.returncode == 0:
                        identity["reverse_dns"] = json.loads(r.stdout)
                    else:
                        identity["reverse_dns_note"] = "No reverse DNS name returned"
                except (ValueError, OSError, subprocess.TimeoutExpired):
                    identity["reverse_dns_note"] = (
                        "Reverse DNS unavailable or timed out"
                    )
                if self.network.is_current(context):
                    self.save_identity(host, identity, context, batch_id=batch_id)

            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                list(pool.map(resolve, hosts))
        except Exception as e:
            failure = log_failure(
                e, operation="enrichment", fallback="ATLAS_COLLECTOR_FAILED"
            )
            self.enrichment["error"] = failure.message
            self.enrichment["failure"] = failure.failure()
        finally:
            try:
                self.finish_run(
                    "enrichment",
                    started,
                    clock,
                    len(self.enrichment["ssdp"]),
                    self.enrichment["error"],
                    batch_id=batch_id,
                )
            finally:
                self.enrichment["running"] = False
                self.enrichment_lock.release()

    def discovery_scope(self):
        wifi = self.wifi_network()
        if not wifi:
            raise AtlasError(
                "ATLAS_WIFI_UNAVAILABLE", "Wi-Fi IPv4 address is unavailable."
            )
        network = ipaddress.ip_network(wifi["cidr"])
        if not network.is_private or network.num_addresses > 256:
            raise AtlasError(
                "ATLAS_SCOPE_UNSUPPORTED",
                "Discovery requires a private Wi-Fi subnet of at most 256 addresses.",
            )
        return (wifi, [str(h) for h in network.hosts() if str(h) != wifi["address"]])

    def discover_host(self, host):
        context = self.discovery.get("network_id", self.network.id)
        opened, refused = ([], False)
        ports = DISCOVERY_PORTS
        for port in ports:
            if not self.network.is_current(context):
                return None
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.settimeout(0.3)
                code = sock.connect_ex((host, port))
            if code == 0:
                opened.append(port)
            elif code == errno.ECONNREFUSED:
                refused = True
        return (
            {
                "host": host,
                "open_ports": opened,
                "evidence": (
                    "TCP connection accepted" if opened else "TCP connection refused"
                ),
            }
            if opened or refused
            else None
        )

    def start_discovery(self):
        wifi, hosts = self.discovery_scope()
        if self.network_tracking and self.network.fingerprint(
            wifi
        ) != self.network.snapshot().get("fingerprint"):
            self.network.observe(wifi)
            self.network_pending = True
        context = self.network.id
        if not self.discovery_lock.acquire(blocking=False):
            return False
        self.discovery_next_due = time.monotonic() + self.discovery_interval
        self.discovery = dict(
            running=True,
            checked=0,
            total=len(hosts),
            found=[],
            error=None,
            cidr=wifi["cidr"],
            network_id=context,
            cancelled=False,
        )
        try:
            threading.Thread(
                target=self.discover, args=(hosts, context), daemon=True
            ).start()
        except Exception as error:
            self.discovery["running"] = False
            self.discovery_lock.release()
            raise AtlasError("ATLAS_WORKER_START_FAILED") from error
        return True

    def discover(self, hosts, context=None):
        context = self.network.id if context is None else context

        def current_probe(host):
            if not self.network.is_current(context):
                return None
            return self.discover_host(host)

        started, clock = (now(), time.monotonic())
        batch_id = None
        try:
            batch_id = begin_batch(self, "discovery", context)
            with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
                futures = [pool.submit(current_probe, host) for host in hosts]
                for future in concurrent.futures.as_completed(futures):
                    result = future.result()
                    if not self.network.is_current(context):
                        self.discovery["cancelled"] = True
                        self.discovery["error"] = AtlasError(
                            "ATLAS_NETWORK_CHANGED"
                        ).message
                        for pending in futures:
                            pending.cancel()
                        break
                    self.discovery["checked"] += 1
                    if result:
                        try:
                            self.add(
                                result["host"],
                                result["host"],
                                sorted(set(DEFAULT_PORTS + result["open_ports"])),
                                network_id=context,
                            )
                            result["added"] = True
                        except (ValueError, sqlite3.IntegrityError) as e:
                            result["added"] = False
                            result["note"] = str(e)
                        self.discovery["found"] = self.discovery["found"] + [result]
            if self.network.is_current(context):
                self.scan()
        except Exception as e:
            failure = log_failure(
                e, operation="discovery", fallback="ATLAS_COLLECTOR_FAILED"
            )
            self.discovery["error"] = failure.message
            self.discovery["failure"] = failure.failure()
        finally:
            try:
                self.finish_run(
                    "discovery",
                    started,
                    clock,
                    self.discovery["checked"],
                    self.discovery["error"],
                    batch_id=batch_id,
                )
            finally:
                self.discovery["finished_at"] = now()
                if self.network.is_current(context):
                    self.discovery_next_due = time.monotonic() + self.discovery_interval
                self.discovery["running"] = False
                self.discovery_lock.release()

    def scan_target(self, target):
        ports = json.loads(target["ports"])
        try:
            address = socket.getaddrinfo(target["host"], None, type=socket.SOCK_STREAM)[
                0
            ][4][0]
        except OSError:
            return {
                "services": [],
                "error": "Name resolution failed.",
                "status": "unknown",
                "failure": AtlasError("ATLAS_DNS_FAILED").failure(),
            }
        context = dict(target).get("network_id", self.network.id)

        def current_probe(port):
            if not self.network.is_current(context):
                return {"port": port, "state": "unreachable", "cancelled": True}
            return self.probe(address, port)

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            services = list(pool.map(current_probe, ports))
        return {
            "address": address,
            "services": services,
            "status": (
                "responding"
                if any((s["state"] == "open" for s in services))
                else "no_response"
            ),
        }

    def record(self, target_id, data, network_id=None, batch_id=None):
        at = now()
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            if network_id is not None:
                if (
                    c.execute(
                        "SELECT active_id FROM network_state WHERE id=1"
                    ).fetchone()["active_id"]
                    != network_id
                ):
                    return False
            previous = c.execute(
                "SELECT data FROM observations WHERE target_id=? ORDER BY id DESC LIMIT 1",
                (target_id,),
            ).fetchone()
            messages = []
            if not previous:
                messages.append("Baseline recorded: " + data["status"])
            else:
                old = json.loads(previous["data"])
                if old["status"] != data["status"]:
                    messages.append(
                        "Response changed: " + old["status"] + " → " + data["status"]
                    )
                if not data.get("error") and (not old.get("error")):
                    old_services = {s["port"]: s for s in old["services"]}
                    for s in data["services"]:
                        prev = old_services.get(s["port"])
                        if prev is None:
                            messages.append(
                                f"TCP {s['port']}: monitoring started ({s['state']})"
                            )
                        elif prev["state"] != s["state"]:
                            messages.append(
                                f"TCP {s['port']}: {prev['state']} → {s['state']}"
                            )
                        elif s["state"] == "open":
                            for field in (
                                "title",
                                "server",
                                "banner",
                                "certificate_sha256",
                            ):
                                if (
                                    field in s
                                    and field in prev
                                    and (s[field] != prev[field])
                                ):
                                    messages.append(
                                        f"TCP {s['port']}: {field.replace('_', ' ')} changed"
                                    )
            c.execute(
                "INSERT INTO observations(id,target_id,at,data,batch_id) SELECT (SELECT value+1 FROM evidence_sequences WHERE name='observations'),?,?,?,?",
                (target_id, at, json.dumps(data), batch_id),
            )
            c.executemany(
                "INSERT INTO events(id,target_id,at,message,batch_id) SELECT (SELECT value+1 FROM evidence_sequences WHERE name='events'),?,?,?,?",
                [(target_id, at, m, batch_id) for m in messages],
            )

    def scan(self):
        if not self.lock.acquire(blocking=False):
            return False
        self.scanning = True
        return self._scan_locked()

    def _scan_locked(self):
        context = self.network.id
        started, clock, completed = (now(), time.monotonic(), 0)
        batch_id = None
        try:
            with self.db() as connection:
                targets = connection.execute(
                    "SELECT * FROM targets WHERE paused=0 AND network_id=?", (context,)
                ).fetchall()

            batch_id = begin_batch(self, "tcp", context, targets)

            def current_scan(target):
                return (
                    self.scan_target(target)
                    if self.network.is_current(context)
                    else None
                )

            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                for target, data in zip(targets, pool.map(current_scan, targets)):
                    if not self.network.is_current(context):
                        raise AtlasError("ATLAS_NETWORK_CHANGED")
                    if (
                        self.record(
                            target["id"], data, network_id=context, batch_id=batch_id
                        )
                        is False
                    ):
                        raise AtlasError("ATLAS_NETWORK_CHANGED")
                    completed += 1
            self.last_error = None
            self.last_failure = None
        except Exception as error:
            failure = log_failure(
                error, operation="tcp_collection", fallback="ATLAS_COLLECTOR_FAILED"
            )
            self.last_error = failure.message
            self.last_failure = failure.failure()
        finally:
            try:
                self.finish_run(
                    "TCP observation",
                    started,
                    clock,
                    completed,
                    self.last_error,
                    batch_id=batch_id,
                )
            finally:
                self.scanning = False
                self.lock.release()
        return True

    def scheduler(self):
        while not self.stop.is_set():
            self.scan()
            self.stop.wait(self.interval)

    def state(self):
        with self.db() as c:
            targets = []
            for row in c.execute(
                "SELECT * FROM targets WHERE network_id=? ORDER BY id",
                (self.network.id,),
            ):
                target = dict(row)
                target["identity"] = self.identity(row["host"])
                target["ports"] = json.loads(target["ports"])
                obs = c.execute(
                    "SELECT at,data FROM observations WHERE target_id=? ORDER BY id DESC LIMIT 1",
                    (row["id"],),
                ).fetchone()
                target["latest"] = (
                    dict(at=obs["at"], **json.loads(obs["data"])) if obs else None
                )
                counts = c.execute(
                    "SELECT count(*),sum(CASE WHEN json_extract(data,'$.status')='responding' THEN 1 ELSE 0 END) FROM observations WHERE target_id=?",
                    (row["id"],),
                ).fetchone()
                summary = c.execute(
                    "SELECT min(at),max(CASE WHEN json_extract(data,'$.status')='responding' THEN at END),count(*) FROM observations WHERE target_id=?",
                    (row["id"],),
                ).fetchone()
                target["first_observed"], target["last_response"], target["checks"] = (
                    tuple(summary)
                )
                target["response_rate"] = (
                    round(100 * (counts[1] or 0) / counts[0]) if counts[0] else None
                )
                targets.append(target)
            events = [
                dict(r)
                for r in c.execute(
                    "SELECT e.*,t.label FROM events e JOIN targets t ON e.target_id=t.id ORDER BY e.id DESC LIMIT 200"
                )
            ]
            since = (
                dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=24)
            ).isoformat(timespec="seconds")
            event_count = c.execute(
                "SELECT count(*) FROM events WHERE at>=?", (since,)
            ).fetchone()[0]
        return dict(
            network=self.network.snapshot(),
            bluetooth=ble_bridge.state(self),
            events_24h=event_count,
            enrichment=self.enrichment.copy(),
            wifi=self.wifi_network(),
            discovery=self.discovery.copy(),
            targets=targets,
            events=events,
            scanning=self.scanning,
            error=self.last_error,
            last_failure=self.last_failure,
            interval=self.interval,
        )

    def wifi_network(self):
        visible = collectors.wifi_network()
        result, self.wifi_context_failure = read_context(
            self.wifi_context_path, visible, time.time()
        )
        return result

    def device_description(self, host, location):
        return collectors.device_description(host, location)

    def probe(self, address, port):
        return collectors.probe(address, port, self.timeout)
