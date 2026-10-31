"""SQLite lifecycle and backward-compatible schema initialization."""

import sqlite3
from .config import ROOT
from contextlib import contextmanager

TARGETS = """CREATE TABLE targets(id INTEGER PRIMARY KEY,host TEXT NOT NULL,label TEXT NOT NULL,
ports TEXT NOT NULL,notes TEXT NOT NULL DEFAULT '',paused INTEGER NOT NULL DEFAULT 0,
network_id TEXT NOT NULL DEFAULT '',UNIQUE(network_id,host))"""
IDENTITIES = """CREATE TABLE identities(host TEXT NOT NULL,data TEXT NOT NULL,at TEXT NOT NULL,
network_id TEXT NOT NULL DEFAULT '',PRIMARY KEY(network_id,host))"""
NETWORK_SCHEMA = """
CREATE TABLE IF NOT EXISTS network_contexts(id TEXT PRIMARY KEY,label TEXT NOT NULL,
address TEXT,cidr TEXT,fingerprint TEXT,created_at TEXT NOT NULL,connected INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS network_state(id INTEGER PRIMARY KEY CHECK(id=1),active_id TEXT NOT NULL DEFAULT '');
INSERT OR IGNORE INTO network_state(id,active_id) VALUES(1,'');
"""


class Store:
    def __init__(self, path):
        self.path = str(path)

    @contextmanager
    def connection(self):
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def initialize(self):
        with self.connection() as c:
            c.executescript(
                """
            CREATE TABLE IF NOT EXISTS collection_runs(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,started_at TEXT NOT NULL,finished_at TEXT NOT NULL,duration_ms INTEGER NOT NULL,items INTEGER NOT NULL,error TEXT);
            CREATE TABLE IF NOT EXISTS ble_samples(id INTEGER PRIMARY KEY,fingerprint TEXT UNIQUE NOT NULL,address TEXT NOT NULL,imported_at TEXT NOT NULL,data TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS ble_address ON ble_samples(address,id);
            CREATE TABLE IF NOT EXISTS observations(id INTEGER PRIMARY KEY,target_id INTEGER NOT NULL,at TEXT NOT NULL,data TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS obs_target ON observations(target_id,id);
            CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,target_id INTEGER NOT NULL,at TEXT NOT NULL,message TEXT NOT NULL);
            """
                + NETWORK_SCHEMA
            )
            c.executescript((ROOT / "evidence_schema.sql").read_text())
            c.execute("BEGIN IMMEDIATE")
            for table, definition in [("targets", TARGETS), ("identities", IDENTITIES)]:
                if not c.execute(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    (table,),
                ).fetchone():
                    c.execute(definition)
            columns = {r["name"] for r in c.execute("PRAGMA table_info(targets)")}
            for name, definition in [
                ("notes", "TEXT NOT NULL DEFAULT ''"),
                ("paused", "INTEGER NOT NULL DEFAULT 0"),
            ]:
                if name not in columns:
                    c.execute(f"ALTER TABLE targets ADD COLUMN {name} {definition}")
            host_unique = any(
                [
                    r["name"]
                    for r in c.execute(
                        'PRAGMA index_info("' + index["name"].replace('"', '""') + '")'
                    )
                ]
                == ["host"]
                for index in c.execute("PRAGMA index_list(targets)").fetchall()
                if index["unique"]
            )
            if host_unique:
                c.execute("ALTER TABLE targets RENAME TO targets_before_network_scope")
                c.execute(TARGETS)
                c.execute(
                    "INSERT INTO targets(id,host,label,ports,notes,paused,network_id) SELECT id,host,label,ports,notes,paused,'legacy' FROM targets_before_network_scope"
                )
                c.execute("DROP TABLE targets_before_network_scope")
            identity_columns = c.execute("PRAGMA table_info(identities)").fetchall()
            if [r["name"] for r in identity_columns if r["pk"]] == ["host"]:
                c.execute(
                    "ALTER TABLE identities RENAME TO identities_before_network_scope"
                )
                c.execute(IDENTITIES)
                c.execute(
                    "INSERT INTO identities(host,data,at,network_id) SELECT host,data,at,'legacy' FROM identities_before_network_scope"
                )
                c.execute("DROP TABLE identities_before_network_scope")
            if c.execute(
                "SELECT 1 FROM targets WHERE network_id='legacy' LIMIT 1"
            ).fetchone():
                c.execute(
                    "INSERT OR IGNORE INTO network_contexts(id,label,created_at,connected) VALUES('legacy','Earlier observations (network unknown)',strftime('%Y-%m-%dT%H:%M:%fZ','now'),0)"
                )

            if "visible_evidence" not in {
                r["name"] for r in c.execute("PRAGMA table_info(network_contexts)")
            }:
                c.execute(
                    "ALTER TABLE network_contexts ADD COLUMN visible_evidence TEXT"
                )
            for table in ("observations", "events", "identities", "collection_runs"):
                columns = {r["name"] for r in c.execute(f"PRAGMA table_info({table})")}
                if "batch_id" not in columns:
                    c.execute(f"ALTER TABLE {table} ADD COLUMN batch_id INTEGER")
