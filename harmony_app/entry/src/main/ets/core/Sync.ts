import { Row, Vehicle } from './Models';
import { parseMeta, parseTrip, controlPacket, timePacket, clockValue, parseVehicle } from './Protocol';

export interface GaugeTransport {
  has(id: number): boolean;
  readable(id: number): boolean;
  read(id: number): Promise<Uint8Array>;
  write(id: number, bytes: Uint8Array): Promise<void>;
}
export interface TripSink {
  cursor(gauge: string): number;
  commitTrips(gauge: string, rows: Row[], cursor: number): Promise<void>;
}
export interface SyncResult { saved: number; clockVerified: boolean; warning: string; vehicle: Vehicle | null; }
export async function synchronize(gauge: string, transport: GaugeTransport, sink: TripSink,
  progress: (message: string) => void): Promise<SyncResult> {
  progress('正在校准仪表时间');
  await transport.write(2, timePacket(Math.floor(Date.now() / 1000)));
  let verified = false;
  if (transport.readable(2)) {
    const epoch = clockValue(await transport.read(2));
    if (Math.abs(epoch - Date.now() / 1000) > 5) throw new Error('仪表时间回读校验失败');
    verified = true;
  }
  const meta = parseMeta(await transport.read(3));
  const localCursor = sink.cursor(gauge);
  let cursor = meta.version >= 3 ? localCursor : Math.max(localCursor, meta.lastAck);
  let warning = '';
  if (meta.version < 3) warning = '旧固件只能读取尚未确认的行程；更早记录请从旧手机导入。';
  if (meta.overflow || (meta.oldest > cursor + 1)) warning += ' 仪表历史可能已覆盖，请合并旧手机数据包补齐。';
  if (meta.newest < localCursor && (meta.version >= 3 || meta.pending > 0)) throw new Error('仪表行程编号已回退，请先导出备份并检查仪表是否重置');
  const rows: Row[] = [];
  if (meta.pending > 0 || meta.version >= 3) {
    while (cursor < meta.newest) {
      if (rows.length >= meta.capacity) throw new Error('仪表记录数量超过容量，未确认本次数据');
      await transport.write(4, controlPacket(1, cursor));
      const bytes = await transport.read(5);
      if (bytes.length !== meta.recordSize) throw new Error('行程记录与元数据版本不一致');
      const row = parseTrip(gauge, bytes);
      const id = Number(row.trip_id);
      if (id <= cursor || id > meta.newest) throw new Error('行程游标异常，未确认本次数据');
      if (id > cursor + 1) warning = '仪表中存在历史缺口，请合并旧手机数据包补齐。';
      rows.push(row); cursor = id; progress('已读取 ' + rows.length + ' 条行程，正在保存');
    }
  }
  // No ACK can be emitted until the database has durably accepted this exact cursor.
  await sink.commitTrips(gauge, rows, cursor);
  if (cursor > 0 && (rows.length > 0 || meta.lastAck < cursor)) await transport.write(4, controlPacket(2, cursor));
  let vehicle: Vehicle | null = null;
  if (transport.has(6)) {
    try { vehicle = parseVehicle(await transport.read(6)); }
    catch (error) { warning += ' 车辆快照读取失败，行程已保存。'; }
  }
  return { saved: rows.length, clockVerified: verified, warning: warning.trim(), vehicle };
}
