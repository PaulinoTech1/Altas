/* No browser dependencies: exercise the actual shared client protocol. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
let implementation;
let calls = 0;
const window = {};
const context = {window, fetch: (...args) => {calls++; return implementation(...args);}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../static/api-client.js'),'utf8'),context);
const {request,describe} = window.AtlasAPI;
(async () => {
  const payload = {targets: [], ok:true};
  implementation = async () => ({ok:true,status:200,json:async()=>payload});
  assert.equal(await request('/api/state'),payload);
  implementation = async () => ({ok:false,status:503,headers:{get:()=> 'request-fixture'},json:async()=>({error:'busy',request_id:'request-fixture',failure:{code:'ATLAS_ENGINE_BUSY',message:'busy',category:'capacity',retryable:true,action:'Wait before retrying.',details:{}}})});
  calls=0;
  await assert.rejects(request('/api/v2/import',{text:'fixture'}),error=>{
    assert.equal(error.code,'ATLAS_ENGINE_BUSY');assert.equal(error.status,503);assert.equal(error.requestId,'request-fixture');assert.equal(error.retryable,true);assert.match(describe(error),/Wait before retrying/);assert.match(describe(error),/request-fixture/);return true;
  });
  assert.equal(calls,1,'A retryable mutation must not be retried automatically');
  implementation=async()=>{throw Error('private credential must not escape');};
  await assert.rejects(request('/api/state'),error=>{assert.equal(error.code,'ATLAS_NETWORK_UNAVAILABLE');assert.equal(error.message.includes('private'),false);return true;});
  implementation=async()=>({ok:true,status:200,headers:{get:()=> 'broken-response'},json:async()=>{throw Error('invalid JSON');}});
  await assert.rejects(request('/api/state'),error=>{assert.equal(error.code,'ATLAS_RESPONSE_INVALID');assert.equal(error.requestId,'broken-response');return true;});
  process.stdout.write('PASS: success compatibility, typed failures, correlation IDs, safe messages, invalid responses, no automatic mutation retries.\n');
})().catch(error=>{process.stderr.write(error.stack+'\n');process.exitCode=1;});
