const $=s=>document.querySelector(s);
let state=null;
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const date=s=>new Date(s).toLocaleString();
function error(e){$('#error').textContent=e.message;$('#error').hidden=false;}
async function api(path,data){const r=await fetch(path,data?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)}:{});const j=await r.json();if(!r.ok)throw Error(j.error||'Request failed');return j;}
function visibleTargets(s){
const filtered=s.targets.filter(t=>JSON.stringify(t).toLowerCase().includes($('#search').value.toLowerCase())).filter(t=>$('#filter').value==='all'||($('#filter').value==='paused'?t.paused:t.latest?.status===$('#filter').value&&!t.paused));
const mode=$('#sort').value;
return filtered.sort((a,b)=>mode==='name'?a.label.localeCompare(b.label):mode==='services'?(b.latest?.services.filter(p=>p.state==='open').length||0)-(a.latest?.services.filter(p=>p.state==='open').length||0):mode==='response'?(b.response_rate||0)-(a.response_rate||0):a.host.localeCompare(b.host,undefined,{numeric:true}));
}
function render(s){state=s;
$('#capture-engine').textContent=s.bluetooth?.engine||'Capture engine unavailable';
const selectedCapture=$('#capture-target').value;
$('#capture-target').innerHTML='<option value="">Select imported device</option>'+(s.bluetooth?.devices||[]).map(d=>`<option value="${esc(d.address)}">${esc(d.advertisement.name||d.address)}</option>`).join('');$('#capture-target').value=selectedCapture;

$('#ble-devices').innerHTML=(s.bluetooth?.devices||[]).map(d=>`<details class="ble-device"><summary>${esc(d.advertisement.name||'Unnamed BLE device')} · ${d.rssi} dBm</summary><p>${esc(d.address)} · ${d.samples} imported samples<br>Signal range: ${d.rssi_min} to ${d.rssi_max} dBm<br>Source time: ${esc(d.time)} (date not supplied)<br>Imported: ${esc(date(d.imported_at))}</p><pre>${esc(JSON.stringify(d.advertisement,null,2))}</pre></details>`).join('')||'<p class="hint">No Bluetooth observations imported.</p>';

$('#network').textContent=s.wifi?`Wi-Fi: ${s.wifi.address} · ${s.wifi.cidr}`:'Wi-Fi IPv4 interface unavailable';
$('#discover').disabled=s.discovery.running||!s.wifi;
$('#discovery-status').textContent=s.discovery.total?`${s.discovery.running?'Discovering':'Finished'}: ${s.discovery.checked}/${s.discovery.total} addresses checked · ${s.discovery.found.length} responding addresses${s.discovery.error?' · '+s.discovery.error:''}`:'';
$('#status').textContent=s.scanning?'Observing…':'Local observatory connected';$('#scan').disabled=s.scanning;$('#interval').textContent=`CHECKS EVERY ${s.interval} SECONDS`;
if(s.error)error(Error(s.error));
const responding=s.targets.filter(t=>t.latest?.status==='responding').length;
$('#metrics').innerHTML=[[s.targets.length,'devices monitored'],[responding,'responding to TCP'],[s.events_24h,'events in the last 24 hours']].map(([n,l])=>`<div class="metric"><strong>${n}</strong><span>${l}</span></div>`).join('');
$('#devices').innerHTML=s.targets.length?visibleTargets(s).map(t=>{const l=t.latest;return `<article class="device"><div class="device-head"><h3>${esc(t.label)}</h3><span class="badge">${esc(t.paused?'Paused':l?l.status.replaceAll('_',' '):'Awaiting baseline')}</span></div><p>${esc(t.host)}${l?.address&&l.address!==t.host?' → '+esc(l.address):''}</p><p class="identity">${esc(t.identity?.description?.friendlyName||t.identity?.reverse_dns||'')}${t.identity?.description?.modelName?' · '+esc(t.identity.description.modelName):''}${t.identity?.description?.manufacturer?' · '+esc(t.identity.description.manufacturer):''}</p><div class="services">${l?l.services.filter(p=>p.state==='open').map(p=>`<span class="service">TCP ${p.port} · ${p.latency_ms} ms${p.title?' · '+esc(p.title):''}${p.server?' · '+esc(p.server):''}${p.banner?' · '+esc(p.banner):''}${p.certificate_sha256?' · TLS '+esc(p.certificate_sha256.slice(0,12))+'…':''}</span>`).join('')||'<span class="hint">No selected services answered.</span>':'<span class="hint">Run an observation to establish a baseline.</span>'}</div><div class="detail">${l?'Observed '+esc(date(l.at))+' · '+t.response_rate+'% of checks received a response':'Ports: '+t.ports.join(', ')}${l?.error?'<br>'+esc(l.error):''}</div><details><summary>Inspect evidence & manage device</summary><p class="hint">Port labels are hints. Device-returned data is evidence; it does not establish a device’s SSID.</p><pre>${esc(JSON.stringify({identity:t.identity,latest:l},null,2))}</pre><label>Device name<input id="name-${t.id}" value="${esc(t.label)}" maxlength="100"></label><label>Monitoring ports<input id="ports-${t.id}" value="${t.ports.join(',')}"></label><label>Notes<textarea id="notes-${t.id}" maxlength="2000">${esc(t.notes)}</textarea></label><label class="check"><input type="checkbox" id="paused-${t.id}" ${t.paused?'checked':''}> Pause automatic observations</label><p class="hint">${t.checks} recorded checks · First observed: ${t.first_observed?esc(date(t.first_observed)):'pending'}<br>Last TCP response: ${t.last_response?esc(date(t.last_response)):'none recorded'}</p><button class="rename" data-id="${t.id}">Save device settings</button></details></article>`;}).join('')||'<div class="panel">No devices match these filters.</div>':'<div class="panel"><h3>Your first observation starts here.</h3><p>Add a known device IP or hostname below, then choose Observe now.</p></div>';
document.querySelectorAll('.rename').forEach(button=>button.onclick=async()=>{try{await api('/api/update',{id:Number(button.dataset.id),label:$('#name-'+button.dataset.id).value,ports:$('#ports-'+button.dataset.id).value.split(',').map(p=>Number(p.trim())),notes:$('#notes-'+button.dataset.id).value,paused:$('#paused-'+button.dataset.id).checked});document.querySelectorAll('.device details[open]').forEach(d=>d.open=false);await refresh();}catch(e){error(e);}});
$('#enrich').disabled=!!s.enrichment?.running;
$('#enrichment-status').textContent=s.enrichment?.running?'Collecting local advertisements and reverse DNS…':s.enrichment?.error?'Collection error: '+s.enrichment.error:`${s.targets.filter(t=>t.identity?.reverse_dns).length} reverse DNS names · ${s.enrichment?.ssdp?.length||0} advertisements in latest collection`;
$('#ssid-info').textContent=s.enrichment?.wifi?.available?'Phone Wi-Fi information: '+JSON.stringify(s.enrichment.wifi.data):'SSID unavailable: '+(s.enrichment?.wifi?.reason||'Android Wi-Fi access is restricted here.')+' Other devices’ SSIDs cannot be determined from TCP services.';
$('#result-count').textContent=`${visibleTargets(s).length} of ${s.targets.length} devices shown`;
$('#events').innerHTML=s.events.map(e=>`<li><strong>${esc(e.label)}</strong> · ${esc(e.message)}<time>${esc(date(e.at))}</time></li>`).join('')||'<li>No changes recorded yet.</li>';
const recent=s.events.filter(e=>Date.now()-Date.parse(e.at)<86400000);
$('#briefing').textContent=s.targets.length?`${responding} of ${s.targets.length} devices answered on their latest check. ${s.events_24h} recorded events in the last 24 hours (including baselines). ${s.targets.filter(t=>!t.latest).length} devices await a first observation.`:'Add devices to begin building an evidence-based briefing.';
const sel=$('#history-target'), selected=sel.value;
sel.innerHTML='<option value="">Select a device</option>'+s.targets.map(t=>`<option value="${t.id}">${esc(t.label)}</option>`).join('');sel.value=selected;
}
async function refresh(){if(document.querySelector('.device details[open]')||document.activeElement?.id?.startsWith('name-'))return;try{render(await api('/api/state'));}catch(e){$('#status').textContent='Connection unavailable';error(e);}}
$('#scan').onclick=async()=>{try{$('#error').hidden=true;await api('/api/scan',{});await refresh();}catch(e){error(e);}};
$('#add').onsubmit=async e=>{e.preventDefault();const f=new FormData(e.target);try{await api('/api/targets',{host:f.get('host'),label:f.get('label'),ports:f.get('ports').split(',').map(p=>Number(p.trim()))});e.target.reset();$('#error').hidden=true;await refresh();}catch(e){error(e);}};
$('#history-target').onchange=async e=>{if(!e.target.value){$('#history').textContent='Select a device to inspect its last 200 observations.';return;}try{const rows=await api('/api/history/'+e.target.value);$('#history').innerHTML=rows.length?rows.map(r=>`<p><time>${esc(date(r.at))}</time><br>${esc(r.status)} · ${r.services.filter(p=>p.state==='open').map(p=>p.port).join(', ')||'no open ports'}${r.error?' · '+esc(r.error):''}</p>`).join(''):'No observations yet.';}catch(e){error(e);}};
$('#export').onclick=()=>{if(!state)return;const u=URL.createObjectURL(new Blob([JSON.stringify(state,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=u;a.download='atlas-inventory.json';a.click();setTimeout(()=>URL.revokeObjectURL(u),1000);};
refresh();setInterval(refresh,4000);

$('#discover').onclick=async()=>{try{await api('/api/discover',{});await refresh();}catch(e){error(e);}};

$('#enrich').onclick=async()=>{try{await api('/api/enrich',{});await refresh();}catch(e){error(e);}};
$('#search').oninput=()=>{if(state)render(state);};

for(const id of ['filter','sort'])$('#'+id).onchange=()=>{if(state)render(state);};

async function importBle(text,source){try{const result=await api('/api/ble/import',{text,source});$('#ble-result').textContent=`${result.imported} samples imported · ${result.duplicates} duplicates skipped · ${result.rejected_lines} unmatched lines · ${result.decoder}${result.limit_reached?' · sample limit reached':''}`;await refresh();}catch(e){error(e);}}
$('#ble-folder').onclick=async()=>{try{const result=await api('/api/ble/folder',{});$('#ble-result').textContent=result.files.length?result.files.map(f=>`${f.file}: ${f.error||f.imported+' samples imported'}`).join(' · '):'No .txt, .log or .csv files found in Download/AtlasBridge.';await refresh();}catch(e){error(e);}};
$('#ble-file').onchange=async e=>{const file=e.target.files[0];if(!file)return;if(file.size>1000000)return error(Error('Choose a file under 1 MB'));await importBle(await file.text(),file.name);};
$('#ble-paste').onclick=()=>importBle($('#ble-text').value,'pasted log');

let capturePoints=[], replayTimer=null;
function stopReplay(){if(replayTimer)clearInterval(replayTimer);replayTimer=null;$('#capture-play').textContent='Play replay';}
function showPoint(){const i=Number($('#capture-replay').value),p=capturePoints[i];if(!p)return;const clock=new Date(p.time_ms).toISOString().slice(11,23);$('#capture-position').textContent=`Observation ${i+1}/${capturePoints.length} · source ${clock} · ${p.rssi} dBm`;const marker=$('#replay-marker');if(marker){marker.setAttribute('x1',String(captureX(p)));marker.setAttribute('x2',String(captureX(p)));};}
function captureX(p){const first=capturePoints[0].time_ms,last=capturePoints.at(-1).time_ms;return 40+540*(p.time_ms-first)/Math.max(1,last-first);}
$('#capture-load').onclick=async()=>{stopReplay();try{const address=$('#capture-target').value;if(!address)throw Error('Select an imported Bluetooth device');const data=await api('/api/ble/history/'+encodeURIComponent(address));capturePoints=data.points;$('#capture-summary').textContent=`${data.count} samples · mean signal ${data.mean_rssi==null?'unavailable':data.mean_rssi.toFixed(1)+' dBm'}`;$('#capture-replay').disabled=!data.count;$('#capture-replay').max=String(Math.max(0,data.count-1));$('#capture-replay').value='0';$('#capture-play').disabled=data.count<2;const points=capturePoints.map(p=>`${captureX(p)},${160-(p.rssi+127)*140/147}`).join(' ');$('#capture-chart').innerHTML='<text x="0" y="24" fill="currentColor" font-size="12">20</text><text x="0" y="164" fill="currentColor" font-size="12">−127</text><path d="M40 20 V160 H580" fill="none" stroke="currentColor" opacity=".4"/>'+(data.count?`<polyline points="${points}" fill="none" stroke="var(--accent)" stroke-width="2"/><line id="replay-marker" x1="40" x2="40" y1="20" y2="160" stroke="var(--warning)"/>`:'');showPoint();}catch(e){error(e);}};
$('#capture-replay').oninput=()=>{stopReplay();showPoint();};
$('#capture-play').onclick=()=>{if(replayTimer){stopReplay();return;}if(Number($('#capture-replay').value)>=capturePoints.length-1)$('#capture-replay').value='0';$('#capture-play').textContent='Pause replay';replayTimer=setInterval(()=>{const slider=$('#capture-replay');const n=Number(slider.value)+1;if(n>=capturePoints.length){stopReplay();return;}slider.value=String(n);showPoint();},150);};
$('#capture-target').onchange=()=>{stopReplay();$('#capture-summary').textContent='Select Load signal history to inspect this device.';$('#capture-play').disabled=true;$('#capture-replay').disabled=true;};
