const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),zlib=require('node:zlib');
const root=path.join(__dirname,'../test-output/core');
const M=require(root+'/Models'),G=require(root+'/Garage'),E=require(root+'/Editor'),T=require(root+'/Transfer'),A=require(root+'/Archive'),P=require(root+'/Protocol'),Q=require(root+'/Gauge'),D=require(root+'/Maintenance');
const gauge='AA:BB:CC:DD:EE:01';
const runtime={sha256:async b=>crypto.createHash('sha256').update(b).digest('hex'),inflateRaw:async b=>zlib.inflateRawSync(b)};
test('tire replacement starts at zero and reminds at 50000 km or 8 years, whichever comes first',()=>{
 const s=M.emptySnapshot(gauge),at=(km,day=20530)=>{G.calibrate(s,gauge,km*1000);return D.maintenanceItems(s,gauge,day).find(x=>x.title==='轮胎');};
 let item=at(6000);assert.equal(item.badge,'首次计划');assert.match(item.subtitle,/50000 km \/ 8年/);
 assert.match(item.timing,/44000 km/);assert.doesNotMatch(item.detail,/首次阈值/);assert.match(item.detail,/已行驶 6000 km/);
 assert.doesNotMatch(item.detail,/时间期限/);assert.doesNotMatch(item.detail,/预计/);assert.equal(item.progress,.12);
 assert.match(at(0).timing,/50000 km/);assert.equal(at(49000).badge,'首次将到');assert.equal(at(50000).badge,'首次已到');
 G.putPref(s,'odometer_display_'+gauge,false);item=at(6000);assert.equal(item.badge,'未跟踪');assert.equal(item.timing,"里程未显示");assert.doesNotMatch(item.detail,/已行驶/);
 G.putPref(s,'odometer_display_'+gauge,true);
 const row={device_id:gauge,date_epoch_day:20500,category:1,title:'更换轮胎',odometer_km:30000,id:2};
 s.tables.expenses=[{...row,date_epoch_day:20000,odometer_km:10000,id:1},row,{...row,title:'执行轮胎换位',date_epoch_day:20510,odometer_km:31000,id:3}];
 item=at(35000);assert.equal(item.badge,'正常');assert.match(item.timing,/45000 km/);assert.match(item.detail,/80000 km/);assert.match(item.detail,/已使用 30 天/);assert.match(item.detail,/更换后已行驶 5000 km/);
 assert.equal(at(81000).badge,'已超期');const due=D.addMonths(20500,96);
 assert.equal(at(35000,due-30).badge,'即将到期');assert.match(at(35000,due).timing,/今天到期/);assert.equal(at(35000,due+1).badge,'已超期');
 s.tables.expenses=[{...row,odometer_km:null}];item=at(35000);assert.doesNotMatch(item.detail,/80000 km|更换后已行驶/);assert.doesNotMatch(item.detail,/补充更换时里程/);assert.equal(at(35000,due+1).badge,'已超期');
 s.tables.expenses=[{...row,category:2},{...row,title:'执行轮胎换位'}];assert.match(at(6000).timing,/44000 km/);
 assert.equal(G.dayText(D.addMonths(G.parseDay('2024-02-29'),96)),'2032-02-29');
 assert.equal(D.SERVICES.find(x=>x.title==='轮胎换位').km,10000);assert.equal(D.SERVICES.find(x=>x.title==='轮胎换位').months,null);
 assert.ok(D.recommended(50000).includes('更换轮胎'));
});
function trip(){return {device_id:gauge,trip_id:9,start_epoch_s:1780000000,end_epoch_s:1780003600,duration_s:3000,distance_m:30000,fuel_ml:4000,avg_l100_x100:1333,flags:1,time_revised:0,data_revised:0,max_speed_kmh:100,max_rpm:5000,max_accel_x100:10,max_decel_x100:-10,poll_requested:3,poll_received:1};}
function edited(table,row,input){return E.fields(table,row).map(f=>({...f,value:input[f.key]??f.value}));}

