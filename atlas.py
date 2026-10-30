#!/usr/bin/env python3
"""Atlas: a local TCP service observatory. Python standard library only."""
import ble_bridge
import argparse
import concurrent.futures
from contextlib import contextmanager
import datetime as dt
import errno
import fcntl
import struct
import hashlib
import http.client
import ipaddress
import json
import re
import socket
import sqlite3
import ssl
import subprocess
import shutil
import tempfile
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit, unquote
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_PORTS = [22, 80, 443, 1883, 8000, 8080, 8443]

def now():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')

class Atlas:
    def __init__(self, database, interval=300, timeout=1.5):
        self.database, self.interval, self.timeout = str(database), interval, timeout
        self.lock = threading.Lock()
        self.discovery_lock = threading.Lock()
        self.discovery = {"running": False, "checked": 0, "total": 0, "found": [], "error": None}
        self.enrichment = {"running": False, "error": None, "ssdp": [], "wifi": {"available": False}}
        self.enrichment_lock = threading.Lock()
        self.scanning = False
        self.last_error = None
        self.stop = threading.Event()
        with self.db() as c:
            c.executescript('''
            CREATE TABLE IF NOT EXISTS ble_samples(id INTEGER PRIMARY KEY, fingerprint TEXT UNIQUE NOT NULL, address TEXT NOT NULL, imported_at TEXT NOT NULL, data TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS ble_address ON ble_samples(address,id);
            CREATE TABLE IF NOT EXISTS identities(host TEXT PRIMARY KEY, data TEXT NOT NULL, at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS targets(id INTEGER PRIMARY KEY, host TEXT UNIQUE NOT NULL, label TEXT NOT NULL, ports TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS observations(id INTEGER PRIMARY KEY, target_id INTEGER NOT NULL, at TEXT NOT NULL, data TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS obs_target ON observations(target_id,id);
            CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, target_id INTEGER NOT NULL, at TEXT NOT NULL, message TEXT NOT NULL);
            ''')
            columns={r['name'] for r in c.execute('PRAGMA table_info(targets)')}
            for name,definition in [('notes', "TEXT NOT NULL DEFAULT ''"),('paused','INTEGER NOT NULL DEFAULT 0')]:
                if name not in columns: c.execute(f'ALTER TABLE targets ADD COLUMN {name} {definition}')
    @contextmanager
    def db(self):
        c = sqlite3.connect(self.database, timeout=10)
        c.row_factory = sqlite3.Row
        try:
            with c:
                yield c
        finally:
            c.close()
    def add(self, host, label, ports):
        host = str(host).strip()
        try:
            ipaddress.ip_address(host)
        except ValueError:
            if not re.fullmatch(r'(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?', host):
                raise ValueError('Enter an IP address or hostname, without a URL or port.')
        if not isinstance(ports, list) or not 1 <= len(ports) <= 32 or any(type(p) is not int or not 1 <= p <= 65535 for p in ports):
            raise ValueError('Choose 1–32 TCP ports between 1 and 65535.')
        with self.db() as c:
            if c.execute('SELECT count(*) FROM targets').fetchone()[0] >= 64:
                raise ValueError('Maximum 64 targets.')
            c.execute('INSERT INTO targets(host,label,ports) VALUES(?,?,?)', (host, str(label).strip()[:100] or host, json.dumps(sorted(set(ports)))))
    def device_description(self, host, location):
        url=urlsplit(location)
        # Only read the advertising device itself; no redirects, DNS or credentials.
        if url.scheme!='http' or url.hostname!=host or url.username or url.password:
            raise ValueError('Description must be HTTP on the advertising IP')
        ipaddress.ip_address(host)
        conn=http.client.HTTPConnection(host,url.port or 80,timeout=2)
        try:
            conn.request('GET',url.path+('?' + url.query if url.query else '') or '/')
            response=conn.getresponse()
            if response.status!=200: raise ValueError('Description HTTP '+str(response.status))
            body=response.read(65537)
            if len(body)>65536: raise ValueError('Description exceeds 64 KiB')
            if b'<!DOCTYPE' in body.upper() or b'<!ENTITY' in body.upper(): raise ValueError('XML entities are unsupported')
            root=ET.fromstring(body)
            fields={}
            allowed={'friendlyName','manufacturer','modelName','modelNumber','deviceType','serialNumber','UDN'}
            for node in root.iter():
                tag=node.tag.rsplit('}',1)[-1]
                if tag in allowed and tag not in fields: fields[tag]=(node.text or '').strip()[:300]
            return fields
        finally: conn.close()
    def update_target(self, data):
        target_id=int(data['id'])
        with self.db() as c:
            row=c.execute('SELECT * FROM targets WHERE id=?',(target_id,)).fetchone()
            if not row: raise ValueError('Unknown device')
            label=str(data.get('label',row['label'])).strip()[:100]
            ports=data.get('ports',json.loads(row['ports']))
            if not label: raise ValueError('Name is required')
            if not isinstance(ports,list) or not 1<=len(ports)<=32 or any(type(p) is not int or not 1<=p<=65535 for p in ports):
                raise ValueError('Choose 1–32 valid TCP ports')
            paused=data.get('paused',bool(row['paused']))
            if type(paused) is not bool: raise ValueError('paused must be boolean')
            c.execute('UPDATE targets SET label=?,ports=?,notes=?,paused=? WHERE id=?',
                (label,json.dumps(sorted(set(ports))),str(data.get('notes',row['notes']))[:2000],int(paused),target_id))
    def identity(self, host):
        with self.db() as c:
            row = c.execute('SELECT data,at FROM identities WHERE host=?',(host,)).fetchone()
        return dict(at=row['at'], **json.loads(row['data'])) if row else None
    def save_identity(self, host, data):
        with self.db() as c:
            c.execute('INSERT OR REPLACE INTO identities(host,data,at) VALUES(?,?,?)',(host,json.dumps(data),now()))
    def start_enrichment(self):
        if not self.enrichment_lock.acquire(blocking=False): return False
        self.enrichment = {'running': True, 'error': None, 'ssdp': [], 'wifi': {'available': False, 'reason': 'Termux Wi-Fi API is unavailable.'}}
        threading.Thread(target=self.enrich,daemon=True).start()
        return True
    def enrich(self):
        try:
            wifi_cmd = shutil.which('termux-wifi-connectioninfo')
            if wifi_cmd:
                try:
                    r = subprocess.run([wifi_cmd],capture_output=True,text=True,timeout=6)
                    value = json.loads(r.stdout)
                    self.enrichment['wifi'] = {'available':True,'data':value,'scope':'This phone only; does not identify other devices SSIDs.'}
                except (ValueError, OSError, subprocess.TimeoutExpired) as e:
                    self.enrichment['wifi'] = {'available':False,'reason':str(e)}
            wifi = self.wifi_network()
            if wifi:
                net = ipaddress.ip_network(wifi['cidr'])
                with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
                    sock.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_IF,socket.inet_aton(wifi['address']))
                    sock.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_TTL,2)
                    sock.bind((wifi['address'],0));sock.settimeout(.4)
                    sock.sendto(b'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n',('239.255.255.250',1900))
                    deadline=time.monotonic()+4
                    found={}
                    while time.monotonic()<deadline:
                        try: packet,peer=sock.recvfrom(8192)
                        except socket.timeout: continue
                        if ipaddress.ip_address(peer[0]) not in net: continue
                        headers={}
                        for line in packet.decode('utf-8','replace').split('\r\n')[1:]:
                            key,sep,value=line.partition(':')
                            if sep and key.lower() in ('server','st','usn','location','cache-control'):
                                headers[key.lower()]=value.strip()[:500]
                        if headers: found[(peer[0],headers.get('usn',''),headers.get('st',''))]={'host':peer[0],**headers}
                        if len(found)>=128: break
                    self.enrichment['ssdp']=list(found.values())
                    for host in {r['host'] for r in found.values()}:
                        identity=self.identity(host) or {}
                        identity.pop('at',None)
                        identity['ssdp']=[r for r in found.values() if r['host']==host]
                        location=next((r.get('location') for r in identity['ssdp'] if r.get('location')),None)
                        if location:
                            try: identity['description']=self.device_description(host,location)
                            except (ValueError,OSError,http.client.HTTPException,ET.ParseError) as e: identity['description_error']=str(e)[:200]
                        self.save_identity(host,identity)
                        try: self.add(host,host,DEFAULT_PORTS)
                        except (ValueError,sqlite3.IntegrityError): pass
            with self.db() as c: hosts=[r['host'] for r in c.execute('SELECT host FROM targets')]
            def resolve(host):
                identity=self.identity(host) or {};identity.pop('at',None)
                try:
                    ipaddress.ip_address(host)
                    script='import socket,sys,json; print(json.dumps(socket.gethostbyaddr(sys.argv[1])[0]))'
                    r=subprocess.run(['python3','-c',script,host],capture_output=True,text=True,timeout=2)
                    if r.returncode==0: identity['reverse_dns']=json.loads(r.stdout)
                    else: identity['reverse_dns_note']='No reverse DNS name returned'
                except (ValueError,OSError,subprocess.TimeoutExpired): identity['reverse_dns_note']='Reverse DNS unavailable or timed out'
                self.save_identity(host,identity)
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: list(pool.map(resolve,hosts))
        except Exception as e: self.enrichment['error']=str(e)
        finally:
            self.enrichment['running']=False
            self.enrichment_lock.release()
    def wifi_network(self):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                name = struct.pack('256s', b'wlan0')
                addr = socket.inet_ntoa(fcntl.ioctl(sock.fileno(), 0x8915, name)[20:24])
                mask = socket.inet_ntoa(fcntl.ioctl(sock.fileno(), 0x891b, name)[20:24])
            network = ipaddress.ip_network(addr+'/'+mask, strict=False)
            return {'address': addr, 'cidr': str(network)}
        except OSError:
            return None
    def discovery_scope(self):
        wifi = self.wifi_network()
        if not wifi:
            raise ValueError('Wi-Fi IPv4 address is unavailable.')
        network = ipaddress.ip_network(wifi['cidr'])
        if not network.is_private or network.num_addresses > 256:
            raise ValueError('Discovery requires a private Wi-Fi subnet of at most 256 addresses.')
        return wifi, [str(h) for h in network.hosts() if str(h) != wifi['address']]
    def discover_host(self, host):
        opened, refused = [], False
        ports = sorted(set(DEFAULT_PORTS + [139, 445, 554, 631, 5000, 7000, 8008, 8009, 9100]))
        for port in ports:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.settimeout(.3)
                code = sock.connect_ex((host, port))
            if code == 0: opened.append(port)
            elif code == errno.ECONNREFUSED: refused = True
        return {'host': host, 'open_ports': opened, 'evidence': 'TCP connection accepted' if opened else 'TCP connection refused'} if opened or refused else None
    def start_discovery(self):
        wifi, hosts = self.discovery_scope()
        if not self.discovery_lock.acquire(blocking=False):
            return False
        self.discovery = dict(running=True, checked=0, total=len(hosts), found=[], error=None, cidr=wifi['cidr'])
        threading.Thread(target=self.discover, args=(hosts,), daemon=True).start()
        return True
    def discover(self, hosts):
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
                futures = [pool.submit(self.discover_host, host) for host in hosts]
                for future in concurrent.futures.as_completed(futures):
                    result = future.result()
                    self.discovery['checked'] += 1
                    if result:
                        try:
                            self.add(result['host'], result['host'], sorted(set(DEFAULT_PORTS + result['open_ports'])))
                            result['added'] = True
                        except (ValueError, sqlite3.IntegrityError) as e:
                            result['added'] = False
                            result['note'] = str(e)
                        self.discovery['found'] = self.discovery['found'] + [result]
            self.scan()
        except Exception as e:
            self.discovery['error'] = str(e)
        finally:
            self.discovery['running'] = False
            self.discovery_lock.release()
    def probe(self, address, port):
        started = time.monotonic()
        result = {'port': port, 'state': 'unreachable'}
        try:
            with socket.create_connection((address, port), self.timeout) as s:
                result['service_hint'] = {22:'SSH',80:'HTTP',443:'HTTPS',1883:'MQTT',139:'NetBIOS',445:'SMB',554:'RTSP',631:'IPP printing',9100:'Raw printing',8008:'HTTP / media',8009:'Media control'}.get(port,'TCP')
                result.update(state='open', latency_ms=round((time.monotonic()-started)*1000, 1))
                if port == 22:
                    result['banner'] = s.recv(256).decode('utf-8', 'replace').strip()
            if port in (80, 443, 8000, 8080, 8443):
                secure = port in (443, 8443)
                context = ssl._create_unverified_context() if secure else None
                conn = http.client.HTTPSConnection(address, port, timeout=self.timeout, context=context) if secure else http.client.HTTPConnection(address, port, timeout=self.timeout)
                try:
                    conn.connect()
                    if secure:
                        der=conn.sock.getpeercert(binary_form=True)
                        result['certificate_sha256'] = hashlib.sha256(der).hexdigest()
                        result['tls_version']=conn.sock.version()
                        result['tls_cipher']=conn.sock.cipher()[0]
                        with tempfile.NamedTemporaryFile(mode='w',suffix='.pem') as cert:
                            cert.write(ssl.DER_cert_to_PEM_cert(der));cert.flush()
                            result['certificate']=ssl._ssl._test_decode_cert(cert.name)
                    conn.request('GET', '/', headers={'User-Agent': 'Atlas/1.0', 'Connection': 'close'})
                    response = conn.getresponse()
                    result['http_status'] = response.status
                    result['http_headers']={k: v[:500] for k,v in response.getheaders() if k.lower() in ('server','content-type','location','www-authenticate','x-powered-by','strict-transport-security')}
                    result['redirect_followed']=False
                    result['server'] = response.getheader('Server', '')[:200]
                    body = response.read(32768).decode('utf-8', 'replace')
                    title = re.search(r'<title[^>]*>(.*?)</title>', body, re.I | re.S)
                    if title:
                        result['title'] = re.sub(r'\s+', ' ', title.group(1)).strip()[:200]
                except (OSError, http.client.HTTPException) as e:
                    result['metadata_error'] = str(e)[:160]
                finally:
                    conn.close()
        except OSError as e:
            result['reason'] = str(e)[:160]
        return result
    def scan_target(self, target):
        ports = json.loads(target['ports'])
        try:
            address = socket.getaddrinfo(target['host'], None, type=socket.SOCK_STREAM)[0][4][0]
        except OSError as e:
            return {'services': [], 'error': 'DNS resolution failed: '+str(e), 'status': 'unknown'}
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            services = list(pool.map(lambda p: self.probe(address, p), ports))
        return {'address': address, 'services': services, 'status': 'responding' if any(s['state']=='open' for s in services) else 'no_response'}
    def record(self, target_id, data):
        at = now()
        with self.db() as c:
            previous = c.execute('SELECT data FROM observations WHERE target_id=? ORDER BY id DESC LIMIT 1', (target_id,)).fetchone()
            messages = []
            if not previous:
                messages.append('Baseline recorded: '+data['status'])
            else:
                old = json.loads(previous['data'])
                if old['status'] != data['status']:
                    messages.append('Response changed: '+old['status']+' → '+data['status'])
                # Resolution failures do not provide evidence that services closed.
                if not data.get('error') and not old.get('error'):
                    old_services = {s['port']:s for s in old['services']}
                    for s in data['services']:
                        prev = old_services.get(s['port'])
                        if prev is None:
                            messages.append(f"TCP {s['port']}: monitoring started ({s['state']})")
                        elif prev['state'] != s['state']:
                            messages.append(f"TCP {s['port']}: {prev['state']} → {s['state']}")
                        elif s['state'] == 'open':
                            for field in ('title', 'server', 'banner', 'certificate_sha256'):
                                if field in s and field in prev and s[field] != prev[field]:
                                    messages.append(f"TCP {s['port']}: {field.replace('_', ' ')} changed")
            c.execute('INSERT INTO observations(target_id,at,data) VALUES(?,?,?)', (target_id,at,json.dumps(data)))
            c.executemany('INSERT INTO events(target_id,at,message) VALUES(?,?,?)', [(target_id,at,m) for m in messages])
    def scan(self):
        if not self.lock.acquire(blocking=False):
            return False
        self.scanning = True
        try:
            with self.db() as c:
                targets = c.execute('SELECT * FROM targets WHERE paused=0').fetchall()
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                for target, data in zip(targets, pool.map(self.scan_target, targets)):
                    self.record(target['id'], data)
            self.last_error = None
        except Exception as e:
            self.last_error = str(e)
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
            for row in c.execute('SELECT * FROM targets ORDER BY id'):
                target = dict(row); target['identity']=self.identity(row['host']); target['ports'] = json.loads(target['ports'])
                obs = c.execute('SELECT at,data FROM observations WHERE target_id=? ORDER BY id DESC LIMIT 1',(row['id'],)).fetchone()
                target['latest'] = dict(at=obs['at'], **json.loads(obs['data'])) if obs else None
                counts = c.execute("SELECT count(*),sum(CASE WHEN json_extract(data,'$.status')='responding' THEN 1 ELSE 0 END) FROM observations WHERE target_id=?",(row['id'],)).fetchone()
                summary=c.execute("SELECT min(at),max(CASE WHEN json_extract(data,'$.status')='responding' THEN at END),count(*) FROM observations WHERE target_id=?",(row['id'],)).fetchone()
                target['first_observed'],target['last_response'],target['checks']=tuple(summary)
                target['response_rate'] = round(100*(counts[1] or 0)/counts[0]) if counts[0] else None
                targets.append(target)
            events = [dict(r) for r in c.execute('SELECT e.*,t.label FROM events e JOIN targets t ON e.target_id=t.id ORDER BY e.id DESC LIMIT 200')]
            since=(dt.datetime.now(dt.timezone.utc)-dt.timedelta(hours=24)).isoformat(timespec='seconds')
            event_count=c.execute('SELECT count(*) FROM events WHERE at>=?',(since,)).fetchone()[0]
        return dict(bluetooth=ble_bridge.state(self), events_24h=event_count, enrichment=self.enrichment.copy(), wifi=self.wifi_network(), discovery=self.discovery.copy(), targets=targets, events=events, scanning=self.scanning, error=self.last_error, interval=self.interval)

