const {test}=require('node:test');
const assert=require('node:assert/strict');
const P=require('../test-output/core/AndroidPresentation');
const E=require('../test-output/core/Editor');
const G=require('../test-output/core/Garage');
const D=require('../test-output/core/Maintenance');
const M=require('../test-output/core/Models');
const gauge='AA:BB:CC:DD:EE:01';
test('Android accounting includes draft money but excludes draft fuel from consumption',()=>{
 const s=M.emptySnapshot(gauge);
 for(const [id,odo,litres,cost] of [['a',1000,'40','300'],['b',1200,'','100']]){
  const values=E.fields('fuel',null).map(f=>({...f,value:({date:'2026-01-01',odometer:String(odo),litres,cost}[f.key]??f.value)}));E.applyEdit(s,'fuel',null,values,id.repeat(32),gauge);
 }
 assert.equal(s.tables.fuel[1].draft,1);assert.equal(P.monthCosts(s,gauge)[0].total,400);assert.equal(G.fuelAverage(s.tables.fuel),null);
});
test('Android calendar uses local start day, ignores invalid time and retains unknown records in totals',()=>{
 const s=M.emptySnapshot(gauge),start=Math.floor(new Date(2026,0,1,23,30).getTime()/1000);
 s.tables.trips=[{device_id:gauge,trip_id:1,start_epoch_s:start,end_epoch_s:start+3600,flags:1,distance_m:30000,duration_s:3600,fuel_ml:3000},{device_id:gauge,trip_id:2,start_epoch_s:0,end_epoch_s:0,flags:0,distance_m:2000,duration_s:60,fuel_ml:100}];
 const weeks=P.calendar(s.tables.trips,2026),cells=weeks.flatMap(w=>w.cells);assert.equal(cells.filter(c=>c.date==='2026-01-01').length,1);assert.equal(cells.find(c=>c.date==='2026-01-01').color,'#8AD2E5');assert.equal(P.summary(s.tables.trips).distance,32000);assert.equal(P.summary(s.tables.trips).days,1);
});
test('Android daily classification is exact with the documented legacy accessory alias',()=>{
 assert.equal(P.dailyKind('车辆配件'),4);assert.equal(P.dailyKind('停车'),0);assert.equal(P.dailyKind('停车相关自定义名称'),8);
});
test('vehicle-scoped lists do not mix expense or trip rows from other gauges',()=>{
 const s=M.emptySnapshot(gauge);s.tables.expenses=[{device_id:gauge,date_epoch_day:1},{device_id:'AA:BB:CC:DD:EE:02',date_epoch_day:2}];assert.equal(P.vehicleRows(s,'expenses',gauge).length,1);assert.equal(P.vehicleRows(s,'expenses','').length,1);s.gauge='';assert.equal(P.vehicleRows(s,'expenses','').length,2);
});
test('Android maintenance recommends milestone packages and hides mileage deadlines when display is off',()=>{
 assert.ok(D.recommended(10000).includes('执行轮胎换位'));assert.equal(D.resolvedMaintenance({maintenance_type:null,title:'更换轮胎'}),3);assert.equal(D.resolvedMaintenance({maintenance_type:null,title:'修复异响'}),4);
 const s=M.emptySnapshot(gauge);G.calibrate(s,gauge,5000000);let oil=D.maintenanceItems(s,gauge).find(x=>x.title==='发动机油');assert.equal(oil.badge,'首次已到');G.putPref(s,'odometer_display_'+gauge,false);oil=D.maintenanceItems(s,gauge).find(x=>x.title==='发动机油');assert.equal(oil.badge,'未跟踪');
});
