import { Cell, Row, Snapshot, Vehicle, cloneSnapshot, rowKey } from './Models';
import { DELETIONS, preferenceType, validate } from './Transfer';

export interface Metric { label: string; value: number; detail: string; recordId?: string; }
export function need(ok: boolean, message: string): void { if (!ok) throw new Error(message); }
export function numeric(text: string, label: string, max: number = 1e12): number {
  need(text.trim().length > 0, label + '不能为空'); const n = Number(text);
  need(Number.isFinite(n) && n >= 0 && n <= max, label + '超出有效范围'); return n;
}
export function dayText(day: number): string { return new Date(day * 86400000).toISOString().substring(0, 10); }
export function parseDay(text: string): number {
  need(/^\d{4}-\d{2}-\d{2}$/.test(text), '日期格式应为 YYYY-MM-DD');
  const day = Date.parse(text + 'T00:00:00Z') / 86400000;
  need(Number.isInteger(day) && day >= -36525 && day <= 47482 && dayText(day) === text, '日期无效'); return day;
}
export function today(): string { const d = new Date(); return dayText(Math.floor((d.getTime() - d.getTimezoneOffset() * 60000) / 86400000)); }
export function sequence(row: Row): number { const id = Number(row.trip_id); return id < 0 ? Math.floor((-id - 1) / 2) : id; }
export function pref(s: Snapshot, key: string, fallback: Cell = ''): Cell { return s.preferences[key]?.value ?? fallback; }
export function putPref(s: Snapshot, key: string, value: Cell): void {
  const type = preferenceType(key); need(type !== null, '不支持的设置'); s.preferences[key] = { type: type!, value };
}
export function upsert(s: Snapshot, table: string, row: Row): void {
  need(['trips', 'fuel', 'expenses', 'custom', 'refuel'].includes(table), '记录类型不支持编辑');
  const key = rowKey(table, row), index = s.tables[table].findIndex((r: Row) => rowKey(table, r) === key);
  if (index < 0) s.tables[table].push(row); else s.tables[table][index] = row;
  if (DELETIONS[table]) s.tables[DELETIONS[table]] = s.tables[DELETIONS[table]].filter((r: Row) => rowKey(table, r) !== key);
  validate(s);
}
export function removeRow(s: Snapshot, table: string, row: Row, now: number = Math.floor(Date.now() / 1000)): void {
  need(!!DELETIONS[table], '此记录需通过仪表操作删除');
  const key = rowKey(table, row); need(s.tables[table].some((r: Row) => rowKey(table, r) === key), '记录已不存在');
  s.tables[table] = s.tables[table].filter((r: Row) => rowKey(table, r) !== key);
  const tomb: Row = table === 'trips' ? { device_id: row.device_id, trip_id: row.trip_id, deleted_at_s: now } : { transfer_id: row.transfer_id, deleted_at_s: now };
  s.tables[DELETIONS[table]] = s.tables[DELETIONS[table]].filter((r: Row) => rowKey(table, r) !== key).concat([tomb]);
  validate(s);
}
export function reviseTrip(s: Snapshot, row: Row, distance: number, duration: number, fuel: number, start: number, end: number): void {
  need(end >= start && start > 0 && end <= 0xffffffff, '结束时间不能早于开始时间');
  need(duration <= end - start, '驾驶时长不能超过起止时间范围');
  const result: Row = { ...row, distance_m: distance, duration_s: duration, fuel_ml: fuel, start_epoch_s: start,
    end_epoch_s: end, time_revised: start !== row.start_epoch_s || end !== row.end_epoch_s ? 1 : row.time_revised, data_revised: 1, flags: Number(row.flags) | 1,
    avg_l100_x100: distance > 0 ? Math.min(65535, Math.round(fuel / distance * 10000)) : 0 };
  upsert(s, 'trips', result);
}
export function splitTrip(s: Snapshot, row: Row, at: number, distanceA: number, durationA: number, fuelA: number, times: number[] | null = null): void {
  need(Number(row.trip_id) > 0, '已拆分行程不能再次拆分');
  const start = times ? times[0] : Number(row.start_epoch_s), firstEnd = times ? times[1] : at;
  const secondStart = times ? times[2] : at, end = times ? times[3] : Number(row.end_epoch_s);
  need([start, firstEnd, secondStart, end].every((v: number) => Number.isInteger(v)) && start >= 1704067200 && end <= 4102444800 &&
    firstEnd >= start && secondStart >= firstEnd && end >= secondStart, '请检查两段时间，时间不能倒序或重叠');
  need(distanceA >= 1 && distanceA < Number(row.distance_m) && durationA >= 1 && durationA < Number(row.duration_s) &&
    fuelA >= 0 && fuelA <= Number(row.fuel_ml), '第一段数值必须小于原行程总值，时长和里程需大于 0');
  const tolerance = times ? 59 : 0;
  need(durationA <= firstEnd - start + tolerance && Number(row.duration_s) - durationA <= end - secondStart + tolerance, '每段起止时间跨度不能短于该段驾驶时长');
  removeRow(s, 'trips', row);
  for (let i = 0; i < 2; i++) {
    const distance = i === 0 ? distanceA : Number(row.distance_m) - distanceA;
    const fuel = i === 0 ? fuelA : Number(row.fuel_ml) - fuelA;
    const child: Row = { ...row, trip_id: -(Number(row.trip_id) * 2 + i + 1), start_epoch_s: i === 0 ? start : secondStart,
      end_epoch_s: i === 0 ? firstEnd : end, distance_m: distance, fuel_ml: fuel,
      duration_s: i === 0 ? durationA : Number(row.duration_s) - durationA, data_revised: 1, time_revised: 1, flags: Number(row.flags) | 1,
      avg_l100_x100: distance > 0 ? Math.min(10000, Math.round(fuel / distance * 10000)) : 0,
      max_speed_kmh: null, max_rpm: null, max_accel_x100: null, max_decel_x100: null };
    upsert(s, 'trips', child);
  }
}
export function mileage(s: Snapshot, gauge: string): number {
  const base = s.preferences['odometer_calibration_m_' + gauge]?.value;
  const anchor = Number(pref(s, 'odometer_anchor_trip_' + gauge, 0));
  return Number(base ?? 0) + s.tables.trips.filter((r: Row) => (!gauge || r.device_id === gauge) && (base === undefined || base === null || sequence(r) > anchor))
    .reduce((sum: number, r: Row) => sum + Number(r.distance_m), 0);
}
export function calibrate(s: Snapshot, gauge: string, metres: number): void {
  need(Number.isInteger(metres) && metres >= 0 && metres <= 999999900, '里程校准值无效');
  putPref(s, 'odometer_calibration_m_' + gauge, metres);
  putPref(s, 'odometer_anchor_trip_' + gauge, s.tables.trips.filter((r: Row) => r.device_id === gauge).reduce((v: number, r: Row) => Math.max(v, sequence(r)), 0));
}
export function fuelAnalysis(rows: Row[]): Metric[] {
  const ordered = rows.filter((r: Row) => r.draft === 0 && Number(r.litres) > 0).slice()
    .sort((a: Row, b: Row) => Number(a.odometer_km) - Number(b.odometer_km) || Number(a.date_epoch_day) - Number(b.date_epoch_day));
  const points: Metric[] = []; let previous: number | null = null, litres = 0, distance = 0;
  ordered.forEach((r: Row) => {
    if (previous === null) { if (r.full_tank === 1) previous = Number(r.odometer_km); return; }
    const delta = Number(r.odometer_km) - previous; if (delta <= 0) return;
    previous = Number(r.odometer_km); litres += Number(r.litres); distance += delta;
    if (r.full_tank === 1) { points.push({ label: dayText(Number(r.date_epoch_day)), value: litres / distance * 100,
      recordId: String(r.transfer_id), detail: litres + '|' + distance }); litres = 0; distance = 0; }
  }); return points;
}
export function fuelAverage(rows: Row[]): number | null {
  let litres = 0, distance = 0; fuelAnalysis(rows).forEach((p: Metric) => { const pair = p.detail.split('|'); litres += Number(pair[0]); distance += Number(pair[1]); });
  return distance > 0 ? litres / distance * 100 : null;
}
export function effectiveFuel(s: Snapshot, v: Vehicle | null): number | null {
  if(v?.fuelPercent!==null && v?.fuelPercent!==undefined)return v.fuelPercent;
  const manual=pref(s,'manual_fuel_percent','');if(manual==='')return null;if(!v)return Number(manual);
  const base=Number(pref(s,'manual_fuel_baseline_ml',v.totalFuel));
  return Math.max(0,Math.min(100,Number(manual)-Math.max(0,v.totalFuel-(base<0||v.totalFuel<base?v.totalFuel:base))/(Number(pref(s,'tank','50'))*10)));
}
export function rangeConsumption(s: Snapshot, gauge: string, v: Vehicle | null): number | null {
  const source = String(pref(s, 'range_consumption_source', 'gauge_history'));
  let consumption: number | null = null;
  if (source === 'fuel_records') consumption = fuelAverage(s.tables.fuel);
  else if (source === 'recent_five') {
    const rows = s.tables.trips.filter((r: Row) => r.device_id === gauge && Number(r.distance_m) > 0 && Number(r.fuel_ml) > 0 && Number(r.avg_l100_x100) > 0)
      .sort((a: Row, b: Row) => sequence(b) - sequence(a) || Number(a.trip_id) - Number(b.trip_id)).slice(0, 5);
    const distance = rows.reduce((n: number, r: Row) => n + Number(r.distance_m), 0);
    if (distance > 0) consumption = rows.reduce((n: number, r: Row) => n + Number(r.fuel_ml), 0) / distance * 100;
  } else consumption = v?.average ?? null;
  return consumption;
}
export function rangeKm(s: Snapshot, gauge: string, v: Vehicle | null): number | null {
  const tank = Number(pref(s, 'tank', '50')), factor = Number(pref(s, 'range_correction_factor', '1.06')), percent = effectiveFuel(s,v);
  const source = String(pref(s, 'range_consumption_source', 'gauge_history')), consumption=rangeConsumption(s,gauge,v);
  if (percent === null || percent < 0 || percent > 100 || tank < 1 || tank > 150 || !consumption || consumption <= 0) return null;
  return Math.round(tank * percent / (consumption * (source === 'fuel_records' ? 1 : factor >= 0.5 && factor <= 2 ? factor : 1.06)));
}
export function monthlyCosts(s: Snapshot): Metric[] {
  const months = new Map<string, number>();
  for (const table of ['fuel', 'expenses']) s.tables[table].forEach((r: Row) => {
    if (table === 'fuel' && r.draft === 1) return;
    const key = dayText(Number(r.date_epoch_day)).substring(0, 7);
    months.set(key, (months.get(key) || 0) + Number(table === 'fuel' ? r.cost : r.amount));
  });
  return Array.from(months.keys()).sort().map((m: string) => ({ label: m, value: months.get(m)!, detail: '元' }));
}
export function drivingDays(s: Snapshot, gauge: string, month: string): Metric[] {
  const days = new Map<string, number>();
  s.tables.trips.filter((r: Row) => (!gauge || r.device_id === gauge) && (Number(r.flags) & 1) !== 0).forEach((r: Row) => {
    const date = new Date(Number(r.start_epoch_s) * 1000); const key = dayText(Math.floor((date.getTime() - date.getTimezoneOffset() * 60000) / 86400000));
    if (key.startsWith(month)) days.set(key, (days.get(key) || 0) + Number(r.distance_m) / 1000);
  }); return Array.from(days.keys()).sort().map((d: string) => ({ label: d, value: days.get(d)!, detail: 'km' }));
}
export function customStats(s: Snapshot, gauge: string, v: Vehicle): number[] {
  return ['distance', 'duration', 'fuel'].map((key: string, i: number) => Math.max(0,
    [v.totalDistance, v.totalDuration, v.totalFuel][i] - Number(pref(s, 'custom_base_' + key + '_' + gauge, [v.totalDistance, v.totalDuration, v.totalFuel][i]))));
}
export function resetCustom(s: Snapshot, gauge: string, v: Vehicle, name: string, id: string, end: number = Math.floor(Date.now() / 1000)): void {
  const now = end, values = customStats(s, gauge, v);
  if (s.preferences['custom_base_distance_' + gauge]) upsert(s, 'custom', { transfer_id: id, device_id: gauge,
    name: String(pref(s, 'custom_trip_name_' + gauge)), start_epoch_s: Math.max(0, Math.min(now, Math.floor(Number(pref(s, 'custom_reset_at_' + gauge, (now - values[1]) * 1000)) / 1000))),
    end_epoch_s: now, duration_s: values[1], distance_m: values[0], fuel_ml: values[2] });
  putPref(s, 'custom_trip_name_' + gauge, Array.from(name.trim()).slice(0, 8).join(''));
  putPref(s, 'custom_reset_at_' + gauge, now * 1000);
  ['distance', 'duration', 'fuel'].forEach((k: string, i: number) => putPref(s, 'custom_base_' + k + '_' + gauge, [v.totalDistance, v.totalDuration, v.totalFuel][i]));
}

