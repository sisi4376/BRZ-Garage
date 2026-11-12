const {test}=require('node:test');
const assert=require('node:assert/strict');
const Q=require('../test-output/core/Gauge');
const P=require('../test-output/core/Protocol');
function snapshot(version,page=6,rotation=0) {
 const b=new Uint8Array(102+2*version),d=new DataView(b.buffer);
 b[0]=version;b[1]=7;b[6]=page;b[7]=75;b[8]=4;d.setUint16(2,b.length,true);
 if(version>=2)d.setUint16(102,10000,true);
 if(version===3)b[104]=rotation;
 d.setUint16(b.length-2,P.crc16(b,b.length-2),true);return b;
}
test('gauge boot settings v3: every page and orientation with preserved legacy fields',()=>{
 for(let page=0;page<=8;page++)for(let orientation=0;orientation<=3;orientation++) {
  const s=Q.parseSettings(snapshot(3,page,orientation));
  assert.equal(s.defaultPage,page);assert.equal(s.defaultOrientation,orientation);
  assert.equal(s.brightness,75);assert.equal(s.threshold,10000);assert.equal(s.profile,4);
  for(const [command,value] of [[2,page],[3,orientation]]) {
   const b=Q.bootSettingPacket(command,value);
   assert.deepEqual([...b.slice(0,4)],[1,command,value,6]);
   assert.equal(new DataView(b.buffer).getUint16(4,true),P.crc16(b,4));
  }
 }
});
test('old firmware has no writable boot capability and malformed snapshots are rejected',()=>{
 for(const version of [1,2]) {
  const s=Q.parseSettings(snapshot(version));assert.equal(s.defaultOrientation,null);
  assert.equal(s.defaultPage,6);assert.equal(s.threshold,version===1?null:10000);
 }
 for(const [command,value] of [[1,0],[2,9],[2,-1],[3,4],[3,1.5],[3,NaN]])assert.throws(()=>Q.bootSettingPacket(command,value));
 for(const bytes of [snapshot(3,9,0),snapshot(3,0,4),snapshot(4)])assert.throws(()=>Q.parseSettings(bytes));
 const broken=snapshot(3);broken[104]^=1;assert.throws(()=>Q.parseSettings(broken));
 const reserved=snapshot(3);reserved[105]=1;new DataView(reserved.buffer).setUint16(106,P.crc16(reserved,106),true);assert.throws(()=>Q.parseSettings(reserved));
});
