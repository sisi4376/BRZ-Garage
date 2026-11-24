"use strict";
const $ = id => document.getElementById(id);
const names = ["三槽位信息","五槽位信息","图表","指针","挡位 + 转速","车速","油量","行程历史","行程总览"];
const systemPage = 85;
const maxPages = 20;
const signals = ["CLT 水温","IAT 进气温度","OIL 机油温度","LOD 发动机负荷","TPS 节气门","RPM 转速","SPD 车速","BAT 电压","OIP 机油压力","BKT 刹车温度","BST 增压压力","AFR 空燃比","IGN 点火提前角"];
const defaults = [[0,1,2],[0,2,3,4,1],[8],[0],[],[],[],[],[]];
const baseSlots = [[0,1,2],[3,4,5,6,7],[9],[8],[],[],[],[],[]];
const factory = "4,5,0,1,3,2,6,8,7/0,1,2,0,2,3,4,1,0,8,0,2,8,5,6,4,7,10,11";

function typeOf(id) {
  return id < 9 || id === systemPage ? id : Math.floor((id - 9) / 19);
}
function storageSlot(id) {
  return id < 9 ? id : 9 + (id - 9) % 19;
}
function fieldsOf(id) {
  if (id === systemPage) return [];
  if (id < 9) return baseSlots[id];
  const start = 10 + (storageSlot(id) - 9) * 5;
  return defaults[typeOf(id)].map((_, i) => start + i);
}
const phone = window.GaugePhone;
function stored() {
  try { return localStorage.getItem("gauge-layout"); } catch { return null; }
}
function parse(raw) {
  const parts = raw.split("/");
  if (parts.length !== 2) throw Error("invalid layout");
  const order = parts[0] === "" ? [] : parts[0].split(",").map(Number);
  let fields = parts[1].split(",").map(Number);
  if (fields.length === 19) {
    const previous = fields;
    fields = previous.slice(0, 10).concat(Array(95).fill(0));
    for (let i = 0; i < 9; i++)
      fields[10 + Math.floor(i / 3) * 5 + i % 3] = previous[10 + i];
  }
  if (order.length > maxPages || new Set(order.map(storageSlot)).size !== order.length ||
      !order.every(id => Number.isInteger(id) && id >= 0 && id < systemPage) ||
      fields.length !== 105 || !fields.every(id => Number.isInteger(id) && signals[id]))
    throw Error("invalid layout");
  return [order, fields];
}
let pages, items, selected = systemPage, saved = "";
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
  [[0, 0, side, 194], [0, 194, side, 91], [0, 285, side, 181]],
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
  const fixed = (id >= 4 && id <= 8) || id === systemPage;
  img.hidden = !fixed;
  canvas.hidden = fixed;
  if (fixed) {
    img.src = "page-" + (id === systemPage ? 12 : id) + ".png";
    return;
  }

  const page = typeOf(id);
  const sources = fieldsOf(id).map(slot => items[slot]);
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
function titleOf(id) {
  if (id === systemPage) return "版本与设置";
  const type = typeOf(id);
  const copies = pages.filter(page => typeOf(page) === type);
  return names[type] + (copies.length > 1 ? " " + (copies.indexOf(id) + 1) : "");
}

function render() {
  const order = [systemPage, ...pages];
  if (!order.includes(selected)) selected = systemPage;
  const fixed = selected === systemPage;
  const title = titleOf(selected);
  const slots = fieldsOf(selected);
  draw(selected);
  $("title").textContent = title;
  $("position").textContent = (order.indexOf(selected) + 1) + " / " + order.length;
  $("status").textContent = phone ? phone.status() : "交互预览 · 更改保存在当前浏览器";
  $("pages").replaceChildren();
  order.forEach((id, i) => {
    const row = document.createElement("button");
    row.className = "row" + (selected === id ? " active" : "");
    const number = document.createElement("i"), label = document.createElement("span"), hint = document.createElement("small");
    number.textContent = String(i + 1).padStart(2, "0");
    label.textContent = titleOf(id);
    const fields = fieldsOf(id);
    hint.textContent = id === systemPage ? "固定首位" : fields.length ?
      fields.map(slot => signals[items[slot]].split(" ")[0]).join(" · ") : "专用表盘";
    row.append(number, label, hint);
    row.onclick = () => { selected = id; render(); };
    $("pages").append(row);
  });

  $("edit").hidden = fixed;
  $("editTitle").textContent = title + " · 页面设置";
  $("items").replaceChildren();
  slots.forEach((slot, i) => {
    const label = document.createElement("label");
    label.className = "slot";
    label.textContent = "项目 " + (i + 1);
    const picker = document.createElement("select");
    signals.forEach((name, j) => picker.append(option(name, j)));
    picker.value = items[slot];
    picker.onchange = () => { items[slot] = Number(picker.value); save(); };
    label.append(picker);
    $("items").append(label);
  });
  if (!fixed && slots.length === 0) {
    const p = document.createElement("p");
    p.textContent = "此专用页面使用固件预设的显示项目。";
    $("items").append(p);
  }
  const full = pages.length === maxPages;
  $("add").replaceChildren(option(full ? "已达 20 个可编辑页" : "＋ 添加页面", ""));
  names.forEach((name, type) => {
    if (type < 4 || !pages.includes(type)) $("add").append(option(name, type));
  });
  $("add").disabled = full;
  $("up").disabled = fixed || selected === pages[0];
  $("down").disabled = fixed || selected === pages[pages.length - 1];
  $("remove").disabled = fixed;
}

function move(delta) {
  const i = pages.indexOf(selected), j = i + delta;
  if (i < 0 || j < 0 || j >= pages.length) return;
  [pages[i], pages[j]] = [pages[j], pages[i]];
  save();
}
function step(delta) {
  const order = [systemPage, ...pages];
  selected = order[(order.indexOf(selected) + delta + order.length) % order.length];
  render();
}
function addPage(type) {
  if (!Number.isInteger(type) || !names[type] || pages.length >= maxPages) return;
  let id = type;
  if (pages.includes(type)) {
    if (type >= 4) return;
    const used = new Set(pages.map(storageSlot));
    let slot = 0;
    while (used.has(9 + slot) && slot < 19) slot++;
    if (slot === 19) return;
    id = 9 + type * 19 + slot;
  }
  fieldsOf(id).forEach((slot, i) => { items[slot] = defaults[type][i]; });
  pages.push(id);
  selected = id;
  save();
}
$("prev").onclick = () => step(-1);
$("next").onclick = () => step(1);
$("up").onclick = () => move(-1);
$("down").onclick = () => move(1);
$("remove").onclick = () => {
  const i = pages.indexOf(selected);
  if (i < 0) return;
  pages.splice(i, 1);
  selected = pages[i] ?? pages[pages.length - 1] ?? systemPage;
  save();
};
$("add").onchange = e => { if (e.target.value !== "") addPage(Number(e.target.value)); };
let startX,startY;
$("dial").onpointerdown=e=>{startX=e.clientX;startY=e.clientY;e.currentTarget.setPointerCapture(e.pointerId);};
$("dial").onpointerup=e=>{const dx=e.clientX-startX;if(Math.abs(dx)>30 && Math.abs(dx)>Math.abs(e.clientY-startY))step(dx<0?1:-1);};
if(phone){
  $("back").hidden=false;$("back").onclick=()=>phone.back();
  setInterval(()=>{
    if(document.activeElement?.tagName==="SELECT")return;
    $("status").textContent=phone.status();
    const raw=phone.initial();
    if(raw!==saved){load(raw);render();}
  },1500);
}
render();
