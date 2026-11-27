"use strict";
const $ = id => document.getElementById(id);
const names = ["三槽位信息","五槽位信息","图表","指针","挡位 + 转速","车速","油量","行程历史","行程总览","四槽位信息"];
const systemPage = 85;
const quadType = 9;
const quadFirst = 86;
const copySlotCount = 19;
const maxPages = 20;
const signals = ["CLT 水温","IAT 进气温度","OIL 机油温度","LOD 发动机负荷","TPS 节气门","RPM 转速","SPD 车速","BAT 电压","OIP 机油压力","BKT 刹车温度","BST 增压压力","AFR 空燃比","IGN 点火提前角","GPS 定位"];
const defaults = [[0,1,2],[0,2,3,4,1],[8],[0],[],[],[],[],[],[0,2,5,8]];
const baseSlots = [[0,1,2],[3,4,5,6,7],[9],[8],[],[],[],[],[]];
const factory = "4,5,0,1,3,2,6,8,7/0,1,2,0,2,3,4,1,0,8,0,2,8,5,6,4,7,10,11";

function typeOf(id) {
  if (id < 9 || id === systemPage) return id;
  return id >= quadFirst ? quadType : Math.floor((id - 9) / copySlotCount);
}
function storageSlot(id) {
  return id < 9 ? id : 9 + (id >= quadFirst ? id - quadFirst : (id - 9) % copySlotCount);
}
function fieldsOf(id) {
  if (id === systemPage) return [];
  if (id < 9) return baseSlots[id];
  const start = 10 + (storageSlot(id) - 9) * 5;
  return defaults[typeOf(id)].map((_, i) => start + i);
}
function allowsGps(type, row) {
  return (type === 0 && row < 3) || (type === 1 && row === 4);
}
function gpsFieldAllowed(order, index) {
  if (index < 10) return index < 3 || index === 7;
  const field = (index - 10) % 5;
  if (field === 3) return false;
  const slot = 9 + Math.floor((index - 10) / 5);
  const page = order.find(id => id >= 9 && storageSlot(id) === slot);
  return page === undefined || allowsGps(typeOf(page), field);
}
const phone = window.GaugePhone;
function stored(key) {
  try { return localStorage.getItem(key); } catch { return null; }
}
function store(key, value) {
  try { localStorage.setItem(key, value); } catch {}
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
      !order.every(id => Number.isInteger(id) && id >= 0 && id !== systemPage && id < quadFirst + copySlotCount) ||
      fields.length !== 105 || !fields.every((id, i) => Number.isInteger(id) && signals[id] &&
        (id !== 13 || gpsFieldAllowed(order, i))))
    throw Error("invalid layout");
  return [order, fields];
}
let pages, items, selected = systemPage, saved = "";
let previewPage = systemPage;
let subpage = 0;
const subpageFrames = {7: "trip-history-2", 8: "trip-intervals"};
const portDirections = ["充电口在右侧", "充电口在上方", "充电口在左侧", "充电口在下方"];
function load(raw) {
  try { [pages,items] = parse(raw); } catch { [pages,items] = parse(factory); }
  saved = pages.join(",") + "/" + items.join(",");
}
load(phone ? phone.initial() : stored("gauge-layout") || factory);
let bootSnapshot = phone ? phone.boot() : stored("gauge-boot") || "6,0";
let [startupId, orientation] = bootSnapshot.split(",").map(Number);

function startupPage() {
  if (startupId === -1) return -1;
  return startupId === systemPage || pages.includes(startupId) ? startupId : pages[0] ?? systemPage;
}

function storeBoot() {
  store("gauge-boot", [startupId, orientation].join(","));
}

function changeBoot(command, value) {
  if (command === 2) startupId = value;
  else orientation = value;
  if (phone) phone.setBoot(command, value);
  else storeBoot();
  renderBoot();
}