test('fuel and expense edits preserve stable IDs and survive real ZIP roundtrip',async()=>{
 const s=M.emptySnapshot(gauge),id='a'.repeat(32);
 E.applyEdit(s,'fuel',null,edited('fuel',null,{odometer:'10000',litres:'40',cost:'320'}),id,gauge);
 E.applyEdit(s,'fuel',s.tables.fuel[0],edited('fuel',s.tables.fuel[0],{cost:'310'}),'b'.repeat(32),gauge);
 assert.equal(s.tables.fuel.length,1);assert.equal(s.tables.fuel[0].transfer_id,id);assert.equal(s.tables.fuel[0].cost,310);
 E.applyEdit(s,'expenses',null,edited('expenses',null,{title:'更换发动机油',category:'1',maintenance:'1',amount:'400',odometer:'10000'}),'c'.repeat(32),gauge);
 assert.deepEqual(await A.decodeArchive(await A.encodeArchive(s,runtime),runtime),s);
 const original=M.cloneSnapshot(s);G.removeRow(s,'fuel',s.tables.fuel[0]);assert.equal(T.merge(s,original,false).snapshot.tables.fuel.length,0);
});
test('split conserves totals, keeps tombstone, resists gauge reimport and can delete one child',()=>{
 const s=M.emptySnapshot(gauge),r=trip();s.tables.trips=[r];
 G.splitTrip(s,r,1780001800,12000,1400,1500);T.validate(s);
 assert.equal(s.tables.trips.length,2);assert.deepEqual(s.tables.trips.map(r=>r.trip_id),[-19,-20]);
 for(const key of ['distance_m','duration_s','fuel_ml'])assert.equal(s.tables.trips.reduce((n,r)=>n+r[key],0),r[key]);
 assert.equal(s.tables.deleted_trips[0].trip_id,9);assert.equal(s.tables.trips[0].max_speed_kmh,null);
 const incoming=M.emptySnapshot(gauge);incoming.tables.trips=[r];assert.equal(T.merge(s,incoming,false).snapshot.tables.trips.length,2);
 G.removeRow(s,'trips',s.tables.trips[0]);T.validate(s);assert.equal(s.tables.trips.length,1);
 assert.throws(()=>G.splitTrip(s,s.tables.trips[0],1780002000,1,1,1));
});
test('bad dates, blank values, false draft flags and inconsistent split are rejected before use',()=>{
 assert.throws(()=>G.parseDay('2026-02-30'));assert.throws(()=>G.numeric('','金额'));
 const s=M.emptySnapshot(gauge),r=trip();s.tables.trips=[r];const before=JSON.stringify(s);
 assert.throws(()=>G.splitTrip(s,r,1780000001,100,100,10));assert.equal(JSON.stringify(s),before);
 assert.throws(()=>E.applyEdit(s,'fuel',null,edited('fuel',null,{odometer:'1',litres:'20',cost:'2',full:'2'}),'f'.repeat(32),gauge));
});
test('mileage anchor counts split family only once and includes later trips',()=>{
 const s=M.emptySnapshot(gauge);s.tables.trips=[trip()];G.calibrate(s,gauge,10000000);
 G.splitTrip(s,s.tables.trips[0],1780001800,15000,1500,2000);assert.equal(G.mileage(s,gauge),10000000);
 s.tables.trips.push({...trip(),trip_id:10,distance_m:12000});assert.equal(G.mileage(s,gauge),10012000);
});
test('full-tank fuel analysis accumulates partial fills and excludes draft and duplicate odometer',()=>{
 const r=(odo,litres,full=1,draft=0)=>({odometer_km:odo,litres,full_tank:full,draft,date_epoch_day:20000});
 const records=[r(1000,40),r(1200,10,0),r(1400,20),r(1400,99),r(1800,99,1,1)];
 assert.equal(G.fuelAverage(records),7.5);assert.equal(G.fuelAnalysis(records).length,1);
 assert.equal(G.fuelAverage([r(1000,40)]),null);
});
test('manual fuel depletion and range correction match Android semantics',()=>{
 const s=M.emptySnapshot(gauge),v={fuelPercent:50,totalFuel:100000,average:10};
 assert.equal(G.rangeKm(s,gauge,v),236);G.putPref(s,'manual_fuel_percent','50');G.putPref(s,'manual_fuel_baseline_ml',95000);
 assert.equal(G.rangeKm(s,gauge,v),236);assert.equal(G.rangeKm(s,gauge,{...v,fuelPercent:null}),189);G.putPref(s,'range_consumption_source','fuel_records');assert.equal(G.rangeKm(s,gauge,v),null);
});
test('custom reset archives prior interval and updates all baseline values together',()=>{
 const s=M.emptySnapshot(gauge),v={totalDistance:10000,totalDuration:1000,totalFuel:1000};
 G.resetCustom(s,gauge,v,'通勤','a'.repeat(32));assert.equal(s.tables.custom.length,0);
 G.resetCustom(s,gauge,{totalDistance:25000,totalDuration:2000,totalFuel:2200},'旅行','b'.repeat(32));
 assert.equal(s.tables.custom[0].distance_m,15000);assert.equal(s.tables.custom[0].name,'通勤');assert.equal(G.pref(s,'custom_base_fuel_'+gauge),2200);T.validate(s);
});
test('maintenance calendar clamps leap-day and end-of-month like LocalDate.plusMonths',()=>{
 assert.equal(G.dayText(D.addMonths(G.parseDay('2024-08-31'),6)),'2025-02-28');
 assert.equal(G.dayText(D.addMonths(G.parseDay('2023-08-31'),6)),'2024-02-29');
 assert.equal(D.SERVICES.length,14);assert.ok(D.matches('机油机滤',D.SERVICES[0]));
});
test('wire control packets enforce supported profiles, ranges, and CRC',()=>{
 for(const bytes of [Q.settingPacket(75),Q.settingPacket(4,true),Q.odometerPacket(true),Q.odometerPacket(false,1234500),Q.refuelPacket(4,10000),Q.customPacket([100,200,300])])
  assert.equal(new DataView(bytes.buffer).getUint16(bytes.length-2,true),P.crc16(bytes,bytes.length-2));
 assert.throws(()=>Q.settingPacket(3,true));assert.throws(()=>Q.settingPacket(9));assert.throws(()=>Q.refuelPacket(4,7500));assert.throws(()=>Q.refuelPacket(2,1));
 assert.equal(new DataView(Q.odometerPacket(false,1234500).buffer).getUint32(4,true),12345);
});
test('refuel parser rejects bad CRC and cursor outside declared history',async()=>{
 const meta=new Uint8Array(36),d=new DataView(meta.buffer);meta.set([2,1,36,0]);d.setUint32(4,3,true);d.setUint32(8,3,true);d.setUint16(34,P.crc16(meta,34),true);
 assert.equal(Q.parseRefuelMeta(meta).newest,3);const broken=meta.slice();broken[4]^=1;assert.throws(()=>Q.parseRefuelMeta(broken));
 const record=new Uint8Array(48),v=new DataView(record.buffer);record.set([1,1,48,0]);v.setUint32(4,4,true);v.setUint16(46,P.crc16(record,46),true);
 await assert.rejects(Q.readRefuel(gauge,{read:async id=>id===11?meta:record,write:async()=>{}}));
});
test('firmware hardware checks reject incompatible screen, partition and board',()=>{
 const file=JSON.parse(fs.readFileSync(path.join(__dirname,'../entry/src/main/resources/rawfile/firmware/latest.json'),'utf8'));
 const a=Q.parseFirmware(JSON.stringify(file));assert.ok(Q.sameHardware(a,a));
 for(const field of ['board','lcd','slots','w','variant']) assert.equal(Q.sameHardware(a,{...a,[field]:field==='slots'?1:'wrong'}),false);
 assert.equal(Q.compareVersion('4.0.10','4.0.3'),7);
});

