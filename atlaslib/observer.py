"""Local lifecycle evidence; timestamps measure missing observation opportunities."""

import datetime as dt
import json
import os
import tempfile
import threading
import time
from pathlib import Path
from .errors import AtlasError, log_failure


def iso(wall):
    return dt.datetime.fromtimestamp(wall, dt.timezone.utc).isoformat(
        timespec="seconds"
    )


class Observer:
    def __init__(self, store, interval, network):
        self.store, self.interval, self.network = store, interval, network
        self.path = Path(store.path + ".status.json")
        self.lock = threading.RLock()
        self.failure = None
        self.last_success_at = None
        self.wall, self.mono = time.time(), time.monotonic()
        with store.connection() as c:
            previous = c.execute("SELECT * FROM observer_state WHERE id=1").fetchone()
            if previous:
                self.last_success_at = previous["last_success_at"]
                self.gap(c, previous["wall"], self.wall, None, "process_restart")
        self.state = "started"
        self.checkpoint()
        self.publish()

    def gap(self, c, start, end, elapsed, reason):
        c.execute(
            "INSERT INTO collection_gaps(network_id,started_at,ended_at,wall_seconds,monotonic_seconds,reason,interval_seconds) VALUES(?,?,?,?,?,?,?)",
            (
                self.network.id,
                iso(start),
                iso(end),
                end - start,
                elapsed,
                reason,
                self.interval,
            ),
        )

    def checkpoint(self):
        with self.store.connection() as c:
            c.execute(
                "INSERT OR REPLACE INTO observer_state VALUES(1,?,?,?,?)",
                (self.wall, iso(self.wall), self.state, self.last_success_at),
            )

    def tick(self):
        with self.lock:
            wall, mono = time.time(), time.monotonic()
            delta, elapsed = wall - self.wall, mono - self.mono
            if (
                delta > self.interval
                or delta < 0
                or abs(delta - elapsed) > self.interval
            ):
                reason = (
                    "clock_discontinuity"
                    if delta < 0 or abs(delta - elapsed) > self.interval
                    else "observer_delay"
                )
                with self.store.connection() as c:
                    self.gap(c, self.wall, wall, elapsed, reason)
            self.wall, self.mono = wall, mono
            self.checkpoint()

    def completed(self, success):
        with self.lock:
            self.tick()
            self.state = "collection_completed"
            if success:
                self.last_success_at = iso(self.wall)
            self.checkpoint()
            self.publish()

    def close(self):
        with self.lock:
            self.tick()
            self.state = "stopped"
            self.checkpoint()
            self.publish()

    def snapshot(self):
        with self.lock:
            wall = time.time()
            age = (
                None
                if not self.last_success_at
                else wall - dt.datetime.fromisoformat(self.last_success_at).timestamp()
            )
            return dict(
                state=self.state,
                updated_at=iso(self.wall),
                last_successful_collection=self.last_success_at,
                wall_clock_at=iso(wall),
                seconds_since_success=age,
                interval_seconds=self.interval,
                overdue=age is not None and age > self.interval,
                status_failure=self.failure,
                interpretation="A successful run completed without a collector error; it does not imply any device responded. Gaps do not establish Android suspension as the cause.",
            )

    def publish(self):
        temporary = None
        try:
            fd, temporary = tempfile.mkstemp(
                prefix=".atlas-status-", dir=self.path.parent
            )
            with os.fdopen(fd, "w") as out:
                json.dump(self.snapshot(), out)
            os.replace(temporary, self.path)
            self.failure = None
        except OSError as error:
            self.failure = log_failure(
                AtlasError("ATLAS_STATUS_WRITE_FAILED"), operation="observer_status"
            ).failure()
        finally:
            if temporary and os.path.exists(temporary):
                os.unlink(temporary)
