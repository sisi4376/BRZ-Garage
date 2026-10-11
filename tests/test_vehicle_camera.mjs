import test from 'node:test';
import assert from 'node:assert/strict';
import { VehicleCamera } from '../preview/vehicle-3d/vehicle-camera.mjs';

for (const embedded of [true,false]) {
  test(`orbit stays centered, including after a plate closeup (embedded=${embedded})`,()=>{
    const camera=new VehicleCamera(embedded);
    camera.setCenter([.03,.67,-.02]);
    for(const preset of ['front','rear','plate','rearPlate']){
      camera.preset(preset); camera.orbit(75,10);
      assert.deepEqual(camera.target,camera.center);
      const position=camera.position(2);
      assert.ok(Math.abs(Math.hypot(...position.map((v,i)=>v-camera.center[i]))-camera.distance)<1e-9);
    }
  });
  test(`page hide resets view and resuming preserves the default (embedded=${embedded})`,()=>{
    const camera=new VehicleCamera(embedded);camera.setCenter([0,.7,0]);
    const initial=camera.position(1.8);
    camera.preset('rearPlate');camera.zoom(.5);camera.orbit(50,20);
    camera.setActive(false);assert.equal(camera.active,false);
    camera.setActive(true);assert.deepEqual(camera.position(1.8),initial);
    assert.equal(camera.mode,'front');assert.deepEqual(camera.center,[0,.7,0]);
  });
}
test('narrow views keep the full car in frame and zoom is bounded',()=>{
  const camera=new VehicleCamera(true);
  assert.ok(Math.hypot(...camera.position(.8).map((v,i)=>v-camera.target[i]))>camera.distance);
  camera.zoom(.0001);assert.equal(camera.distance,3.4);
  camera.zoom(10000);assert.equal(camera.distance,12);
  camera.preset('rearPlate');camera.zoom(.0001);assert.equal(camera.distance,1.5);
});
