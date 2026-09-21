"""Read-only audit of trip wall-clock timestamps in an ESP32 NVS backup."""
import json
import struct
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.toolchains/espressif/v5.5.3/esp-idf/components/nvs_flash/nvs_partition_tool'))
from nvs_parser import NVS_Partition


def blob(partition, name):
    entries = [e for p in partition.pages if p.header['status'] in ('Active', 'Full')
               for e in p.entries if e.state == 'Written']
    namespaces = [e.data['value'] for e in entries if e.metadata['namespace'] == 0 and e.key == 'cfg']
    indexes = [e for e in entries if e.key == name and e.metadata['namespace'] in namespaces
               and e.metadata['type'] == 'blob_index']
    if not indexes:
        return None
    if len(indexes) != 1:
        raise ValueError(f'Ambiguous live blob {name}: {len(indexes)} indexes')
    index = indexes[0]
    parts = []
    for number in range(index.data['chunk_start'], index.data['chunk_start'] + index.data['chunk_count']):
        candidates = [e for e in entries if e.key == name
                      and e.metadata['namespace'] == index.metadata['namespace']
                      and e.metadata['type'] == 'blob_data' and e.metadata['chunk_index'] == number]
        if len(candidates) != 1:
            raise ValueError(f'Missing/ambiguous chunk {name}:{number}')
        entry = candidates[0]
        crc = entry.metadata['crc']
        if crc['original'] != crc['computed'] or crc['data_original'] != crc['data_computed']:
            raise ValueError(f'CRC failure for {name}:{number}')
        parts.append(b''.join(bytes(child.raw) for child in entry.children)[:entry.data['size']])
    result = b''.join(parts)
    if len(result) != index.data['size']:
        raise ValueError(f'Blob length mismatch: {name}')
    return result


def date(epoch):
    return datetime.fromtimestamp(epoch, timezone(timedelta(hours=8))).isoformat() if 1704067200 <= epoch <= 4102444800 else None


def audit(path):
    partition = NVS_Partition(path.name, bytearray(path.read_bytes()))
    history_blob = blob(partition, 'fueltrips')
    sync_blob = blob(partition, 'tripsync')
    out = {'backup': str(path.resolve()), 'timezone': 'UTC+08:00', 'records': []}
    if sync_blob:
        # Xtensa ABI: records align to 8 bytes; each record is 40 bytes.
        version, last_acked, count, overflow = struct.unpack_from('<IIBB', sync_blob)
        if version != 1 or len(sync_blob) != 2576 or count > 64:
            raise ValueError(f'Unexpected sync layout: version={version}, size={len(sync_blob)}, count={count}')
        out.update(sync_count=count, last_acked_id=last_acked, overflowed=bool(overflow))
        for n in range(count):
            record_id, start, end, duration, distance, fuel, avg, flags = struct.unpack_from('<I4xQQIIIHH', sync_blob, 16 + n * 40)
            out['records'].append(dict(id=record_id, start_epoch=start, end_epoch=end,
                                      start=date(start), end=date(end), duration_s=duration,
                                      distance_m=distance, fuel_ml=fuel, avg_l100=avg / 100,
                                      flags=flags, wall_clock_valid=bool(flags & 1) and date(start) is not None and date(end) is not None and end >= start))
        out['records_with_wall_clock'] = sum(r['wall_clock_valid'] for r in out['records'])
    if history_blob:
        version, next_id = struct.unpack_from('<II', history_blob)
        if version != 3 or len(history_blob) != 528 or history_blob[56] > 20:
            raise ValueError(f'Unexpected fuel layout: version={version}, size={len(history_blob)}')
        history_ids = [struct.unpack_from('<I', history_blob, 64 + 20 * n)[0] for n in range(history_blob[56])]
        out.update(history_count=len(history_ids), history_ids=history_ids, next_trip_id=next_id)
        by_id = {r['id']: r for r in out['records']}
        out['history_with_wall_clock'] = sum(by_id[i]['wall_clock_valid'] for i in history_ids if i in by_id)
        out['history_without_sync_record'] = [i for i in history_ids if i not in by_id]
        for label, offsets in [('active', (32, 40, 48, 512, 464)), ('pending', (472, 480, 488, 520, 496))]:
            distance, fuel, duration, start, end = [struct.unpack_from('<Q', history_blob, offset)[0] for offset in offsets]
            out[label] = dict(distance_m=distance / 1000, fuel_ml=fuel / 1000, duration_s=duration / 1000,
                              start_epoch=start, end_epoch=end, start=date(start), end=date(end))
        out['pending']['valid'] = bool(history_blob[504])
    return out


if __name__ == '__main__':
    print(json.dumps(audit(Path(sys.argv[1])), ensure_ascii=False, indent=2))
