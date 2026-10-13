import { GaugeTransport } from './Sync';
import { Row } from './Models';
import { parseRefuel, parseRefuelMeta, refuelPacket, customPacket, parseSettings } from './Gauge';
import { RefuelWindow, RefuelMutation, sameWindow, validateWindow } from './RefuelHistory';
import { CustomRequest, CustomReceipt, customConfirmedPacket, parseCustomReceipt } from './CustomTrip';
import { need } from './Garage';

export interface IntervalStore {
  commitRefuel(gauge: string, rows: Row[], window: RefuelWindow): Promise<void>;
  refuelRows(gauge: string): Row[];
  pendingRefuel(gauge: string): RefuelMutation | null;
  clearRefuel(gauge: string, id: number): Promise<void>;
  queueRefuel(gauge: string, mutation: RefuelMutation): Promise<void>;
  deleteArchivedRefuel(gauge: string, id: number, discard: boolean): Promise<void>;
  customRequest(gauge: string): CustomRequest | null;
  updateCustom(gauge: string, request: CustomRequest, receipt: CustomReceipt | null, status: string): Promise<void>;
}
const delay = (ms: number): Promise<void> => new Promise<void>((resolve) => setTimeout(resolve, ms));
export async function readRefuelWindow(gauge: string, transport: GaugeTransport): Promise<RefuelWindow> {
  const before = parseRefuelMeta(await transport.read(11)), rows: Row[] = []; let cursor = 0;
  for (let i = 0; i < before.count; i++) {
    await transport.write(12, refuelPacket(1, cursor));
    const row = parseRefuel(gauge, await transport.read(13));
    need(Number(row.interval_id) > cursor && Number(row.interval_id) >= before.oldest && Number(row.interval_id) <= before.newest, '加油历史游标异常');
    rows.push(row); cursor = Number(row.interval_id);
  }
  const after = parseRefuelMeta(await transport.read(11));
  need(sameWindow(before, after), '加油历史读取期间发生变化，已保留原记录，请重试');
  const window: RefuelWindow = { meta: after, rows }; validateWindow(gauge, window); return window;
}
export async function syncRefuel(gauge: string, transport: GaugeTransport, store: IntervalStore): Promise<RefuelWindow> {
  const window = await readRefuelWindow(gauge, transport);
  await store.commitRefuel(gauge, window.rows, window); return window;
}
export async function mutateRefuel(gauge: string, transport: GaugeTransport, store: IntervalStore, command: number, id: number): Promise<void> {
  const before = await syncRefuel(gauge, transport, store);
  if (command !== 2) {
    const oldIntent = store.pendingRefuel(gauge);
    need(!oldIntent || oldIntent.id === id && oldIntent.discard === (command === 5), '另一个加油节点操作等待确认，请先同步');
    const rows = store.refuelRows(gauge), target = rows.find((r: Row) => r.interval_id === id);
    if (!target && oldIntent?.id === id) { await store.clearRefuel(gauge, id); return; }
    need(!!target, '节点已变化，请刷新后重试');
    if (command === 5) need(id === Math.min(...rows.map((r: Row) => Number(r.interval_id))), '只能删除第一个节点以前的数据');
    if (before.meta.count === 0 || id < before.meta.oldest) {
      await store.deleteArchivedRefuel(gauge, id, command === 5); return;
    }
    need(before.rows.some((r: Row) => r.interval_id === id), '节点不在仪表保留窗口中，请刷新');
    need(before.meta.version >= 2, '旧固件不支持节点删除');
    if (command === 5) need(id === before.meta.oldest && parseSettings(await transport.read(7)).discardSupported, '仪表尚不支持删除首节点之前的数据');
    if (command === 3 && id === before.meta.newest) need(target!.data_revised !== 1, '最近节点有手机修订数据，请等待产生下一个加油区间后再删除');
    await store.queueRefuel(gauge, { id, discard: command === 5, revision: before.meta.revision, issued: true });
  }
  await transport.write(12, refuelPacket(command, id));
  for (let i = 0; i < 5; i++) {
    await delay(400);
    const after = await syncRefuel(gauge, transport, store);
    if (command === 2 ? after.meta.newest !== before.meta.newest : after.meta.revision !== before.meta.revision && !after.rows.some((r: Row) => r.interval_id === id)) return;
  }
  throw new Error('仪表尚未确认变更，已保存待核对操作，请重新同步');
}
export async function syncCustom(gauge: string, transport: GaugeTransport, store: IntervalStore): Promise<string> {
  const request = store.customRequest(gauge); if (!request) return '';
  if (!transport.has(14)) return request.pending ? '当前起点已在手机保存，仪表需升级后才能同步。' : '';
  try {
    if (!transport.readable(14)) {
      await transport.write(14, customPacket(request.values));
      await store.updateCustom(gauge, request, null, 'legacy_sent');
      return '当前起点已发送，旧固件不支持保存确认。';
    }
    await transport.write(14, customConfirmedPacket(request));
    for (let i = 0; i < 5; i++) {
      await delay(300); const receipt = parseCustomReceipt(await transport.read(14));
      if (store.customRequest(gauge)?.generation !== request.generation) return '新的自定义起点等待同步。';
      if (receipt.durable && receipt.token === request.token && receipt.values.every((n: number, index: number) => n <= request.values[index])) {
        await store.updateCustom(gauge, request, receipt, 'confirmed'); return '';
      }
    }
    await store.updateCustom(gauge, request, null, 'pending');
    return '当前起点已在手机保存，等待仪表持久化确认。';
  } catch (error) {
    await store.updateCustom(gauge, request, null, 'pending'); throw error as Error;
  }
}
