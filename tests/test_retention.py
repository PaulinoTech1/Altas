import tempfile
import unittest
from pathlib import Path
from atlas import Atlas
from atlaslib.errors import AtlasError
from ble_bridge import investigate


class RetentionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Atlas(Path(self.tmp.name) / "db")
        investigate(self.app, "init")
        self.app.add("127.0.0.1", "here", [80])
        with self.app.db() as c:
            c.execute(
                "insert into network_contexts(id,label,created_at) values('other','Other','2000-01-01')"
            )
            c.execute(
                "insert into targets(id,host,label,ports,network_id) values(2,'127.0.0.1','other','[80]','other')"
            )
            for target in (1, 2):
                c.execute(
                    "insert into observations(target_id,at,data) values(?,'2000-01-01','{}')",
                    (target,),
                )
                c.execute(
                    "insert into events(target_id,at,message) values(?,'2000-01-01','old')",
                    (target,),
                )
                c.execute(
                    "insert into identity_history(entity,at,digest,data) values(?,'2000-01-01','x','{}')",
                    (f"ip:{target}",),
                )
            c.execute(
                "insert into identity_history(entity,at,digest,data) values('ip:1','2000-01-01','y','{}')"
            )

    def tearDown(self):
        self.tmp.cleanup()

    def test_scoped_counts_and_preservation_and_no_id_reuse(self):
        result = investigate(
            self.app, "purge", network_id="", age_seconds=60, delete_snapshot_ids=[1]
        )
        self.assertEqual(
            result["removed"], dict(observations=1, events=1, identity_snapshots=1)
        )
        with self.app.db() as c:
            self.assertEqual(
                [r[0] for r in c.execute("select target_id from observations")], [2]
            )
            self.assertEqual(
                c.execute("select count(*) from identity_history").fetchone()[0], 2
            )
            self.assertEqual(c.execute("select count(*) from targets").fetchone()[0], 2)
        investigate(self.app, "purge", network_id="other", age_seconds=60)
        self.app.record(1, dict(status="responding", services=[]))
        with self.app.db() as c:
            self.assertGreater(
                c.execute("select id from observations").fetchone()[0], 2
            )
        self.assertEqual(
            investigate(self.app, "purge", network_id="", age_seconds=60)["removed"][
                "observations"
            ],
            0,
        )

    def test_validation_and_transaction_rollback(self):
        for args in [
            dict(network_id="", age_seconds=0),
            dict(network_id="missing", age_seconds=60),
            dict(network_id="", age_seconds=60, delete_snapshot_ids=[1, 2]),
            dict(network_id="", age_seconds=60, delete_snapshot_ids=[True]),
        ]:
            with self.subTest(args=args), self.assertRaises(AtlasError):
                investigate(self.app, "purge", **args)
        with self.app.db() as c:
            self.assertEqual(
                c.execute("select count(*) from observations").fetchone()[0], 2
            )
            self.assertEqual(
                c.execute("select count(*) from identity_history").fetchone()[0], 3
            )
