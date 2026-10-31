/* DOM/API interaction checks against the isolated GUI fixture. */
const {JSDOM,VirtualConsole}=require('jsdom');
const assert=require('node:assert/strict');
const base=process.env.ATLAS_TEST_URL||'http://127.0.0.1:8877';
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function until(check){for(let i=0;i<160;i++){if(check())return;await wait(75);}throw Error('Research GUI did not reach the expected state');}
(async()=>{const errors=[];let downloads=0;const console=new VirtualConsole();console.on('jsdomError',e=>{if(e.type!=='css-parsing')errors.push(e.message);});
 const dom=await JSDOM.fromURL(base,{resources:'usable',runScripts:'dangerously',pretendToBeVisual:true,virtualConsole:console,beforeParse(w){w.fetch=(path,opts)=>fetch(new URL(path,base),opts);w.TextEncoder=TextEncoder;w.URL.createObjectURL=()=>{downloads++;return 'blob:fixture';};w.URL.revokeObjectURL=()=>{};w.HTMLAnchorElement.prototype.click=()=>{};}});
 const w=dom.window,d=w.document;await until(()=>w.AtlasAPI);d.querySelector("#control-token").value="1".repeat(64);d.querySelector("#control-unlock").dispatchEvent(new w.Event("submit",{cancelable:true}));await until(()=>d.querySelector('[data-node="ip:1"]'));
 d.querySelector('[data-view="research"]').click();await until(()=>d.querySelector('#research-target option[value="1"]'));
 d.querySelector('#research-form').dispatchEvent(new w.Event('submit',{cancelable:true}));await until(()=>d.querySelector('#research-status').textContent.startsWith('Analysis complete'));
 assert.match(d.querySelector('#research-results').textContent,/1 accepted samples/);assert.match(d.querySelector('#research-results').textContent,/ARM64/);
 d.querySelector('#research-json').click();assert.equal(downloads,1);d.querySelector('#research-html').click();await until(()=>downloads===2);
 d.querySelector('#research-dataset').value='ble';d.querySelector('#research-dataset').dispatchEvent(new w.Event('change'));assert.equal(d.querySelector('#research-ble-fields').hidden,false);
 d.querySelector('#research-form').dispatchEvent(new w.Event('submit',{cancelable:true}));await until(()=>d.querySelector('#research-status').textContent.startsWith('Analysis complete')&&d.querySelector('#research-results').textContent.includes('2 samples'));
 assert.match(d.querySelector('#research-results').textContent,/mean -50\.000/);
 d.querySelector('#research-benchmark').click();await until(()=>d.querySelectorAll('#research-benchmark-result tbody tr').length===5);
 d.querySelector('#research-window').value='3';d.querySelector('#research-form').dispatchEvent(new w.Event('submit',{cancelable:true}));await until(()=>d.querySelector('#research-status').textContent.startsWith('Analysis failed'));
 assert.match(d.querySelector('#error').textContent,/ATLAS_INPUT_INVALID/);assert.equal(d.querySelector('#research-json').disabled,true);
 assert.deepEqual(errors,[]);w.close();process.stdout.write('PASS: TCP/BLE research, provenance, JSON/notebook downloads, five kernel benchmarks, typed validation and failure cleanup; no script errors.\n');
})().catch(error=>{process.stderr.write(error.stack+'\n');process.exit(1);});
