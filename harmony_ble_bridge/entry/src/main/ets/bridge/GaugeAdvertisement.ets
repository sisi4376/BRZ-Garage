export const INFO_SERVICE = '00001ffa-0000-1000-8000-00805f9b34fb';

// Only match UUID AD structures, never arbitrary bytes in manufacturer data.
export function hasGaugeService(data: ArrayBuffer, services: string[] = []): boolean {
  if (services.some((uuid: string) => uuid.toLowerCase() === INFO_SERVICE ||
    uuid.toLowerCase() === '1ffa')) return true;
  const bytes = new Uint8Array(data);
  const uuid128: number[] = [0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80,
    0x00, 0x10, 0x00, 0x00, 0xfa, 0x1f, 0x00, 0x00];
  for (let offset = 0; offset < bytes.length;) {
    const length = bytes[offset];
    if (length === 0) break;
    const end = offset + length + 1;
    if (end > bytes.length) break;
    const type = bytes[offset + 1];
    if (type === 0x02 || type === 0x03) {
      for (let i = offset + 2; i + 1 < end; i += 2) {
        if (bytes[i] === 0xfa && bytes[i + 1] === 0x1f) return true;
      }
    } else if (type === 0x06 || type === 0x07) {
      for (let i = offset + 2; i + 15 < end; i += 16) {
        let same = true;
        for (let j = 0; j < 16; j++) {
          if (bytes[i + j] !== uuid128[j]) same = false;
        }
        if (same) return true;
      }
    }
    offset = end;
  }
  return false;
}