def handler(app):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, value, status=200):
            body = json.dumps(value).encode()
            self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.send_header('Cache-Control','no-store'); self.end_headers(); self.wfile.write(body)
        def do_GET(self):
            path = self.path.split('?')[0]
            if path.startswith('/api/ble/history/'):
                try: return self.reply(ble_bridge.history(app,unquote(path.rsplit('/',1)[1])))
                except ValueError as e: return self.reply({'error':str(e)},400)
            if path == '/api/backup':
                with tempfile.TemporaryDirectory() as folder:
                    backup=Path(folder)/'atlas.sqlite3'
                    with app.db() as source:
                        dest=sqlite3.connect(backup)
                        try: source.backup(dest)
                        finally: dest.close()
                    body=backup.read_bytes()
                self.send_response(200); self.send_header('Content-Type','application/octet-stream'); self.send_header('Content-Disposition','attachment; filename="atlas-backup.sqlite3"'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
                return
            if path == '/api/state': return self.reply(app.state())
            if path.startswith('/api/history/'):
                try: target_id = int(path.rsplit('/',1)[1])
                except ValueError: return self.reply({'error':'Invalid target'},400)
                with app.db() as c:
                    rows = c.execute('SELECT at,data FROM observations WHERE target_id=? ORDER BY id DESC LIMIT 200',(target_id,)).fetchall()
                return self.reply([dict(at=r['at'],**json.loads(r['data'])) for r in rows])
            name = {'/':'index.html','/app.js':'app.js','/style.css':'style.css'}.get(path)
            if not name: return self.reply({'error':'Not found'},404)
            body = (ROOT/'static'/name).read_bytes()
            self.send_response(200); self.send_header('Content-Type',{'html':'text/html','js':'text/javascript','css':'text/css'}[name.rsplit('.',1)[1]]); self.send_header('Content-Length',str(len(body))); self.send_header('X-Content-Type-Options','nosniff'); self.end_headers(); self.wfile.write(body)
        def do_POST(self):
            # Require JSON plus a local Origin, preventing cross-site browser writes.
            if self.headers.get('Content-Type','').split(';')[0] != 'application/json': return self.reply({'error':'JSON required'},415)
            origin = self.headers.get('Origin')
            if origin and origin != 'http://'+self.headers.get('Host',''): return self.reply({'error':'Origin rejected'},403)
            try:
                length = int(self.headers.get('Content-Length','0'))
                if not 0 < length <= 1100000: raise ValueError('Invalid request size')
                data = json.loads(self.rfile.read(length))
                if self.path == '/api/ble/import':
                    return self.reply(ble_bridge.import_text(app,data.get('text'),data.get('source','browser import')))
                if self.path == '/api/ble/folder':
                    return self.reply(ble_bridge.import_folder(app))
                if self.path == '/api/enrich':
                    started=app.start_enrichment()
                    return self.reply({'started':started},202 if started else 409)
                if self.path == '/api/update':
                    app.update_target(data)
                    return self.reply({'ok':True})
                if self.path == '/api/targets':
                    app.add(data.get('host',''), data.get('label',''), data.get('ports',DEFAULT_PORTS))
                    return self.reply({'ok':True},201)
                if self.path == '/api/discover':
                    started = app.start_discovery()
                    return self.reply({'started':started},202 if started else 409)
                if self.path == '/api/scan':
                    threading.Thread(target=app.scan,daemon=True).start()
                    return self.reply({'ok':True},202)
                return self.reply({'error':'Not found'},404)
            except (ValueError,TypeError,AttributeError,sqlite3.IntegrityError) as e:
                return self.reply({'error':str(e)},400)
        def log_message(self, *args): pass
    return Handler

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port',type=int,default=8765)
    p.add_argument('--interval',type=int,default=300)
    p.add_argument('--database',default=str(ROOT/'atlas.sqlite3'))
    args=p.parse_args()
    if args.interval < 10: p.error('interval must be at least 10 seconds')
    app=Atlas(args.database,args.interval)
    server=ThreadingHTTPServer(('127.0.0.1',args.port),handler(app))
    threading.Thread(target=app.scheduler,daemon=True).start()
    print(f'Atlas ready: http://127.0.0.1:{args.port}',flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: app.stop.set(); server.server_close()

if __name__=='__main__': main()
