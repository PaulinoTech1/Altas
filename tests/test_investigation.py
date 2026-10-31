import http.client
import json
import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path
from http.server import ThreadingHTTPServer
from atlas import Atlas, handler
from ble_bridge import investigate

CAPTURE='23:59:59.000 AA:BB:CC:DD:EE:FF -40 05095465737403030D18\n00:00:01.000 AA:BB:CC:DD:EE:FF -60 05095465737403030D18'

class InvestigationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.app=Atlas(Path(self.tmp.name)/'db')
    def tearDown(self):self.tmp.cleanup()
    def call(self,op,**data):return investigate(self.app,op,**data)
    def test_session_source_and_midnight(self):
        result=self.call('import',text=CAPTURE,source='capture.log',name='Evening')
        sid=result['session_id']
        self.assertEqual(self.call('source',session_id=sid)['text'],CAPTURE)
        self.assertEqual(self.call('replay',session_id=sid)['points'][1]['elapsed_ms'],2000)
        self.assertEqual(self.call('import',text=CAPTURE,source='capture.log')['duplicates'],2)
        self.assertEqual(self.call('import',text=CAPTURE,source='capture.log',session_key='next-day')['imported'],2)
    def test_packet_filters_and_compare(self):
        a=self.call('import',text=CAPTURE,source='a')['session_id']
        b=self.call('import',text='12:00:00 11:22:33:44:55:66 -70 020106',source='b')['session_id']
        self.assertEqual(len(self.call('packets',session_id=a,q='180d',rssi_min=-50)['packets']),1)
        page=self.call('packets',session_id=a,limit=1)
        self.assertIsNotNone(page['next_cursor'])
        self.assertEqual(len(self.call('packets',session_id=a,cursor=page['next_cursor'])['packets']),1)
        diff=self.call('compare',a=a,b=b)
        self.assertEqual(diff['added_addresses'],['11:22:33:44:55:66'])
    def test_missed_alerts_identity_and_ack(self):
        self.app.add('192.168.1.20','Fixture',[80]);self.call('init')
        self.app.save_identity('192.168.1.20',{'description':{'modelName':'A'}})
        self.app.record(1,{'status':'responding','services':[{'port':80,'state':'open'}]})
        self.call('overview')
        self.app.save_identity('192.168.1.20',{'description':{'modelName':'B'}})
        for _ in range(3):self.app.record(1,{'status':'no_response','services':[{'port':80,'state':'unreachable'}]})
        overview=self.call('overview')
        missed=[a for a in overview['alerts'] if a['kind']=='missed_checks']
        self.assertEqual(len(missed),1)
        self.assertEqual(len(self.call('detail',entity='ip:1')['history']),2)
        self.call('ack',id=missed[0]['id'])
        self.assertTrue(next(a for a in self.call('alerts') if a['id']==missed[0]['id'])['acknowledged'])
        self.call('overview')
        self.assertEqual(len([a for a in self.call('alerts') if a['kind']=='missed_checks']),1)
    def test_legacy_migration_preserves_observations(self):
        self.app.add('192.168.1.20','Fixture',[80]);self.app.record(1,{'status':'responding','services':[]})
        data={'time':'12:00:00','address':'AA:BB:CC:DD:EE:FF','rssi':-45,'raw':'020106','advertisement':{'records':[]}}
        with self.app.db() as c:c.execute("INSERT INTO ble_samples(fingerprint,address,imported_at,data) VALUES('old',?,'2026-01-01T00:00:00Z',?)",(data['address'],json.dumps(data)))
        self.call('init');self.call('init')
        self.assertEqual(self.call('sessions')[0]['packets'],1)
        self.assertEqual(self.app.state()['targets'][0]['checks'],1)
    def test_report_and_health(self):
        sid=self.call('import',text=CAPTURE,source='a',name='<script>bad</script>')['session_id']
        html=self.call('report',session_id=sid)['html']
        self.assertIn('<svg',html);self.assertNotIn('<script>',html)
        health=self.call('health');self.assertEqual(health['journal_mode'],'wal')
        self.assertGreater(health['database_bytes'],0)

class GatewayTests(unittest.TestCase):
    def test_http_sensor_auth_revocation_and_route_isolation(self):
        with tempfile.TemporaryDirectory() as tmp:
            app=Atlas(Path(tmp)/'db');server=ThreadingHTTPServer(('127.0.0.1',0),handler(app))
            threading.Thread(target=server.serve_forever,daemon=True).start();conn=http.client.HTTPConnection('127.0.0.1',server.server_address[1])
            def post(path,body,token=None,origin=None):
                headers={'Content-Type':'application/json'}
                if token:headers['Authorization']='Bearer '+token
                if origin:headers['Origin']=origin
                conn.request('POST',path,json.dumps(body),headers);response=conn.getresponse();return response.status,json.loads(response.read())
            try:
                status,sensor=post('/api/v2/sensor_create',{'name':'Fixture'})
                self.assertEqual(status,200)
                payload={'observations':[{'host':'192.168.1.20','status':'responding','services':[{'port':80,'state':'open'}]}]}
                self.assertEqual(post('/api/v2/ingest',payload)[0],401)
                self.assertEqual(post('/api/v2/ingest',payload,sensor['token'])[1]['accepted'],1)
                conn.request('GET','/api/v2/overview');r=conn.getresponse();overview=json.loads(r.read());self.assertTrue(any(n['kind']=='External IP observation' for n in overview['nodes']))
                self.assertEqual(post('/api/v2/sensor_revoke',{'id':sensor['id']})[0],200)
                self.assertEqual(post('/api/v2/ingest',payload,sensor['token'])[0],401)
                self.assertEqual(post('/api/v2/import',{},origin='http://foreign.example')[0],403)
                conn.request('GET','/api/v2/sensor_create');r=conn.getresponse();self.assertEqual(r.status,405);r.read()
            finally:conn.close();server.shutdown();server.server_close()
