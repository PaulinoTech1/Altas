/* Authenticated controls against an isolated fixture, never the live database. */
const {JSDOM,VirtualConsole}=require('jsdom');
const assert=require('node:assert/strict');
const base=process.env.ATLAS_TEST_URL||'http://127.0.0.1:8877';
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function until(check){for(let i=0;i<200;i++){if(check())return;await wait(75);}throw Error('Evidence UI state timed out');}
(async()=>{
 const errors=[],requests=[];let downloads=0;
 const virtualConsole=new VirtualConsole();virtualConsole.on('jsdomError',e=>{if(e.type!=='css-parsing')errors.push(e.message);});
 const dom=await JSDOM.fromURL(base,{resources:'usable',runScripts:'dangerously',pretendToBeVisual:true,virtualConsole,beforeParse(w){w.fetch=(path,opts)=>{requests.push({path,opts});return fetch(new URL(path,base),opts);};w.TextEncoder=TextEncoder;w.URL.createObjectURL=()=>{downloads++;return 'blob:fixture';};w.URL.revokeObjectURL=()=>{};w.HTMLAnchorElement.prototype.click=()=>{};}});
 const w=dom.window,d=w.document;await until(()=>w.AtlasAPI);
 assert.equal(requests.length,0);
 d.querySelector('#control-token').value='0'.repeat(64);
 d.querySelector('#control-unlock').dispatchEvent(new w.Event('submit',{cancelable:true}));
 await until(()=>d.querySelector('#control-error').textContent==='ATLAS_CONTROL_AUTH_INVALID');
 assert.equal(d.querySelector('#control-unlock').hidden,false);
 d.querySelector('#control-token').value='1'.repeat(64);
 d.querySelector('#control-unlock').dispatchEvent(new w.Event('submit',{cancelable:true}));
 await until(()=>d.querySelector('[data-node="ip:1"]'));
 assert.equal(d.querySelector('#control-token').value,'');
 d.querySelector('[data-view="health"]').click();
 await until(()=>d.querySelector('#evidence-context option'));
 assert.match(d.querySelector('#health-summary').textContent,/Last successful run/);
 d.querySelector('#evidence-export').click();await until(()=>downloads===1);
 d.querySelector('#retention-confirm').checked=true;
 d.querySelector('#retention-form').dispatchEvent(new w.Event('submit',{cancelable:true}));
 await until(()=>d.querySelector('#retention-result').textContent.includes('observations'));
 assert.equal(d.querySelector('#retention-confirm').checked,false);
 assert.ok(requests.filter(r=>r.path!=='/api/control/unlock').every(r=>r.opts.headers['X-Atlas-Control']==='1'.repeat(64)));
 assert.ok(requests.every(r=>!r.path.includes('1'.repeat(64))));
 assert.deepEqual(errors,[]);w.close();process.stdout.write('PASS: locked bootstrap, invalid/valid unlock, authenticated API headers, freshness/gaps, evidence download and scoped retention UI.\n');
})().catch(e=>{process.stderr.write(e.stack+'\n');process.exit(1);});
