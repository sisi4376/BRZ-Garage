const pageNames = [
  'GEAR + RPM', 'RPM (LEGACY)', 'SPEED', 'TEMP', 'INFO', 'NEEDLE', 'CHART', 'DEVICE',
  'BLE SCAN', 'SETTINGS', 'MULTI-GAUGE', 'OBD PROTOCOL', 'LOGO', 'YELLOWLINE',
  'TEMP SOURCE', 'INFO SOURCE', 'NEEDLE SOURCE', 'CHART SOURCE', 'CHART ALARM',
  'FUEL', 'TRIP HISTORY',
];
const frame = document.getElementById('lvgl-frame');
const display = document.getElementById('display-frame');
const offline = document.getElementById('offline');
const pageName = document.getElementById('page-name');
const renderer = document.getElementById('renderer');
const source = document.getElementById('source');
let page = 3;
let dragStart = null;

function markPage(next, reportedName = null) {
  page = next;
  pageName.textContent = reportedName || pageNames[page] || 'UNKNOWN';
  document.querySelectorAll('[data-page]').forEach((button) => {
    button.classList.toggle('active', Number(button.dataset.page) === page);
  });
}

async function postNative(path, body) {
  await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

function selectPage(next) {
  markPage(next);
  return postNative('/api/native/page', { page: next });
}

function gesture(direction) {
  return postNative('/api/native/gesture', { direction });
}

document.querySelectorAll('[data-page]').forEach((button) => {
  button.addEventListener('click', () => selectPage(Number(button.dataset.page)));
});

display.addEventListener('pointerdown', (event) => {
  dragStart = { x: event.clientX, y: event.clientY, id: event.pointerId };
  display.setPointerCapture(event.pointerId);
  display.classList.add('dragging');
});

display.addEventListener('pointerup', (event) => {
  if (!dragStart || dragStart.id !== event.pointerId) return;
  const dx = event.clientX - dragStart.x;
  const dy = event.clientY - dragStart.y;
  dragStart = null;
  display.classList.remove('dragging');
  if (Math.hypot(dx, dy) < 32) return;
  const rect = display.getBoundingClientRect();
  const localX = (event.clientX - rect.left) * 466 / rect.width;
  const localY = (event.clientY - rect.top) * 466 / rect.height;
  // The headless preview has no OS pointer device. On YELLOWLINE, translate a
  // horizontal drag across the real LVGL slider into the same saved threshold.
  if (page === 13 && localY >= 220 && localY <= 280 && Math.abs(dx) > Math.abs(dy)) {
    const value = Math.max(1000, Math.min(6500,
      Math.round((1000 + (localX - 123) * 5500 / 220) / 500) * 500));
    postNative('/api/native/rpm-warn', { value });
    return;
  }
  if (Math.abs(dx) > Math.abs(dy)) gesture(dx < 0 ? 'left' : 'right');
  else gesture(dy < 0 ? 'up' : 'down');
});

display.addEventListener('pointercancel', () => {
  dragStart = null;
  display.classList.remove('dragging');
});

document.addEventListener('keydown', (event) => {
  const keys = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
  if (keys[event.key]) gesture(keys[event.key]);
});

async function pollStatus() {
  try {
    const response = await fetch('/api/native/status', { cache: 'no-store' });
    const status = await response.json();
    if (!status.online) throw new Error('offline');
    renderer.textContent = 'LVGL 8.4.0 / 固件页面 C 源码';
    source.textContent = status.source === 'elm327' ? 'ELM327 TCP（已连接）' : '动态演示（等待 ELM327）';
    if (Number.isInteger(status.page)) markPage(status.page, status.pageName);
    offline.classList.add('hidden');
  } catch (error) {
    renderer.textContent = '原生渲染器未运行';
    source.textContent = '--';
    offline.classList.remove('hidden');
  }
}

setInterval(() => { frame.src = `/lvgl_frame.bmp?t=${Date.now()}`; }, 120);
frame.addEventListener('load', () => offline.classList.add('hidden'));
markPage(3);
pollStatus();
setInterval(pollStatus, 250);
