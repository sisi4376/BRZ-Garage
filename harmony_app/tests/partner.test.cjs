const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const ts=require('C:/Program Files/Huawei/DevEco Studio/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
function setup({consent=true,automatic=true,binding='AA:BB:CC:DD:EE:01',busy=false}={}){
 const calls=[],exports={};
 const repository={open:async()=>calls.push('open'),reload:async()=>{},consent:()=>consent,automatic:()=>automatic,binding:()=>binding};
 const bluetooth={isBusy:()=>busy,syncBackground:async(c,a)=>calls.push(a),cancelBackground:()=>calls.push('cancel')};
 const source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/entryability/PartnerAgentAbility.ets'),'utf8');
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}}).outputText,{exports,require:id=>id==='@kit.ConnectivityKit'?{PartnerAgentExtensionAbility:class{context={filesDir:'test'};}}:id.endsWith('Repository')?{repository}:{bluetooth}});
 return {ability:new exports.default(),calls};
}
const tick=()=>new Promise(r=>setImmediate(r));
test('partner discovery requires persisted consent, automatic mode and matching bound gauge',async()=>{
 for(const options of [{consent:false},{automatic:false},{binding:'AA:BB:CC:DD:EE:02'},{busy:true}]){
  const h=setup(options);h.ability.onDeviceDiscovered({bluetoothAddress:{address:'AA:BB:CC:DD:EE:01'}});await tick();assert.ok(!h.calls.includes('AA:BB:CC:DD:EE:01'));
 }
 const h=setup();h.ability.onDeviceDiscovered({bluetoothAddress:{address:'aa:bb:cc:dd:ee:01'}});await tick();assert.ok(h.calls.includes('AA:BB:CC:DD:EE:01'));
});
test('partner discovery coalesces duplicate callbacks and destruction cancels its own connection',async()=>{
 const h=setup(),device={bluetoothAddress:{address:'AA:BB:CC:DD:EE:01'}};
 h.ability.onDeviceDiscovered(device);h.ability.onDeviceDiscovered(device);await tick();assert.equal(h.calls.filter(v=>v===device.bluetoothAddress.address).length,1);
 h.ability.onDestroyWithReason(0);h.ability.onDeviceDiscovered(device);await tick();assert.equal(h.calls.at(-1),'cancel');
});
