const {test}=require('node:test'),assert=require('node:assert/strict');
const M=require('../test-output/core/Models'),G=require('../test-output/core/Garage');
const P=require('../test-output/core/AndroidPresentation'),T=require('../test-output/core/Transfer');
const D=require('../test-output/core/Maintenance'),Protocol=require('../test-output/core/Protocol');
const fs=require('node:fs'),crypto=require('node:crypto'),zlib=require('node:zlib');
const A=require('../test-output/core/Archive');
const gauge='AA:BB:CC:DD:EE:01';
test('trip occurrence order matches Android for unknown and revised clocks and split children',()=>{
 const s=M.emptySnapshot(gauge);
 s.tables.trips=[{device_id:gauge,trip_id:1,end_epoch_s:2000000000},
 {device_id:gauge,trip_id:3,end_epoch_s:0},{device_id:gauge,trip_id:-5,end_epoch_s:1800000000},
 {device_id:gauge,trip_id:-6,end_epoch_s:1800000001}];
 assert.deepEqual(P.vehicleRows(s,'trips',gauge).map(r=>r.trip_id),[3,-6,-5,1]);
});
test('null calibration in an Android export means uncalibrated history, including trips before an old anchor',()=>{
 const s=M.emptySnapshot(gauge);s.tables.trips=[{device_id:gauge,trip_id:1,distance_m:1000},{device_id:gauge,trip_id:2,distance_m:2000}];
 G.putPref(s,'odometer_calibration_m_'+gauge,null);G.putPref(s,'odometer_anchor_trip_'+gauge,99);
 assert.equal(G.mileage(s,gauge),3000);
 G.putPref(s,'odometer_calibration_m_'+gauge,0);assert.equal(G.mileage(s,gauge),0);
});
test('legacy maintenance headings resolve identically to Android',()=>{
 ['A保','B保','轮胎更换','其他维修'].forEach((title,i)=>assert.equal(D.resolvedMaintenance({title:' '+title+' ',maintenance_type:null}),i+1));
});
test('equal-time cache conflicts are counted and respect the same strategy as Android',async()=>{
 const runtime={sha256:async b=>crypto.createHash('sha256').update(b).digest('hex'),inflateRaw:async b=>zlib.inflateRawSync(b)};
 const a=await A.decodeArchive(new Uint8Array(fs.readFileSync(__dirname+'/../test-output/android.zip')),runtime),b=M.cloneSnapshot(a);
 b.devices[gauge].firmware_version='edited';
 const keep=T.merge(a,b,false),take=T.merge(a,b,true);
 assert.equal(keep.conflicts,1);assert.equal(take.conflicts,1);
 assert.equal(keep.snapshot.devices[gauge].firmware_version,a.devices[gauge].firmware_version);
 assert.equal(take.snapshot.devices[gauge].firmware_version,'edited');
});
test('live v2 vehicle snapshot preserves peaks and sample sequence for Android roundtrip',()=>{
 const bytes=new Uint8Array(84),v=new DataView(bytes.buffer);
 bytes[0]=2;bytes[1]=11;v.setUint16(2,84,true);v.setUint16(16,6250,true);v.setUint16(18,820,true);
 v.setUint16(60,165,true);v.setUint16(62,7200,true);v.setInt16(64,135,true);v.setInt16(66,-92,true);v.setUint32(76,77,true);
 v.setUint16(82,Protocol.crc16(bytes,82),true);
 const result=Protocol.parseVehicle(bytes);
 assert.equal(result.maxSpeed,165);assert.equal(result.maxRpm,7200);assert.equal(result.maxAccel,135);assert.equal(result.maxDecel,-92);assert.equal(result.fuelSequence,77);
});
