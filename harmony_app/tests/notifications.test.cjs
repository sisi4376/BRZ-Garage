const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const ts=require((process.env.DEVECO_HOME||'C:/Program Files/Huawei/DevEco Studio')+'/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
function harness(){
 const state={enabled:true,consent:true,fail:false,day:20000,rows:[{key:'insurance|local|交强险|20007',title:'交强险',due:20007,window:null}],published:[],cancelled:[],stored:new Map(),scheduled:0};
 const store={get:async(k,d)=>state.stored.get(k)??d,put:async(k,v)=>state.stored.set(k,v),flush:async()=>{}};
 const notificationManager={isNotificationEnabled:async()=>state.enabled,publish:async r=>{if(state.fail)throw Error('permission lost');state.published.push(r);},cancel:async id=>state.cancelled.push(id),requestEnableNotification:async()=>state.enabled=true,openNotificationSettings:async()=>{}};
 const exports={},source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/platform/LedgerNotifications.ets'),'utf8');
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}}).outputText,{exports,console,JSON,Error,require:id=>id==='@kit.NotificationKit'?{notificationManager}:id==='@kit.AbilityKit'?{wantAgent:{getWantAgent:async()=>({}),OperationType:{START_ABILITY:1},WantAgentFlags:{UPDATE_PRESENT_FLAG:1}}}:id==='@kit.ArkData'?{preferences:{getPreferences:async()=>store}}:id==='@kit.BackgroundTasksKit'?{workScheduler:{getWorkStatus:async()=>{if(!state.scheduled)throw Error('absent');},startWork:()=>state.scheduled++}}:id==='./Repository'?{repository:{open:async()=>{},reload:async()=>{},consent:()=>state.consent,snapshot:()=>({})}}:id.endsWith('/Notifications')?require('../test-output/core/Notifications'):id.endsWith('/Deadlines')?{ledgerReminders:()=>state.rows,reminderStage:require('../test-output/core/Deadlines').reminderStage}:{parseDay:()=>state.day,today:()=>''}});
 return {state,n:exports.ledgerNotifications};
}
test('notifications deduplicate repeated checks and process recreation; new urgency band delivers',async()=>{
 const {n,state}=harness();await Promise.all([n.check({}),n.check({})]);await n.check({});assert.equal(state.published.length,1);state.day+=4;await n.check({});assert.equal(state.published.length,2);await n.schedule();await n.schedule();assert.equal(state.scheduled,1);
});
test('failed publish remains retryable; denied permission and withdrawn consent stay quiet',async()=>{
 const {n,state}=harness();state.fail=true;await assert.rejects(n.check({}));state.fail=false;await n.check({});assert.equal(state.published.length,1);state.day+=4;state.enabled=false;await n.check({});state.enabled=true;state.consent=false;await n.check({});assert.equal(state.published.length,1);
});
test('renewal/deletion cancels obsolete notifications and schedules from current data',async()=>{
 const {n,state}=harness();await n.check({});state.rows=[];await n.check({});assert.equal(state.cancelled.length,1);assert.equal(state.cancelled[0],state.published[0].id);
});

test('category controls cancel only disabled reminders and preserve other categories',async()=>{
 const {n,state}=harness();state.rows.push({key:'inspection|local|inspection|20007',title:'年审',due:20007,window:null},{key:'maintenance|local|service|20007',title:'保养',due:20007,window:null});
 await n.check({});await n.setOption({},'insurance',false);
 const text=state.published.at(-1).content.normal.text;assert.ok(!text.includes('交强险'));assert.ok(text.includes('年审'));assert.ok(text.includes('保养'));
 await n.setOption({},'inspection',false);await n.setOption({},'maintenance',false);assert.ok(state.cancelled.length>0);
});
test('category choice persists and enabling resumes future reminders without duplicate alerts',async()=>{
 const {n,state}=harness();await n.setOption({},'insurance',false);await n.check({});assert.equal(state.published.length,0);
 assert.equal((await n.options({})).insurance,false);await n.setOption({},'insurance',true);assert.equal(state.published.length,1);
 await n.setOption({},'insurance',false);assert.ok(state.cancelled.length>0);await n.setOption({},'insurance',true);assert.equal(state.published.length,1);
 state.day+=4;await n.check({});assert.equal(state.published.length,2);
});
