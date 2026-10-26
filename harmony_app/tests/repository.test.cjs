const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),zlib=require('node:zlib');
const {DatabaseSync}=require('node:sqlite');
const root=path.join(__dirname,'../test-output');
const ts=require((process.env.DEVECO_HOME||'C:/Program Files/Huawei/DevEco Studio')+'/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
const M=require(root+'/core/Models'),A=require(root+'/core/Archive');
const gauge='AA:BB:CC:DD:EE:01';
const runtime={sha256:async b=>crypto.createHash('sha256').update(b).digest('hex'),inflateRaw:async(b,n)=>new Uint8Array(zlib.inflateRawSync(b,{maxOutputLength:n+1}))};
function harness(name){
 const folder=path.join(root,'repository-'+name);fs.mkdirSync(folder,{recursive:true});
 const database=new DatabaseSync(':memory:');let failAt=0,steps=0;
 const db={executeSql:async(sql,args=[])=>{steps++;if(failAt&&steps===failAt)throw Error('injected disk failure');database.prepare(sql).run(...args);},
  querySql:async(sql,args=[])=>{const result=database.prepare(sql).all(...args);let index=-1;return{goToFirstRow:()=>{index=0;return result.length>0;},goToNextRow:()=>++index<result.length,getString:i=>Object.values(result[index])[i],close:()=>{}};},
  batchInsert:async(table,rows)=>{for(const row of rows){steps++;if(failAt&&steps===failAt)throw Error('injected disk failure');const keys=Object.keys(row);database.prepare('INSERT INTO '+table+'('+keys.join(',')+') VALUES('+keys.map(()=>'?').join(',')+')').run(...Object.values(row));}return rows.length;},
  beginTransaction:()=>database.exec('BEGIN'),commit:()=>database.exec('COMMIT'),rollBack:()=>database.exec('ROLLBACK')};
 const exports={};
 const source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/platform/Repository.ets'),'utf8');
 const js=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}}).outputText;
 const requireMock=id=>id==='@kit.ArkData'?{relationalStore:{SecurityLevel:{S1:1},getRdbStore:async()=>db}}:
 id==='./ArchiveRuntime'?{HarmonyArchiveRuntime:class{sha256=runtime.sha256;inflateRaw=runtime.inflateRaw;},listBackupFiles:p=>fs.readdirSync(p).filter(n=>/^before-import-[0-9]+-[0-9a-f]+\.zip$/.test(n)).sort().reverse().map(n=>path.join(p,n)),readFile:p=>new Uint8Array(fs.readFileSync(p)),writeFile:(p,b)=>fs.writeFileSync(p,b)}:
 id.startsWith('../core/')?require(root+'/core/'+id.split('/').pop()):{};
 vm.runInNewContext(js,{exports,require:requireMock,console,Set,Map,JSON,Date,Error,Number,Uint8Array});
 return {Repository:exports.Repository,context:{filesDir:folder},inject:()=>{steps=0;failAt=3;},reset:()=>failAt=0,
  seedMeta:m=>database.prepare('INSERT OR REPLACE INTO local_state(id,payload) VALUES(1,?)').run(JSON.stringify(m))};
}
test('legacy local metadata without later cache fields exports and backs up successfully',async()=>{
 const h=harness('legacy-meta'),initial=new h.Repository();await initial.open(h.context);
 h.seedMeta({binding:'',archiveGauge:gauge,consent:false,cursors:{},lastSync:0,backup:'',automatic:false});
 const repo=new h.Repository();await repo.open(h.context);
 const exported=await A.decodeArchive(await A.encodeArchive(repo.snapshot(),runtime),runtime);
 assert.equal(exported.devices[gauge].last_fuel_percent,null);
 assert.equal(exported.devices[gauge].last_fuel_at,0);
 assert.equal(exported.devices[gauge].vehicle_at,0);
 await repo.importData(exported,true);
 assert.equal((await A.decodeArchive(new Uint8Array(fs.readFileSync(repo.backupPath())),runtime)).gauge,gauge);
});
function trip(id=1){return {device_id:gauge,trip_id:id,start_epoch_s:1780000000,end_epoch_s:1780000060,duration_s:60,distance_m:1000,fuel_ml:100,avg_l100_x100:1000,flags:1,time_revised:0,data_revised:0,max_speed_kmh:null,max_rpm:null,max_accel_x100:null,max_decel_x100:null,poll_requested:0,poll_received:0};}
test('current-trip archive marker commits atomically, survives deletion/restart and allows a new trip',async()=>{
 const h=harness('current-driving'),repo=new h.Repository();await repo.open(h.context);await repo.agree();await repo.bind(gauge);
 const v={epoch:1780000060,uptime:600,currentStart:1780000000,currentDistance:1000,currentDuration:60,currentFuel:100,
  totalDistance:10000,totalDuration:600,totalFuel:1000,fuelPercent:50,rpm:800,speed:0,average:10};
 await repo.cacheVehicle(v);assert.equal(repo.currentTripSnapshotArchived(),false);
 h.inject();await assert.rejects(repo.commitTrips(gauge,[trip()],1));h.reset();
 assert.equal(repo.currentTripSnapshotArchived(),false);assert.equal(repo.snapshot().tables.trips.length,0);
 await repo.commitTrips(gauge,[trip()],1);assert.equal(repo.currentTripSnapshotArchived(),true);
 await repo.edit(s=>{s.tables.trips=[];s.tables.deleted_trips=[{device_id:gauge,trip_id:1,deleted_at_s:1780000070}];});
 const reopened=new h.Repository();await reopened.open(h.context);
 assert.equal(reopened.currentTripSnapshotArchived(),true);assert.equal(reopened.snapshot().tables.trips.length,0);
 await reopened.cacheVehicle({...v,currentStart:1780001000,epoch:1780001060});
 assert.equal(reopened.currentTripSnapshotArchived(),false);
 assert.equal(reopened.snapshot().tables.trips.length,0);
 assert.equal(JSON.stringify(reopened.snapshot()).includes('archivedVehicles'),false);
});
const normalize=x=>JSON.parse(JSON.stringify(x));
test('production repository persists trips and cursor atomically, rolls back injected write failure',async()=>{
 const h=harness('atomic'),repo=new h.Repository();await repo.open(h.context);await repo.agree();await repo.bind(gauge);
 await repo.commitTrips(gauge,[trip()],1);assert.equal(repo.cursor(gauge),1);
 h.inject();await assert.rejects(repo.commitTrips(gauge,[trip(2)],2));h.reset();
 assert.equal(repo.cursor(gauge),1);
 const reopened=new h.Repository();await reopened.open(h.context);
 assert.equal(reopened.cursor(gauge),1);assert.equal(reopened.snapshot().tables.trips.length,1);assert.equal(reopened.consent(),true);assert.equal(reopened.binding(),gauge);
});
test('production import backs up before merge, retains tombstones and does not transfer pairing',async()=>{
 const h=harness('import'),repo=new h.Repository();await repo.open(h.context);await repo.commitTrips(gauge,[trip()],1);
 const incoming=M.emptySnapshot(gauge);incoming.tables.deleted_trips=[{device_id:gauge,trip_id:1,deleted_at_s:1780000010}];
 await repo.importData(incoming,true);assert.equal(repo.binding(),'');assert.equal(repo.cursor(gauge),0);
 const backup=await A.decodeArchive(new Uint8Array(fs.readFileSync(repo.backupPath())),runtime);assert.equal(backup.tables.trips.length,1);
 await repo.commitTrips(gauge,[trip()],1);assert.equal(repo.snapshot().tables.trips.length,0);
 const reopened=new h.Repository();await reopened.open(h.context);assert.equal(reopened.snapshot().tables.deleted_trips.length,1);assert.equal(reopened.snapshot().gauge,gauge);
});
test('v3 import persists and hydrates Android dashboard, firmware and appearance data',async()=>{
 const h=harness('v3-device'),repo=new h.Repository();await repo.open(h.context);
 const incoming=await A.decodeArchive(new Uint8Array(fs.readFileSync(root+'/android.zip')),runtime);
 await repo.importData(incoming,true);assert.equal(repo.binding(),'');assert.equal(repo.vehicle().totalDistance,456789);
 assert.equal(repo.snapshot().devices[gauge].firmware_version,'2.6.0');
 assert.equal(repo.snapshot().preferences['appearance.ZD8_finish'].value,'satin');
 const reopened=new h.Repository();await reopened.open(h.context);assert.equal(reopened.vehicle().fuelPercent,62.5);
 assert.equal(reopened.snapshot().devices[gauge].vehicle_max_decel,-92);
});
test('production database handles archive larger than one query buffer through paged records',async()=>{
 const h=harness('large'),repo=new h.Repository();await repo.open(h.context);
 const rows=Array.from({length:8000},(_,i)=>trip(i+1));await repo.commitTrips(gauge,rows,8000);
 const reopened=new h.Repository();await reopened.open(h.context);
 assert.equal(reopened.snapshot().tables.trips.length,8000);assert.equal(reopened.cursor(gauge),8000);
});
test('failed merge rolls back local data and retains a readable on-disk backup',async()=>{
 const h=harness('failed-merge'),repo=new h.Repository();await repo.open(h.context);await repo.commitTrips(gauge,[trip()],1);
 const incoming=M.emptySnapshot(gauge);incoming.tables.trips=[trip(2)];
 h.inject();await assert.rejects(repo.importData(incoming,true));h.reset();
 assert.equal(repo.snapshot().tables.trips.length,1);
 const reopened=new h.Repository();await reopened.open(h.context);assert.equal(reopened.snapshot().tables.trips.length,1);assert.equal(reopened.cursor(gauge),1);
 const backups=fs.readdirSync(h.context.filesDir).filter(n=>n.endsWith('.zip'));assert.ok(backups.length);
 const backup=await A.decodeArchive(new Uint8Array(fs.readFileSync(path.join(h.context.filesDir,backups.at(-1)))),runtime);assert.equal(backup.tables.trips.length,1);
});