test('maintenance category remains Android code 1 and daily code 2 is never a service',()=>{
 const s=M.emptySnapshot(gauge);G.calibrate(s,gauge,10000000);
 E.applyEdit(s,'expenses',null,edited('expenses',null,{date:'2026-10-01',category:'1',title:'更换发动机油',amount:'300',odometer:'10000',maintenance:'1'}),'1'.repeat(32),gauge);
 E.applyEdit(s,'expenses',null,edited('expenses',null,{date:'2026-10-02',category:'2',title:'更换发动机油',amount:'20',odometer:'12000'}),'2'.repeat(32),gauge);
 const oil=D.maintenance(s,gauge).find(x=>x.label.startsWith('发动机油'));
 assert.match(oil.detail,/15000 km/);assert.equal(s.tables.expenses[0].maintenance_type,1);assert.equal(s.tables.expenses[1].maintenance_type,null);
});
test('deleting refuel node preserves user revisions in combined next interval',()=>{
 const s=M.emptySnapshot(gauge);G.putPref(s,'refuel_history_revision_'+gauge,1);
 const row={device_id:gauge,interval_id:1,start_epoch_s:1,end_epoch_s:2,duration_s:10,distance_m:100,fuel_ml:10,odometer_x10_km:1000,detected_added_ml:20000,flags:1,data_revised:1};
 s.tables.refuel=[row,{...row,interval_id:2,start_epoch_s:2,end_epoch_s:3,data_revised:0,distance_m:150}];
 G.reconcileDeletedNode(s,gauge,1,false,1,2);assert.equal(s.tables.refuel.length,1);assert.equal(s.tables.refuel[0].distance_m,250);assert.equal(s.tables.refuel[0].data_revised,1);T.validate(s);
});
test('plate parser agrees with conventional blue plate constraints',()=>{
 assert.equal(G.plateText('粤 b·12a34'),'粤B·12A34');assert.throws(()=>G.plateText('粤B12I34'));assert.throws(()=>G.plateText('粤BABC12'));
});
test('revised driving details survive later gauge sync',()=>{
 const s=M.emptySnapshot(gauge),r=trip();s.tables.trips=[r];
 E.applyEdit(s,'trips',r,edited('trips',r,{speed:'90',rpm:'4500',accel:'1.23',decel:'-2.34',average:'12'}),'a'.repeat(32),gauge);
 const result=P.mergeGaugeTrip(s.tables.trips[0],r);assert.equal(result.max_speed_kmh,90);assert.equal(result.max_decel_x100,-234);assert.equal(result.avg_l100_x100,1200);
});

