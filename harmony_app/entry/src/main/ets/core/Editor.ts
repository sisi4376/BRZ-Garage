import { Row, Snapshot } from './Models';
import { need, numeric, parseDay, dayText, today, upsert, reviseTrip, splitTrip } from './Garage';
export interface Field { key: string; label: string; value: string; }
export function localTime(epoch: number): string { const d = new Date(epoch * 1000); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().substring(0, 19); }
export function epoch(text: string): number { const n = Date.parse(text); need(Number.isFinite(n), '日期时间无效'); return Math.floor(n / 1000); }
export function fields(table: string, row: Row | null): Field[] {
  const r: Row = row || {}; const out: Field[] = [];
  const add = (key: string, label: string, value: string): void => { out.push({ key, label, value }); };
  if (table === 'fuel' || table === 'expenses') add('date', '日期 YYYY-MM-DD', r.date_epoch_day === undefined ? today() : dayText(Number(r.date_epoch_day)));
  if (table === 'fuel') {
    add('odometer', '里程表 km', String(r.odometer_km ?? '')); add('litres', '加油量 L', String(r.litres ?? '')); add('cost', '金额 元', String(r.cost ?? ''));
    add('full', '是否加满（1 是 / 0 否）', String(r.full_tank ?? 1)); add('draft', '草稿（1 是 / 0 否）', String(r.draft ?? 0));
  } else if (table === 'expenses') {
    add('category', '类别（1 保养 / 2 日常）', String(r.category ?? 2)); add('title', '项目名称（保养可填 更换发动机油 等）', String(r.title ?? ''));
    add('amount', '金额 元', String(r.amount ?? '')); add('odometer', '里程 km（可留空）', r.odometer_km === null ? '' : String(r.odometer_km ?? ''));
    add('maintenance', '保养分类（1 A保 / 2 B保 / 3 轮胎 / 4 维修；可空）', r.maintenance_type === null ? '' : String(r.maintenance_type ?? '')); add('note', '备注', String(r.note ?? ''));
  } else {
    if (table === 'custom') add('name', '自定义区间名称', String(r.name ?? ''));
    if (table === 'trips' || table === 'custom') { add('start', '开始时间 YYYY-MM-DDTHH:mm:ss', localTime(Number(r.start_epoch_s || Date.now() / 1000))); add('end', '结束时间 YYYY-MM-DDTHH:mm:ss', localTime(Number(r.end_epoch_s || Date.now() / 1000))); }
    if (table === 'split') {
      const end = (Number(r.flags) & 1) ? Number(r.end_epoch_s) : Math.floor(Date.now()/1000);
      const start = (Number(r.flags) & 1) ? Number(r.start_epoch_s) : end - Number(r.duration_s);
      const middle = start + Math.max(1, Math.floor((end-start)/2));
      add('firstStart','第一段开始',localTime(start)); add('firstEnd','第一段结束',localTime(middle));
      add('secondStart','第二段开始',localTime(middle)); add('secondEnd','第二段结束',localTime(end));
    }
    add('distance', (table === 'split' ? '第一段' : '') + '距离 km', String(Number(r.distance_m || 0) / (table === 'split' ? 2000 : 1000)));
    add('duration', (table === 'split' ? '第一段' : '') + '驾驶时长 秒', String(Math.floor(Number(r.duration_s || 0) / (table === 'split' ? 2 : 1))));
    add('fuel', (table === 'split' ? '第一段' : '') + '耗油 L', String(Number(r.fuel_ml || 0) / (table === 'split' ? 2000 : 1000)));
    if (table === 'trips') {
      add('average', '平均油耗 L/100km（留空自动计算）', String(Number(r.avg_l100_x100 || 0) / 100));
      add('speed', '最高车速 km/h（可留空）', r.max_speed_kmh === null ? '' : String(r.max_speed_kmh ?? ''));
      add('rpm', '最高转速 rpm（可留空）', r.max_rpm === null ? '' : String(r.max_rpm ?? ''));
      add('accel', '最大加速度 m/s²（可留空）', r.max_accel_x100 === null ? '' : String(Number(r.max_accel_x100 || 0) / 100));
      add('decel', '最大减速度 m/s²（负数，可留空）', r.max_decel_x100 === null ? '' : String(Number(r.max_decel_x100 || 0) / 100));
    }
  } return out;
}
export function applyEdit(s: Snapshot, table: string, old: Row | null, values: Field[], id: string, gauge: string, mode: string = 'all'): void {
  const v: Record<string, string> = {}; values.forEach((f: Field) => v[f.key] = f.value.trim());
  const number = (key: string, label: string, max: number = 1e12): number => numeric(v[key], label, max);
  let row: Row;
  if (table === 'fuel') {
    need(['0', '1'].includes(v.full) && ['0', '1'].includes(v.draft), '加满和草稿请填 0 或 1');
    const litres = v.litres === '' ? 0 : number('litres', '加油量', 1000); const draft = v.litres === '' || litres <= 0 || v.cost === '';
    row = { transfer_id: old?.transfer_id ?? id, date_epoch_day: parseDay(v.date), odometer_km: number('odometer', '里程'), litres,
      cost: v.cost === '' ? 0 : number('cost', '金额'), full_tank: Number(v.full), draft: draft ? 1 : 0 };
  } else if (table === 'expenses') {
    need(v.category === '1' || v.category === '2', '类别请填 1 或 2'); need(v.title.length > 0, '请输入项目名称');
    need(v.maintenance === '' || ['1', '2', '3', '4'].includes(v.maintenance), '保养分类请填 1 至 4');
    row = { transfer_id: old?.transfer_id ?? id, device_id: old?.device_id ?? (gauge || 'local'), date_epoch_day: parseDay(v.date), category: Number(v.category),
      title: v.title, amount: number('amount', '金额'), odometer_km: v.odometer === '' ? null : number('odometer', '里程'), note: v.note,
      maintenance_type: v.category === '1' && v.maintenance !== '' ? Number(v.maintenance) : null };
  } else {
    const distance = Math.round(number('distance', '距离', 50000) * 1000), duration = number('duration', '时长', 31536000), fuel = Math.round(number('fuel', '耗油', 5000) * 1000);
    need(Number.isInteger(duration), '驾驶时长请填整数秒');
    if (table === 'split') { need(old !== null, '未选择行程'); splitTrip(s, old!, epoch(v.firstEnd), distance, duration, fuel, [epoch(v.firstStart),epoch(v.firstEnd),epoch(v.secondStart),epoch(v.secondEnd)]); return; }
    if (table === 'trips') { need(old !== null, '未选择行程');
      if(mode==='time'){const start=epoch(v.start),end=epoch(v.end);need(start>=1704067200&&end>=start&&end<=4102444800,'起止时间无效');upsert(s,'trips',{...old!,start_epoch_s:start,end_epoch_s:end,time_revised:1,flags:Number(old!.flags)|1});return;}
      if(mode==='data')upsert(s,'trips',{...old!,distance_m:distance,duration_s:duration,fuel_ml:fuel,data_revised:1,avg_l100_x100:distance>0?Math.round(fuel/distance*10000):0});
      else reviseTrip(s, old!, distance, duration, fuel, epoch(v.start), epoch(v.end));
      const result = s.tables.trips.find((r: Row) => r.device_id === old!.device_id && r.trip_id === old!.trip_id)!;
      if (v.average) result.avg_l100_x100 = Math.round(number('average', '平均油耗', 100) * 100);
      result.max_speed_kmh = v.speed ? number('speed', '最高速度', 500) : null;
      result.max_rpm = v.rpm ? number('rpm', '最高转速', 20000) : null;
      result.max_accel_x100 = v.accel ? Math.round(number('accel', '最大加速度', 30) * 100) : null;
      const decel = v.decel ? Number(v.decel) : null;
      need(decel === null || Number.isFinite(decel) && decel >= -30 && decel <= 0, '减速度应为 -30 至 0');
      result.max_decel_x100 = decel === null ? null : Math.round(decel * 100); upsert(s, 'trips', result); return; }
    if (table === 'refuel') { need(old !== null, '未选择区间'); row = { ...old!, distance_m: distance, duration_s: duration, fuel_ml: fuel, data_revised: 1 }; }
    else { const start = epoch(v.start), end = epoch(v.end); need(end >= start && duration <= end - start, '起止时间或驾驶时长无效');
      row = { transfer_id: old?.transfer_id ?? id, device_id: old?.device_id ?? gauge, name: Array.from(v.name).slice(0, 8).join(''), start_epoch_s: start, end_epoch_s: end, distance_m: distance, duration_s: duration, fuel_ml: fuel }; }
  }
  upsert(s, table, row);
}
