import { Snapshot, Vehicle } from './Models';
import { pref, putPref, resetCustom, need } from './Garage';
import { crc16 } from './Protocol';
import { view } from './Gauge';

export interface CustomRequest { generation: string; token: number; values: number[]; pending: boolean; status: string; }
export interface CustomReceipt { token: number; values: number[]; durable: boolean; }
export function newCustomRequest(s: Snapshot, gauge: string): CustomRequest {
  const token = Math.max(1, Math.floor(Math.random() * 4294967296));
  return { generation: Date.now().toString(16) + '-' + token.toString(16) + '-' + Math.random().toString(16).substring(2), token,
    values: ['distance', 'duration', 'fuel'].map((key: string) => Number(pref(s, 'custom_base_' + key + '_' + gauge, 0))), pending: true, status: 'pending' };
}
export function sameCustomRequest(a: CustomRequest | undefined, b: CustomRequest): boolean {
  return a !== undefined && a.generation === b.generation && a.token === b.token && a.values.every((v: number, i: number) => v === b.values[i]);
}
export function archiveCustom(s: Snapshot, gauge: string, v: Vehicle, name: string, id: string, at: number): void {
  need(gauge.length > 0 && [v.totalDistance, v.totalDuration, v.totalFuel].every((n: number) => Number.isInteger(n) && n >= 0 && n <= 0xffffffff), '自定义行程累计值无效');
  const end = v.epoch >= 1704067200 && v.epoch <= 4102444800 ? v.epoch : Math.floor(at / 1000);
  need(end > 0, '没有可用的车辆快照时间');
  resetCustom(s, gauge, v, name, id, end);
}
export function customConfirmedPacket(request: CustomRequest): Uint8Array {
  need(request.token > 0 && request.token <= 0xffffffff && request.values.length === 3 && request.values.every((n: number) => Number.isInteger(n) && n >= 0 && n <= 0xffffffff), '自定义行程请求无效');
  const bytes = new Uint8Array(20); bytes[0] = 2; bytes[1] = 1;
  request.values.forEach((n: number, i: number) => view(bytes).setUint32(2 + i * 4, n, true));
  view(bytes).setUint32(14, request.token, true); view(bytes).setUint16(18, crc16(bytes, 18), true); return bytes;
}
export function parseCustomReceipt(bytes: Uint8Array): CustomReceipt {
  need(bytes.length === 20 && bytes[0] === 2 && bytes[1] <= 1 && view(bytes).getUint16(18, true) === crc16(bytes, 18), '自定义行程保存回执校验失败');
  return { durable: bytes[1] === 1, token: view(bytes).getUint32(14, true), values: [2, 6, 10].map((p: number) => view(bytes).getUint32(p, true)) };
}
export function applyCustomReceipt(s: Snapshot, gauge: string, request: CustomRequest, receipt: CustomReceipt): void {
  need(receipt.durable && receipt.token === request.token && receipt.values.every((n: number, i: number) => n <= request.values[i]), '仪表尚未保存当前起点');
  ['distance', 'duration', 'fuel'].forEach((key: string, i: number) => putPref(s, 'custom_base_' + key + '_' + gauge, receipt.values[i]));
}
