"use strict";
const $ = id => document.getElementById(id);
const names = ["温度","信息","图表","指针","挡位 + 转速","车速","油量","行程历史","行程总览","自定义 A","自定义 B","自定义 C"];
const signals = ["CLT 水温","IAT 进气温度","OIL 机油温度","LOD 发动机负荷","TPS 节气门","RPM 转速","SPD 车速","BAT 电压","OIP 机油压力","BKT 刹车温度","BST 增压压力","AFR 空燃比","IGN 点火提前角"];
const units = ["°C","°C","°C","%","%","rpm","km/h","V","bar","°C","bar",":1","°"];
const values = ["93","31","98","42","24","3250","86","14.2","4.2","142","0.2","14.7","18"];
const slots = [[0,1,2],[3,4,5,6,7],[9],[8],[],[],[],[],[],[10,11,12],[13,14,15],[16,17,18]];
const factory = "4,5,0,1,3,2,6,8,7/0,1,2,0,2,3,4,1,0,8,0,2,8,5,6,4,7,10,11";
const phone = window.GaugePhone;
function stored() {
  try { return localStorage.getItem("gauge-layout"); } catch { return null; }
}
function parse(raw) {
  const [pages, items] = raw.split("/").map(part => part.split(",").map(Number));
  if (!pages.length || pages.length > 12 || new Set(pages).size !== pages.length ||
      !pages.every(id => Number.isInteger(id) && names[id]) || items?.length !== 19 ||
      !items.every(id => Number.isInteger(id) && signals[id])) throw Error("invalid layout");
  return [pages,items];
}
let pages, items, selected = 0, saved = "";
function load(raw) {
  try { [pages,items] = parse(raw); } catch { [pages,items] = parse(factory); }
  saved = pages.join(",") + "/" + items.join(",");
}
load(phone ? phone.initial() : stored() || factory);
function save() {
  saved = pages.join(",") + "/" + items.join(",");
  if (phone) phone.save(saved);
  else { try { localStorage.setItem("gauge-layout", saved); } catch {} }
  render();
}
const canvas = document.querySelector("canvas"), img = document.querySelector("img");
const ctx = canvas.getContext("2d");
function text(str,x,y,size,color="#fff",align="center") {
  ctx.fillStyle=color; ctx.font="600 "+size+"px system-ui,sans-serif"; ctx.textAlign=align;
  ctx.fillText(str,x,y);
}
function line(x1,y1,x2,y2,color,width=2) {
  ctx.beginPath(); ctx.strokeStyle=color; ctx.lineWidth=width;ctx.moveTo(x1,y1);ctx.lineTo(x2,y2);ctx.stroke();
}
function draw(id) {
  const fixed=(id>=4 && id<=8) || id===12; img.hidden=!fixed; canvas.hidden=fixed;
  if(fixed){ img.src="page-"+id+".png"; return; }
  ctx.fillStyle="#000"; ctx.fillRect(0,0,466,466);
  ctx.beginPath();ctx.arc(233,233,227,0,2*Math.PI);ctx.strokeStyle="#f4f4f4";ctx.lineWidth=9;ctx.stroke();
  const fields=slots[id].map(slot=>items[slot]);
  if(id===0 || id>=9) fields.forEach((v,i)=>{
    const y=169+i*70, color=["#44aaff","#44ff88","#ff7722","#eabb63","#5fd0a2","#55cdfa","#5dbfec","#e2c65b","#ffd05b","#ed8c6d","#aa9bd5","#76cfd0","#b6be72"][v];
    text(values[v],38,y+13,40,"#fff","left");
    ctx.beginPath();ctx.arc(190,y,5,0,2*Math.PI);ctx.fillStyle=color;ctx.fill();
    text(signals[v].split(" ")[0],200,y+7,21,color,"left");
    text(units[v],396,y+7,19,"#888","right");
    if(i<2) line(123,y+35,343,y+35,"#343434",1);
  });
  else if(id===1) fields.forEach((v,i)=>{
    const x=[155,308,233,155,308][i], y=[151,151,246,338,338][i];
    text(signals[v].split(" ")[0],x,y-23,16,"#75c9cb");text(values[v],x,y+13,34);text(units[v],x,y+34,15,"#99a9b1");
  });
  else {
    const v=fields[0];
    if(id===3){
      for(let i=0;i<=32;i++){
        const a=(140+i*260/32)*Math.PI/180, r=i%4===0?145:155;
        line(233+r*Math.cos(a),238+r*Math.sin(a),233+166*Math.cos(a),238+166*Math.sin(a),i>25?"#e9985f":"#6e8c95",3);
      }
      line(233,238,310,139,"#fb9753",6);
    }else{
      for(let i=0;i<4;i++)line(67,164+i*30,399,164+i*30,"#233139");
      for(let i=1;i<=60;i++){
        const y=n=>213-18*Math.sin(n*.17)-9*Math.cos(n*.51)-n*.38;
        line(67+(i-1)*5.5,y(i-1),67+i*5.5,y(i),"#60d4bd",3);
      }
    }
    text(signals[v].split(" ")[0],233,301,21,"#71d7c7");text(values[v]+" "+units[v],233,353,45);
  }
}
function option(label,value){const el=document.createElement("option");el.textContent=label;el.value=value;return el;}
function render(){
  const systemPage=selected===pages.length;
  const id=systemPage ? 12 : pages[selected]; draw(id);
  $("title").textContent=systemPage ? "版本与设置" : names[id]; $("position").textContent=(selected+1)+" / "+(pages.length+1);
  $("status").textContent=phone ? phone.status() : "交互预览 · 更改保存在当前浏览器";
  $("pages").replaceChildren();
  pages.forEach((p,i)=>{
    const row=document.createElement("button"); row.className="row"+(selected===i?" active":"");
    const number=document.createElement("i"), title=document.createElement("span"), hint=document.createElement("small");
    number.textContent=String(i+1).padStart(2,"0");title.textContent=names[p];
    hint.textContent=slots[p].length ? slots[p].map(s=>signals[items[s]].split(" ")[0]).join(" · ") : "专用表盘";
    row.append(number,title,hint);row.onclick=()=>{selected=i;render();$("edit").scrollIntoView({block:"nearest"});};$("pages").append(row);
  });
  $("edit").hidden=systemPage;
  $("editTitle").textContent=(names[id] || "版本与设置")+" · 页面设置"; $("items").replaceChildren();
  (slots[id] || []).forEach((s,i)=>{
    const label=document.createElement("label");label.className="slot";label.textContent="项目 "+(i+1);
    const picker=document.createElement("select");signals.forEach((v,j)=>picker.append(option(v,j)));
    picker.value=items[s];picker.onchange=()=>{items[s]=Number(picker.value);save();};
    label.append(picker);$("items").append(label);
  });
  if(!systemPage && !slots[id].length){const p=document.createElement("p");p.textContent="此专用页面使用固件预设的显示项目。";$("items").append(p);}
  const missing=names.map((_,i)=>i).filter(i=>!pages.includes(i));
  $("add").replaceChildren(option(missing.length?"＋ 添加页面":"已包含全部页面",""));
  missing.forEach(i=>$("add").append(option(names[i],i)));$("add").disabled=!missing.length;
  $("up").disabled=selected===0;$("down").disabled=selected===pages.length-1;$("remove").disabled=pages.length===1;
}
function move(delta){const i=selected+delta;if(i<0||i>=pages.length)return;[pages[i],pages[selected]]=[pages[selected],pages[i]];selected=i;save();}
function step(delta){selected=(selected+delta+pages.length+1)%(pages.length+1);render();}
$("prev").onclick=()=>step(-1);$("next").onclick=()=>step(1);
$("up").onclick=()=>move(-1);$("down").onclick=()=>move(1);
$("remove").onclick=()=>{if(pages.length>1){pages.splice(selected,1);selected=Math.min(selected,pages.length-1);save();}};
$("add").onchange=e=>{if(e.target.value!==""){pages.push(Number(e.target.value));selected=pages.length-1;save();}};
let startX,startY;
$("dial").onpointerdown=e=>{startX=e.clientX;startY=e.clientY;e.currentTarget.setPointerCapture(e.pointerId);};
$("dial").onpointerup=e=>{const dx=e.clientX-startX;if(Math.abs(dx)>30 && Math.abs(dx)>Math.abs(e.clientY-startY))step(dx<0?1:-1);};
if(phone){
  $("back").hidden=false;$("back").onclick=()=>phone.back();
  setInterval(()=>{
    if(document.activeElement?.tagName==="SELECT")return;
    $("status").textContent=phone.status();
    const raw=phone.initial();
    if(raw!==saved){const current=pages[selected];load(raw);selected=Math.max(0,pages.indexOf(current));render();}
  },1500);
}
render();
