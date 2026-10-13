const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto');
const root=path.join(__dirname,'../test-output/core'),raw=path.join(__dirname,'../entry/src/main/resources/rawfile');
const ts=require((process.env.DEVECO_HOME||'C:/Program Files/Huawei/DevEco Studio')+'/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
function harness({wrongHardware=false,badHash=false,failChunk=false,cancelAtChunk=false}={}){
 const calls=[];let received=0,failed=false,firmware;
 const original=JSON.parse(fs.readFileSync(raw+'/firmware/latest.json'));const remote=JSON.parse(JSON.stringify(original));remote.firmware.version='3.0.0';if(wrongHardware)remote.device.board='other';
 const http={RequestMethod:{POST:'POST',GET:'GET'},HttpDataType:{STRING:0},createHttp:()=>({destroy:()=>{},request:async(url,options)=>{
  calls.push({url,options});let result={};
  if(url.endsWith('/ota/discover'))result={ip:'192.168.4.1',port:80,ssid:'OBD-Gauge-OTA-TEST',password:'12345678',token:'test'};
  else if(url.endsWith('/ota/info'))result=remote;
  else if(url.endsWith('/ota/status'))result={state:'receiving',received,expected:received?original.files.firmware.size:0};
  else if(url.endsWith('/ota/firmware')){
   if(cancelAtChunk){await firmware.cancel();throw Error('cancelled request');}
   if(failChunk&&!failed){failed=true;throw Error('temporary network failure');}
   assert.equal(Number(options.header['X-Offset']),received);assert.ok(options.extraData.byteLength<=16384);
   received+=options.extraData.byteLength;result={nextOffset:received};
  }
  return{responseCode:200,result:JSON.stringify(result)};
 }})};
 const exports={};const js=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/platform/Firmware.ets'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
 const mock=id=>id==='@kit.NetworkKit'?{http}:id==='./Repository'?{repository:{archive:{sha256:async b=>badHash?'bad':crypto.createHash('sha256').update(b).digest('hex')}}}:id.startsWith('../core/')?require(root+'/'+id.split('/').pop()):{};
 vm.runInNewContext(js,{exports,require:mock,Uint8Array,ArrayBuffer,JSON,Error,Number,setTimeout:f=>setTimeout(f,0)});firmware=new exports.Firmware();
 return {firmware,calls,context:{resourceManager:{getRawFileContent:async p=>new Uint8Array(fs.readFileSync(raw+'/'+p))}},received:()=>received};
}
test('OTA validates bundled bytes and hardware before any flash request',async()=>{
 for(const options of [{badHash:true},{wrongHardware:true}]){const h=harness(options),entry=(await h.firmware.catalog(h.context))[0];
  if(options.badHash)await assert.rejects(h.firmware.select(h.context,entry));else{await h.firmware.select(h.context,entry);await h.firmware.recover();await assert.rejects(h.firmware.upload(()=>{}));}
  assert.equal(h.calls.filter(c=>c.url.endsWith('/ota/firmware')).length,0);
 }
});
test('OTA chunk upload retries from confirmed offset and completes exact size',async()=>{
 const h=harness({failChunk:true});await h.firmware.select(h.context,(await h.firmware.catalog(h.context))[0]);await h.firmware.recover();await h.firmware.upload(()=>{});
 assert.equal(h.received(),JSON.parse(fs.readFileSync(raw+'/firmware/latest.json')).files.firmware.size);assert.ok(h.calls.filter(c=>c.url.endsWith('/ota/status')).length>=2);
});
test('OTA cancellation cannot restart upload or commit another chunk',async()=>{
 const h=harness({cancelAtChunk:true});await h.firmware.select(h.context,(await h.firmware.catalog(h.context))[0]);await h.firmware.recover();await assert.rejects(h.firmware.upload(()=>{}));
 assert.equal(h.calls.filter(c=>c.url.endsWith('/ota/firmware')).length,1);assert.equal(h.received(),0);
});
