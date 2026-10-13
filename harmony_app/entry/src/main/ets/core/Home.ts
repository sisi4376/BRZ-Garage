import { Snapshot, Vehicle } from './Models';
import { customStats } from './Garage';
import { vehicleRows, summary } from './AndroidPresentation';
// A history total is a separate data source, never a fabricated gauge snapshot.
export function homeMetrics(s: Snapshot, gauge: string, vehicle: Vehicle | null, kind: string, refuel: number[] = []): number[] {
  if (kind === 'refuel') return refuel.slice();
  if (!vehicle) {
    const rows = vehicleRows(s, 'trips', gauge);
    if (kind !== 'total' || !rows.length) return [];
    const total = summary(rows); return [total.distance, total.duration, total.fuel];
  }
  if (kind === 'custom') return customStats(s, gauge, vehicle);
  return kind === 'total' ? [vehicle.totalDistance, vehicle.totalDuration, vehicle.totalFuel] :
    [vehicle.currentDistance, vehicle.currentDuration, vehicle.currentFuel];
}
