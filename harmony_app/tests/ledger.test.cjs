const {test}=require('node:test');
const assert=require('node:assert/strict');
const M=require('../test-output/core/Models');
const L=require('../test-output/core/Ledger');
const E=require('../test-output/core/Editor');
const G=require('../test-output/core/Garage');
const T=require('../test-output/core/Transfer');
const gauge='AA:BB:CC:DD:EE:01';
function registration(s){const f=E.fields('expenses',null);const v={category:'2',title:'配件用品',amount:'1000',date:'2026-01-01',ledger_kind:'asset',item_name:'轮毂'};f.forEach(x=>{if(x.key in v)x.value=v[x.key]});E.applyEdit(s,'expenses',null,f,'1'.repeat(32),gauge);return s.tables.expenses[0];}
test('asset sale retains purchase; deleting sale restores holding and propagates tombstone',()=>{
 const s=M.emptySnapshot(gauge),root=registration(s);
 L.disposeAsset(s,root.transfer_id,'2'.repeat(32),G.parseDay('2026-02-01'),400,true,'出售');
 assert.equal(s.tables.expenses.length,2);assert.equal(L.heldAssets(s.tables.expenses).length,0);
 assert.equal(L.ledgerTotal([],s.tables.expenses,'2026',true).net,600);
 assert.equal(L.ledgerTotal([],s.tables.expenses,'2026',false).net,600);
 assert.throws(()=>L.disposeAsset(s,root.transfer_id,'3'.repeat(32),G.parseDay('2026-03-01'),0,false,''));
 assert.throws(()=>G.removeRow(M.cloneSnapshot(s),'expenses',root));
 G.removeRow(s,'expenses',s.tables.expenses[1]);
 assert.equal(L.heldAssets(s.tables.expenses).length,1);assert.equal(L.ledgerTotal([],s.tables.expenses,'2026',true).net,1000);
 assert.equal(s.tables.expenses_deleted.length,1);T.validate(s);
});
test('asset retirement has no income and retains both historical events',()=>{
 const s=M.emptySnapshot(gauge),root=registration(s);
 assert.throws(()=>L.disposeAsset(M.cloneSnapshot(s),root.transfer_id,'2'.repeat(32),G.parseDay('2025-12-31'),0,false,''));
 L.disposeAsset(s,root.transfer_id,'2'.repeat(32),G.parseDay('2026-02-01'),100,false,'');
 assert.equal(s.tables.expenses.length,2);assert.equal(s.tables.expenses[1].amount,0);
 assert.equal(L.ledgerTotal([],s.tables.expenses,'2026',true).income,0);
});
test('ledger editor preserves imported dates, counterparties and income on edits',()=>{
 const s=M.emptySnapshot(gauge), f=E.fields('expenses',null),values={category:'2',title:'报销',amount:'200',income:'1',ledger_kind:'income',party:'测试对象',item_name:'项目',date:'2026-01-02'};
 f.forEach(x=>{if(x.key in values)x.value=values[x.key]});E.applyEdit(s,'expenses',null,f,'4'.repeat(32),gauge);
 const old=s.tables.expenses[0],fields=E.fields('expenses',old);fields.find(x=>x.key==='note').value='修改备注';E.applyEdit(s,'expenses',old,fields,'5'.repeat(32),gauge);
 assert.equal(s.tables.expenses[0].income,1);assert.equal(s.tables.expenses[0].party,'测试对象');assert.equal(L.ledgerTotal([],s.tables.expenses,'2026',true).net,-200);
});
test('custom interval edit does not add expense fields to transfer schema',()=>{
 const s=M.emptySnapshot(gauge), f=E.fields('custom',null);
 f.find(x=>x.key==='name').value='测试';E.applyEdit(s,'custom',null,f,'6'.repeat(32),gauge);
 T.validate(s);assert.equal('income' in s.tables.custom[0],false);
});

const R=require('../test-output/core/Registration');
test('registration dates survive transfer and overdue dates are not advanced automatically',()=>{
 const s=M.emptySnapshot(gauge),day=G.parseDay('2020-02-29');
 assert.equal(G.dayText(R.annualInsuranceEnd(day)),'2021-02-27');
 const due=R.initialInspectionDue(day,G.parseDay('2026-11-05'));assert.equal(G.dayText(due),'2026-02-28');
 R.saveRegistration(s,gauge,{registeredDay:day,regularPrivateCar:true,inspectionDueDay:due,estimated:true});T.validate(s);
 assert.equal(R.registration(M.cloneSnapshot(s),gauge).inspectionDueDay,due);
 assert.equal(G.dayText(R.nextInspectionDue(R.registration(s,gauge))),'2028-02-29');
});
test('insurance expiry is extended only by continuous dated policies',()=>{
 const row={...T.LEDGER_DEFAULTS,device_id:gauge,ledger_kind:'insurance',item_name:'商业险',category:2,title:'保险',amount:100,date_epoch_day:20000,start_epoch_day:20000,due_epoch_day:20364};
 let tracks=R.insuranceDeadlines([row,{...row,start_epoch_day:20366,due_epoch_day:20730}],20300);assert.equal(tracks[0].due,20364);
 tracks=R.insuranceDeadlines([row,{...row,start_epoch_day:20365,due_epoch_day:20729}],20300);assert.equal(tracks[0].due,20729);
 tracks=R.insuranceDeadlines([row,{...row,start_epoch_day:null,due_epoch_day:20729}],20300);assert.equal(tracks[0].due,20364);
});

test('Harmony-authored asset events and deletion markers export for production Android verification',async()=>{
 const fs=require('node:fs'),crypto=require('node:crypto'),zlib=require('node:zlib'),A=require('../test-output/core/Archive');
 const runtime={sha256:async b=>crypto.createHash('sha256').update(b).digest('hex'),inflateRaw:async(b,n)=>new Uint8Array(zlib.inflateRawSync(b,{maxOutputLength:n+1}))};
 const s=M.emptySnapshot(gauge),root=registration(s);
 L.disposeAsset(s,root.transfer_id,'2'.repeat(32),G.parseDay('2026-02-01'),400,true,'出售');G.removeRow(s,'expenses',s.tables.expenses[1]);
 L.disposeAsset(s,root.transfer_id,'3'.repeat(32),G.parseDay('2026-02-02'),0,false,'移除');
 R.saveRegistration(s,gauge,{registeredDay:18000,regularPrivateCar:true,inspectionDueDay:21365,estimated:false});
 fs.writeFileSync(require('node:path').join(__dirname,'../test-output/harmony-ledger.zip'),await A.encodeArchive(s,runtime));
});
