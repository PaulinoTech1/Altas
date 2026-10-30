import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from atlas import Atlas, handler

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.app=Atlas(Path(self.tmp.name)/'test.sqlite3',timeout=.3)
    def tearDown(self): self.tmp.cleanup()
    def test_validation(self):
        for host,ports in [('http://localhost', [80]),('localhost',[0]),('localhost',[True]),('localhost',[])]:
            with self.assertRaises(ValueError): self.app.add(host,'',ports)
    def test_changes_and_dns_failure(self):
        self.app.add('127.0.0.1','test',[80])
        opened={'status':'responding','services':[{'port':80,'state':'open','title':'Old'}]}
        self.app.record(1,opened)
        self.app.record(1,opened)
        self.assertEqual(len(self.app.state()['events']),1)
        self.app.record(1,{'status':'responding','services':[{'port':80,'state':'open','title':'New'}]})
        self.assertIn('title changed',self.app.state()['events'][0]['message'])
        self.app.record(1,{'status':'unknown','services':[],'error':'DNS failed'})
        self.assertFalse(any('unreachable' in e['message'] for e in self.app.state()['events']))
    def test_real_tcp_open_then_closed(self):
        class Quiet(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200);self.end_headers();self.wfile.write(b'ok')
            def log_message(self,*a): pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Quiet)
        port=server.server_address[1]
        threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            self.app.add('127.0.0.1','fixture',[port]);self.app.scan()
            self.assertEqual(self.app.state()['targets'][0]['latest']['status'],'responding')
        finally: server.shutdown();server.server_close()
        self.app.scan()
        self.assertEqual(self.app.state()['targets'][0]['latest']['status'],'no_response')
        self.assertTrue(any('open → unreachable' in e['message'] for e in self.app.state()['events']))
    def test_http_api_and_origin(self):
        import http.client
        server=ThreadingHTTPServer(('127.0.0.1',0),handler(self.app))
        threading.Thread(target=server.serve_forever,daemon=True).start()
        conn=http.client.HTTPConnection('127.0.0.1',server.server_address[1])
        try:
            conn.request('POST','/api/targets',json.dumps({'host':'127.0.0.1','ports':[80]}),{'Content-Type':'application/json'})
            r=conn.getresponse();self.assertEqual(r.status,201);r.read()
            conn.request('GET','/api/state');r=conn.getresponse();self.assertEqual(len(json.loads(r.read())['targets']),1)
            conn.request('POST','/api/scan','{}',{'Content-Type':'application/json','Origin':'http://foreign.example'})
            r=conn.getresponse();self.assertEqual(r.status,403);r.read()
            conn.request('GET','/');r=conn.getresponse();self.assertIn(b'Know what changed',r.read())
        finally: conn.close();server.shutdown();server.server_close()
if __name__=='__main__': unittest.main()

class DiscoveryTests(unittest.TestCase):
    def test_scope_is_wifi_only_and_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db')
            app.wifi_network=lambda: {'address':'192.168.1.158','cidr':'192.168.1.0/24'}
            _,hosts=app.discovery_scope()
            self.assertEqual(len(hosts),253)
            self.assertNotIn('192.168.1.158',hosts)
            app.wifi_network=lambda: {'address':'10.0.0.2','cidr':'10.0.0.0/8'}
            with self.assertRaises(ValueError): app.discovery_scope()
    def test_discovery_persists_devices(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db')
            app.discover_host=lambda host: {'host':host,'open_ports':[631],'evidence':'TCP connection accepted'}
            app.scan=lambda: True
            app.discovery_lock.acquire()
            app.discover(['192.168.1.20'])
            self.assertEqual(app.state()['targets'][0]['host'],'192.168.1.20')
            self.assertIn(631,app.state()['targets'][0]['ports'])
            self.assertFalse(app.discovery['running'])

class IntelligenceTests(unittest.TestCase):
    def test_identity_survives_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'db'
            app=Atlas(path);app.add('192.168.1.2','Device',[80])
            app.save_identity('192.168.1.2',{'reverse_dns':'printer.local','ssdp':[{'server':'device server'}]})
            restored=Atlas(path).state()['targets'][0]['identity']
            self.assertEqual(restored['reverse_dns'],'printer.local')
            self.assertEqual(restored['ssdp'][0]['server'],'device server')
    def test_reverse_dns_collection_without_wifi(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db');app.add('192.168.1.2','Device',[80])
            app.wifi_network=lambda:None
            app.enrichment_lock.acquire()
            with patch('atlas.shutil.which',return_value=None), patch('atlas.subprocess.run',return_value=SimpleNamespace(returncode=0,stdout='"printer.local"')):
                app.enrich()
            self.assertEqual(app.identity('192.168.1.2')['reverse_dns'],'printer.local')
            self.assertFalse(app.enrichment['running'])

class ManagementTests(unittest.TestCase):
    def test_pause_ports_notes_and_history_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db');app.add('127.0.0.1','Device',[80])
            app.record(1,{'status':'no_response','services':[]})
            app.update_target({'id':1,'label':'Printer','notes':'Office','ports':[631,9100],'paused':True})
            app.scan_target=lambda t: (_ for _ in ()).throw(AssertionError('paused target scanned'))
            app.scan()
            t=app.state()['targets'][0]
            self.assertEqual((t['label'],t['notes'],t['ports'],t['checks']),('Printer','Office',[631,9100],1))
            self.assertTrue(t['paused'])
            with self.assertRaises(ValueError): app.update_target({'id':1,'ports':[0]})
    def test_description_bounded_to_advertising_host(self):
        from unittest.mock import patch,MagicMock
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db')
            with self.assertRaises(ValueError): app.device_description('192.168.1.2','http://127.0.0.1/admin')
            with patch('atlas.http.client.HTTPConnection') as factory:
                response=MagicMock(status=200)
                response.read.return_value=b'<root xmlns="urn:test"><device><friendlyName>Office printer</friendlyName><modelName>Model A</modelName></device></root>'
                factory.return_value.getresponse.return_value=response
                self.assertEqual(app.device_description('192.168.1.2','http://192.168.1.2/device.xml')['modelName'],'Model A')
                response.read.return_value=b'<!DOCTYPE root><root/>'
                with self.assertRaises(ValueError): app.device_description('192.168.1.2','http://192.168.1.2/device.xml')
    def test_full_daily_event_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db');app.add('127.0.0.1','Device',[80])
            from atlas import now
            with app.db() as c: c.executemany('INSERT INTO events(target_id,at,message) VALUES(1,?,?)',[(now(),'Change')]*205)
            state=app.state()
            self.assertEqual(len(state['events']),200)
            self.assertEqual(state['events_24h'],205)