test('editable repository rolls back invalid edits and serializes live metadata with content',async()=>{
 const h=harness('edit'),repo=new h.Repository();await repo.open(h.context);await repo.commitTrips(gauge,[trip()],1);
 await assert.rejects(repo.edit(s=>{s.tables.trips[0].distance_m=-1;}));assert.equal(repo.snapshot().tables.trips[0].distance_m,1000);
 await Promise.all([repo.setAutomatic(true),repo.cacheVehicle({epoch:1780000000,uptime:10,fuelPercent:null,currentDistance:0,currentDuration:0,currentFuel:0,totalDistance:50000,totalDuration:1000,totalFuel:4000,rpm:null,speed:null,average:8,currentStart:0}),repo.edit(s=>{s.tables.trips[0].distance_m=1200;s.tables.trips[0].data_revised=1;})]);
 const reopened=new h.Repository();await reopened.open(h.context);assert.equal(reopened.automatic(),true);assert.equal(reopened.vehicle().totalDistance,50000);assert.equal(reopened.snapshot().tables.trips[0].distance_m,1200);
});
test('pending settings remain private, persist across reopen and clear when binding changes',async()=>{
 const h=harness('pending'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);await repo.queueSetting('display',false);await repo.queueSetting('profile',1);await repo.setRefinedHome(false);
 const reopened=new h.Repository();await reopened.open(h.context);assert.equal(reopened.pendingDisplay(),false);assert.equal(reopened.pendingProfile(),1);assert.equal(reopened.refinedHome(),false);assert.equal(reopened.snapshot().preferences.refined_home_ui.value,false);assert.equal(reopened.groupedSettings(),true);assert.equal(reopened.snapshot().preferences.grouped_settings_ui,undefined);assert.equal(Object.keys(reopened.snapshot().preferences).some(k=>k.startsWith('pending')),false);
 await reopened.bind('AA:BB:CC:DD:EE:02');assert.equal(reopened.pendingProfile(),null);assert.equal(reopened.pendingDisplay(),null);
});

