import sys,tempfile,os
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
from atlas import Atlas,handler
from ble_bridge import investigate
from http.server import ThreadingHTTPServer
os.environ['ATLAS_CONTROL_TOKEN']='1'*64
folder=tempfile.TemporaryDirectory();app=Atlas(folder.name+'/fixture.sqlite3')
app.add('192.168.1.20','Office printer',[80,631]);app.save_identity('192.168.1.20',{'description':{'friendlyName':'Office','manufacturer':'Fixture','modelName':'Printer'}})
app.record(1,{'status':'responding','services':[{'port':80,'state':'open','title':'Printer panel','latency_ms':2},{'port':631,'state':'open','latency_ms':3}]})
app.add('192.168.1.30','Quiet device',[443]);app.record(2,{'status':'no_response','services':[{'port':443,'state':'unreachable'}]})
investigate(app,'import',source='fixture.log',name='Browser fixture',text='23:59:59.000 AA:BB:CC:DD:EE:FF -40 05095465737403030D18\n00:00:01.000 AA:BB:CC:DD:EE:FF -60 05095465737403030D18')
investigate(app,'overview')
server=ThreadingHTTPServer(('127.0.0.1',int(os.environ.get('ATLAS_TEST_PORT','8877'))),handler(app));print('Fixture http://127.0.0.1:8877',flush=True)
try:server.serve_forever()
finally:server.server_close();folder.cleanup()
