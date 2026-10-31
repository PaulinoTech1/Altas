import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from atlas import Atlas


class ObserverTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name)/'db'
        self.app = Atlas(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def test_start_complete_stop_and_restart(self):
        observer=self.app.observer
        self.assertEqual(json.loads(observer.path.read_text())['state'],'started')
        observer.completed(True)
        self.assertIsNotNone(observer.snapshot()['last_successful_collection'])
        observer.completed(False)
        success=observer.last_success_at
        observer.close()
        self.assertEqual(json.loads(observer.path.read_text())['state'],'stopped')
        other=Atlas(self.path)
        self.assertEqual(other.observer.last_success_at,success)
        with other.db() as c:
            self.assertEqual(c.execute('select reason from collection_gaps').fetchone()[0],'process_restart')

    def test_delays_backwards_clock_and_no_false_gap(self):
        o=self.app.observer
        for delta,mono,expected in [(2,2,0),(400,400,1),(-20,2,2),(400,2,3)]:
            with patch('atlaslib.observer.time.time',return_value=o.wall+delta),patch('atlaslib.observer.time.monotonic',return_value=o.mono+mono):
                o.tick()
            with self.app.db() as c:
                self.assertEqual(c.execute('select count(*) from collection_gaps').fetchone()[0],expected)

    def test_status_failure_cleans_temporary_and_keeps_collection(self):
        with patch('atlaslib.observer.os.replace',side_effect=PermissionError('private')):
            self.app.observer.completed(True)
        self.assertEqual(self.app.observer.failure['code'],'ATLAS_STATUS_WRITE_FAILED')
        self.assertEqual(list(Path(self.tmp.name).glob('.atlas-status-*')),[])
