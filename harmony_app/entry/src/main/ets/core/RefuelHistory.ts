import { Row } from './Models';
import { RefuelMeta } from './Gauge';
import { need } from './Garage';

export interface RefuelWindow { meta: RefuelMeta; rows: Row[]; }
export interface RefuelMutation { id: number; discard: boolean; revision: number; issued?: boolean; }
const metrics: string[] = ['duration_s', 'distance_m', 'fuel_ml'];
function id(row: Row): number { return Number(row.interval_id); }
export function sameWindow(a: RefuelMeta, b: RefuelMeta): boolean {
  return a.version === b.version && a.count === b.count && a.oldest === b.oldest && a.newest === b.newest && a.revision === b.revision;
}
export function validateWindow(gauge: string, window: RefuelWindow): void {
  const m = window.meta, rows = window.rows;
  need(rows.length === m.count && m.count >= 0 && m.count <= 20, '加油历史窗口不完整');
  need(rows.every((r: Row, i: number) => r.device_id === gauge && (i === 0 || id(rows[i - 1]) < id(r))), '加油历史顺序异常');
  need(rows.length === 0 ? m.oldest === 0 && m.newest === 0 : id(rows[0]) === m.oldest && id(rows[rows.length - 1]) === m.newest, '加油历史边界不一致');
}
// Raw gauge records and displayed records must stay separate: their delta contains phone-only edits.
export function reconcileRefuel(gauge: string, local: Row[], previous: RefuelWindow | undefined,
  incoming: RefuelWindow, mutation: RefuelMutation | null = null): Row[] {
  validateWindow(gauge, incoming);
  const m = incoming.meta, rows = incoming.rows, raw = previous?.rows || [];
  const ids = new Set<number>(rows.map(id)), removed = new Set<number>(), carried = new Set<number>();
  const revisionChanged = previous !== undefined && previous.meta.revision !== m.revision;
  const oldNewest = raw.reduce((v: number, r: Row) => Math.max(v, id(r)), 0);
  raw.forEach((old: Row) => {
    if (!ids.has(id(old)) && (m.count > 0 && id(old) >= m.oldest ||
      revisionChanged && (m.count === 0 || m.newest <= oldNewest) || rows.some((r: Row) => Number(r.start_epoch_s) > 0 &&
        Number(old.start_epoch_s) >= Number(r.start_epoch_s) && Number(old.end_epoch_s) > Number(r.start_epoch_s) && Number(old.end_epoch_s) <= Number(r.end_epoch_s)))) removed.add(id(old));
  });
  local.forEach((r: Row) => { if (m.count > 0 && id(r) >= m.oldest && id(r) <= m.newest && !ids.has(id(r))) removed.add(id(r)); });
  if (mutation && !ids.has(mutation.id)) removed.add(mutation.id);
  const output = local.map((r: Row): Row => JSON.parse(JSON.stringify(r)) as Row);
  rows.forEach((record: Row) => {
    const contributions = raw.filter((old: Row) => id(old) === id(record) || removed.has(id(old)) &&
      !(mutation?.discard && mutation.id === id(old)) && id(old) < id(record) && rows.find((r: Row) => id(r) > id(old))?.interval_id === record.interval_id &&
      (mutation?.id === id(old) || Number(record.start_epoch_s) > 0 && Number(old.start_epoch_s) >= Number(record.start_epoch_s) && Number(old.end_epoch_s) <= Number(record.end_epoch_s)));
    const edited = contributions.filter((old: Row) => local.find((r: Row) => id(r) === id(old))?.data_revised === 1);
    const before = local.find((r: Row) => id(r) === id(record));
    const value: Row = JSON.parse(JSON.stringify(record)) as Row;
    if (edited.length) {
      edited.forEach((old: Row) => carried.add(id(old)));
      value.start_epoch_s = Math.min(Number(record.start_epoch_s), ...edited.map((old: Row) => Number(local.find((r: Row) => id(r) === id(old))!.start_epoch_s)));
      metrics.forEach((key: string) => value[key] = Math.max(0, Number(record[key]) + edited.reduce((sum: number, old: Row) => sum + Number(local.find((r: Row) => id(r) === id(old))![key]) - Number(old[key]), 0)));
      value.data_revised = 1;
    } else if (before?.data_revised === 1 && !raw.some((r: Row) => id(r) === id(record))) {
      value.start_epoch_s = Math.min(Number(record.start_epoch_s), Number(before.start_epoch_s));
      metrics.forEach((key: string) => value[key] = before[key]); value.data_revised = 1;
    }
    const index = output.findIndex((r: Row) => id(r) === id(record));
    if (index < 0) output.push(value); else output[index] = value;
  });
  removed.forEach((removedId: number) => {
    if (carried.has(removedId) || mutation?.discard && mutation.id === removedId) return;
    const edited = local.find((r: Row) => id(r) === removedId), baseline = raw.find((r: Row) => id(r) === removedId);
    need(edited?.data_revised !== 1 || baseline !== undefined && [...metrics, 'start_epoch_s'].every((k: string) => edited[k] === baseline[k]), '修订数据尚无已完成的后续区间承接，已保留历史，稍后重试');
  });
  return output.filter((r: Row) => !removed.has(id(r)));
}
export function deleteArchived(local: Row[], node: number, discard: boolean): Row[] {
  const rows: Row[] = JSON.parse(JSON.stringify(local)) as Row[];
  const old = rows.find((r: Row) => id(r) === node);
  need(!!old, '节点已变化，请刷新后重试');
  if (discard) need(node === Math.min(...rows.map(id)), '只能删除第一个节点以前的数据');
  else {
    const next = rows.filter((r: Row) => id(r) > node).sort((a: Row, b: Row) => id(a) - id(b))[0];
    need(!!next, '没有可承接的后续区间');
    next.start_epoch_s = old!.start_epoch_s;
    metrics.forEach((key: string) => next[key] = Number(next[key]) + Number(old![key])); next.data_revised = 1;
  }
  return rows.filter((r: Row) => id(r) !== node);
}
