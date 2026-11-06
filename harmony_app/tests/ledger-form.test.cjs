const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const G=require('../test-output/core/Garage'),R=require('../test-output/core/Registration');
// Execute the production form transition method, without replacing its date logic.
function form(){
 const text=fs.readFileSync(require('node:path').join(__dirname,'../entry/src/main/ets/pages/Index.ets'),'utf8');
 const start=text.indexOf('  private ledgerTitleChanged'),end=text.indexOf('  private editMaintenance',start);
 const source=text.slice(start,end).replace('private ledgerTitleChanged(title:string):void','function change(title)');
 const change=new Function('dayText','annualInsuranceEnd','parseDay',source+';return change;')(G.dayText,R.annualInsuranceEnd,G.parseDay);
 const values={title:'停车',ledger_kind:'daily',date:'2026-11-05',income:'0',start_epoch_day:'',due_epoch_day:''};
 return {values,change,context:{selected:null,insuranceInitialized:false,ordinaryStart:'',ordinaryDue:'',insuranceDraftStart:'',insuranceDraftDue:'',fieldValue:key=>values[key]||'',setField:(key,value)=>values[key]=value}};
}
test('basic insurance selection exposes date fields, restores ordinary dates and preserves explicit clearing',()=>{
 const {values,change,context}=form();change.call(context,'保险');assert.equal(values.ledger_kind,'insurance');assert.equal(values.start_epoch_day,'2026-11-05');assert.equal(values.due_epoch_day,'2027-11-04');assert.equal(context.ledgerMore,true);
 values.start_epoch_day='';values.due_epoch_day='';change.call(context,'停车');assert.equal(values.start_epoch_day,'');change.call(context,'保险');assert.equal(values.start_epoch_day,'');assert.equal(values.due_epoch_day,'');
});
test('ordinary legacy optional dates survive repeated category switches',()=>{
 const {values,change,context}=form();values.start_epoch_day='2020-01-01';values.due_epoch_day='2028-10-31';change.call(context,'保险');values.due_epoch_day='2027-01-01';change.call(context,'证件手续');assert.equal(values.due_epoch_day,'2028-10-31');change.call(context,'保险');assert.equal(values.due_epoch_day,'2027-01-01');
});

test('refined daily lists retain imported unknown categories while fixed items stay in their own pages',()=>{
 const ts=require((process.env.DEVECO_HOME||'C:/Program Files/Huawei/DevEco Studio')+'/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
 const source=fs.readFileSync(require('node:path').join(__dirname,'../entry/src/main/ets/pages/Index.ets'),'utf8');
 const a=source.indexOf('  private dailyLedgerRows'),b=source.indexOf('  @Builder private dailyBreakdown',a);
 const js=ts.transpileModule(source.slice(a,b).replace('private dailyLedgerRows','function dailyLedgerRows'),{compilerOptions:{target:ts.ScriptTarget.ES2020}}).outputText;
 const select=new Function('dayText','ledgerSection',js+';return dailyLedgerRows;')(G.dayText,require('../test-output/core/Ledger').ledgerSection);
 const rows=[{date_epoch_day:G.parseDay('2026-01-01'),category:2,income:0,title:'旧版自定义',ledger_kind:'custom_old'}, {date_epoch_day:G.parseDay('2026-01-01'),category:2,income:0,title:'保险',ledger_kind:'insurance'}];
 assert.deepEqual(select.call({rows:()=>rows,ledgerYear:'2026',refinedAccounting:true},false),[rows[0]]);
});
