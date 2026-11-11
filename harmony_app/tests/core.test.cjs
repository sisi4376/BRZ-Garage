const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const {test} = require('node:test');
const root=path.join(__dirname,'../test-output');
const M=require(root+'/core/Models');
const P=require(root+'/core/Protocol');
const T=require(root+'/core/Transfer');
const A=require(root+'/core/Archive');
const S=require(root+'/core/Sync');
const gauge='AA:BB:CC:DD:EE:01';
const runtime={sha256:async b=>crypto.createHash('sha256').update(b).digest('hex'),
 inflateRaw:async(b,n)=>new Uint8Array(zlib.inflateRawSync(b,{maxOutputLength:n+1}))};
function packet(version=3,id=1) {
 const b=new Uint8Array(version===3?56:version===2?48:40),d=new DataView(b.buffer);
 b[0]=version;b[1]=1;d.setUint16(2,b.length,true);d.setUint32(4,id,true);
 d.setUint32(8,1780000000,true);d.setUint32(16,1780000060,true);
 d.setUint32(24,60,true);d.setUint32(28,1000,true);d.setUint32(32,100,true);d.setUint16(36,1000,true);
 if(version>=2){d.setUint16(38,120,true);d.setUint16(40,5000,true);d.setInt16(42,130,true);d.setInt16(44,-240,true);}
 if(version>=3){d.setUint32(46,0xffffffff,true);d.setUint32(50,0x80000000,true);}
 d.setUint16(b.length-2,P.crc16(b,b.length-2),true);return b;
}
function snap(){const s=M.emptySnapshot(gauge);s.tables.trips=[P.parseTrip(gauge,packet())];return s;}
test('Android production ZIP -> Harmony -> Android fixture',async()=>{
 const b=fs.readFileSync(root+'/android.zip'),s=await A.decodeArchive(b,runtime);
 assert.equal(Object.keys(s.tables).length,9);assert.equal(s.tables.expenses[0].maintenance_type,3);
 assert.equal(s.preferences.vehicle_display_name.value,'BRZ 测试');
 assert.equal(JSON.parse(s.preferences['vehicle_registration_'+gauge].value).inspectionDueDay,21365);
 assert.equal(s.preferences.refined_home_ui.value,false);assert.equal(s.preferences.refined_accounting_ui.value,true);assert.equal(s.preferences.default_start_tab.value,2);
 assert.equal(s.preferences['appearance.ZD8_finish'].value,'satin');
 assert.equal(s.devices[gauge].vehicle_total_distance,456789);
 assert.equal(s.devices[gauge].vehicle_max_decel,-92);
 assert.equal(s.devices[gauge].firmware_version,'2.6.0');
 fs.writeFileSync(root+'/harmony.zip',await A.encodeArchive(s,runtime));
 assert.deepEqual(await A.decodeArchive(await A.encodeArchive(s,runtime),runtime),s);
});
test('empty ZIP roundtrip and UTF8 with surrogate pairs',async()=>{
 const s=M.emptySnapshot();assert.deepEqual(await A.decodeArchive(await A.encodeArchive(s,runtime),runtime),s);
 for(const text of ['中文 🏎','abc','\u0000','𠀀'])assert.equal(A.utf8Decode(A.utf8Encode(text)),text);
 assert.throws(()=>A.utf8Decode(Uint8Array.of(0xc0,0x80)));assert.throws(()=>A.utf8Encode('\ud800'));
});
test('archive rejects truncation, corrupted CRC, unsafe entry and expansion',async()=>{
 const b=await A.encodeArchive(snap(),runtime);
 await assert.rejects(A.decodeArchive(b.slice(0,-10),runtime));
 const bad=b.slice();bad[100]^=1;await assert.rejects(A.decodeArchive(bad,runtime));
 const zip=fs.readFileSync(root+'/android.zip');
 await assert.rejects(A.decodeArchive(zip,{...runtime,inflateRaw:async()=>new Uint8Array(0)}));
});
test('v1 maintenance upgrade and v2 mandatory fields',()=>{
 const s=snap();s.tables.expenses=[{transfer_id:'a'.repeat(32),device_id:gauge,date_epoch_day:20000,category:2,amount:10,title:'机油',note:'备注',odometer_km:null}];
 const data=JSON.stringify({tables:s.tables,preferences:s.preferences});
 const old=T.fromJson(data,gauge,s.createdAt,1);assert.equal(old.tables.expenses[0].maintenance_type,null);assert.deepEqual(old.devices,{});
 assert.throws(()=>T.fromJson(data,gauge,s.createdAt,2));
});
test('same import is idempotent and conflict choice explicit',()=>{
 const a=snap(),b=M.cloneSnapshot(a);b.tables.trips[0].distance_m=2000;
 assert.equal(T.merge(a,b,false).snapshot.tables.trips[0].distance_m,1000);
 assert.equal(T.merge(a,b,true).snapshot.tables.trips[0].distance_m,2000);
 const r=T.merge(a,a,true);assert.equal(r.added+r.updated+r.removed,0);
});
test('device cache groups merge by their own timestamps',async()=>{
 const base=await A.decodeArchive(new Uint8Array(fs.readFileSync(root+'/android.zip')),runtime),newer=M.cloneSnapshot(base);
 newer.devices[gauge].vehicle_at+=1000;newer.devices[gauge].vehicle_total_distance=500000;
 newer.devices[gauge].firmware_version='stale-with-same-time';
 const result=T.merge(base,newer,true).snapshot.devices[gauge];
 assert.equal(result.vehicle_total_distance,500000);assert.equal(result.firmware_version,'stale-with-same-time');
 const keep=T.merge(base,newer,false).snapshot.devices[gauge];assert.equal(keep.vehicle_total_distance,500000);assert.equal(keep.firmware_version,'2.6.0');
});
test('v3 import normalizes legacy optional cache values without dropping raw packets',async()=>{
 const base=await A.decodeArchive(new Uint8Array(fs.readFileSync(root+'/android.zip')),runtime);
 const data=JSON.parse(T.toJson(base)),device=data.devices[gauge];
 device.vehicle_fuel_percent='87.5';device.vehicle_average=-1;device.last_fuel_percent='unavailable';
 device.vehicle_rpm='4200';device.time_verified='1';
 data.tables.expenses.forEach(row=>Object.keys(T.LEDGER_DEFAULTS).forEach(key=>delete row[key]));
 const result=T.fromJson(JSON.stringify(data),base.gauge,base.createdAt,3).devices[gauge];
 assert.equal(result.vehicle_fuel_percent,87.5);assert.equal(result.vehicle_average,null);
 assert.equal(result.last_fuel_percent,null);assert.equal(result.vehicle_rpm,4200);
 assert.equal(result.time_verified,true);assert.equal(result.vehicle_data,base.devices[gauge].vehicle_data);
});
test('v3 import identifies an invalid required cache field',async()=>{
 const base=await A.decodeArchive(new Uint8Array(fs.readFileSync(root+'/android.zip')),runtime);
 const data=JSON.parse(T.toJson(base));data.devices[gauge].vehicle_total_distance='broken';
 data.tables.expenses.forEach(row=>Object.keys(T.LEDGER_DEFAULTS).forEach(key=>delete row[key]));
 assert.throws(()=>T.fromJson(JSON.stringify(data),base.gauge,base.createdAt,3),/vehicle_total_distance/);
});
test('split family and tombstone merge atomically',()=>{
 const a=snap(),b=M.emptySnapshot(gauge);
 b.tables.deleted_trips=[{device_id:gauge,trip_id:1,deleted_at_s:1780000000}];
 b.tables.trips=[{...a.tables.trips[0],trip_id:-3},{...a.tables.trips[0],trip_id:-4}];
 const selected=T.merge(a,b,true).snapshot;
 assert.equal(selected.tables.trips.length,2);assert.equal(selected.tables.deleted_trips.length,1);
 assert.equal(T.merge(selected,a,false).snapshot.tables.trips.length,2);
 assert.equal(T.merge(selected,a,true).snapshot.tables.deleted_trips.length,0);
 assert.throws(()=>T.validate({...b,tables:{...b.tables,deleted_trips:[]}}));
});
test('refuel history revisions are not combined across generations',()=>{
 const a=snap(),b=M.cloneSnapshot(a),key='refuel_history_revision_'+gauge;
 const base={device_id:gauge,interval_id:1,start_epoch_s:0,end_epoch_s:0,duration_s:0,distance_m:0,fuel_ml:0,odometer_x10_km:0,detected_added_ml:0,flags:0,data_revised:0};
 a.tables.refuel=[base];b.tables.refuel=[{...base,interval_id:2}];
 a.preferences[key]={type:'int',value:1};b.preferences[key]={type:'int',value:2};
 const r=T.merge(a,b,true).snapshot;assert.deepEqual(r.tables.refuel,b.tables.refuel);assert.equal(r.preferences[key].value,2);
});
test('validation excludes pairing, invalid numbers and duplicate IDs',()=>{
 const a=snap();a.preferences.address={type:'string',value:gauge};assert.throws(()=>T.validate(a));
 delete a.preferences.address;a.tables.trips.push({...a.tables.trips[0]});assert.throws(()=>T.validate(a));
 a.tables.trips.pop();a.tables.trips[0].distance_m=NaN;assert.throws(()=>T.validate(a));
});
test('all trip versions, CRC and corrected values',()=>{
 for(const v of [1,2,3]){const b=packet(v);assert.equal(P.parseTrip(gauge,b).trip_id,1);b[15]^=1;assert.throws(()=>P.parseTrip(gauge,b));}
 const old=P.parseTrip(gauge,packet());old.time_revised=1;old.data_revised=1;old.distance_m=99;old.start_epoch_s=1780000020;
 const merged=P.mergeGaugeTrip(old,P.parseTrip(gauge,packet()));assert.equal(merged.distance_m,99);assert.equal(merged.start_epoch_s,1780000020);assert.equal(merged.poll_requested,4294967295);
});
test('vehicle v1/v2 and unavailable fuel',()=>{
 for(const [version,size] of [[1,64],[2,84]]){const b=new Uint8Array(size),d=new DataView(b.buffer);b[0]=version;d.setUint16(2,size,true);d.setUint16(size-2,P.crc16(b,size-2),true);assert.equal(P.parseVehicle(b).fuelPercent,null);}
});
function transport({failCommit=false,badCrc=false,localCursor=0,lastAck=1}={}) {
 const events=[],m=new Uint8Array(20),d=new DataView(m.buffer);
 m[0]=4;m[1]=1;d.setUint32(4,1,true);d.setUint32(8,1,true);d.setUint32(12,lastAck,true);d.setUint16(16,64,true);d.setUint16(18,56,true);
 const record=packet();if(badCrc)record[20]^=1;
 return {events,t:{has:()=>false,readable:()=>true,read:async id=>id===2?P.timePacket(Math.floor(Date.now()/1000)):id===3?m:record,
 write:async(id,b)=>{events.push(id===4?(b[0]===2?'ack':'cursor'):'clock');}},
 sink:{cursor:()=>localCursor,commitTrips:async(g,r,c)=>{events.push('commit');if(failCommit)throw Error('disk full');assert.equal(c,1);}}};
}
test('sync uses device-local cursor despite another phone ACK and commits before ACK',async()=>{
 const f=transport();await S.synchronize(gauge,f.t,f.sink,()=>{});assert.deepEqual(f.events,['clock','cursor','commit','ack']);
});
test('failed storage or corrupt BLE record never ACK',async()=>{
 for(const options of [{failCommit:true},{badCrc:true}]){const f=transport(options);await assert.rejects(S.synchronize(gauge,f.t,f.sink,()=>{}));assert.ok(!f.events.includes('ack'));}
});
test('reconnection after committed data does not duplicate or ACK another record',async()=>{
 const f=transport({localCursor:1});await S.synchronize(gauge,f.t,f.sink,()=>{});assert.deepEqual(f.events,['clock','commit']);
});
test('clock range and cursor bounds',()=>{assert.throws(()=>P.timePacket(100));assert.throws(()=>P.controlPacket(1,-1));assert.throws(()=>P.controlPacket(3,1));assert.equal(P.clockValue(P.timePacket(1780000000)),1780000000);});

