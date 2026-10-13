const {test}=require('node:test'),assert=require('node:assert/strict');
const S=require('../test-output/core/Sync'),P=require('../test-output/core/Protocol');
test('legacy empty acknowledged queue is not mistaken for a reset',async()=>{
 const meta=new Uint8Array(20),view=new DataView(meta.buffer);meta[0]=2;
 view.setUint16(16,64,true);view.setUint16(18,48,true);view.setUint32(12,7,true);
 const writes=[];
 const t={has:()=>false,readable:()=>false,read:async()=>meta,write:async(id)=>writes.push(id)};
 let cursor=0;
 await S.synchronize('AA:BB:CC:DD:EE:01',t,{cursor:()=>7,commitTrips:async(g,r,c)=>{cursor=c;assert.equal(r.length,0);}},()=>{});
 assert.equal(cursor,7);assert.deepEqual(writes,[2]);
});
test('rolling history reset is rejected before altering local cursor',async()=>{
 const meta=new Uint8Array(20),view=new DataView(meta.buffer);meta[0]=4;
 view.setUint16(16,64,true);view.setUint16(18,56,true);
 await assert.rejects(S.synchronize('AA:BB:CC:DD:EE:01',{has:()=>false,readable:()=>false,read:async()=>meta,write:async()=>{}},
 {cursor:()=>8,commitTrips:async()=>assert.fail('must not commit')},()=>{}));
});
