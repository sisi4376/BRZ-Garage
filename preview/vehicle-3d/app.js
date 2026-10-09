/* Original procedural coupe geometry. +Z is the front, +Y is up; units are metres. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const viewport = $('viewport');
  const loading = $('loading');
  if (!window.THREE) { loading.textContent = '3D 引擎未加载，请检查 vendor 文件夹。'; return; }
  const T = window.THREE;
  let renderer;
  try { renderer = new T.WebGLRenderer({ antialias: true, alpha: true }); }
  catch (_) { loading.textContent = '当前浏览器无法启用 WebGL，请使用支持硬件加速的浏览器。'; return; }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = T.PCFSoftShadowMap;
  renderer.outputColorSpace = T.SRGBColorSpace;
  renderer.toneMapping = T.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.15;
  viewport.appendChild(renderer.domElement);
  const scene = new T.Scene();
  const camera = new T.PerspectiveCamera(36, 1, .05, 80);
  scene.add(new T.HemisphereLight(0xe5eeff, 0x757e8c, 2.5));
  function light(color, power, x, y, z) {
    const lamp = new T.DirectionalLight(color, power); lamp.position.set(x,y,z); scene.add(lamp); return lamp;
  }
  const key = light(0xffffff, 3.8, -3, 7, 5);
  key.castShadow = true; key.shadow.mapSize.set(2048,2048);
  Object.assign(key.shadow.camera, { left:-5,right:5,top:5,bottom:-5,near:.1,far:20 });
  key.shadow.bias = -.0002; key.shadow.normalBias = .015;
  light(0xc7d9ff, 2.2, 5,3,-4); light(0xffffff,1.1,-4,2,-5);

  // A local studio environment supplies broad paint reflections, without external HDR assets.
  const studio = new T.Scene(); studio.background = new T.Color('#a5b0c0');
  for (const [x,y,z,w,h,power] of [[0,7,0,7,5,4],[-5,3,1,3,6,2],[4,3,-3,3,5,3]]) {
    const panel = new T.Mesh(new T.PlaneGeometry(w,h),new T.MeshBasicMaterial({color:new T.Color(power,power,power),side:T.DoubleSide}));
    panel.position.set(x,y,z); panel.lookAt(0,0,0); studio.add(panel);
  }
  const pmrem = new T.PMREMGenerator(renderer);
  const environment = pmrem.fromScene(studio,.05); scene.environment = environment.texture;
  studio.traverse(o=>{o.geometry?.dispose();o.material?.dispose();}); pmrem.dispose();

  const paint = new T.MeshPhysicalMaterial({color:'#155dcc',metalness:.5,roughness:.25,clearcoat:1,clearcoatRoughness:.12});
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
  const stations=[[-2.12,.73,.83],[-1.95,.88,.94],[-1.35,.90,1.00],[-.65,.86,.97],[.4,.85,.96],[1.3,.9,.93],[1.8,.86,.85],[2.12,.73,.71]];
  function profile(z) {
    let i=0;while(i<stations.length-2 && z>stations[i+1][0])i++;
    const a=stations[i],b=stations[i+1],t=(z-a[0])/(b[0]-a[0]);
    return [T.MathUtils.lerp(a[1],b[1],t),T.MathUtils.lerp(a[2],b[2],t)];
  }
  const rows=[];
  for(let i=0;i<=120;i++) {
    const z=-2.12+i*4.24/120,[width,top]=profile(z);
    let bottom=.29;
    for(const axle of [-1.31,1.29]) {const d=Math.abs(z-axle);if(d<.432)bottom=Math.max(bottom,.39+Math.sqrt(.432*.432-d*d));}
    rows.push([[-width*.94,bottom,z],[-width,bottom+(top-bottom)*.7,z],[-width*.93,top-.005,z],[-width*.7,top+.036,z],[0,top+.065,z],[width*.7,top+.036,z],[width*.93,top-.005,z],[width,bottom+(top-bottom)*.7,z],[width*.94,bottom,z]]);
  }
  surface(rows,paint);
  // Sills, wheel lips and lower body give the shell a continuous planted silhouette.
  for(const side of [-1,1]) {
    box(.065,.09,1.64,side*.844,.31,-.01,paint);
    for(const z of [-1.31,1.29]) {
      const pts=[];for(let i=0;i<=36;i++){const angle=Math.PI*i/36;pts.push([side*.883,.39+Math.sin(angle)*.439,z+Math.cos(angle)*.439]);}
      line(pts,paint,.025);
    }
  }
  // Roof section: a curved coupe canopy, with separately coloured glass and painted roof.
  function canopy(z,width,y) { return [[-width,y-.065,z],[-width*.8,y+.017,z],[0,y+.045,z],[width*.8,y+.017,z],[width,y-.065,z]]; }
  surface([canopy(-1.35,.71,1.00),canopy(-.78,.64,1.40)],glass);
  surface([canopy(-.78,.64,1.40),canopy(-.55,.65,1.435),canopy(-.06,.63,1.43),canopy(.12,.61,1.39)],paint);
  surface([canopy(.12,.61,1.39),canopy(.77,.73,1.00)],glass);
  for(const s of [-1,1]) {
    surface([[[s*.718,1.004,-1.34],[s*.737,1.009,.76]],[[s*.634,1.355,-.77],[s*.614,1.345,.12]]],glass);
    line([[s*.72,1.003,-1.36],[s*.642,1.354,-.78],[s*.648,1.415,-.45],[s*.61,1.347,.13],[s*.737,1.009,.79]],paint,.028);
    line([[s*.72,1.005,-1.35],[s*.753,1.006,-.35],[s*.737,1.006,.77]],black,.017);
    line([[s*.653,1.36,-.65],[s*.729,1.007,-.76]],black,.017);
    line([[s*.754,1.009,.72],[s*.856,.91,.64],[s*.85,.43,.48],[s*.851,.42,-.69],[s*.867,.81,-.91],[s*.754,1.00,-.89]],black,.004);
    box(.019,.025,.14,s*.858,.91,-.65,chrome);
    const stem=box(.13,.035,.05,s*.78,1.02,.54,black);stem.rotation.z=s*.2;
    const mirror=mesh(new T.SphereGeometry(1,24,12),paint);mirror.scale.set(.145,.065,.105);mirror.position.set(s*.925,1.067,.54);
    const mirrorGlass=mesh(new T.SphereGeometry(1,20,10),glass);mirrorGlass.scale.set(.106,.047,.012);mirrorGlass.position.set(s*.933,1.066,.447);
    line([[s*.56,.922,1.73],[s*.51,1.015,1.1],[s*.49,1.022,.78]],paint,.009);
  }
  // Front and rear bumper fascia, sculpted rather than a camera-facing sprite.
  function fascia(z,front) {
    const sign=front?1:-1;
    const top=front?.77:.94;
    surface([[[-.74,.32,z],[0,.30,z+sign*.045],[.74,.32,z]],[[-.8,.56,z-.02*sign],[0,.55,z+sign*.035],[.8,.56,z-.02*sign]],[[-.73,top,z-.025*sign],[0,top+.01,z],[.73,top,z-.025*sign]]],paint);
    box(1.21,.22,.044,0,.437,z+sign*.058,black);
    box(1.51,.04,.13,0,.29,z,black);
    for(let i=-8;i<=8;i++)box(.014,.18,.012,i*.064,.432,z+sign*.085,alloy);
    if(front) {
      for(const s of [-1,1]) {
        const housing=box(.37,.095,.065,s*.565,.742,z-.005,black);housing.rotation.z=s*.13;
        const lamp=box(.31,.026,.016,s*.565,.755,z+.032,whiteLight);lamp.rotation.z=s*.13;
        box(.12,.16,.026,s*.703,.435,z+.011,black);
      }
      const badge=mesh(new T.SphereGeometry(1,20,12),chrome);badge.scale.set(.052,.027,.015);badge.position.set(0,.697,z+.058);
    } else {
      for(const s of [-1,1]) {
        box(.44,.09,.045,s*.52,.82,z-.018,black);
        line([[s*.74,.84,z-.045],[s*.65,.852,z-.05],[s*.5,.82,z-.05],[s*.31,.82,z-.05]],redLight,.021);
        const pipe=mesh(new T.CylinderGeometry(.065,.065,.17,24),chrome);pipe.rotation.x=Math.PI/2;pipe.position.set(s*.61,.29,z-.05);
        const hole=mesh(new T.CircleGeometry(.049,24),black);hole.rotation.y=Math.PI;hole.position.set(s*.61,.29,z-.14);
      }
      box(1.47,.032,.17,0,.986,-1.99,paint);
    }
  }
  fascia(2.115,true);fascia(-2.115,false);
  box(1.5,.10,3.5,0,.28,0,black);
  // Four wheels: actual tyre volume, brake rotors, coloured calipers and ten alloy spokes.
  for(const s of [-1,1])for(const z of [-1.31,1.29]) {
    const wheel=new T.Group();wheel.position.set(s*.847,.385,z);car.add(wheel);
    const tire=mesh(new T.CylinderGeometry(.382,.382,.22,64),rubber,wheel);tire.rotation.z=Math.PI/2;
    const rim=mesh(new T.CylinderGeometry(.283,.283,.229,48),black,wheel);rim.rotation.z=Math.PI/2;
    const disc=mesh(new T.CylinderGeometry(.237,.237,.015,48),alloy,wheel);disc.rotation.z=Math.PI/2;disc.position.x=s*.086;
    box(.03,.17,.07,s*.103,.035,-.192,red,wheel);
    const torus=mesh(new T.TorusGeometry(.279,.015,10,64),chrome,wheel);torus.rotation.y=Math.PI/2;torus.position.x=s*.12;
    for(let i=0;i<10;i++) {
      const a=i*Math.PI/5;
      const spoke=box(.03,.205,.023,s*.12,Math.cos(a)*.15,Math.sin(a)*.15,alloy,wheel);spoke.rotation.x=a;
    }
    const hub=mesh(new T.CylinderGeometry(.062,.062,.032,24),chrome,wheel);hub.rotation.z=Math.PI/2;hub.position.x=s*.125;
    for(let i=0;i<5;i++) {
      const a=i*Math.PI*2/5;const bolt=mesh(new T.SphereGeometry(.009,8,6),black,wheel);bolt.position.set(s*.148,Math.cos(a)*.043,Math.sin(a)*.043);
    }
    for(const offset of [-.061,0,.061]) {const tread=mesh(new T.TorusGeometry(.382,.003,4,64),black,wheel);tread.rotation.y=Math.PI/2;tread.position.x=offset;}
  }

  const plates=[];
  const plateCanvas=document.createElement('canvas');plateCanvas.width=1024;plateCanvas.height=320;
  const ctx=plateCanvas.getContext('2d');
  const plateTexture=new T.CanvasTexture(plateCanvas);plateTexture.colorSpace=T.SRGBColorSpace;
  plateTexture.anisotropy=renderer.capabilities.getMaxAnisotropy();
  const plateFace=new T.MeshStandardMaterial({map:plateTexture,roughness:.38,metalness:.15});
  for(const front of [true,false]) {
    const mount=new T.Group();mount.name=front?'front-plate-mount':'rear-plate-mount';
    mount.position.set(0,front?.535:.635,front?2.205:-2.192);mount.rotation.y=front?0:Math.PI;car.add(mount);
    box(.49,.164,.033,0,0,0,black,mount);
    box(.455,.143,.009,0,0,.022,chrome,mount);
    const face=mesh(new T.PlaneGeometry(.445,.136),plateFace,mount);face.position.z=.027;
    for(const x of [-.202,.202])for(const y of [-.055,.055]) {
      const screw=mesh(new T.CylinderGeometry(.006,.006,.004,12),chrome,mount);screw.rotation.x=Math.PI/2;screw.position.set(x,y,.030);
      box(.007,.0015,.001,x,y,.0325,black,mount);
    }
    plates.push(mount);
  }
  const ground=mesh(new T.PlaneGeometry(200,200),new T.ShadowMaterial({opacity:.18}),scene);ground.rotation.x=-Math.PI/2;ground.position.y=-.003;ground.castShadow=false;
  // Soft contact shadow under the chassis, including when hardware shadow filtering is limited.
  const shadowCanvas=document.createElement('canvas');shadowCanvas.width=128;shadowCanvas.height=128;
  const sc=shadowCanvas.getContext('2d'),gradient=sc.createRadialGradient(64,64,8,64,64,64);
  gradient.addColorStop(0,'rgba(30,43,62,.32)');gradient.addColorStop(1,'rgba(30,43,62,0)');sc.fillStyle=gradient;sc.fillRect(0,0,128,128);
  const contact=mesh(new T.PlaneGeometry(2.8,5.5),new T.MeshBasicMaterial({map:new T.CanvasTexture(shadowCanvas),transparent:true,depthWrite:false}),scene);contact.rotation.x=-Math.PI/2;contact.position.y=.003;contact.castShadow=false;

  const defaults={color:'#155dcc',name:'拉力蓝',finish:'gloss',plate:'粤B·BRZ86',style:'blue',visible:true};
  let state={...defaults};
  try {
    const saved=JSON.parse(localStorage.getItem('brz-3d-prototype-v1'));
    if(saved && /^#[0-9a-f]{6}$/i.test(saved.color)) {
      state={color:saved.color,name:typeof saved.name==='string'?saved.name.slice(0,20):'自定义',
        finish:saved.finish==='satin'?'satin':'gloss',plate:typeof saved.plate==='string'?saved.plate.slice(0,10):defaults.plate,
        style:['blue','green','white'].includes(saved.style)?saved.style:'blue',visible:typeof saved.visible==='boolean'?saved.visible:true};
    }
  }catch(_){}
  const palette=[['拉力蓝','#155dcc'],['珍珠白','#e7e9e8'],['烈焰红','#b91930'],['石墨灰','#565d66'],['曜石黑','#141a22'],['冰银','#a4b1bf']];
  let currentName=state.name;
  function save(){try{localStorage.setItem('brz-3d-prototype-v1',JSON.stringify(state));}catch(_){}}
  function updatePaint(){
    paint.color.set(state.color);paint.roughness=state.finish==='satin'?.7:.25;paint.clearcoat=state.finish==='satin'?.08:1;
    $('custom-color').value=state.color;$('finish').value=state.finish;
    document.querySelectorAll('.swatch').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.color===state.color)));
    $('selection').textContent=`${currentName} · ${state.finish==='satin'?'哑光车漆':'亮面车漆'}`;save();
  }
  function updatePlate(){
    const green=state.style==='green',white=state.style==='white';
    if(green){const g=ctx.createLinearGradient(0,0,0,320);g.addColorStop(0,'#f0fff0');g.addColorStop(1,'#68d780');ctx.fillStyle=g;}
    else ctx.fillStyle=white?'#f3f4f1':'#0752bd';
    ctx.fillRect(0,0,1024,320);ctx.strokeStyle=white||green?'#25312c':'#f5f8ff';ctx.lineWidth=7;ctx.strokeRect(15,15,994,290);
    ctx.fillStyle=ctx.strokeStyle;ctx.textAlign='center';ctx.textBaseline='middle';
    const text=state.plate.trim()||'BRZ';let size=190;ctx.font=`600 ${size}px "Microsoft YaHei", sans-serif`;
    while(ctx.measureText(text).width>910 && size>60){size-=2;ctx.font=`600 ${size}px "Microsoft YaHei", sans-serif`;}
    ctx.fillText(text,512,168);plateTexture.needsUpdate=true;plates.forEach(p=>p.visible=state.visible);save();
  }
  for(const [name,color] of palette){const b=document.createElement('button');b.className='swatch';b.dataset.color=color;b.style.setProperty('--paint',color);b.setAttribute('aria-label',name);b.innerHTML=`<i aria-hidden="true"></i>${name}`;b.onclick=()=>{state.color=color;state.name=currentName=name;updatePaint();};$('swatches').appendChild(b);}
  $('custom-color').oninput=e=>{state.color=e.target.value;state.name=currentName='自定义';updatePaint();};
  $('finish').onchange=e=>{state.finish=e.target.value;updatePaint();};
  $('plate-text').oninput=e=>{state.plate=e.target.value;updatePlate();};
  $('plate-style').onchange=e=>{state.style=e.target.value;updatePlate();};
  $('plate-visible').onchange=e=>{state.visible=e.target.checked;updatePlate();};
  function sync(){currentName=state.name;$('plate-text').value=state.plate;$('plate-style').value=state.style;$('plate-visible').checked=state.visible;updatePaint();updatePlate();}

  let yaw=.72,pitch=.27,distance=7.5,focusY=.66,focusZ=0,automatic=false,lastTime=0;
  const views={front:[.72,.27,7.5,.66,0],side:[Math.PI/2,.12,7.4,.68,0],rear:[2.5,.23,7.5,.65,0],plate:[.30,.08,2.65,.54,1.50]};
  function view(name){[yaw,pitch,distance,focusY,focusZ]=views[name];setAuto(false);document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('selected',b.dataset.view===name));}
  function setAuto(value){automatic=value;$('rotate').setAttribute('aria-pressed',String(value));$('rotate').textContent=value?'暂停旋转':'自动旋转';}
  document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>view(b.dataset.view));
  $('rotate').onclick=()=>{if(!automatic){focusY=.66;focusZ=0;distance=7.5;document.querySelectorAll('[data-view]').forEach(b=>b.classList.remove('selected'));}setAuto(!automatic);};
  $('reset').onclick=()=>{state={...defaults};sync();view('front');};
  const pointers=new Map();let previousPinch=0;
  viewport.addEventListener('pointerdown',e=>{viewport.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});previousPinch=0;setAuto(false);});
  viewport.addEventListener('pointermove',e=>{
    const old=pointers.get(e.pointerId);if(!old)return;
    if(pointers.size===1){yaw-=(e.clientX-old.x)*.008;pitch=T.MathUtils.clamp(pitch+(e.clientY-old.y)*.006,-.03,.85);}
    pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
    if(pointers.size===2){const [a,b]=[...pointers.values()],d=Math.hypot(a.x-b.x,a.y-b.y);if(previousPinch)distance=T.MathUtils.clamp(distance*previousPinch/d,2.2,12);previousPinch=d;}
    document.querySelectorAll('[data-view]').forEach(b=>b.classList.remove('selected'));
  });
  function endPointer(e){pointers.delete(e.pointerId);previousPinch=0;}
  viewport.addEventListener('pointerup',endPointer);viewport.addEventListener('pointercancel',endPointer);viewport.addEventListener('lostpointercapture',endPointer);
  viewport.addEventListener('wheel',e=>{e.preventDefault();distance=T.MathUtils.clamp(distance*Math.exp(e.deltaY*.001),2.2,12);},{passive:false});
  function resize(){const w=viewport.clientWidth,h=viewport.clientHeight;renderer.setSize(w,h);camera.aspect=w/h;camera.updateProjectionMatrix();}
  const observer=new ResizeObserver(resize);observer.observe(viewport);resize();sync();loading.hidden=true;
  renderer.domElement.addEventListener('webglcontextlost',e=>{e.preventDefault();loading.hidden=false;loading.textContent='3D 显示已中断，请刷新页面恢复。';});
  function render(time){
    const dt=Math.min((time-lastTime)/1000,.05);lastTime=time;
    if(automatic && !document.hidden)yaw+=dt*.26;
    // Fit the long body to narrow mobile screens; the close-up remains focused on the plate.
    const fit=focusZ>1?1:Math.max(1,1.28/camera.aspect);
    camera.position.set(Math.sin(yaw)*Math.cos(pitch)*distance*fit,focusY+Math.sin(pitch)*distance*fit,focusZ+Math.cos(yaw)*Math.cos(pitch)*distance*fit);
    camera.lookAt(0,focusY,focusZ);renderer.render(scene,camera);
  }
  renderer.setAnimationLoop(render);
  window.addEventListener('pagehide',()=>{renderer.setAnimationLoop(null);});
  window.addEventListener('pageshow',()=>{lastTime=0;renderer.setAnimationLoop(render);});
})();
