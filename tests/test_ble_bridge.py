import tempfile
import unittest
from pathlib import Path
from atlas import Atlas
from ble_bridge import import_text,state,decode_advertisement

class BridgeTests(unittest.TestCase):
    def test_decode_and_deduplicate(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db')
            line='12:34:56.123 AA:BB:CC:DD:EE:FF -45 05095465737403030D18'
            self.assertEqual(import_text(app,line,'fixture')['imported'],1)
            self.assertEqual(import_text(app,line,'fixture')['duplicates'],1)
            d=state(app)['devices'][0]
            self.assertEqual(d['advertisement']['name'],'Test')
            self.assertEqual(d['advertisement']['service_uuids'],['180d'])
            self.assertEqual(d['rssi'],-45)
    def test_invalid_import_is_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db')
            with self.assertRaises(ValueError):import_text(app,'unsupported export','fixture')
    def test_truncated_packet(self):
        self.assertIn('warning',decode_advertisement('050954'))

class NativeWorkbenchTests(unittest.TestCase):
    def test_history_analysis_and_order(self):
        from ble_bridge import history
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db')
            text='12:00:01 AA:BB:CC:DD:EE:FF -60 020106\n12:00:00 AA:BB:CC:DD:EE:FF -40 020106'
            result=import_text(app,text,'fixture')
            self.assertEqual(result['decoder'],'ARM64 assembly')
            analysis=history(app,'aa:bb:cc:dd:ee:ff')
            self.assertEqual(analysis['mean_rssi'],-50)
            self.assertLess(analysis['points'][0]['time_ms'],analysis['points'][1]['time_ms'])
            with self.assertRaises(ValueError):history(app,'invalid')
    def test_native_rejects_bad_clock_and_hex(self):
        from ble_bridge import native
        result=native({'text':'25:00:00 AA:BB:CC:DD:EE:FF -45 020106\n12:00:00 AA:BB:CC:DD:EE:FF -45 0Z'})
        self.assertEqual(result['records'],[])
        self.assertEqual(result['rejected_lines'],2)

class WorkbenchApiTests(unittest.TestCase):
    def test_import_through_api_and_load_encoded_address(self):
        import threading,json,http.client
        from http.server import ThreadingHTTPServer
        from atlas import handler
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db');server=ThreadingHTTPServer(('127.0.0.1',0),handler(app))
            threading.Thread(target=server.serve_forever,daemon=True).start()
            conn=http.client.HTTPConnection('127.0.0.1',server.server_address[1])
            try:
                body=json.dumps({'source':'fixture','text':'12:00:00 AA:BB:CC:DD:EE:FF -45 050954657374'})
                conn.request('POST','/api/ble/import',body,{'Content-Type':'application/json'})
                r=conn.getresponse();self.assertEqual(r.status,200);self.assertEqual(json.loads(r.read())['imported'],1)
                conn.request('GET','/api/ble/history/AA%3ABB%3ACC%3ADD%3AEE%3AFF')
                r=conn.getresponse();self.assertEqual(r.status,200);self.assertEqual(json.loads(r.read())['mean_rssi'],-45)
            finally:conn.close();server.shutdown();server.server_close()
