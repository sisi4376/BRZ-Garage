/* Downloaded BRZ models: sources and CC-BY credits are in models/. +Z front, +Y up. */
import * as T from './vendor/three.module.js';
import { GLTFLoader } from './vendor/GLTFLoader.js';
import { VehicleCamera } from './vehicle-camera.mjs';
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const viewport = $('viewport');
  const loading = $('loading');
  const embedded=document.documentElement.dataset.embedded==='true';
  const host=embedded?window.BrzHost:null;
  let nativePlateUrl='',plateImageSerial=0,active=true;
  const fail=()=>host?.failed();
  let frameDirty=true,lastView='';
  document.addEventListener('input',()=>{frameDirty=true;});
  document.addEventListener('change',()=>{frameDirty=true;});
  document.addEventListener('click',()=>{frameDirty=true;});

  let renderer;
  try { renderer = new T.WebGLRenderer({ antialias: true, alpha: true }); }
  catch (_) { loading.textContent = '当前浏览器无法启用 WebGL，请使用支持硬件加速的浏览器。'; fail(); return; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, embedded?1.5:2));
  // The studio uses a soft contact shadow; low-resolution directional shadows made a hard slab.
  renderer.shadowMap.enabled = false;
  renderer.shadowMap.type = T.PCFSoftShadowMap;
  renderer.outputColorSpace = T.SRGBColorSpace;
  // Backport Three.js NeutralToneMapping (MIT; vendor/THREE-LICENSE.txt) to r160.
  // https://github.com/mrdoob/three.js/blob/dev/src/renderers/shaders/ShaderChunk/tonemapping_pars_fragment.glsl.js
  T.ShaderChunk.tonemapping_pars_fragment=T.ShaderChunk.tonemapping_pars_fragment.replace(
    'vec3 CustomToneMapping( vec3 color ) { return color; }',`vec3 CustomToneMapping(vec3 color) {
      color *= toneMappingExposure;
      float x = min(color.r, min(color.g, color.b));
      color -= x < 0.08 ? x - 6.25 * x * x : 0.04;
      float peak = max(color.r, max(color.g, color.b));
      if (peak < 0.76) return color;
      float newPeak = 1.0 - 0.24 * 0.24 / (peak + 0.24 - 0.76);
      color *= newPeak / peak;
      float g = 1.0 - 1.0 / (0.15 * (peak - newPeak) + 1.0);
      return mix(color, vec3(newPeak), g);
    }`);
  renderer.toneMapping = T.CustomToneMapping;
  renderer.toneMappingExposure = 1.15;
  renderer.shadowMap.autoUpdate = false;
  viewport.appendChild(renderer.domElement);
  const scene = new T.Scene();
  const camera = new T.PerspectiveCamera(30, 1, .05, 80);
  scene.add(new T.HemisphereLight(0xffffff, 0x3a3a3a, .22));
  function light(color, power, x, y, z) {
    const lamp = new T.DirectionalLight(color, power); lamp.position.set(x,y,z); scene.add(lamp); return lamp;
  }
  const key = light(0xffffff, .65, -3, 7, 5);

  // Neutral softboxes supply both diffuse illumination and coherent paint reflections.
  // Broad sources avoid the isolated white hotspots from multiple directional lights.
  const studio = new T.Scene(); studio.background = new T.Color('#626262');
  for (const [x,y,z,w,h,power] of [[-3,6,2,7,3,2.2],[-6,2,0,7,3,2],[5,3,-4,5,4,2.0],[0,1,7,7,2,.7]]) {
    const panel = new T.Mesh(new T.PlaneGeometry(w,h),new T.MeshBasicMaterial({color:new T.Color(power,power,power),side:T.DoubleSide}));
    panel.position.set(x,y,z); panel.lookAt(0,0,0); studio.add(panel);
  }
  const pmrem = new T.PMREMGenerator(renderer);
  const environment = pmrem.fromScene(studio,.08); scene.environment = environment.texture;
  studio.traverse(o=>{o.geometry?.dispose();o.material?.dispose();}); pmrem.dispose();

  const paint = new T.MeshPhysicalMaterial({color:'#155dcc',metalness:.48,roughness:.32,clearcoat:1,clearcoatRoughness:.14,envMapIntensity:1.0});
  const glass = new T.MeshPhysicalMaterial({color:0x142332,metalness:.45,roughness:.13,clearcoat:1});
  const rubber = new T.MeshStandardMaterial({color:0x101419,roughness:.9});
  const black = new T.MeshStandardMaterial({color:0x151d27,roughness:.55});
  const alloy = new T.MeshStandardMaterial({color:0x7c8799,metalness:.95,roughness:.26});
  const chrome = new T.MeshStandardMaterial({color:0xc8d4e3,metalness:.9,roughness:.2});
  const red = new T.MeshStandardMaterial({color:0xbb152b,roughness:.25,metalness:.3});
  const whiteLight = new T.MeshStandardMaterial({color:0xe5f3ff,emissive:0xc6e5ff,emissiveIntensity:1.5});
  const redLight = new T.MeshStandardMaterial({color:0xbb1730,emissive:0xff1028,emissiveIntensity:.6});
  const car = new T.Group(); car.name='vehicle'; scene.add(car);
  function mesh(geometry, material, parent=car) {
    const result = new T.Mesh(geometry,material);result.castShadow=true;result.receiveShadow=true;parent.add(result);return result;
  }
  function box(w,h,d,x,y,z,mat,parent=car) {
    const obj=mesh(new T.BoxGeometry(w,h,d),mat,parent);obj.position.set(x,y,z);return obj;
  }
  function surface(rows, material, parent=car) {
    const vertices=[], indices=[];
    rows.forEach(row=>row.forEach(p=>vertices.push(...p)));
    const n=rows[0].length;
    for(let a=0;a<rows.length-1;a++)for(let b=0;b<n-1;b++) {
      const i=a*n+b;indices.push(i,i+n,i+1,i+1,i+n,i+n+1);
    }
    const g=new T.BufferGeometry();g.setAttribute('position',new T.Float32BufferAttribute(vertices,3));g.setIndex(indices);g.computeVertexNormals();
    return mesh(g,material,parent);
  }
  // Materials are double-sided where thin exterior body shells meet their cabin panels.
  paint.side=T.DoubleSide; glass.side=T.DoubleSide;
  function line(points,material=black,radius=.006,parent=car) {
    const curve=new T.CatmullRomCurve3(points.map(p=>new T.Vector3(...p)));
    return mesh(new T.TubeGeometry(curve,Math.max(12,points.length*8),radius,6,false),material,parent);
  }
  const plates=[];
  const plateMount={width:.465,height:.154,depth:.014,clearance:.0025};
  const plateCanvas=document.createElement('canvas');
  plateCanvas.width=Math.min(2640,renderer.capabilities.maxTextureSize);
  plateCanvas.height=Math.round(plateCanvas.width*140/440);
  const ctx=plateCanvas.getContext('2d');
  // Keep text layout in logical units while uploading the full-resolution texture.
  ctx.scale(plateCanvas.width/1024,plateCanvas.height/320);
  const plateTexture=new T.CanvasTexture(plateCanvas);plateTexture.colorSpace=T.SRGBColorSpace;
  plateTexture.anisotropy=renderer.capabilities.getMaxAnisotropy();
  const plateFace=new T.MeshStandardMaterial({map:plateTexture,roughness:.38,metalness:.15});
  for(const front of [true,false]) {
    const mount=new T.Group();mount.name=front?'front-plate-mount':'rear-plate-mount';
    mount.position.set(0,front?.535:.635,front?2.205:-2.192);mount.rotation.y=front?0:Math.PI;car.add(mount);
    box(plateMount.width,plateMount.height,plateMount.depth,0,0,0,black,mount);
    box(.455,.143,.003,0,0,.0085,chrome,mount);
    const face=mesh(new T.PlaneGeometry(embedded?.44:.445,embedded?.14:.136),plateFace,mount);face.position.z=.0105;
    for(const x of (embedded?[-.105,.105]:[-.202,.202]))for(const y of [-.055,.055]) {
      const screw=mesh(new T.CylinderGeometry(.006,.006,.004,12),chrome,mount);screw.rotation.x=Math.PI/2;screw.position.set(x,y,.013);
      box(.007,.0015,.001,x,y,.0155,black,mount);
    }
    plates.push(mount);
  }
  // Soft contact shadow under the chassis, including when hardware shadow filtering is limited.
  const shadowCanvas=document.createElement('canvas');shadowCanvas.width=128;shadowCanvas.height=128;
  const sc=shadowCanvas.getContext('2d'),gradient=sc.createRadialGradient(64,64,8,64,64,64);
  gradient.addColorStop(0,'rgba(30,43,62,.32)');gradient.addColorStop(1,'rgba(30,43,62,0)');sc.fillStyle=gradient;sc.fillRect(0,0,128,128);
  const contact=mesh(new T.PlaneGeometry(2.8,5.5),new T.MeshBasicMaterial({map:new T.CanvasTexture(shadowCanvas),transparent:true,depthWrite:false}),scene);contact.rotation.x=-Math.PI/2;contact.position.y=.003;contact.castShadow=false;

  let activeModel=null;
  const defaults={model:'zd8',color:'#155dcc',name:'拉力蓝',finish:'gloss',plate:'粤B·BRZ86',style:'blue',visible:true};
  if(embedded){defaults.plate='';defaults.visible=false;}
  let state={...defaults};
  try {
    const saved=embedded?null:JSON.parse(localStorage.getItem('brz-3d-prototype-v1'));
    if(saved && /^#[0-9a-f]{6}$/i.test(saved.color)) {
      state={model:saved.model==='zc6'?'zc6':'zd8',color:saved.color,name:typeof saved.name==='string'?saved.name.slice(0,20):'自定义',
        finish:saved.finish==='satin'?'satin':'gloss',plate:typeof saved.plate==='string'?saved.plate.slice(0,10):defaults.plate,
        style:['blue','green','white'].includes(saved.style)?saved.style:'blue',visible:typeof saved.visible==='boolean'?saved.visible:true};
    }
  }catch(_){}
  const palette=[['拉力蓝','#155dcc'],['珍珠白','#e7e9e8'],['烈焰红','#b91930'],['石墨灰','#565d66'],['曜石黑','#141a22'],['冰银','#a4b1bf']];
  let currentName=state.name;
  function save(){if(embedded)return;try{localStorage.setItem('brz-3d-prototype-v1',JSON.stringify(state));}catch(_){}}
  function updatePaint(){
    frameDirty=true;
    paint.color.set(state.color);paint.roughness=state.finish==='satin'?.62:.32;paint.clearcoat=state.finish==='satin'?.08:1;paint.clearcoatRoughness=state.finish==='satin'?.45:.14;
    $('custom-color').value=state.color;$('finish').value=state.finish;
    document.querySelectorAll('.swatch').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.color===state.color)));
    $('selection').textContent=`${currentName} · ${state.finish==='satin'?'哑光车漆':'亮面车漆'}`;save();
  }
  function updatePlate(){
    frameDirty=true;
    const green=state.style==='green',white=state.style==='white';
    if(green){const g=ctx.createLinearGradient(0,0,0,320);g.addColorStop(0,'#f0fff0');g.addColorStop(1,'#68d780');ctx.fillStyle=g;}
    else ctx.fillStyle=white?'#f3f4f1':'#0752bd';
    ctx.fillRect(0,0,1024,320);ctx.strokeStyle=white||green?'#25312c':'#f5f8ff';ctx.lineWidth=7;ctx.strokeRect(15,15,994,290);
    ctx.fillStyle=ctx.strokeStyle;ctx.textAlign='center';ctx.textBaseline='middle';
    const text=state.plate.trim()||'BRZ';let size=190;ctx.font=`600 ${size}px "Microsoft YaHei", sans-serif`;
    while(ctx.measureText(text).width>910 && size>60){size-=2;ctx.font=`600 ${size}px "Microsoft YaHei", sans-serif`;}
    ctx.fillText(text,512,168);plateTexture.needsUpdate=true;plates.forEach(p=>p.visible=state.visible && !!activeModel);renderer.shadowMap.needsUpdate=true;save();
    if(embedded && nativePlateUrl){
      const serial=++plateImageSerial,img=new Image();
      img.onload=()=>{if(serial!==plateImageSerial)return;ctx.clearRect(0,0,1024,320);ctx.drawImage(img,0,0,1024,320);plateTexture.needsUpdate=true;frameDirty=true;};
      img.onerror=()=>{if(serial===plateImageSerial)fail();};img.src=nativePlateUrl;
    }else{plateImageSerial++;}
  }
  for(const [name,color] of palette){const b=document.createElement('button');b.className='swatch';b.dataset.color=color;b.style.setProperty('--paint',color);b.setAttribute('aria-label',name);b.innerHTML=`<i aria-hidden="true"></i>${name}`;b.onclick=()=>{state.color=color;state.name=currentName=name;updatePaint();};$('swatches').appendChild(b);}
  $('custom-color').oninput=e=>{state.color=e.target.value;state.name=currentName='自定义';updatePaint();};
  $('finish').onchange=e=>{state.finish=e.target.value;updatePaint();};
  $('plate-text').oninput=e=>{state.plate=e.target.value;updatePlate();};
  $('plate-style').onchange=e=>{state.style=e.target.value;updatePlate();};
  $('plate-visible').onchange=e=>{state.visible=e.target.checked;updatePlate();};
  function sync(){currentName=state.name;$('plate-text').value=state.plate;$('plate-style').value=state.style;$('plate-visible').checked=state.visible;updatePaint();updatePlate();}

  const orbit=new VehicleCamera(embedded);
  let automatic=false,lastTime=0;
  function view(name){if(name==='plate'&&Math.cos(orbit.yaw)<0)name='rearPlate';frameDirty=true;orbit.preset(name);setAuto(false);document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('selected',b.dataset.view===name));}
  function setAuto(value){automatic=value;$('rotate').setAttribute('aria-pressed',String(value));$('rotate').textContent=value?'暂停旋转':'自动旋转';}
  document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>view(b.dataset.view));
  $('rotate').onclick=()=>{if(!automatic){orbit.orbit(0,0);document.querySelectorAll('[data-view]').forEach(b=>b.classList.remove('selected'));}setAuto(!automatic);};
  $('reset').onclick=()=>{const model=state.model;state={...defaults,model};sync();view('front');};
  const pointers=new Map();let previousPinch=0;
  viewport.addEventListener('pointerdown',e=>{viewport.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});previousPinch=0;setAuto(false);});
  viewport.addEventListener('pointermove',e=>{
    const old=pointers.get(e.pointerId);if(!old)return;
    if(pointers.size===1)orbit.orbit(e.clientX-old.x,e.clientY-old.y);
    pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
    if(pointers.size===2){const [a,b]=[...pointers.values()],d=Math.hypot(a.x-b.x,a.y-b.y);if(previousPinch)orbit.zoom(previousPinch/d);previousPinch=d;}
    document.querySelectorAll('[data-view]').forEach(b=>b.classList.remove('selected'));
  });
  function endPointer(e){pointers.delete(e.pointerId);previousPinch=0;}
  viewport.addEventListener('pointerup',endPointer);viewport.addEventListener('pointercancel',endPointer);viewport.addEventListener('lostpointercapture',endPointer);
  viewport.addEventListener('wheel',e=>{e.preventDefault();orbit.zoom(Math.exp(e.deltaY*.001));},{passive:false});
  function resize(){const w=viewport.clientWidth,h=viewport.clientHeight;renderer.setSize(w,h);camera.aspect=w/h;camera.updateProjectionMatrix();frameDirty=true;}
  const observer=new ResizeObserver(resize);observer.observe(viewport);resize();sync();
  renderer.domElement.addEventListener('webglcontextlost',e=>{e.preventDefault();loading.hidden=false;loading.textContent='3D 显示已中断，请刷新页面恢复。';fail();});
  function render(time){
    const dt=Math.min((time-lastTime)/1000,.05);lastTime=time;
    if(document.hidden || !active)return;
    if(automatic && !document.hidden)orbit.yaw+=dt*.26;
    const viewKey=[orbit.yaw,orbit.pitch,orbit.distance,...orbit.target,camera.aspect].join(',');
    if(!frameDirty && lastView===viewKey)return;
    lastView=viewKey;frameDirty=false;
    camera.position.set(...orbit.position(camera.aspect));
    camera.lookAt(...orbit.target);renderer.render(scene,camera);
  }
  renderer.setAnimationLoop(render);
  window.addEventListener('pagehide',()=>setActive(false));
  document.addEventListener('visibilitychange',()=>setActive(!document.hidden));
  window.addEventListener('pageshow',()=>setActive(true));
  function setActive(value){active=!!value;orbit.setActive(active);pointers.clear();previousPinch=0;setAuto(false);lastTime=0;frameDirty=true;renderer.setAnimationLoop(active?render:null);}

  const models={
    // Visual calibration against Subaru's 2021-11 TechTIPS p.7 bumper landmarks.
    // These are model-space plate centers, not factory mounting-height measurements.
    zd8:{length:4.265,paint:'2022_Subaru_BRZ_WR_Blue_Pearl',frontY:.45,rearY:.51,triangles:836008,id:'2e8aca0407ae44d2802bc34761054a69'},
    // ZC6 visual adjustment: lower the front plate 40 mm to clear the badge.
    zc6:{length:4.24,paint:'body',frontY:.44,rearY:.75,triangles:148158,id:'321a9ece66bb4616872892c01d279542'}
  };
  const loader=new GLTFLoader();
  let loadSerial=0,loadingModel=null;
  function disposeModel(root){
    const geometries=new Set(),materials=new Set(),textures=new Set();
    root.traverse(o=>{if(o.geometry)geometries.add(o.geometry);for(const m of o.material?(Array.isArray(o.material)?o.material:[o.material]):[]){if(m!==paint)materials.add(m);}});
    for(const m of materials){for(const value of Object.values(m)){if(value?.isTexture)textures.add(value);}m.dispose();}
    geometries.forEach(g=>g.dispose());textures.forEach(t=>t.dispose());
  }
  function installPlates(config){
    activeModel.updateMatrixWorld(true);
    const targets=[];activeModel.traverse(o=>{if(o.isMesh && !o.material.transparent)targets.push(o);});
    const ray=new T.Raycaster();
    plates.forEach((mount,index)=>{
      const sign=index===0?1:-1,y=index===0?config.frontY:config.rearY;
      const points=[];
      // Sample the entire backing footprint so its corners cannot sink into the bumper.
      for(const x of [-plateMount.width/2,0,plateMount.width/2])for(const dy of [-plateMount.height/2,0,plateMount.height/2]){
        ray.set(new T.Vector3(x,y+dy,sign*4),new T.Vector3(0,0,-sign));
        const hit=ray.intersectObjects(targets,false)[0];if(hit)points.push(hit.point.z);
      }
      const z=points.length?(sign===1?Math.max(...points):Math.min(...points)):sign*config.length/2;
      mount.position.set(0,y,z+sign*(plateMount.depth/2+plateMount.clearance));mount.rotation.set(0,index===0?0:Math.PI,0);
      mount.visible=state.visible;
    });
    renderer.shadowMap.needsUpdate=true;
  }
  async function loadModel(key){
    const serial=++loadSerial,config=models[key];
    loadingModel=key;
    loading.hidden=false;loading.textContent=`正在加载 BRZ ${key.toUpperCase()}…`;
    $('model-status').textContent='正在读取本地模型';
    $('vehicle-model').value=key;
    try{
      const result=await loader.loadAsync(`models/${key}/vehicle.glb`,event=>{
        if(serial!==loadSerial)return;
        loading.textContent=`正在加载 BRZ ${key.toUpperCase()} · ${event.total?Math.round(event.loaded/event.total*100)+'%':Math.round(event.loaded/1048576)+' MB'}`;
      });
      if(serial!==loadSerial){disposeModel(result.scene);return;}
      const root=result.scene;
      const oldPaint=new Set();let paintMeshCount=0;
      root.traverse(o=>{
        if(o.isCamera || o.isLight){o.visible=false;return;}
        if(!o.isMesh)return;
        const replace=m=>{
          if(m.name===config.paint){oldPaint.add(m);paintMeshCount++;return paint;}
          // The exports use pure black, mirror-smooth wheels and rubber. Give each its own finish.
          if(/^(2022_Subaru_BRZ_Tires|tires)$/.test(m.name)){
            m.color.set('#252525');m.metalness=0;m.roughness=.88;
          }else if(/^(2022_Subaru_BRZ_Rims1|wheel1)$/.test(m.name)){
            m.color.set('#41454a');m.metalness=.7;m.roughness=.3;
          }else if(m.name==='2022_Subaru_BRZ_Rims2'){
            m.metalness=.85;m.roughness=.28;
          }else if(/^(2022_Subaru_BRZ_Windows|material_19|material_23)$/.test(m.name)){
            m.color.set('#70777d');m.metalness=0;m.roughness=.12;
          }
          if(m.transparent)m.depthWrite=false;
          if(m.emissiveIntensity>1.5)m.emissiveIntensity=1.5;
          return m;
        };
        o.material=Array.isArray(o.material)?o.material.map(replace):replace(o.material);
        // Thin imported body shells self-shadow with severe acne; retain their ground shadow.
        o.castShadow=!o.material.transparent;o.receiveShadow=o.material!==paint;
      });
      if(!paintMeshCount){disposeModel(root);throw new Error('未找到已核实的独立车漆材质');}
      oldPaint.forEach(m=>m.dispose());
      // glTF already supplies the SketchUp Z-up to Y-up transform. Both models face +Z.
      root.updateMatrixWorld(true);
      const bounds=new T.Box3().setFromObject(root),size=bounds.getSize(new T.Vector3());
      const wrapper=new T.Group();wrapper.add(root);wrapper.scale.setScalar(config.length/size.z);
      root.position.sub(new T.Vector3((bounds.min.x+bounds.max.x)/2,bounds.min.y,(bounds.min.z+bounds.max.z)/2));
      if(activeModel){car.remove(activeModel);disposeModel(activeModel);}
      activeModel=wrapper;car.add(activeModel);state.model=key;
      const centeredBounds=new T.Box3().setFromObject(wrapper);
      orbit.setCenter(centeredBounds.getCenter(new T.Vector3()).toArray());orbit.rearPlateY=config.rearY;
      frameDirty=true;
      installPlates(config);updatePaint();save();
      $('vehicle-title').textContent=`SUBARU / ${key.toUpperCase()} · 6MT`;
      $('model-status').textContent=`${key.toUpperCase()} · ${(config.triangles/10000).toFixed(1)} 万三角面`;
      $('model-source').textContent=`Subaru BRZ (${key.toUpperCase()})`;
      $('model-source').href=`https://sketchfab.com/3d-models/subaru-brz-${key}-${config.id}`;
      viewport.setAttribute('aria-label',`可旋转的 BRZ ${key.toUpperCase()} 车辆模型，前后安装立体车牌`);
      loadingModel=null;loading.hidden=true;view('front');host?.loaded();
    }catch(error){
      if(serial!==loadSerial)return;
      loadingModel=null;
      loading.hidden=false;loading.textContent='模型加载失败，请刷新重试，并确认通过本地预览地址打开。';
      $('model-status').textContent=activeModel?'保留上一辆车的预览':'未能加载模型';
      $('vehicle-model').value=state.model;console.error(error);fail();
    }
  }
  $('vehicle-model').onchange=e=>loadModel(e.target.value);
  if(embedded){
    window.BrzVehicle={
      apply(value){
        const key=value.model==='zc6'?'zc6':'zd8';
        if(/^#[0-9a-f]{6}$/i.test(value.color))state.color=value.color;
        state.finish=value.finish==='satin'?'satin':'gloss';
        state.plate=String(value.plate||'').slice(0,10);state.visible=!!value.visible && !!state.plate;
        nativePlateUrl=typeof value.plateUrl==='string'?value.plateUrl:'';
        sync();if((!activeModel && !loadingModel) || (loadingModel || state.model)!==key)loadModel(key);
      },
      view(name){view(name);},
      setActive
    };
    if(host)host.ready();else loadModel(state.model);
  }else loadModel(state.model);
})();
