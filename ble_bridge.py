"""Import Nordic automated scan lines; no Bluetooth privileges required."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

SCAN = re.compile(r'(?P<time>\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+(?P<address>(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})\s+(?P<rssi>-?\d{1,3})\s+(?P<raw>[0-9A-Fa-f]{2,3300})(?=\s|$)')

def decode_advertisement(raw):
    data=bytes.fromhex(raw); result={'records':[]}; offset=0
    while offset<len(data):
        length=data[offset]; offset+=1
        if length==0: break
        if offset+length>len(data):
            result['warning']='Truncated advertisement record';break
        kind=data[offset];value=data[offset+1:offset+length];offset+=length
        result['records'].append({'type':kind,'hex':value.hex()})
        if kind in (8,9): result['name']=value.decode('utf-8','replace')
        if kind in (2,3): result.setdefault('service_uuids',[]).extend(f'{int.from_bytes(value[i:i+2],"little"):04x}' for i in range(0,len(value)-1,2))
        if kind in (6,7): result.setdefault('service_uuids',[]).extend(value[i:i+16][::-1].hex() for i in range(0,len(value)-15,16))
        if kind==255 and len(value)>=2:
            result['manufacturer_id']=int.from_bytes(value[:2],'little');result['manufacturer_data']=value[2:].hex()
    return result

def import_text(app,text,source):
    if not isinstance(text,str) or len(text.encode())>1_000_000: raise ValueError('Import must be text under 1 MB')
    parsed=native({'text':text})
    samples=parsed['records']
    source=str(source)[:160]
    for record in samples:
        record['source']=source
        record['fingerprint']=hashlib.sha256((source+'\n'+record.pop('source_line')).encode()).hexdigest()
    if not samples: raise ValueError('No supported Nordic scan lines found. Expected: TIME AA:BB:CC:DD:EE:FF -45 HEXDATA. Other export formats need a sample before they can be supported.')
    from atlas import now
    imported=0
    with app.db() as c:
        for record in samples:
            cursor=c.execute('INSERT OR IGNORE INTO ble_samples(fingerprint,address,imported_at,data) VALUES(?,?,?,?)',(record['fingerprint'],record['address'],now(),json.dumps(record)))
            imported+=cursor.rowcount
    return {'imported':imported,'parsed':len(samples),'duplicates':len(samples)-imported,'rejected_lines':parsed['rejected_lines'],'limit_reached':parsed['limit_reached'],'decoder':parsed['decoder']}

NATIVE=Path(__file__).resolve().parent/'native/target/release/atlas-capture'

def native(request):
    if not NATIVE.is_file(): raise ValueError('Rust capture engine is not built. Run cargo build --release in native/.')
    try:
        result=subprocess.run([str(NATIVE)],input=json.dumps(request),capture_output=True,text=True,timeout=10)
    except (OSError,subprocess.TimeoutExpired) as e: raise ValueError('Capture engine unavailable: '+str(e)) from e
    if result.returncode: raise ValueError(result.stderr.strip()[:300] or 'Capture engine failed')
    return json.loads(result.stdout)

def state(app):
    with app.db() as c:
        rows=c.execute("""SELECT b.*,x.samples,x.first_import,x.last_import,x.rssi_min,x.rssi_max
        FROM ble_samples b JOIN (SELECT address,max(id) AS latest,count(*) AS samples,min(imported_at) AS first_import,
        max(imported_at) AS last_import,min(json_extract(data,'$.rssi')) AS rssi_min,max(json_extract(data,'$.rssi')) AS rssi_max
        FROM ble_samples GROUP BY address) x ON b.id=x.latest ORDER BY b.id DESC LIMIT 256""").fetchall()
        devices=[dict(json.loads(r['data']),imported_at=r['imported_at'],**{k:r[k] for k in ('samples','first_import','last_import','rssi_min','rssi_max')}) for r in rows]
    return {'devices':devices,'engine':'Rust + ARM64 assembly' if NATIVE.is_file() else 'Rust engine unavailable',
        'mode':'Imported observations; not a live Bluetooth scan','folder':'/sdcard/Download/AtlasBridge'}

def history(app,address):
    if not re.fullmatch(r'(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}',address): raise ValueError('Invalid Bluetooth address')
    with app.db() as c:
        rows=c.execute('SELECT id,data FROM ble_samples WHERE address=? ORDER BY id DESC LIMIT 2000',(address.upper(),)).fetchall()
    records=[]
    for row in reversed(rows):
        r=json.loads(row['data'])
        if 'time_ms' not in r:
            h,m,sec=r['time'].split(':');r['time_ms']=round((int(h)*3600+int(m)*60+float(sec))*1000)
        records.append({k:r.get(k) for k in ('time_ms','rssi','address','advertisement')})
        records[-1]['advertisement']={'name':r.get('advertisement',{}).get('name')}
    return native({'mode':'analyze','records':records})

def import_folder(app):
    folder=Path('/sdcard/Download/AtlasBridge');results=[]
    for path in sorted(folder.glob('*'))[:50]:
        if path.suffix.lower() not in ('.txt','.log','.csv') or path.is_symlink() or not path.is_file(): continue
        try:
            if path.stat().st_size>1_000_000: raise ValueError('File exceeds 1 MB')
            results.append({'file':path.name,**import_text(app,path.read_text(errors='replace'),path.name)})
        except (ValueError,OSError) as e: results.append({'file':path.name,'error':str(e)})
    return {'files':results}
