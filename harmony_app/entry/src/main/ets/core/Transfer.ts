import { Cell, Row, Snapshot, Preference, DeviceState, TABLES, emptySnapshot, cloneSnapshot, rowKey, rowEqual, tripFamily, MergeReport } from './Models';

export const FORMAT = 'brz-garage-transfer';
export const MAX_DATA = 32 * 1024 * 1024;
export const GAUGE = /^[0-9A-F]{2}(:[0-9A-F]{2}){5}$/;
// Archive selection is independent from local Bluetooth permissions and binding.
export function archiveGauge(s: Snapshot): string {
  if (GAUGE.test(s.gauge)) return s.gauge;
  const candidates = Object.keys(s.devices).concat(Object.keys(s.preferences).map((key: string) => key.slice(-17)));
  Object.keys(s.tables).forEach((table: string) => s.tables[table].forEach((row: Row) => candidates.push(String(row.device_id || ''))));
  const gauges = Array.from(new Set(candidates.filter((value: string) => GAUGE.test(value))));
  return gauges.length === 1 ? gauges[0] : '';
}
type Column = { kind: string; min: number; max: number; nullable: boolean };
const n: Column = { kind: 'number', min: 0, max: 0xffffffff, nullable: false };
const text: Column = { kind: 'string', min: 0, max: 8192, nullable: false };
const real: Column = { kind: 'real', min: 0, max: 1e12, nullable: false };
function optional(c: Column): Column { return { kind: c.kind, min: c.min, max: c.max, nullable: true }; }
function integer(min: number, max: number): Column { return { kind: 'number', min, max, nullable: false }; }
const bit = integer(0, 1);
const signed = integer(-8589934592, 0xffffffff);
const interval: Record<string, Column> = { start_epoch_s: n, end_epoch_s: n, duration_s: n, distance_m: n, fuel_ml: n };
export const SCHEMAS: Record<string, Record<string, Column>> = {
  trips: { ...interval, device_id: text, trip_id: signed, avg_l100_x100: n, flags: n, time_revised: bit,
    data_revised: bit, poll_requested: n, poll_received: n, max_speed_kmh: optional(integer(0, 65535)),
    max_rpm: optional(integer(0, 65535)), max_accel_x100: optional(integer(-32768, 32767)),
    max_decel_x100: optional(integer(-32768, 32767)) },
  deleted_trips: { device_id: text, trip_id: signed, deleted_at_s: n },
  fuel: { transfer_id: text, date_epoch_day: integer(-36525, 47482), odometer_km: real, litres: real,
    cost: real, full_tank: bit, draft: bit },
  expenses: { transfer_id: text, device_id: text, date_epoch_day: integer(-36525, 47482),
    category: integer(1, 2), amount: real, title: text, note: text, odometer_km: optional(real),
    maintenance_type: optional(integer(1, 4)) },
  refuel: { ...interval, device_id: text, interval_id: n, odometer_x10_km: n, detected_added_ml: n, flags: n, data_revised: bit },
  custom: { ...interval, transfer_id: text, device_id: text, name: text },
  fuel_deleted: { transfer_id: text, deleted_at_s: n },
  expenses_deleted: { transfer_id: text, deleted_at_s: n },
  custom_deleted: { transfer_id: text, deleted_at_s: n }
};
const globalPrefs: Record<string, string> = {
  vehicle_display_name: 'string', custom_license_plate: 'string', show_custom_license_plate: 'boolean',
  show_3d_vehicle: 'boolean', selected_vehicle_profile: 'int', tank: 'string', range_consumption_source: 'string',
  range_correction_factor: 'string', manual_fuel_percent: 'string', manual_fuel_baseline_ml: 'long',
  maintenance_severe_conditions: 'boolean', refined_home_ui: 'boolean', grouped_settings_ui: 'boolean',
  'appearance.ZD8_color': 'string', 'appearance.ZD8_finish': 'string',
  'appearance.ZC6_color': 'string', 'appearance.ZC6_finish': 'string'
};
const devicePrefs: Record<string, string> = {
  show_since_refuel: 'boolean', show_custom_trip: 'boolean', custom_trip_name: 'string', custom_reset_at: 'long',
  custom_base_distance: 'long', custom_base_duration: 'long', custom_base_fuel: 'long',
  refuel_history_revision: 'int', odometer_calibration_m: 'long', odometer_anchor_trip: 'long',
  odometer_display: 'boolean', overflow: 'boolean'
};
export const DELETIONS: Record<string, string> = { trips: 'deleted_trips', fuel: 'fuel_deleted',
  expenses: 'expenses_deleted', custom: 'custom_deleted' };