test('backup history exposes all saved packages including failed imports, preserving restorable records',async()=>{
 const h=harness('backup-history'),repo=new h.Repository();await repo.open(h.context);const existing=repo.backupPaths().length;await repo.commitTrips(gauge,[trip()],1);
 await repo.importData(M.emptySnapshot(gauge),false);await repo.importData(M.emptySnapshot(gauge),false);
 h.inject();await assert.rejects(repo.importData(M.emptySnapshot(gauge),false));h.reset();
 const reopened=new h.Repository();await reopened.open(h.context);assert.equal(reopened.backupPaths().length,existing+3);
 for(const file of reopened.backupPaths()){const backup=await A.decodeArchive(new Uint8Array(fs.readFileSync(file)),runtime);assert.equal(backup.tables.trips.length,1);}
});

test('layout preferences import, conflict selection, re-export and reopen stay aligned',async()=>{
 const h=harness('layout-parity'),repo=new h.Repository();await repo.open(h.context);
 await repo.setRefinedHome(false);await repo.setGroupedSettings(false);
 const incoming=M.emptySnapshot(gauge);incoming.preferences={refined_home_ui:{type:'boolean',value:true},grouped_settings_ui:{type:'boolean',value:true}};
 await repo.importData(incoming,false);assert.equal(repo.refinedHome(),false);assert.equal(repo.groupedSettings(),false);
 await repo.importData(incoming,true);
 const reopened=new h.Repository();await reopened.open(h.context);
 assert.equal(reopened.refinedHome(),true);assert.equal(reopened.groupedSettings(),true);
 assert.equal(reopened.snapshot().preferences.refined_home_ui.value,true);
 await reopened.setRefinedHome(false);incoming.preferences.refined_home_ui.value=null;
 await reopened.importData(incoming,true);assert.equal(reopened.refinedHome(),true);
});

