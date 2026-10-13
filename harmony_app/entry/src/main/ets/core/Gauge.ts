import { Row } from './Models';
import { crc16 } from './Protocol';
import { utf8Decode } from './Archive';
import { need } from './Garage';
import { GaugeTransport } from './Sync';
export interface RefuelMeta { version: number; count: number; oldest: number; newest: number; revision: number; start: number; duration: number; distance: number; fuel: number; }
export interface GaugeSettings { values: Row; brightness: number; profile: number; threshold: number | null; discardSupported: boolean; }
export interface Odometer { enabled: boolean; calibrated: boolean; metres: number; }
export interface FirmwareInfo { build?: string; version: string; project: string; board: string; variant: string; lcd: string; w: number; h: number; bpp: number; flash: number; slots: number; }
export function view(b: Uint8Array): DataView { return new DataView(b.buffer, b.byteOffset, b.length); }
function u64(d: DataView, p: number): number { const n = d.getUint32(p, true) + d.getUint32(p + 4, true) * 4294967296; need(Number.isSafeInteger(n), '仪表数值溢出'); return n; }
function checked(b: Uint8Array, n: number): DataView {
  need(b.length === n && view(b).getUint16(2, true) === n && view(b).getUint16(n - 2, true) === crc16(b, n - 2), '仪表数据长度或 CRC 错误'); return view(b);
}
function finish(b: Uint8Array): Uint8Array { view(b).setUint16(b.length - 2, crc16(b, b.length - 2), true); return b; }
export function settingPacket(value: number, profile: boolean = false): Uint8Array {
  need(Number.isInteger(value) && (profile ? value === 1 || value === 4 : value >= 10 && value <= 100), '仪表设置值无效');
  return finish(Uint8Array.of(1, 1, value, 6, 0, 0));
}
export function odometerPacket(enabled: boolean, metres: number | null = null): Uint8Array {
  need(metres === null || (Number.isFinite(metres) && metres >= 0 && metres <= 999999900), '总里程超出范围');
  const b = new Uint8Array(12); b.set([1, metres === null ? 1 : 2, enabled ? 1 : 0, 12]);
  view(b).setUint32(4, metres === null ? 0 : Math.round(metres / 100), true); return finish(b);
}
export function refuelPacket(command: number, value: number = 0): Uint8Array {
  need(Number.isInteger(value) && value >= 0 && value <= 0xffffffff &&
    (command === 1 || command === 2 && value === 0 || command === 3 && value > 0 ||
    command === 4 && value >= 5000 && value <= 20000 && value % 1000 === 0 || command === 5 && value > 0), '加油区间命令无效');
  const b = new Uint8Array(10); b.set([1, command, 10, 0]); view(b).setUint32(4, value, true); return finish(b);
}
export function customPacket(values: number[]): Uint8Array {
  need(values.length === 3 && values.every((v: number) => Number.isInteger(v) && v >= 0 && v <= 0xffffffff), '自定义区间基线超过仪表范围');
  const b = new Uint8Array(20); b.set([1, 1, 20, 0]); values.forEach((v: number, i: number) => view(b).setUint32(4 + i * 4, v, true)); return finish(b);
}
export function parseRefuelMeta(b: Uint8Array): RefuelMeta {
  const d = checked(b, 36); need((b[0] === 1 || b[0] === 2) && b[1] <= 20, '加油区间协议不支持');
  const m: RefuelMeta = { version: b[0], count: b[1], oldest: d.getUint32(4, true), newest: d.getUint32(8, true),
    start: u64(d, 12), duration: d.getUint32(20, true), distance: d.getUint32(24, true), fuel: d.getUint32(28, true), revision: d.getUint16(32, true) };
  need(m.count === 0 || m.oldest >= 1 && m.newest >= m.oldest, '加油区间游标无效'); return m;
}
export function parseRefuel(gauge: string, b: Uint8Array): Row {
  const d = checked(b, 48); need(b[0] === 1 && d.getUint32(4, true) > 0, '加油区间记录无效');
  return { device_id: gauge, interval_id: d.getUint32(4, true), start_epoch_s: u64(d, 8), end_epoch_s: u64(d, 16),
    duration_s: d.getUint32(24, true), distance_m: d.getUint32(28, true), fuel_ml: d.getUint32(32, true),
    odometer_x10_km: d.getUint32(36, true), detected_added_ml: d.getUint16(40, true), flags: b[1], data_revised: 0 };
}
export async function readRefuel(gauge: string, t: GaugeTransport): Promise<Row[]> {
  const m = parseRefuelMeta(await t.read(11)), rows: Row[] = []; let cursor = 0;
  for (let i = 0; i < m.count; i++) {
    await t.write(12, refuelPacket(1, cursor)); const row = parseRefuel(gauge, await t.read(13));
    need(Number(row.interval_id) > cursor && Number(row.interval_id) <= m.newest, '加油区间游标异常'); rows.push(row); cursor = Number(row.interval_id);
  }
  need(m.count === 0 || cursor === m.newest, '加油区间历史不完整'); return rows;
}
export function parseSettings(b: Uint8Array): GaugeSettings {
  need(b[0] === 1 && b.length === 104 || b[0] === 2 && b.length === 106, '设置协议不支持');
  const d = checked(b, b.length); need((b[1] & 1) !== 0, '设置数据标识无效');
  const names = ['协议', '主题', '默认页面', '亮度', '车型', '设备角色', '开机动画', '设备位置', '转速报警动画', '联动报警', 'RaceChrono', '启动模式'];
  const values: Row = {}; names.forEach((n: string, i: number) => values[n] = b[4 + i]);
  ['刹车温度报警', '机油压力报警×10', '转速报警', '行程合并分钟'].forEach((n: string, i: number) => values[n] = d.getUint16(16 + 2 * i, true));
  values['温度页显示项目'] = Array.from(b.slice(24, 27)).join(', '); values['信息页显示项目'] = Array.from(b.slice(27, 32)).join(', ');
  values['指针项目'] = b[32]; values['图表项目'] = b[33];
  values['OBD 地址'] = Array.from(b.slice(34, 40)).map((n: number) => n.toString(16).padStart(2, '0')).join(':');
  values['主机地址'] = Array.from(b.slice(40, 46)).map((n: number) => n.toString(16).padStart(2, '0')).join(':');
  values['OBD 名称'] = utf8Decode(b.slice(46, 78)).split('\0')[0];
  values['图表报警值'] = Array.from({ length: 12 }, (_: unknown, i: number) => d.getInt16(78 + i * 2, true)).join(', ');
  return { values, brightness: b[7], profile: b[8], threshold: b[0] === 2 ? d.getUint16(102, true) : null, discardSupported: (b[1] & 4) !== 0 };
}
export function parseOdometer(b: Uint8Array): Odometer {
  const d = checked(b, 12); need(b[0] === 1 && (b[1] & 252) === 0 && d.getUint16(8, true) === 0 && d.getUint32(4, true) <= 9999999, '总里程回读无效');
  return { enabled: (b[1] & 1) !== 0, calibrated: (b[1] & 2) !== 0, metres: d.getUint32(4, true) * 100 };
}
interface Manifest { device: { board: string; variant: string; lcd: string; screen: { w: number; h: number; bpp: number }; flash_mb: number; ota_slots: number }; firmware: { version: string; project: string; build?: string }; }
export function parseFirmware(text: string): FirmwareInfo {
  const root = JSON.parse(text.replace(/\0+$/, '')) as Manifest;
  const d = root.device, f = root.firmware;
  const result: FirmwareInfo = { version: f.version, build:f.build, project: f.project, board: d.board, variant: d.variant, lcd: d.lcd,
    w: d.screen.w, h: d.screen.h, bpp: d.screen.bpp, flash: d.flash_mb, slots: d.ota_slots };
  need(typeof result.version === 'string' && result.version.length > 0, '固件信息无效'); return result;
}
export function sameHardware(a: FirmwareInfo, b: FirmwareInfo): boolean {
  return a.project === b.project && a.board === b.board && a.variant === b.variant && a.lcd === b.lcd &&
    a.w === b.w && a.h === b.h && a.bpp === b.bpp && a.flash === b.flash && a.slots === b.slots && a.slots >= 2;
}
export function compareVersion(a: string, b: string): number {
  const x = a.split('.').map((v: string) => parseInt(v) || 0), y = b.split('.').map((v: string) => parseInt(v) || 0);
  for (let i = 0; i < Math.max(x.length, y.length); i++) { const diff = (x[i] || 0) - (y[i] || 0); if (diff !== 0) return diff; } return 0;
}
