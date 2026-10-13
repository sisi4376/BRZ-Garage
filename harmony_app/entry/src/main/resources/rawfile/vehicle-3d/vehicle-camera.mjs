// Camera state shared by the viewer and host tests. All free orbit targets the vehicle center.
export class VehicleCamera {
  constructor(embedded=false){
    this.embedded=embedded;this.center=[0,.64,0];this.active=true;this.reset();
  }
  setCenter(center){this.center=[...center];this.reset();}
  preset(name){
    const presets={
      front:[-.88,.12,this.embedded?5.35:8.0],
      side:[-Math.PI/2,.09,this.embedded?6.2:8.0],
      rear:[-2.3,.13,this.embedded?5.35:8.0],
      plate:[-.10,.06,this.embedded?2.0:2.65],
      rearPlate:[Math.PI+.10,.06,this.embedded?2.0:2.65]
    };
    if(!presets[name])return;
    [this.yaw,this.pitch,this.distance]=presets[name];this.mode=name;
    this.target=[...this.center];
    if(name==='plate')this.target=[0,.45,1.2];
    if(name==='rearPlate')this.target=[0,this.rearPlateY??.61,-1.2];
  }
  reset(){this.preset('front');}
  orbit(dx,dy){
    if(this.mode==='plate'||this.mode==='rearPlate')this.distance=this.embedded?5.35:8.0;
    this.target=[...this.center];this.mode='orbit';
    this.yaw-=dx*.008;this.pitch=Math.max(.02,Math.min(.72,this.pitch+dy*.006));
  }
  zoom(factor){this.distance=Math.max(this.isDetail?1.5:3.4,Math.min(12,this.distance*factor));}
  get isDetail(){return this.mode==='plate'||this.mode==='rearPlate';}
  setActive(value){this.active=!!value;if(!this.active)this.reset();}
  position(aspect){
    const d=this.distance*(this.isDetail?1:Math.max(1,1.28/aspect));
    return [this.target[0]+Math.sin(this.yaw)*Math.cos(this.pitch)*d,
      this.target[1]+Math.sin(this.pitch)*d,this.target[2]+Math.cos(this.yaw)*Math.cos(this.pitch)*d];
  }
}