test('unbound multi-gauge imports never project another gauge cache onto the active archive gauge',async()=>{
 const h=harness('multi-gauge-cache'),repo=new h.Repository();await repo.open(h.context);
 const a=await A.decodeArchive(new Uint8Array(fs.readFileSync(root+'/android.zip')),runtime),b=M.cloneSnapshot(a);
 const other='AA:BB:CC:DD:EE:02';b.gauge=other;b.tables=M.emptySnapshot().tables;b.preferences={};
 b.devices={[other]:{...a.devices[gauge],vehicle_total_distance:1234567,vehicle_at:Number(a.devices[gauge].vehicle_at)+10000}};
 await repo.importData(a,true);await repo.importData(b,true);
 assert.equal(repo.snapshot().gauge,gauge);assert.equal(repo.vehicle().totalDistance,456789);
 assert.equal(repo.snapshot().devices[gauge].vehicle_total_distance,456789);
 assert.equal(repo.snapshot().devices[other].vehicle_total_distance,1234567);
 await repo.bind(other);assert.equal(repo.vehicle().totalDistance,1234567);
 await repo.bind(gauge);assert.equal(repo.vehicle().totalDistance,456789);
 const reopened=new h.Repository();await reopened.open(h.context);assert.equal(reopened.vehicle().totalDistance,456789);
});

test('live vehicle peaks and cache groups persist in exports after restarting',async()=>{
 const h=harness('live-cache-parity'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);
 await repo.cacheVehicle({epoch:1780000000,uptime:100,fuelPercent:62.5,currentDistance:12000,currentDuration:900,currentFuel:1250,
  totalDistance:456789,totalDuration:32100,totalFuel:38500,rpm:2500,speed:88,average:8.2,currentStart:1779999100,
  maxSpeed:165,maxRpm:7200,maxAccel:135,maxDecel:-92,fuelSequence:77});
 await repo.cacheDevice(gauge,{firmware_version:'4.0.3',firmware_at:Date.now(),time_at:Date.now(),time_verified:true,gauge_settings_data:'AQIDBA==',gauge_settings_at:Date.now()});
 const reopened=new h.Repository();await reopened.open(h.context);const d=reopened.snapshot().devices[gauge];
 assert.equal(d.vehicle_max_speed,165);assert.equal(d.vehicle_max_rpm,7200);assert.equal(d.vehicle_max_accel,135);assert.equal(d.vehicle_max_decel,-92);
 assert.equal(d.vehicle_fuel_sequence,77);assert.equal(d.last_fuel_sequence,77);assert.equal(d.firmware_version,'4.0.3');assert.equal(d.time_verified,true);
 const exported=await A.decodeArchive(await A.encodeArchive(reopened.snapshot(),runtime),runtime);assert.deepEqual(normalize(exported.devices[gauge]),normalize(d));
 reopened.state.createdAt=1;assert.ok(reopened.snapshot().createdAt>1);
});