const DEVICE_TYPES: Record<string, string> = {
  vehicle_data:'base64?',vehicle_epoch:'long',vehicle_uptime:'long',vehicle_fuel_percent:'real?',vehicle_current_distance:'long',
  vehicle_current_duration:'long',vehicle_current_fuel:'long',vehicle_total_distance:'long',vehicle_total_duration:'long',
  vehicle_total_fuel:'long',vehicle_rpm:'int?',vehicle_speed:'int?',vehicle_average:'real?',vehicle_max_speed:'int?',
  vehicle_max_rpm:'int?',vehicle_max_accel:'signed?',vehicle_max_decel:'signed?',vehicle_current_start:'long',vehicle_fuel_sequence:'long?',vehicle_at:'long',
  last_fuel_percent:'real?',last_fuel_at:'long',last_fuel_sequence:'long?',refuel_start:'long',refuel_duration:'long',
  refuel_distance:'long',refuel_fuel:'long',refuel_protocol:'int',refuel_meta_at:'long',gauge_settings_data:'base64?',
  gauge_settings_at:'long',firmware_version:'text?',firmware_build:'text?',firmware_project:'text?',firmware_board:'text?',
  firmware_variant:'text?',firmware_lcd:'text?',firmware_screen_w:'int?',firmware_screen_h:'int?',firmware_bpp:'int?',
  firmware_flash_mb:'int?',firmware_ota_slots:'int?',firmware_at:'long',time_at:'long',time_verified:'boolean'
};
const DEVICE_GROUPS: string[][] = [
  ['vehicle','vehicle_at'],['last_fuel','last_fuel_at'],['refuel','refuel_meta_at'],
  ['gauge_settings','gauge_settings_at'],['firmware','firmware_at'],['time','time_at']
];
export function preferenceType(key: string): string | null {
  if (Object.prototype.hasOwnProperty.call(globalPrefs, key)) return globalPrefs[key];
  const prefix = Object.keys(devicePrefs).find((p: string) => key.startsWith(p + '_') && GAUGE.test(key.substring(p.length + 1)));
  return prefix ? devicePrefs[prefix] : null;
}
function ensure(value: boolean, message: string): void { if (!value) throw new Error(message); }
function object(value: unknown): Record<string, unknown> {
  ensure(value !== null && typeof value === 'object' && !Array.isArray(value), '数据对象无效');
  return value as Record<string, unknown>;
}
function keysEqual(value: object, expected: string[]): boolean {
  const keys = Object.keys(value); return keys.length === expected.length && expected.every((k: string) => keys.includes(k));
}
function deviceValue(type: string, value: unknown): Cell {
  if (value === null) return null;
  if (type === 'boolean') {
    if (value === true || value === false) return value;
    if (value === 0 || value === '0' || value === 'false') return false;
    if (value === 1 || value === '1' || value === 'true') return true;
    return value as Cell;
  }
  if (type.startsWith('real') || type.startsWith('signed') || type.startsWith('int') || type.startsWith('long')) {
    let numberValue = value;
    if (typeof value === 'string' && /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(value)) numberValue = Number(value);
    if (typeof numberValue === 'number' && Number.isFinite(numberValue)) {
      const valid = type.startsWith('real') ? numberValue >= 0 && numberValue <= 1e12 :
        Number.isSafeInteger(numberValue) && (type.startsWith('signed') ? numberValue >= -32768 && numberValue <= 32767 : numberValue >= 0);
      if (valid) return numberValue;
    }
    // Old app builds sometimes persisted unavailable sensor values as -1 or text. They are derived cache fields;
    // keep the raw vehicle/settings packet and discard only the unusable optional projection.
    if (type.endsWith('?')) return null;
    return numberValue as Cell;
  }
  if (type.startsWith('text')) return typeof value === 'string' && value.length <= 256 ? value : (type.endsWith('?') ? null : value as Cell);
  if (type.startsWith('base64')) return typeof value === 'string' && value.length <= 16384 && /^[A-Za-z0-9+/]*={0,2}$/.test(value) ?
    value : (type.endsWith('?') ? null : value as Cell);
  return value as Cell;
}
function normalizeDevices(value: unknown): Record<string, DeviceState> {
  const source = object(value); const devices: Record<string, DeviceState> = {};
  Object.keys(source).forEach((gauge: string) => {
    const raw = object(source[gauge]);
    // Keep the v3 schema closed: normalization is only for value representations from older exporters.
    ensure(keysEqual(raw, Object.keys(DEVICE_TYPES)), '仪表缓存字段不完整：' + gauge);
    const state: DeviceState = {};
    Object.keys(DEVICE_TYPES).forEach((key: string) => { state[key] = deviceValue(DEVICE_TYPES[key], raw[key]); });
    devices[gauge] = state;
  });
  return devices;
}
export function validate(snapshot: Snapshot): void {
  ensure(Number.isSafeInteger(snapshot.createdAt) && snapshot.createdAt > 0 && snapshot.createdAt <= 253402300799999, '导出时间无效');
  ensure(typeof snapshot.gauge === 'string' && (snapshot.gauge === '' || GAUGE.test(snapshot.gauge)), '仪表标识无效');
  ensure(keysEqual(object(snapshot.tables), TABLES), '数据表不完整或版本不支持');
  let count = 0;
  TABLES.forEach((table: string) => {
    const rows = snapshot.tables[table]; ensure(Array.isArray(rows), '数据表格式无效');
    count += rows.length; ensure(count <= 100000, '数据包超过 100000 条记录上限');
    const seen = new Set<string>();
    rows.forEach((row: Row) => {
      ensure(keysEqual(object(row), Object.keys(SCHEMAS[table])), table + ' 字段不完整');
      Object.keys(SCHEMAS[table]).forEach((name: string) => {
        const col = SCHEMAS[table][name]; const value = row[name];
        if (value === null) { ensure(col.nullable, name + ' 不允许为空'); return; }
        if (col.kind === 'string') ensure(typeof value === 'string' && value.length <= col.max, name + ' 文本无效');
        else ensure(typeof value === 'number' && Number.isFinite(value) && value >= col.min && value <= col.max &&
          (col.kind === 'real' || Number.isSafeInteger(value)), name + ' 数值无效');
      });
      if ('device_id' in row) ensure(GAUGE.test(String(row.device_id)) || table === 'expenses' && row.device_id === 'local', '仪表地址无效');
      if ('transfer_id' in row) ensure(/^[0-9a-f]{32}$/.test(String(row.transfer_id)), '记录标识无效');
      if ('trip_id' in row) ensure(Number(row.trip_id) !== 0 && Number(row.trip_id) !== -1 && Number(row.trip_id) !== -2, '行程编号无效');
      if ('interval_id' in row) ensure(Number(row.interval_id) >= 1, '区间编号无效');
      const id = rowKey(table, row); ensure(!seen.has(id), '数据包内有重复记录标识'); seen.add(id);
    });
  });
  Object.keys(DELETIONS).forEach((table: string) => {
    const deleted = new Set(snapshot.tables[DELETIONS[table]].map((r: Row) => rowKey(table, r)));
    ensure(snapshot.tables[table].every((r: Row) => !deleted.has(rowKey(table, r))), '记录与删除标记矛盾');
  });
  const parents = new Set(snapshot.tables.deleted_trips.map((r: Row) => rowKey('trips', r)));
  snapshot.tables.trips.filter((r: Row) => Number(r.trip_id) < 0).forEach((r: Row) =>
    ensure(parents.has(tripFamily(r)), '拆分行程缺少原行程删除标记'));
  const preferences = object(snapshot.preferences);
  ensure(Object.keys(preferences).length <= 2048, '配置数量超限');
  Object.keys(preferences).forEach((key: string) => {
    const entry = object(preferences[key]); const type = preferenceType(key); const value = entry.value;
    ensure(type !== null && keysEqual(entry, ['type', 'value']) && entry.type === type, '不支持的配置：' + key);
    if (value === null) return;
    if (type === 'string') ensure(typeof value === 'string' && value.length <= 8192, '配置文本无效');
    else if (type === 'boolean') ensure(typeof value === 'boolean', '配置布尔值无效');
    else ensure(typeof value === 'number' && Number.isSafeInteger(value) &&
      (type === 'int' ? value >= -2147483648 && value <= 2147483647 : value >= -1), '配置整数无效或精度不支持');
  });
  const devices = object(snapshot.devices);
  ensure(Object.keys(devices).length <= 128, '仪表缓存数量超限');
  Object.keys(devices).forEach((gauge: string) => {
    ensure(GAUGE.test(gauge), '仪表缓存标识无效');
    const state = object(devices[gauge]); ensure(keysEqual(state, Object.keys(DEVICE_TYPES)), '仪表缓存字段不完整');
    Object.keys(DEVICE_TYPES).forEach((key: string) => {
      const type=DEVICE_TYPES[key],value=state[key]; if(value===null){ensure(type.endsWith('?'),'仪表缓存字段不允许为空');return;}
      const detail = '：' + gauge + ' / ' + key;
      if(type.startsWith('text'))ensure(typeof value==='string'&&value.length<=256,'仪表缓存文本无效'+detail);
      else if(type.startsWith('base64'))ensure(typeof value==='string'&&value.length<=16384&&/^[A-Za-z0-9+/]*={0,2}$/.test(value),'仪表缓存编码无效'+detail);
      else if(type==='boolean')ensure(typeof value==='boolean','仪表缓存布尔值无效'+detail);
      else if(type.startsWith('real'))ensure(typeof value==='number'&&Number.isFinite(value)&&value>=0&&value<=1e12,'仪表缓存数值无效'+detail);
      else if(type.startsWith('signed'))ensure(typeof value==='number'&&Number.isSafeInteger(value)&&value>=-32768&&value<=32767,'仪表缓存有符号整数无效'+detail);
      else ensure(typeof value==='number'&&Number.isSafeInteger(value)&&value>=0&&value<=Number.MAX_SAFE_INTEGER,'仪表缓存整数无效'+detail);
    });
  });
}
export function fromJson(dataText: string, gauge: string, createdAt: number, version: number): Snapshot {
  ensure(version === 1 || version === 2 || version === 3, '数据包版本不支持');
  const data = object(JSON.parse(dataText));
  ensure(keysEqual(data, version >= 3 ? ['tables', 'preferences', 'devices'] : ['tables', 'preferences']), '数据内容不完整');
  const tables = object(data.tables);
  ensure(keysEqual(tables, TABLES), '数据表不完整');
  if (version === 1) {
    ensure(Array.isArray(tables.expenses), '记账数据格式无效');
    (tables.expenses as unknown[]).forEach((value: unknown) => {
      const row = object(value);
      ensure(keysEqual(row, Object.keys(SCHEMAS.expenses).filter((k: string) => k !== 'maintenance_type')), 'v1 记账字段无效');
      row.maintenance_type = null;
    });
  }
  const snapshot: Snapshot = { tables: tables as Record<string, Row[]>, preferences: data.preferences as Record<string, Preference>,
    devices: version >= 3 ? normalizeDevices(data.devices) : {}, gauge, createdAt };
  validate(snapshot); return snapshot;
}
export function toJson(snapshot: Snapshot): string {
  validate(snapshot); return JSON.stringify({ tables: snapshot.tables, preferences: snapshot.preferences, devices: snapshot.devices });
}
function groupRows(snapshot: Snapshot, table: string, names: string[]): Map<string, Record<string, Row[]>> {
  const groups = new Map<string, Record<string, Row[]>>();
  names.forEach((name: string) => snapshot.tables[name].forEach((row: Row) => {
    const key = table === 'trips' ? tripFamily(row) : rowKey(table, row);
    let group = groups.get(key);
    if (!group) { group = {}; names.forEach((n: string) => { group![n] = []; }); groups.set(key, group); }
    group[name].push(row);
  }));
  return groups;
}
function groupsEqual(a: Record<string, Row[]>, b: Record<string, Row[]>, names: string[]): boolean {
  return names.every((name: string) => {
    const indexed = new Map(a[name].map((r: Row) => [rowKey(name, r), r]));
    return a[name].length === b[name].length && b[name].every((r: Row) => {
      const old = indexed.get(rowKey(name, r)); return old !== undefined && rowEqual(old, r);
    });
  });
}
export function merge(local: Snapshot, incoming: Snapshot, preferIncoming: boolean): MergeReport {
  validate(local); validate(incoming);
  const output = cloneSnapshot(local); const handled = new Set<string>(); let conflicts = 0;
  TABLES.forEach((table: string) => {
    if (handled.has(table)) return;
    const names = [table]; if (DELETIONS[table]) names.push(DELETIONS[table]);
    names.forEach((name: string) => handled.add(name));
    const a = groupRows(local, table, names); const b = groupRows(incoming, table, names);
    b.forEach((value: Record<string, Row[]>, key: string) => {
      const old = a.get(key);
      if (old && !groupsEqual(old, value, names)) { conflicts++; if (!preferIncoming) return; }
      a.set(key, value);
    });
    names.forEach((name: string) => { output.tables[name] = []; a.forEach((group: Record<string, Row[]>) => output.tables[name].push(...group[name])); });
  });
  const revisions = new Set(Object.keys(local.preferences).concat(Object.keys(incoming.preferences)));
  revisions.forEach((key: string) => {
    if (!key.startsWith('refuel_history_revision_')) return;
    const a = local.preferences[key]?.value; const b = incoming.preferences[key]?.value;
    if (a !== undefined && a !== null && b !== undefined && b !== null && a !== b) {
      conflicts++; const gauge = key.substring('refuel_history_revision_'.length);
      output.tables.refuel = output.tables.refuel.filter((r: Row) => r.device_id !== gauge)
        .concat((preferIncoming ? incoming : local).tables.refuel.filter((r: Row) => r.device_id === gauge));
    }
  });
  Object.keys(incoming.preferences).forEach((key: string) => {
    const value = incoming.preferences[key]; const old = local.preferences[key];
    if (old && old.value !== null && (old.type !== value.type || old.value !== value.value)) { conflicts++; if (!preferIncoming) return; }
    output.preferences[key] = { type: value.type, value: value.value };
  });
  Object.keys(incoming.devices).forEach((gauge: string) => {
    const a=output.devices[gauge],b=incoming.devices[gauge]; if(!a){output.devices[gauge]={...b};return;}
    const merged: DeviceState={...a};
    DEVICE_GROUPS.forEach((group: string[])=>{const prefix=group[0],stamp=group[1],atA=Number(a[stamp]||0),atB=Number(b[stamp]||0);
      const keys=Object.keys(b).filter((k:string)=>k===stamp||k.startsWith(prefix+'_'));
      if(atB===atA&&keys.some((k:string)=>a[k]!==b[k]))conflicts++;
      if(atB>atA||(atB===atA&&preferIncoming))keys.forEach((k:string)=>merged[k]=b[k]);});
    output.devices[gauge]=merged;
  });
  output.gauge = local.gauge || incoming.gauge; output.gauge = archiveGauge(output); output.createdAt = Date.now(); validate(output);
  let added = 0; let updated = 0; let removed = 0; let unchanged = 0;
  TABLES.filter((t: string) => t !== 'deleted_trips' && !t.endsWith('_deleted')).forEach((table: string) => {
    const a = new Map(local.tables[table].map((r: Row) => [rowKey(table, r), r]));
    const b = new Map(output.tables[table].map((r: Row) => [rowKey(table, r), r]));
    b.forEach((row: Row, key: string) => { const old = a.get(key); if (!old) added++; else if (!rowEqual(old, row)) updated++; else unchanged++; });
    a.forEach((_row: Row, key: string) => { if (!b.has(key)) removed++; });
  });
  return { snapshot: output, added, updated, removed, unchanged, conflicts };
}