test('data-only revision preserves unknown original time; time-only revision preserves measured data flags',()=>{
 const s=M.emptySnapshot(gauge),r={...trip(),start_epoch_s:0,end_epoch_s:0,flags:0};s.tables.trips=[r];
 E.applyEdit(s,'trips',r,edited('trips',r,{distance:'5',duration:'600',fuel:'1'}),'a'.repeat(32),gauge,'data');assert.equal(s.tables.trips[0].start_epoch_s,0);assert.equal(s.tables.trips[0].flags,0);assert.equal(s.tables.trips[0].time_revised,0);
 const measured=trip();s.tables.trips=[measured];E.applyEdit(s,'trips',measured,edited('trips',measured,{start:'2026-10-01T12:00:00',end:'2026-10-01T13:00:00'}),'a'.repeat(32),gauge,'time');assert.equal(s.tables.trips[0].time_revised,1);assert.equal(s.tables.trips[0].data_revised,0);assert.equal(s.tables.trips[0].distance_m,measured.distance_m);
});

test('Android split accepts independent non-overlapping times, repairs unknown times and rejects empty segments',()=>{
 const r={...trip(),flags:0,start_epoch_s:0,end_epoch_s:0},s=M.emptySnapshot(gauge);s.tables.trips=[r];
 const input={firstStart:E.localTime(1780000000),firstEnd:E.localTime(1780001500),secondStart:E.localTime(1780001800),secondEnd:E.localTime(1780003600),distance:'12',duration:'1400',fuel:'1.5'};
 const before=JSON.stringify(s);
 assert.throws(()=>E.applyEdit(s,'split',r,edited('split',r,{...input,distance:'0'}),'',gauge));assert.equal(JSON.stringify(s),before);
 assert.throws(()=>E.applyEdit(s,'split',r,edited('split',r,{...input,secondStart:E.localTime(1780001400)}),'',gauge));assert.equal(JSON.stringify(s),before);
 E.applyEdit(s,'split',r,edited('split',r,input),'',gauge);T.validate(s);
 assert.equal(s.tables.trips[0].end_epoch_s,1780001500);assert.equal(s.tables.trips[1].start_epoch_s,1780001800);
 assert.ok(s.tables.trips.every(t=>t.flags&1));assert.equal(s.tables.trips.reduce((n,t)=>n+t.duration_s,0),r.duration_s);
});

test('archive-only home shows scoped historical totals without inventing current, refuel or custom readings',()=>{
 const H=require(root+'/Home'),s=M.emptySnapshot(gauge);s.tables.trips=[trip(),{...trip(),device_id:'AA:BB:CC:DD:EE:02',distance_m:999999}];const before=JSON.stringify(s);
 assert.deepEqual(H.homeMetrics(s,gauge,null,'total'),[30000,3000,4000]);
 for(const kind of ['current','refuel','custom'])assert.deepEqual(H.homeMetrics(s,gauge,null,kind),[]);
 assert.equal(JSON.stringify(s),before);assert.deepEqual(H.homeMetrics(M.emptySnapshot(gauge),gauge,null,'total'),[]);
});
test('gauge snapshot remains authoritative over revised or incomplete phone history',()=>{
 const H=require(root+'/Home'),s=M.emptySnapshot(gauge);s.tables.trips=[trip()];
 const v={totalDistance:900000,totalDuration:50000,totalFuel:70000,currentDistance:1000,currentDuration:100,currentFuel:80};
 assert.deepEqual(H.homeMetrics(s,gauge,v,'total'),[900000,50000,70000]);assert.deepEqual(H.homeMetrics(s,gauge,v,'current'),[1000,100,80]);
 assert.deepEqual(H.homeMetrics(s,gauge,v,'refuel',[2000,200,180]),[2000,200,180]);
});