test('fresh-phone ZIP migration restores visible home, calibrated mileage and both expense categories across restart and re-export',async()=>{
 const G=require(root+'/core/Garage'),P=require(root+'/core/AndroidPresentation'),H=require(root+'/core/Home'),T=require(root+'/core/Transfer'),K=require(root+'/core/Maintenance');
 const source=await A.decodeArchive(new Uint8Array(fs.readFileSync(root+'/android.zip')),runtime);
 source.preferences['odometer_calibration_m_'+gauge]={type:'long',value:42000000};
 source.preferences['odometer_anchor_trip_'+gauge]={type:'long',value:1};
 source.preferences['show_since_refuel_'+gauge]={type:'boolean',value:true};
 source.tables.trips=[trip()];source.tables.deleted_trips=[];
 source.tables.expenses=[{transfer_id:'11111111111111111111111111111111',device_id:gauge,date_epoch_day:20500,category:1,amount:300,title:'机油',note:'换手机保留',odometer_km:41000,maintenance_type:1},
  {transfer_id:'22222222222222222222222222222222',device_id:'local',date_epoch_day:20500,category:2,amount:25,title:'停车',note:'绑定前费用',odometer_km:null,maintenance_type:null}];
 const h=harness('fresh-migration'),repo=new h.Repository();await repo.open(h.context);
 await repo.importData(await A.decodeArchive(await A.encodeArchive(source,runtime),runtime),false);
 const reopened=new h.Repository();await reopened.open(h.context);
 const s=reopened.snapshot();assert.equal(reopened.binding(),'');assert.equal(reopened.consent(),false);assert.equal(s.gauge,gauge);
 assert.equal(H.homeMetrics(s,s.gauge,reopened.vehicle(),'total')[0],456789);
 assert.equal(G.mileage(s,s.gauge),42000000);
 const costs=P.vehicleRows(s,'expenses','');assert.equal(costs.length,2);assert.equal(costs.filter(r=>r.category===1)[0].amount,300);assert.equal(costs.filter(r=>r.category===2)[0].amount,25);
 assert.ok(K.maintenanceItems(s,s.gauge,20501).find(r=>r.title==='发动机油').detail.includes('41000'));
 assert.equal(P.monthCosts(s,'').find(r=>r.maintenance===300).daily,25);
 const exported=await A.decodeArchive(await A.encodeArchive(s,runtime),runtime);
 assert.equal(exported.gauge,gauge);assert.equal(exported.devices[gauge].vehicle_total_distance,456789);
 const h2=harness('fresh-migration-again'),newPhone=new h2.Repository();await newPhone.open(h2.context);await newPhone.importData(exported,true);
 const cold=new h2.Repository();await cold.open(h2.context);assert.equal(G.mileage(cold.snapshot(),gauge),42000000);assert.equal(cold.vehicle().fuelPercent,62.5);
 await cold.bind('AA:BB:CC:DD:EE:02');assert.equal(cold.vehicle(),null);assert.equal(P.vehicleRows(cold.snapshot(),'expenses',cold.binding()).length,1);
 const invalid=normalize(source);invalid.tables.trips[0].device_id='local';assert.throws(()=>T.validate(invalid));
});
test('legacy package without selected gauge hydrates caches; old local metadata without cache fields recovers on cold start',async()=>{
 const source=await A.decodeArchive(new Uint8Array(fs.readFileSync(root+'/android.zip')),runtime);source.gauge='';
 const h=harness('missing-selection'),repo=new h.Repository();await repo.open(h.context);await repo.importData(source,false);
 assert.equal(repo.snapshot().gauge,gauge);assert.equal(repo.vehicle().totalDistance,456789);
 h.seedMeta({binding:'',archiveGauge:'',consent:false,cursors:{},lastSync:0,backup:'',automatic:false});
 const cold=new h.Repository();await cold.open(h.context);assert.equal(cold.snapshot().gauge,gauge);assert.equal(cold.vehicle().totalDistance,456789);
 // Local live caches may be newer than archive_devices; cold start must not roll them back.
 await cold.cacheVehicle({...cold.vehicle(),totalDistance:987654});
 const latest=new h.Repository();await latest.open(h.context);assert.equal(latest.vehicle().totalDistance,987654);
});
test('local maintenance recorded before binding remains visible in tracking after binding and export',async()=>{
 const E=require(root+'/core/Editor'),K=require(root+'/core/Maintenance'),P=require(root+'/core/AndroidPresentation');
 const h=harness('local-before-binding'),repo=new h.Repository();await repo.open(h.context);
 await repo.edit(s=>E.applyEdit(s,'expenses',null,[{key:'date',value:'2026-10-08'},{key:'category',value:'1'},{key:'amount',value:'300'},{key:'title',value:'机油'},{key:'note',value:''},{key:'odometer',value:'41000'},{key:'maintenance',value:'1'}],'33333333333333333333333333333333',''));
 assert.equal(repo.snapshot().tables.expenses[0].device_id,'local');await repo.bind(gauge);
 assert.equal(P.vehicleRows(repo.snapshot(),'expenses',gauge).length,1);
 assert.ok(K.maintenanceItems(repo.snapshot(),gauge).find(r=>r.title==='发动机油').detail.includes('41000'));
 const zip=await A.decodeArchive(await A.encodeArchive(repo.snapshot(),runtime),runtime);assert.equal(zip.tables.expenses[0].device_id,'local');
});


test('real Android database export -> fresh Harmony repository -> re-export for fresh Android store',
 {skip:!process.env.BRZ_TRANSFER_FIXTURE_DIR || !fs.existsSync(path.join(process.env.BRZ_TRANSFER_FIXTURE_DIR,'android-store.zip'))},async()=>{
 const folder=process.env.BRZ_TRANSFER_FIXTURE_DIR;
 const input=fs.readFileSync(path.join(folder,'android-store.zip'));fs.writeFileSync(path.join(root,'android-store-source.zip'),input);
 const incoming=await A.decodeArchive(new Uint8Array(input),runtime);
 const h=harness('real-android-store'),repo=new h.Repository();await repo.open(h.context);await repo.importData(incoming,false);
 const cold=new h.Repository();await cold.open(h.context);const s=cold.snapshot(),G=require(root+'/core/Garage'),P=require(root+'/core/AndroidPresentation');
 assert.equal(cold.binding(),'');assert.equal(cold.refinedHome(),false);assert.equal(cold.groupedSettings(),false);assert.deepEqual(normalize(s.preferences),normalize(incoming.preferences));assert.equal(cold.vehicle().totalDistance,456789);assert.equal(cold.vehicle().maxDecel,-92);assert.equal(G.mileage(s,s.gauge),42000000);
 const costs=P.vehicleRows(s,'expenses',s.gauge);assert.equal(costs.length,2);assert.equal(costs.find(r=>r.category===1).amount,300);assert.equal(costs.find(r=>r.category===2).amount,25);
 assert.equal(s.tables.custom.length,55);assert.equal(cold.refuelLive().distance,12345);
 fs.writeFileSync(path.join(root,'harmony-store.zip'),await A.encodeArchive(s,runtime));
});

