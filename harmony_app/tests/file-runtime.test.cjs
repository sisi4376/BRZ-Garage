const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const ts=require((process.env.DEVECO_HOME||'C:/Program Files/Huawei/DevEco Studio')+'/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
function harness(input){
 let position=0,closed=0,synced=0,fail=false;const output=[];
 const fileIo={OpenMode:{READ_ONLY:1,READ_WRITE:2,CREATE:4,TRUNC:8},openSync:()=>({fd:1}),statSync:()=>({size:input.length}),
 readSync:(fd,buffer)=>{const n=Math.min(4093,buffer.byteLength,input.length-position);new Uint8Array(buffer).set(input.subarray(position,position+n));position+=n;return n;},
 writeSync:(fd,buffer)=>{if(fail)return 0;const n=Math.min(4091,buffer.byteLength);output.push(Buffer.from(buffer).subarray(0,n));return n;},
 fsyncSync:()=>synced++,closeSync:()=>closed++};
 const exports={},source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/platform/ArchiveRuntime.ets'),'utf8');
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}}).outputText,
 {exports,require:id=>id==='@kit.CoreFileKit'?{fileIo}:id==='../core/Transfer'?require('../test-output/core/Transfer'):{},Uint8Array,ArrayBuffer,Error});
 return{api:exports,output:()=>Buffer.concat(output),closed:()=>closed,synced:()=>synced,fail:()=>fail=true};
}
test('file-provider reads and writes tolerate short IO across many buffer boundaries',()=>{
 const input=Buffer.alloc(200123);for(let i=0;i<input.length;i++)input[i]=i%251;
 const h=harness(input);assert.deepEqual(Buffer.from(h.api.readFile('file://document/1')),input);
 h.api.writeFile('file://document/2',new Uint8Array(input));assert.deepEqual(h.output(),input);assert.equal(h.closed(),2);assert.equal(h.synced(),1);
});
test('zero-byte writes and invalid input sizes fail and close the provider handle',()=>{
 const h=harness(Buffer.alloc(21));assert.throws(()=>h.api.readFile('uri'));assert.equal(h.closed(),1);
 h.fail();assert.throws(()=>h.api.writeFile('uri',new Uint8Array(100)));assert.equal(h.closed(),2);assert.equal(h.synced(),0);
});