function save(replacementStartup = -1) {
  saved = pages.join(",") + "/" + items.join(",");
  if (replacementStartup !== -1) startupId = replacementStartup;
  if (phone) phone.save(saved, replacementStartup);
  else {
    store("gauge-layout", saved);
    if (replacementStartup !== -1) storeBoot();
  }
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
  [[0, 0, side, 192], [0, 192, side, 86], [0, 278, side, 188]],
  [[0, 0, 233, 206], [233, 0, 233, 206], [0, 206, 233, 114],
   [233, 206, 233, 114], [0, 320, side, 146]],
  [fullFrame],
  [fullFrame]
];
frameCells[quadType] = [
  [0, 0, 233, 233], [233, 0, 233, 233], [0, 233, 233, 233], [233, 233, 233, 233]
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
    const frame = subpage && subpageFrames[id] ? subpageFrames[id] : "page-" + (id === systemPage ? 12 : id);
    img.src = frame + ".png";
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

function renderBoot() {
  const page = startupPage();
  const chosen = page === selected;
  $("startupStar").textContent = chosen ? "★" : "☆";
  $("setStartup").setAttribute("aria-pressed", chosen);
  const rows = $("pages").children;
  [systemPage, ...pages].forEach((id, index) => {
    rows[index].querySelector(".startup-marker").hidden = id !== page;
  });

  if (Number($("orientation").value) !== orientation) $("orientation").value = orientation;
  const turn = Math.max(0, orientation);
  // The physical USB port is on the right with the firmware's unrotated screen.
  $("portTrack").style.transform = "rotate(" + (-90 * (turn + 1)) + "deg)";
  const portLabel = portDirections[turn];
  $("port").setAttribute("aria-label", portLabel);
  $("port").title = portLabel;

  const message = phone?.bootStatus() || "启动页取当前预览的表盘。同步后，两项设置在仪表下次上电时生效。";
  if ($("bootStatus").textContent !== message) $("bootStatus").textContent = message;
}

function render() {
  const order = [systemPage, ...pages];
  if (!order.includes(selected)) selected = systemPage;
  if (previewPage !== selected) {
    previewPage = selected;
    subpage = 0;
  }
  const fixed = selected === systemPage;
  const canTurnVertically = Boolean(subpageFrames[selected]);
  $("dial").classList.toggle("has-subpages", canTurnVertically);
  $("subpage").hidden = !canTurnVertically;
  if (canTurnVertically) {
    $("subpage").textContent = selected === 8
      ? (subpage ? "↓ 返回行程总览" : "↑ 查看行程区间")
      : (subpage ? "↑ 上一页记录" : "↓ 下一页记录");
  }
  const title = selected === 8 && subpage ? "行程区间" : titleOf(selected);
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
    const marker = document.createElement("strong");
    marker.className = "startup-marker";
    marker.setAttribute("role", "img");
    marker.setAttribute("aria-label", "默认启动页");
    marker.title = "默认启动页";
    marker.textContent = "★";
    row.append(number, label, hint, marker);
    row.onclick = () => { selected = id; render(); };
    $("pages").append(row);
  });
  renderBoot();

  $("edit").hidden = fixed;
  $("editTitle").textContent = title + " · 页面设置";
  $("items").replaceChildren();
  slots.forEach((slot, i) => {
    const label = document.createElement("label");
    label.className = "slot";
    label.textContent = "项目 " + (i + 1);
    const picker = document.createElement("select");
    signals.forEach((name, j) => {
      if (j !== 13 || allowsGps(typeOf(selected), i)) picker.append(option(name, j));
    });
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
    const canAddQuad = type === quadType && pages.filter(id => id >= 9).length < copySlotCount;
    if (type === quadType ? canAddQuad : type < 4 || !pages.includes(type)) $("add").append(option(name, type));
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
  if (type === quadType || pages.includes(type)) {
    if (type >= 4 && type !== quadType) return;
    const used = new Set(pages.map(storageSlot));
    let slot = 0;
    while (slot < copySlotCount && used.has(9 + slot)) slot++;
    if (slot === copySlotCount) return;
    id = (type === quadType ? quadFirst : 9 + type * copySlotCount) + slot;
  }
  if (id >= 9) items.fill(0, fieldsOf(id)[0], fieldsOf(id)[0] + 5);
  if (id >= 9) items.fill(0, 10 + (storageSlot(id) - 9) * 5, 15 + (storageSlot(id) - 9) * 5);
  fieldsOf(id).forEach((slot, i) => { items[slot] = defaults[type][i]; });
  // The fixed version page is outside the editable array. Insert at page 02
  // when it is selected, otherwise use the selected page's current position.
  pages.splice(Math.max(0, pages.indexOf(selected)), 0, id);
  selected = id;
  save();
}
$("setStartup").onclick = () => {
  if (startupId !== selected) changeBoot(2, selected);
};
portDirections.forEach((name, turn) => $("orientation").append(option(name, turn)));
$("orientation").onchange = e => changeBoot(3, Number(e.target.value));

$("subpage").onclick = () => {
  subpage = 1 - subpage;
  render();
};
$("prev").onclick = () => step(-1);
$("next").onclick = () => step(1);
$("up").onclick = () => move(-1);
$("down").onclick = () => move(1);
$("remove").onclick = () => {
  const i = pages.indexOf(selected);
  if (i < 0) return;
  const wasStartup = startupPage() === selected;
  if (selected >= 9) {
    const start = 10 + (storageSlot(selected) - 9) * 5;
    items.fill(0, start, start + 5);
  }
  pages.splice(i, 1);
  selected = pages[i] ?? pages[pages.length - 1] ?? systemPage;
  save(wasStartup ? pages[0] ?? systemPage : -1);
};
$("add").onchange = e => { if (e.target.value !== "") addPage(Number(e.target.value)); };
let startX,startY;
$("dial").onpointerdown=e=>{startX=e.clientX;startY=e.clientY;e.currentTarget.setPointerCapture(e.pointerId);};
$("dial").onpointerup = e => {
  const dx = e.clientX - startX, dy = e.clientY - startY;
  if (Math.abs(dx) > 30 && Math.abs(dx) > Math.abs(dy)) {
    step(dx < 0 ? 1 : -1);
  } else if (Math.abs(dy) > 30 && Math.abs(dy) > Math.abs(dx) && subpageFrames[selected]) {
    subpage = (selected === 8 ? dy < 0 : dy > 0) ? 1 : 0;
    render();
  }
};
if (phone) {
  $("back").hidden = false;
  $("back").onclick = () => phone.back();
  setInterval(() => {
    const raw = phone.initial();
    const boot = phone.boot();
    if (boot !== bootSnapshot) {
      bootSnapshot = boot;
      [startupId, orientation] = boot.split(",").map(Number);
    }
    if (raw !== saved && document.activeElement?.tagName !== "SELECT") {
      load(raw);
      render();
    } else {
      $("status").textContent = phone.status();
      renderBoot();
    }
  }, 1500);
}
render();