const H=require('../test-output/core/RefuelHistory'),C=require('../test-output/core/CustomTrip'),I=require('../test-output/core/IntervalSync'),P=require('../test-output/core/Protocol');
function fuelRow(id){return {device_id:gauge,interval_id:id,start_epoch_s:id*100,end_epoch_s:(id+1)*100,duration_s:100,distance_m:1000,fuel_ml:100,odometer_x10_km:id*10,detected_added_ml:10000,flags:1,data_revised:0};}
function fuelWindow(rows,revision=0){return {rows,meta:{version:2,count:rows.length,oldest:rows[0]?.interval_id||0,newest:rows.at(-1)?.interval_id||0,revision,start:3000,duration:10,distance:10,fuel:1}};}
const vehicle={epoch:1780000000,uptime:10,fuelPercent:null,currentDistance:0,currentDuration:0,currentFuel:0,totalDistance:50000,totalDuration:1000,totalFuel:4000,rpm:null,speed:null,average:8,currentStart:0};
test('rolling refuel window retains 25 archives; phone deletion survives repeated gauge sync exactly once',async()=>{
 const h=harness('window25'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);
 const all=Array.from({length:25},(_,i)=>fuelRow(i+1));await repo.edit(s=>s.tables.refuel=all);
 let window=fuelWindow(all.slice(5));await repo.commitRefuel(gauge,window.rows,window);
 await repo.deleteArchivedRefuel(gauge,5,false);
 assert.equal(repo.refuelRows(gauge).find(r=>r.interval_id===6).distance_m,2000);
 await repo.commitRefuel(gauge,window.rows,window);await repo.commitRefuel(gauge,window.rows,window);
 assert.equal(repo.refuelRows(gauge).length,24);assert.equal(repo.refuelRows(gauge).find(r=>r.interval_id===6).distance_m,2000);
 // Another phone deletes node 6; gauge folds only its raw contribution into 7.
 const merged={...fuelRow(7),start_epoch_s:600,distance_m:2000,duration_s:200,fuel_ml:200};
 window=fuelWindow([merged,...all.slice(7)],1);await repo.commitRefuel(gauge,window.rows,window);await repo.commitRefuel(gauge,window.rows,window);
 assert.equal(repo.refuelRows(gauge).find(r=>r.interval_id===7).distance_m,3000);
 assert.equal(repo.refuelRows(gauge).length,23);
 const reopened=new h.Repository();await reopened.open(h.context);assert.equal(reopened.refuelRows(gauge).find(r=>r.interval_id===7).distance_m,3000);
});
test('refuel transaction rollback and unresolved latest correction preserve both raw and visible history',async()=>{
 const h=harness('refuel-rollback'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);
 const w=fuelWindow([fuelRow(1),fuelRow(2)]);await repo.commitRefuel(gauge,w.rows,w);
 h.inject();await assert.rejects(repo.commitRefuel(gauge,[fuelRow(3)],fuelWindow([fuelRow(3)])));h.reset();
 assert.equal(repo.refuelRows(gauge).length,2);
 await repo.edit(s=>{s.tables.refuel[1].distance_m=1200;s.tables.refuel[1].data_revised=1;});
 await assert.rejects(repo.commitRefuel(gauge,[fuelRow(1)],fuelWindow([fuelRow(1)],1)),/后续区间/);
 const merged={...fuelRow(3),start_epoch_s:200,distance_m:2000,duration_s:200,fuel_ml:200};
 const after=fuelWindow([fuelRow(1),merged],1);await repo.commitRefuel(gauge,after.rows,after);
 assert.equal(repo.refuelRows(gauge).find(r=>r.interval_id===3).distance_m,2200);
});
test('custom reset transaction persists history, baseline and intent together and rejects stale dialog',async()=>{
 const h=harness('custom-atomic'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);await repo.cacheVehicle(vehicle);await repo.ensureCustom(gauge,vehicle);
 const old=repo.customRequest(gauge),v={...vehicle,epoch:vehicle.epoch+120,totalDistance:51000,totalDuration:1120,totalFuel:4100};
 h.inject();await assert.rejects(repo.archiveCustom(gauge,old.generation,v,'旅途','11111111111141118111111111111111',Date.now()));h.reset();
 assert.equal(repo.snapshot().tables.custom.length,0);assert.equal(repo.customGeneration(gauge),old.generation);
 await repo.archiveCustom(gauge,old.generation,v,'旅途','22222222222242228222222222222222',Date.now());
 await assert.rejects(repo.archiveCustom(gauge,old.generation,v,'旅途','33333333333343338333333333333333',Date.now()),/不会重复归档/);
 const reopened=new h.Repository();await reopened.open(h.context);
 assert.equal(reopened.snapshot().tables.custom.length,1);assert.equal(reopened.snapshot().tables.custom[0].end_epoch_s,v.epoch);
 assert.equal(reopened.customRequest(gauge).values[0],51000);assert.equal(reopened.customRequest(gauge).pending,true);
 await reopened.updateCustom(gauge,old,{token:old.token,values:old.values,durable:true},'confirmed');assert.equal(reopened.customRequest(gauge).pending,true);
 const current=reopened.customRequest(gauge);await reopened.updateCustom(gauge,current,{token:current.token,values:[50900,1110,4090],durable:true},'confirmed');
 assert.equal(reopened.customStatus(gauge),'confirmed');assert.equal(reopened.snapshot().preferences['custom_base_distance_'+gauge].value,50900);
 const exported=reopened.snapshot();await reopened.importData(exported,true);
 assert.notEqual(reopened.customGeneration(gauge),current.generation);assert.equal(reopened.customRequest(gauge).pending,true);
 assert.equal(Object.keys(exported.preferences).some(k=>k.includes('generation')||k.includes('token')),false);
});
test('custom v2 receipt validates CRC, token and persistence; legacy write remains pending',async()=>{
 const h=harness('custom-wire'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);await repo.ensureCustom(gauge,vehicle);
 const request=repo.customRequest(gauge),bytes=C.customConfirmedPacket(request);
 assert.equal(bytes.length,20);assert.equal(new DataView(bytes.buffer).getUint32(14,true),request.token);
 assert.throws(()=>C.parseCustomReceipt(bytes.slice(1)));
 const broken=bytes.slice();broken[2]^=1;assert.throws(()=>C.parseCustomReceipt(broken));
 let reads=0;const t={has:()=>true,readable:()=>true,write:async(id,b)=>assert.equal(b[0],2),read:async()=>{const b=bytes.slice();b[1]=++reads<2?0:1;new DataView(b.buffer).setUint16(18,P.crc16(b,18),true);return b;}};
 await I.syncCustom(gauge,t,repo);assert.equal(reads,2);assert.equal(repo.customStatus(gauge),'confirmed');
 await I.syncCustom(gauge,{...t,readable:()=>false,write:async(id,b)=>assert.equal(b[0],1)},repo);
 assert.equal(repo.customStatus(gauge),'legacy_sent');assert.equal(repo.customRequest(gauge).pending,true);
});
function metaBytes(w){const b=new Uint8Array(36),d=new DataView(b.buffer);b[0]=2;b[1]=w.meta.count;d.setUint16(2,36,true);d.setUint32(4,w.meta.oldest,true);d.setUint32(8,w.meta.newest,true);d.setUint16(32,w.meta.revision,true);d.setUint16(34,P.crc16(b,34),true);return b;}
function rowBytes(r){const b=new Uint8Array(48),d=new DataView(b.buffer);b[0]=1;b[1]=1;d.setUint16(2,48,true);d.setUint32(4,r.interval_id,true);d.setUint32(8,r.start_epoch_s,true);d.setUint32(16,r.end_epoch_s,true);d.setUint32(24,r.duration_s,true);d.setUint32(28,r.distance_m,true);d.setUint32(32,r.fuel_ml,true);d.setUint16(46,P.crc16(b,46),true);return b;}
test('full refuel read rejects partial records and changed bounds before committing',async()=>{
 const w=fuelWindow([fuelRow(1),fuelRow(2)]);let meta=0,record=0;
 const t={has:()=>true,readable:()=>true,write:async()=>{},read:async(id)=>id===11?metaBytes(++meta===1?w:{...w,meta:{...w.meta,oldest:2}}):rowBytes(w.rows[record++])};
 await assert.rejects(I.readRefuelWindow(gauge,t),/发生变化/);
 meta=0;record=0;await assert.rejects(I.readRefuelWindow(gauge,{...t,read:async(id)=>id===11?metaBytes(w):rowBytes(w.rows[0])}),/游标/);
});
test('deleting an archived refuel node sends no destructive command to gauge',async()=>{
 const h=harness('archive-command'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);
 await repo.edit(s=>s.tables.refuel=[fuelRow(1),fuelRow(2)]);const w=fuelWindow([fuelRow(2)]),writes=[];
 const t={has:()=>true,readable:()=>true,write:async(id,b)=>writes.push(b[1]),read:async(id)=>id===11?metaBytes(w):rowBytes(w.rows[0])};
 await I.mutateRefuel(gauge,t,repo,3,1);assert.ok(writes.every(c=>c===1));assert.equal(repo.refuelRows(gauge).length,1);assert.equal(repo.refuelRows(gauge)[0].distance_m,2000);
});