test('lost ACK is retried only for a previously committed cursor',async()=>{
 const f=transport({localCursor:1,lastAck:0});await S.synchronize(gauge,f.t,f.sink,()=>{});assert.deepEqual(f.events,['clock','commit','ack']);
});

test('refined fields survive legacy editor and archive without treating income as spending',async()=>{
 const E=require(root+'/core/Editor'),G=require(root+'/core/Garage'),P=require(root+'/core/AndroidPresentation');
 const s=snap(),row={...T.LEDGER_DEFAULTS,transfer_id:'f'.repeat(32),device_id:gauge,date_epoch_day:21000,category:2,amount:400,title:'保险赔付',note:'原备注',odometer_km:null,maintenance_type:null,
  income:1,ledger_kind:'income',due_epoch_day:21365,start_epoch_day:21000,party:'测试公司',item_name:'商业险'};
 s.tables.expenses=[row];const values=E.fields('expenses',row);values.find(f=>f.key==='note').value='精简编辑';
 E.applyEdit(s,'expenses',row,values,'e'.repeat(32),gauge);
 const edited=s.tables.expenses[0];for(const key of Object.keys(T.LEDGER_DEFAULTS))assert.equal(edited[key],row[key]);
 assert.equal(edited.note,'精简编辑');assert.equal(edited.transfer_id,row.transfer_id);
 assert.equal(G.monthlyCosts(s).reduce((sum,x)=>sum+x.value,0),0);
 assert.equal(P.monthCosts(s,gauge).reduce((sum,x)=>sum+x.total,0),0);
 const roundtrip=await A.decodeArchive(await A.encodeArchive(s,runtime),runtime);
 assert.deepEqual(roundtrip.tables.expenses,s.tables.expenses);
});

