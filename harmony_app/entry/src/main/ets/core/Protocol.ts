import { Row, TripMeta, Vehicle } from './Models';

export const INFO_SERVICE = '00001ffa-0000-1000-8000-00805f9b34fb';
export function characteristic(id: number): string {
  return id.toString(16).padStart(8, '0') + '-0000-1000-8000-00805f9b34fb';
}
export function crc16(bytes: Uint8Array, length: number = bytes.length): number {
  let crc = 0xffff;
  for (let i = 0; i < length; i++) {
    crc ^= bytes[i] << 8;
    for (let bit = 0; bit < 8; bit++) crc = ((crc & 0x8000) ? (crc << 1) ^ 0x1021 : crc << 1) & 0xffff;
  }
  return crc;
}
function view(bytes: Uint8Array): DataView { return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength); }
function u64(data: DataView, offset: number): number {
  const value = data.getUint32(offset, true) + data.getUint32(offset + 4, true) * 4294967296;
  if (!Number.isSafeInteger(value)) throw new Error('仪表数值超出安全范围');
  return value;
}
function checkCrc(bytes: Uint8Array, length: number): DataView {
  if (bytes.length !== length || crc16(bytes, length - 2) !== view(bytes).getUint16(length - 2, true))
    throw new Error('仪表数据长度或校验不正确，未确认记录');
  return view(bytes);
}
export function timePacket(epoch: number): Uint8Array {
  if (!Number.isInteger(epoch) || epoch < 1704067200 || epoch > 4102444800) throw new Error('请先校准手机系统时间');
  const result = new Uint8Array(8);
  view(result).setUint32(0, epoch, true);
  return result;
}
export function clockValue(bytes: Uint8Array): number {
  if (bytes.length !== 8) throw new Error('授时回读长度不正确');
  return u64(view(bytes), 0);
}
export function controlPacket(command: number, cursor: number): Uint8Array {
  if ((command !== 1 && command !== 2) || !Number.isInteger(cursor) || cursor < 0 || cursor > 0xffffffff)
    throw new Error('同步游标无效');
  const bytes = new Uint8Array(5); bytes[0] = command; view(bytes).setUint32(1, cursor, true); return bytes;
}
export function parseMeta(bytes: Uint8Array): TripMeta {
  if (bytes.length !== 20) throw new Error('行程元数据长度不正确');
  const d = view(bytes);
  const meta: TripMeta = { version: bytes[0], pending: bytes[1], overflow: bytes[2] !== 0,
    oldest: d.getUint32(4, true), newest: d.getUint32(8, true), lastAck: d.getUint32(12, true),
    capacity: d.getUint16(16, true), recordSize: d.getUint16(18, true) };
  if (!((meta.version === 1 && meta.recordSize === 40) ||
    ((meta.version === 2 || meta.version === 3) && meta.recordSize === 48) ||
    (meta.version === 4 && meta.recordSize === 56)) || meta.pending > meta.capacity ||
    (meta.pending > 0 && (meta.oldest < 1 || meta.oldest > meta.newest)) ||
    (meta.newest === 0 && meta.oldest !== 0)) throw new Error('不支持或无效的仪表行程协议');
  return meta;
}
export function parseTrip(gauge: string, bytes: Uint8Array): Row {
  const version = bytes[0];
  const length = version === 1 ? 40 : version === 2 ? 48 : version === 3 ? 56 : 0;
  if (!length) throw new Error('不支持的行程记录版本');
  const d = checkCrc(bytes, length);
  if (d.getUint16(2, true) !== length || d.getUint32(4, true) === 0) throw new Error('行程记录头无效');
  const detail = version >= 2 && (d.getUint16(38, true) !== 0 || d.getUint16(40, true) !== 0 ||
    d.getInt16(42, true) !== 0 || d.getInt16(44, true) !== 0);
  return { device_id: gauge, trip_id: d.getUint32(4, true), start_epoch_s: u64(d, 8), end_epoch_s: u64(d, 16),
    duration_s: d.getUint32(24, true), distance_m: d.getUint32(28, true), fuel_ml: d.getUint32(32, true),
    avg_l100_x100: d.getUint16(36, true), flags: bytes[1], time_revised: 0, data_revised: 0,
    max_speed_kmh: detail ? d.getUint16(38, true) : null, max_rpm: detail ? d.getUint16(40, true) : null,
    max_accel_x100: detail ? d.getInt16(42, true) : null, max_decel_x100: detail ? d.getInt16(44, true) : null,
    poll_requested: version >= 3 ? d.getUint32(46, true) : 0,
    poll_received: version >= 3 ? d.getUint32(50, true) : 0 };
}
export function mergeGaugeTrip(old: Row | null, incoming: Row): Row {
  if (!old) return incoming;
  const result: Row = {};
  Object.keys(incoming).forEach((key: string) => { result[key] = incoming[key]; });
  if (old.time_revised === 1) {
    ['start_epoch_s', 'end_epoch_s', 'time_revised'].forEach((key: string) => { result[key] = old[key]; });
    result.flags = Number(incoming.flags) | Number(old.flags) | 1;
  }
  if (old.data_revised === 1) {
    ['duration_s', 'distance_m', 'fuel_ml', 'avg_l100_x100', 'max_speed_kmh', 'max_rpm', 'max_accel_x100',
      'max_decel_x100', 'data_revised'].forEach((key: string) => { result[key] = old[key]; });
  }
  result.poll_requested = (Number(old.poll_requested) | Number(incoming.poll_requested)) >>> 0;
  result.poll_received = (Number(old.poll_received) | Number(incoming.poll_received)) >>> 0;
  return result;
}
export function parseVehicle(bytes: Uint8Array): Vehicle {
  const length = bytes[0] === 1 ? 64 : bytes[0] === 2 ? 84 : 0;
  const d = checkCrc(bytes, length);
  if (!length || d.getUint16(2, true) !== length) throw new Error('车辆状态版本不支持');
  const flags = bytes[1]; const level = d.getUint16(16, true);
  return { epoch: u64(d, 4), uptime: d.getUint32(12, true), fuelPercent: (flags & 1) && level <= 10000 ? level / 100 : null,
    currentDistance: d.getUint32(20, true), currentDuration: d.getUint32(24, true), currentFuel: d.getUint32(28, true),
    totalDistance: u64(d, 32), totalDuration: u64(d, 40), totalFuel: u64(d, 48),
    average: (flags & 8) && d.getUint16(18, true) > 0 && d.getUint16(18, true) <= 6000 ? d.getUint16(18, true) / 100 : null,
    maxSpeed:length===84&&d.getUint16(60,true)>0?d.getUint16(60,true):null,
    maxRpm:length===84&&d.getUint16(62,true)>0?d.getUint16(62,true):null,
    maxAccel:length===84&&d.getInt16(64,true)!==0?d.getInt16(64,true):null,
    maxDecel:length===84&&d.getInt16(66,true)!==0?d.getInt16(66,true):null,
    fuelSequence:length===84?d.getUint32(76,true):null,
    currentStart: length === 84 ? u64(d, 68) : 0, rpm: (flags & 2) ? d.getUint16(56, true) : null, speed: (flags & 2) ? d.getUint16(58, true) : null };
}
