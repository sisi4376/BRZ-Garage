const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const ts=require((process.env.DEVECO_HOME||'C:/Program Files/Huawei/DevEco Studio')+'/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
function harness(fail=false){
 const stats={sessions:0,held:0,stopped:0,syncs:0};const timers=new Map();let id=0;
 const bt={close:()=>{},has:()=>false,session:async(c,a,p,action)=>{stats.sessions++;if(fail)throw Error('offline');return action();},syncConnected:async()=>{stats.syncs++;return{warning:''};}};
 const mock=id=>id==='@kit.AbilityKit'?{wantAgent:{getWantAgent:async()=>({}),OperationType:{START_ABILITY:0},WantAgentFlags:{UPDATE_PRESENT_FLAG:0}}}:id==='@kit.BackgroundTasksKit'?{backgroundTaskManager:{BackgroundMode:{BLUETOOTH_INTERACTION:5},startBackgroundRunning:async()=>{stats.held++;},stopBackgroundRunning:async()=>{stats.stopped++;}}}:id==='./Bluetooth'?{bluetooth:bt}:id==='./Repository'?{repository:{automatic:()=>true,binding:()=>'AA:BB:CC:DD:EE:01',consent:()=>true}}:{};
 const exports={};const source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/platform/Automatic.ets'),'utf8');
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,{exports,require:mock,Error,setTimeout:fn=>{timers.set(++id,fn);return id;},clearTimeout:n=>timers.delete(n)});
 return{auto:new exports.Automatic(),stats,tick:()=>{const item=timers.entries().next().value;if(item){timers.delete(item[0]);item[1]();}}};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
test('automatic session is exclusive, suspended during manual work, and releases background task on stop',async()=>{
 const h=harness();h.auto.suspended=true;h.auto.start({});assert.equal(h.stats.sessions,0);h.auto.suspended=false;h.auto.start({});h.auto.start({});await settle();
 assert.equal(h.stats.sessions,1);assert.equal(h.stats.held,1);assert.equal(h.stats.syncs,1);assert.equal(h.auto.backgroundHeld,true);
 await h.auto.stop();assert.equal(h.auto.running,false);assert.equal(h.auto.backgroundHeld,false);assert.equal(h.stats.stopped,1);
});
test('background reconnection stops after three failures and never requests an idle long task',async()=>{
 const h=harness(true);h.auto.foreground=false;h.auto.start({});await settle();h.tick();await settle();h.tick();await settle();
 assert.equal(h.stats.sessions,3);assert.equal(h.auto.running,false);assert.equal(h.stats.held,0);
});