export function plateText(text: string): string {
  const s = text.toUpperCase().replace(/[\s·•.\-]/g, '');
  need(s.length === 7 && '京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新'.includes(s[0]) && /^[A-Z]$/.test(s[1]), '请输入省份简称、发牌字母和五位序号，共 7 位');
  need(/^[A-HJ-NP-Z0-9]{5}$/.test(s.substring(2)) && s.substring(2).replace(/[0-9]/g, '').length <= 2, '序号不使用 I/O，且最多两位字母');
  return s.substring(0, 2) + '·' + s.substring(2);
}
export function expenseBreakdown(s: Snapshot, category: number): Metric[] {
  const result = new Map<string, number>();
  s.tables.expenses.filter((r: Row) => r.category === category).forEach((r: Row) => result.set(String(r.title), (result.get(String(r.title)) || 0) + Number(r.amount)));
  return Array.from(result.keys()).map((k: string) => ({ label: k, value: result.get(k)!, detail: '元' })).sort((a: Metric, b: Metric) => b.value - a.value);
}
export function reconcileDeletedNode(s: Snapshot, gauge: string, id: number, discard: boolean, before: number, after: number): void {
  if (s.preferences['refuel_history_revision_' + gauge]?.value !== before) return;
  const rows = s.tables.refuel.filter((r: Row) => r.device_id === gauge).sort((a: Row, b: Row) => Number(a.interval_id) - Number(b.interval_id));
  const old = rows.find((r: Row) => r.interval_id === id), next = rows.find((r: Row) => Number(r.interval_id) > id);
  if (old && next && !discard) {
    next.start_epoch_s = old.start_epoch_s;
    ['distance_m', 'duration_s', 'fuel_ml'].forEach((k: string) => next[k] = Number(old[k]) + Number(next[k]));
    next.data_revised = old.data_revised === 1 || next.data_revised === 1 ? 1 : 0;
  }
  s.tables.refuel = s.tables.refuel.filter((r: Row) => r.device_id !== gauge || r.interval_id !== id);
  putPref(s, 'refuel_history_revision_' + gauge, after);
}
