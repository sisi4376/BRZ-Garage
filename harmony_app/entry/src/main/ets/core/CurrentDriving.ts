import { Row, Vehicle } from './Models';

const MIN_TIME = 1704067200, MAX_TIME = 4102444800;
export function validTripTime(row: Row): boolean {
  const start = Number(row.start_epoch_s), end = Number(row.end_epoch_s);
  return (Number(row.flags) & 1) !== 0 && start >= MIN_TIME && start <= MAX_TIME && end >= start && end <= MAX_TIME;
}
/** Zero is reserved for this display-only row; never persist it as history. */
export function currentDrivingRecord(gauge: string, vehicle: Vehicle | null): Row | null {
  if (!gauge || !vehicle || !(vehicle.currentDistance || vehicle.currentDuration || vehicle.currentFuel)) return null;
  const estimated = vehicle.epoch - vehicle.currentDuration;
  const start = vehicle.currentStart >= MIN_TIME && vehicle.currentStart <= MAX_TIME ? vehicle.currentStart :
    vehicle.epoch >= MIN_TIME && vehicle.epoch <= MAX_TIME && estimated >= MIN_TIME ? estimated : 0;
  return { device_id: gauge, trip_id: 0, start_epoch_s: start, end_epoch_s: vehicle.epoch,
    distance_m: vehicle.currentDistance, duration_s: vehicle.currentDuration, fuel_ml: vehicle.currentFuel,
    avg_l100_x100: vehicle.currentDistance > 0 ? Math.round(vehicle.currentFuel / vehicle.currentDistance * 10000) : 0,
    flags: start > 0 && vehicle.epoch >= start && vehicle.epoch <= MAX_TIME ? 1 : 0,
    max_speed_kmh: vehicle.maxSpeed ?? null, max_rpm: vehicle.maxRpm ?? null,
    max_accel_x100: vehicle.maxAccel ?? null, max_decel_x100: vehicle.maxDecel ?? null };
}
export function archivedCurrentDriving(archived: Row, current: Row): boolean {
  return archived.device_id === current.device_id && Number(archived.trip_id) !== 0 &&
    validTripTime(archived) && validTripTime(current) &&
    (archived.start_epoch_s === current.start_epoch_s ||
      (Number(current.start_epoch_s) >= Number(archived.start_epoch_s) && Number(current.end_epoch_s) <= Number(archived.end_epoch_s)));
}
export function vehicleFingerprint(vehicle: Vehicle | null): string {
  return vehicle ? JSON.stringify([vehicle.epoch, vehicle.uptime, vehicle.currentStart,
    vehicle.currentDistance, vehicle.currentDuration, vehicle.currentFuel]) : '';
}
export function drivingHistory(archived: Row[], gauge: string, vehicle: Vehicle | null, snapshotArchived: boolean = false): Row[] {
  const current = currentDrivingRecord(gauge, vehicle);
  return !current || snapshotArchived || archived.some((r: Row) => archivedCurrentDriving(r, current)) ?
    archived.slice() : [current].concat(archived);
}