test('explicit asset undo deletes the selected event and legacy cancel marker while preserving purchase',()=>{
 const G=require(root+'/core/Garage');
 const s=snap();
 const purchase={...T.LEDGER_DEFAULTS,transfer_id:'1'.repeat(32),device_id:gauge,date_epoch_day:21000,category:2,amount:500,title:'配件用品',note:'',odometer_km:null,maintenance_type:null,ledger_kind:'asset'};
 const sale={...purchase,transfer_id:'2'.repeat(32),date_epoch_day:21001,title:'配件出售',ledger_kind:'asset_sale',income:1,amount:300,asset_ref:purchase.transfer_id};
 const marker={...purchase,transfer_id:'3'.repeat(32),date_epoch_day:21002,title:'撤销',ledger_kind:'asset_cancel',amount:0,asset_ref:sale.transfer_id};
 s.tables.expenses=[purchase,sale,marker];T.validate(s);
 assert.throws(()=>G.removeRow(s,'expenses',purchase));
 G.removeRow(s,'expenses',sale);
 assert.deepEqual(s.tables.expenses,[purchase]);assert.equal(s.tables.expenses_deleted.length,2);
 G.removeRow(s,'expenses',purchase);assert.equal(s.tables.expenses.length,0);
});

test('migration separates preferences and counts a whole refuel revision once',()=>{
 const a=snap(),b=M.cloneSnapshot(a),key='refuel_history_revision_'+gauge;
 const row={device_id:gauge,interval_id:1,start_epoch_s:0,end_epoch_s:0,duration_s:0,distance_m:0,fuel_ml:0,odometer_x10_km:0,detected_added_ml:0,flags:0,data_revised:0};
 a.tables.refuel=[row];b.tables.refuel=[{...row,distance_m:1000}];
 a.preferences[key]={type:'int',value:1};b.preferences[key]={type:'int',value:2};
 a.preferences.refined_home_ui={type:'boolean',value:true};b.preferences.refined_home_ui={type:'boolean',value:false};
 for(const prefer of [false,true]){
  const r=T.merge(a,b,prefer);assert.equal(r.conflicts,2);assert.equal(r.recordConflicts,1);assert.equal(r.preferenceConflicts,1);assert.equal(r.cacheConflicts,0);
  assert.deepEqual(r.snapshot.tables.refuel,(prefer?b:a).tables.refuel);assert.equal(r.snapshot.preferences[key].value,prefer?2:1);
 }
 const same=M.cloneSnapshot(a);same.tables.refuel[0].distance_m=20;
 assert.equal(T.merge(a,same,false).recordConflicts,1);
});
test('registration key order is not a conflict but changed values remain a conflict',()=>{
 const a=snap(),b=M.cloneSnapshot(a),key='vehicle_registration_'+gauge;
 a.preferences[key]={type:'string',value:'{"registeredDay":18000,"regularPrivateCar":true,"inspectionDueDay":null,"estimated":true}'};
 b.preferences[key]={type:'string',value:'{"estimated":true,"inspectionDueDay":null,"regularPrivateCar":true,"registeredDay":18000}'};
 assert.equal(T.merge(a,b,false).conflicts,0);
 b.preferences[key].value=b.preferences[key].value.replace('18000','18001');
 assert.equal(T.merge(a,b,false).preferenceConflicts,1);
});

test('diagnostics omit error messages, user paths and arbitrary stack text',()=>{
 const D=require(root+'/core/Diagnostics');
 const report=D.diagnosticReport('test','Error: secret vehicle ABC\n at foo (/private/secret/Index.ets:12:3)\nnot a frame secret',[]);
 assert.ok(report.includes('Index.ets:12:3'));assert.ok(!report.includes('secret'));assert.ok(!report.includes('/private/'));assert.ok(report.length<=12000);
});
