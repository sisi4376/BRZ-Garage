export type Cell = string | number | boolean | null;
export type Row = Record<string, Cell>;
export interface Preference { type: string; value: Cell; }
export type DeviceState = Record<string, Cell>;
export interface Snapshot {
  tables: Record<string, Row[]>;
  preferences: Record<string, Preference>;
  devices: Record<string, DeviceState>;
  gauge: string;
  createdAt: number;
}
export const TABLES: string[] = ['trips', 'deleted_trips', 'fuel', 'expenses', 'refuel', 'custom',
  'fuel_deleted', 'expenses_deleted', 'custom_deleted'];
export function emptySnapshot(gauge: string = ''): Snapshot {
  const tables: Record<string, Row[]> = {};
  TABLES.forEach((name: string) => { tables[name] = []; });
  return { tables, preferences: {}, devices: {}, gauge, createdAt: Date.now() };
}
export function cloneSnapshot(snapshot: Snapshot): Snapshot {
  return JSON.parse(JSON.stringify(snapshot)) as Snapshot;
}
export function rowKey(table: string, row: Row): string {
  if (table === 'trips' || table === 'deleted_trips') return row.device_id + '|' + row.trip_id;
  if (table === 'refuel') return row.device_id + '|' + row.interval_id;
  return String(row.transfer_id);
}
export function tripFamily(row: Row): string {
  const id = Number(row.trip_id);
  return row.device_id + '|' + (id < 0 ? Math.floor((-id - 1) / 2) : id);
}
export function rowEqual(a: Row, b: Row): boolean {
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every((key: string) => a[key] === b[key]);
}
export function displayTripId(id: number): string {
  if (id > 0) return String(id);
  return Math.floor((-id - 1) / 2) + ((-id) % 2 === 1 ? '-A' : '-B');
}
export interface MergeReport {
  snapshot: Snapshot; added: number; updated: number; removed: number; unchanged: number; conflicts: number;
}
export interface ScanItem { address: string; name: string; rssi: number; }
export interface TripMeta {
  version: number; pending: number; overflow: boolean; oldest: number; newest: number;
  lastAck: number; capacity: number; recordSize: number;
}
export interface Vehicle {
  epoch: number; uptime: number; fuelPercent: number | null; currentDistance: number;
  currentDuration: number; currentFuel: number; totalDistance: number; totalDuration: number;
  totalFuel: number; rpm: number | null; speed: number | null; average: number | null; currentStart: number;
  maxSpeed?: number | null; maxRpm?: number | null; maxAccel?: number | null; maxDecel?: number | null; fuelSequence?: number | null;
}