test('two repository instances reload committed changes instead of overwriting each other',async()=>{
 const h=harness('two-instances'),front=new h.Repository(),back=new h.Repository();await front.open(h.context);await front.bind(gauge);await back.open(h.context);
 await back.commitTrips(gauge,[trip(1)],1);await front.edit(s=>s.preferences.vehicle_display_name={type:'string',value:'保留后台行程'});
 await back.reload();assert.equal(back.snapshot().tables.trips.length,1);assert.equal(back.snapshot().preferences.vehicle_display_name.value,'保留后台行程');
 await back.ensureCustom(gauge,vehicle);await front.reload();const before=front.customRequest(gauge);
 await back.archiveCustom(gauge,before.generation,{...vehicle,totalDistance:51000},'后台','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',Date.now());
 await assert.rejects(front.archiveCustom(gauge,before.generation,vehicle,'前台','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',Date.now()),/不会重复归档/);
 await front.reload();assert.equal(front.snapshot().tables.custom.length,1);
});
test('custom pending receipt stays pending for wrong token and retries after NVS failure',async()=>{
 const h=harness('wrong-receipt'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);await repo.ensureCustom(gauge,vehicle);
 const request=repo.customRequest(gauge);let durable=false;
 const t={has:()=>true,readable:()=>true,write:async()=>{},read:async()=>{const b=C.customConfirmedPacket(request);b[1]=durable?1:0;new DataView(b.buffer).setUint16(18,P.crc16(b,18),true);return b;}};
 assert.ok((await I.syncCustom(gauge,t,repo)).includes('等待'));assert.equal(repo.customRequest(gauge).pending,true);
 durable=true;await I.syncCustom(gauge,t,repo);assert.equal(repo.customRequest(gauge).pending,false);
});
test('pending refuel deletion survives restart and requires missing target plus changed revision',async()=>{
 const h=harness('refuel-receipt'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);const before=fuelWindow([fuelRow(1),fuelRow(2)]);
 await repo.commitRefuel(gauge,before.rows,before);await repo.queueRefuel(gauge,{id:1,discard:false,revision:0});
 const restarted=new h.Repository();await restarted.open(h.context);
 const unrelated=fuelWindow(before.rows,1);await restarted.commitRefuel(gauge,unrelated.rows,unrelated);assert.equal(restarted.pendingRefuel(gauge).id,1);
 const merged={...fuelRow(2),start_epoch_s:100,distance_m:2000,duration_s:200,fuel_ml:200},after=fuelWindow([merged],2);
 await restarted.commitRefuel(gauge,after.rows,after);assert.equal(restarted.pendingRefuel(gauge),null);assert.equal(restarted.refuelRows(gauge)[0].distance_m,2000);
});


test('offline queued archive deletion is not mistaken for a confirmed gauge mutation',async()=>{
 const h=harness('offline-refuel'),repo=new h.Repository();await repo.open(h.context);await repo.bind(gauge);
 await repo.edit(s=>s.tables.refuel=[fuelRow(1),fuelRow(2)]);
 await repo.queueRefuel(gauge,{id:1,discard:false,revision:-1,issued:false});
 const w=fuelWindow([fuelRow(2)],5);await repo.commitRefuel(gauge,w.rows,w);
 assert.equal(repo.refuelRows(gauge).length,2);assert.equal(repo.pendingRefuel(gauge).issued,false);
 const restarted=new h.Repository();await restarted.open(h.context);const writes=[];
 const t={has:()=>true,readable:()=>true,write:async(id,b)=>writes.push(b[1]),read:async(id)=>id===11?metaBytes(w):rowBytes(w.rows[0])};
 await I.mutateRefuel(gauge,t,restarted,3,1);
 assert.equal(restarted.refuelRows(gauge)[0].distance_m,2000);assert.equal(restarted.pendingRefuel(gauge),null);assert.ok(writes.every(c=>c===1));
});
