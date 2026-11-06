const {test}=require('node:test'),assert=require('node:assert/strict');
const D=require('../test-output/core/Deadlines'),M=require('../test-output/core/Models'),L=require('../test-output/core/Ledger'),T=require('../test-output/core/Transfer'),G=require('../test-output/core/Garage');
const day=G.parseDay('2026-10-30'),device='AA:BB:CC:DD:EE:91';
const old={...T.LEDGER_DEFAULTS,transfer_id:'1'.repeat(32),device_id:device,date_epoch_day:day-300,category:2,amount:900,title:'保险',ledger_kind:'insurance',item_name:'交强险',start_epoch_day:day-350,due_epoch_day:day+7,party:'旧公司',note:'',odometer_km:null,maintenance_type:null};
const renewal={...old,transfer_id:'2'.repeat(32),date_epoch_day:day-1,start_epoch_day:day+8,due_epoch_day:day+373,party:'新公司'};
test('continuous insurance renewal changes reminder but retains current policy status',()=>{
 const [track]=D.deadlineTracks([old,renewal],{},day);assert.equal(track.record.transfer_id,old.transfer_id);assert.equal(track.due,old.due_epoch_day);assert.equal(track.reminder,renewal.due_epoch_day);assert.match(track.detail,/连续续保/);assert.match(track.status,/保障日期内/);
});
test('insurance gaps, missing dates and future-only policies retain Android reminder semantics',()=>{
 for(const candidate of [{...renewal,start_epoch_day:day+10},{...renewal,start_epoch_day:null},{...renewal,start_epoch_day:null,due_epoch_day:null}])assert.equal(D.deadlineTracks([old,candidate],{},day)[0].reminder,day+7);
 assert.equal(D.deadlineTracks([renewal],{},day)[0].reminder,null);
 assert.equal(D.deadlineTracks([{...old,due_epoch_day:day-2},{...renewal,start_epoch_day:null}],{},day)[0].reminder,day-2);
});
test('insurance aliases merge by vehicle but unrelated policies stay independent',()=>{
 assert.equal(D.deadlineTracks([old,{...renewal,item_name:'机动车交强险'}],{},day).length,1);
 assert.equal(D.deadlineTracks([old,{...renewal,item_name:'商业险'}],{},day).length,2);
 assert.equal(D.deadlineTracks([old,{...renewal,device_id:'local'}],{},day).length,2);
});
test('inspection profiles override receipts, preserve overdue periods and compute window',()=>{
 const due=G.parseDay('2026-10-31'),registered=G.parseDay('2020-10-10');
 const row={...old,title:'年审',ledger_kind:'',due_epoch_day:due,start_epoch_day:registered,item_name:'常规私家车检验'};
 assert.equal(L.ledgerSection(row),'inspection');
 const t=D.deadlineTracks([row],{},day)[0];assert.match(t.detail,/需上线/);assert.match(t.detail,/2026-08-01/);
 const p={registeredDay:registered,regularPrivateCar:true,inspectionDueDay:G.parseDay('2024-10-31'),estimated:false};
 const x=D.deadlineTracks([row],{[device]:p},day)[0];assert.equal(x.profile,true);assert.equal(x.due,p.inspectionDueDay);assert.match(x.status,/已到期/);assert.match(x.detail,/免上线/);
 assert.equal(D.deadlineTracks([{...row,title:'证件手续',ledger_kind:'paperwork'}],{},day).length,0);
});
test('reminder urgency bands catch up once per band and include inspection window',()=>{
 for(const [offset,expected] of [[40,null],[30,30],[8,30],[7,7],[4,7],[3,3],[1,3],[0,0],[-1,-1]])assert.equal(D.reminderStage({due:day+offset,window:null},day),expected);
 assert.equal(D.reminderStage({due:day+40,window:day-1},day),90);
});
test('mode switches follow page meaning and never modify financial rows',()=>{
 assert.deepEqual(L.switchLedgerMode(2,'',false,false),{section:3,page:'',income:false});
 assert.deepEqual(L.switchLedgerMode(3,'',false,true),{section:2,page:'',income:false});
 assert.equal(L.switchLedgerMode(2,'',false,true).page,'assets');
 assert.equal(L.switchLedgerMode(2,'income',true,false).section,3);
 assert.equal(L.switchLedgerMode(1,'insurance',false,true).section,2);
 assert.equal(L.switchLedgerMode(1,'maintenance',false,true).section,1);
 assert.equal(L.switchLedgerMode(3,'',false,false).page,'statistics');
});
