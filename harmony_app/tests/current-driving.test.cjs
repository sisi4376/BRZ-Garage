const {test}=require('node:test'),assert=require('node:assert/strict');
const C=require('../test-output/core/CurrentDriving'),P=require('../test-output/core/AndroidPresentation');
const gauge='AA:BB:CC:DD:EE:01';
const start=new Date(2026,9,20,23,55).getTime()/1000;
const v={epoch:start+600,uptime:3600,currentStart:start,currentDistance:1000,currentDuration:600,currentFuel:100,
 totalDistance:10000,totalDuration:6000,totalFuel:1000,fuelPercent:50,rpm:800,speed:0,average:10};
test('current driving and calendar update one display row without altering archived data',()=>{
 const old={...C.currentDrivingRecord(gauge,v),trip_id:1,start_epoch_s:start-86400,end_epoch_s:start-85800};
 const original=JSON.stringify(old), first=C.drivingHistory([old],gauge,v);
 assert.equal(first.length,2);assert.equal(first[0].trip_id,0);
 const changed={...v,epoch:start+800,currentDistance:2500,currentDuration:800,currentFuel:200};
 const rows=C.drivingHistory([old],gauge,changed);
 assert.equal(rows.length,2);assert.equal(rows[0].distance_m,2500);assert.equal(JSON.stringify(old),original);
 for(let i=0;i<3;i++)assert.deepEqual(C.drivingHistory([old],gauge,changed),rows);
 const day=P.calendar(rows,2026).flatMap(w=>w.cells).find(c=>c.date==='2026-10-20');
 assert.ok(day.detail.includes('1 次 · 2.5 km'));assert.ok(day.detail.includes('00:13:20'));assert.ok(day.detail.includes('0.20 L'));
 assert.equal(P.summary(rows).distance,3500);
});
test('archive replaces cached current trip; a new trip and other devices stay independent',()=>{
 const current=C.currentDrivingRecord(gauge,v), archived={...current,trip_id:2,end_epoch_s:start+900};
 assert.deepEqual(C.drivingHistory([archived],gauge,v),[archived]);
 assert.equal(C.drivingHistory([{...archived,device_id:'other'}],gauge,v).length,2);
 assert.equal(C.drivingHistory([archived],gauge,{...v,currentStart:start+1800,epoch:start+2400}).length,2);
 assert.deepEqual(C.drivingHistory([],gauge,v,true),[]);
 assert.deepEqual(C.drivingHistory([],gauge,null),[]);
 assert.deepEqual(C.drivingHistory([],gauge,{...v,currentDistance:0,currentDuration:0,currentFuel:0}),[]);
 const unknown=C.drivingHistory([],gauge,{...v,currentStart:0,epoch:0});
 assert.equal(unknown.length,1);assert.equal(C.validTripTime(unknown[0]),false);
 assert.ok(!P.calendar(unknown,2026).flatMap(w=>w.cells).some(c=>c.detail.includes('1 次')));
});
