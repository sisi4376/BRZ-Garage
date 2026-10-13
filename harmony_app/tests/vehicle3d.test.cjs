const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto');
const ts=require('C:/Program Files/Huawei/DevEco Studio/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
const raw=path.join(__dirname,'../entry/src/main/resources/rawfile');
test('3D interceptor serves bundled modules and denies remote URLs, traversal and unknown resources',async()=>{
 let source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/components/Vehicle3D.ets'),'utf8').split('  build()')[0]+'\n}';
 source=source.replace('@Component','').replace('export struct Vehicle3D','export class Vehicle3D').replace(/@Prop @Watch\('sendState'\) /g,'').replace(/@State /g,'');
 class Response{setResponseCode(n){this.code=n;}setReasonMessage(){}setResponseData(b){this.data=b;}setResponseIsReady(b){this.ready=b;}setResponseMimeType(m){this.mime=m;}setResponseEncoding(){}}
 const exports={};vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{exports,require:id=>id==='@kit.ArkWeb'?{webview:{WebviewController:class{}}}:{},WebResourceResponse:Response});
 const view=new exports.Vehicle3D();view.getUIContext=()=>({getHostContext:()=>({resourceManager:{getRawFileContent:async name=>new Uint8Array(fs.readFileSync(path.join(raw,name)))}})});
 for(const url of ['https://example.com/a','https://brz.local/vehicle-3d/../secret','file:///data/private','https://brz.local/vehicle-3d/%2e%2e/secret'])assert.equal(view.intercept(url).code,403);
 const ok=view.intercept('https://brz.local/vehicle-3d/real-models.js?v=4.1.3'),missing=view.intercept('https://brz.local/vehicle-3d/missing.js');await new Promise(r=>setImmediate(r));
 assert.equal(ok.code,200);assert.equal(ok.mime,'text/javascript');assert.equal(ok.ready,true);assert.ok(ok.data.byteLength>1000);assert.equal(missing.code,404);assert.equal(missing.ready,true);
});
test('bundled licensed 3D renderer and firmware are identical to current Android resources',()=>{
 const main=process.env.BRZ_ANDROID_REFERENCE_ROOT||'C:/Users/18739/Documents/ChatGPT/BRZ_OBD';
 const names=['app.html','real-models.js','vehicle-camera.mjs','vendor/three.module.js','vendor/GLTFLoader.js','vendor/BufferGeometryUtils.js','vendor/THREE-LICENSE.txt',...['zd8','zc6'].flatMap(m=>['vehicle.glb','source.json','original/license.txt'].map(n=>'models/'+m+'/'+n))];
 const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
 for(const name of names)assert.equal(hash(raw+'/vehicle-3d/'+name),hash(main+'/preview/vehicle-3d/'+name),name);
 for(const name of ['latest.json','obd_brz_gauge.bin'])assert.equal(hash(raw+'/firmware/'+name),hash(main+'/android_app/app/src/main/assets/firmware/'+name),name);
});
