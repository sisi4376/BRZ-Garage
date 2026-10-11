const car=document.getElementById('car'),picker=document.getElementById('model');
const palette=document.getElementById('palette');
let color='#155dcc';
function apply(){
  car.contentWindow.BrzVehicle?.apply({model:picker.value,color,finish:document.getElementById('satin').checked?'satin':'gloss',plate:'粤B·12345',visible:document.getElementById('show-plate').checked});
  document.getElementById('subtitle').textContent=`SUBARU / ${picker.value.toUpperCase()} · 6MT`;
  document.getElementById('chip').textContent=`${picker.value.toUpperCase()} · 6MT`;
  document.getElementById('flat-car').src=`../../android_app/app/src/main/res/drawable-nodpi/brz_${picker.value}_hero.png`;
}
let retries=0;
const ready=setInterval(()=>{if(car.contentWindow.BrzVehicle){clearInterval(ready);apply();}else if(++retries>200)clearInterval(ready);},100);
picker.onchange=apply;
let vehiclePage=true;
const show3D=document.getElementById('show-3d');
show3D.onchange=()=>{
  car.style.display=show3D.checked?'':'none';
  document.querySelector('.toolbar').style.display=show3D.checked?'':'none';
  document.getElementById('flat-car').style.display=show3D.checked?'none':'block';
  car.contentWindow.BrzVehicle?.setActive(show3D.checked && vehiclePage);
};
document.getElementById('switch-page').onclick=()=>{
  vehiclePage=!vehiclePage;
  car.contentWindow.BrzVehicle?.setActive(vehiclePage && show3D.checked);
  document.getElementById('vehicle-page').style.display=vehiclePage?'':'none';
  document.getElementById('switch-page').textContent=vehiclePage?'模拟离开车辆页':'返回车辆页';
  const reads=car.contentWindow.performance.getEntriesByType('resource').filter(r=>r.name.endsWith('/vehicle.glb')).length;
  document.getElementById('cache-status').textContent=`本次预览模型读取：${reads} 次`;
};
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>car.contentWindow.BrzVehicle?.view(b.dataset.view));
document.getElementById('color').onclick=()=>palette.showModal();
palette.querySelector('.close').onclick=()=>palette.close();
document.getElementById('satin').onchange=apply;
document.getElementById('show-plate').onchange=apply;
for(const [name,hex] of [['拉力蓝','#155dcc'],['珍珠白','#e7e9e8'],['烈焰红','#b91930'],['石墨灰','#565d66'],['曜石黑','#141a22'],['冰银','#a4b1bf']]){
  const b=document.createElement('button');b.textContent=name;b.onclick=()=>{color=hex;apply();};palette.querySelector('.colors').append(b);
}
