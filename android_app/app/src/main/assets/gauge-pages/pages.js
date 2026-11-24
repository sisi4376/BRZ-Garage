"use strict";
const $ = id => document.getElementById(id);
const names = ["温度","信息","图表","指针","挡位 + 转速","车速","油量","行程历史","行程总览","自定义 A","自定义 B","自定义 C"];
const signals = ["CLT 水温","IAT 进气温度","OIL 机油温度","LOD 发动机负荷","TPS 节气门","RPM 转速","SPD 车速","BAT 电压","OIP 机油压力","BKT 刹车温度","BST 增压压力","AFR 空燃比","IGN 点火提前角"];
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
const canvas = document.querySelector("#dial canvas");
const img = document.querySelector("#dial img");
const ctx = canvas.getContext("2d");
const side = canvas.width;
// These rectangles partition a full LVGL framebuffer. Every pixel, including
// labels, units, scales and the bezel, comes directly from the native renderer.
const fullFrame = [0, 0, side, side];
const frameCells = [
  [[0, 0, side, 203], [0, 203, side, 70], [0, 273, side, 193]],
  [[0, 0, 233, 197], [233, 0, 233, 197], [0, 197, 233, 104],
   [233, 197, 233, 104], [0, 301, side, 165]],
  [fullFrame],
  [fullFrame]
];
let drawRequest = 0;
let atlasPage = -1;
let atlasReady;

function loadAtlas(page) {
  if (page !== atlasPage) {
    atlasPage = page;
    const image = new Image();
    atlasReady = new Promise((resolve, reject) => {
      image.onload = () => resolve(image);
      image.onerror = reject;
    });
    image.src = "lvgl-" + page + ".png";
  }
  return atlasReady;
}

async function draw(id) {
  const request = ++drawRequest;
  $("previewError").hidden = true;
  const fixed = (id >= 4 && id <= 8) || id === 12;
  img.hidden = !fixed;
  canvas.hidden = fixed;
  if (fixed) {
    img.src = "page-" + id + ".png";
    return;
  }

  const page = id >= 9 ? 0 : id;
  const sources = slots[id].map(slot => items[slot]);
  ctx.clearRect(0, 0, side, side);
  try {
    const atlas = await loadAtlas(page);
    if (request !== drawRequest) return;
    frameCells[page].forEach(([x, y, width, height], slot) => {
      const source = sources[slot];
      const left = source % 4 * side;
      const top = Math.floor(source / 4) * side;
      ctx.drawImage(atlas, left + x, top + y, width, height, x, y, width, height);
    });
  } catch {
    if (request !== drawRequest) return;
    atlasPage = -1;
    $("previewError").hidden = false;
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
