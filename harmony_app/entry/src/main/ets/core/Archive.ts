import { Snapshot } from './Models';
import { FORMAT, MAX_DATA, fromJson, toJson } from './Transfer';

export interface ArchiveRuntime {
  inflateRaw(bytes: Uint8Array, expectedSize: number): Promise<Uint8Array>;
  sha256(bytes: Uint8Array): Promise<string>;
}
interface EntryInfo { name: string; method: number; flags: number; crc: number; packed: number; size: number; offset: number; }
export function utf8Encode(text: string): Uint8Array {
  let size = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) { size += 4; i++; }
    else size += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
  }
  const bytes = new Uint8Array(size); let offset = 0;
  for (let i = 0; i < text.length; i++) {
    let c = text.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const next = text.charCodeAt(++i);
      if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error('文本包含无效 Unicode');
      c = 0x10000 + ((c - 0xd800) << 10) + next - 0xdc00;
    } else if (c >= 0xdc00 && c <= 0xdfff) throw new Error('文本包含无效 Unicode');
    if (c < 0x80) bytes[offset++] = c;
    else if (c < 0x800) { bytes[offset++] = 0xc0 | (c >> 6); bytes[offset++] = 0x80 | (c & 63); }
    else if (c < 0x10000) { bytes[offset++] = 0xe0 | (c >> 12); bytes[offset++] = 0x80 | ((c >> 6) & 63); bytes[offset++] = 0x80 | (c & 63); }
    else { bytes[offset++] = 0xf0 | (c >> 18); bytes[offset++] = 0x80 | ((c >> 12) & 63); bytes[offset++] = 0x80 | ((c >> 6) & 63); bytes[offset++] = 0x80 | (c & 63); }
  }
  return bytes;
}
export function utf8Decode(bytes: Uint8Array): string {
  const parts: string[] = []; let part = '';
  for (let i = 0; i < bytes.length;) {
    const first = bytes[i++]; let c = first; let remain = 0; let min = 0;
    if (first >= 0xc2 && first <= 0xdf) { c = first & 31; remain = 1; min = 0x80; }
    else if (first >= 0xe0 && first <= 0xef) { c = first & 15; remain = 2; min = 0x800; }
    else if (first >= 0xf0 && first <= 0xf4) { c = first & 7; remain = 3; min = 0x10000; }
    else if (first >= 0x80) throw new Error('UTF-8 编码无效');
    for (let j = 0; j < remain; j++) {
      if (i >= bytes.length || (bytes[i] & 0xc0) !== 0x80) throw new Error('UTF-8 编码不完整');
      c = (c << 6) | (bytes[i++] & 63);
    }
    if (c < min || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) throw new Error('UTF-8 字符无效');
    part += c <= 0xffff ? String.fromCharCode(c) : String.fromCharCode(0xd800 + ((c - 0x10000) >> 10), 0xdc00 + ((c - 0x10000) & 1023));
    if (part.length >= 4096) { parts.push(part); part = ''; }
  }
  parts.push(part); return parts.join('');
}
const crcTable: number[] = [];
for (let i = 0; i < 256; i++) {
  let value = i;
  for (let j = 0; j < 8; j++) value = (value & 1) ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  crcTable.push(value >>> 0);
}
export function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) crc = crcTable[(crc ^ bytes[i]) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
function check(ok: boolean, message: string = 'ZIP 结构无效或不支持'): void { if (!ok) throw new Error(message); }
function dataView(bytes: Uint8Array): DataView { return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength); }
function readEntries(bytes: Uint8Array): EntryInfo[] {
  check(bytes.length >= 22 && bytes.length <= MAX_DATA + 65536, '文件过大或不完整');
  const d = dataView(bytes); let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (d.getUint32(i, true) === 0x06054b50 && i + 22 + d.getUint16(i + 20, true) === bytes.length) { end = i; break; }
  }
  check(end >= 0);
  check(d.getUint16(end + 4, true) === 0 && d.getUint16(end + 6, true) === 0 &&
    d.getUint16(end + 8, true) === 2 && d.getUint16(end + 10, true) === 2, '数据包必须且只能包含两个文件');
  const start = d.getUint32(end + 16, true); const size = d.getUint32(end + 12, true);
  check(start + size === end);
  const entries: EntryInfo[] = []; let cursor = start; const names = new Set<string>();
  for (let i = 0; i < 2; i++) {
    check(cursor + 46 <= end && d.getUint32(cursor, true) === 0x02014b50);
    const nameLength = d.getUint16(cursor + 28, true); const extra = d.getUint16(cursor + 30, true);
    const comment = d.getUint16(cursor + 32, true);
    check(cursor + 46 + nameLength + extra + comment <= end && d.getUint16(cursor + 34, true) === 0);
    const name = utf8Decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength));
    check((name === 'manifest.json' || name === 'data.json') && !names.has(name), '数据包包含异常路径或重复文件');
    names.add(name);
    const entry: EntryInfo = { name, flags: d.getUint16(cursor + 8, true), method: d.getUint16(cursor + 10, true),
      crc: d.getUint32(cursor + 16, true), packed: d.getUint32(cursor + 20, true),
      size: d.getUint32(cursor + 24, true), offset: d.getUint32(cursor + 42, true) };
    check((entry.flags & ~0x808) === 0 && (entry.method === 0 || entry.method === 8), '压缩方式不支持或文件已加密');
    check(entry.size <= (name === 'manifest.json' ? 8192 : MAX_DATA) && entry.packed <= MAX_DATA + 32768, '解压大小超限');
    check(entry.offset + 30 <= start && d.getUint32(entry.offset, true) === 0x04034b50);
    check(d.getUint16(entry.offset + 6, true) === entry.flags && d.getUint16(entry.offset + 8, true) === entry.method);
    const localNameLength = d.getUint16(entry.offset + 26, true); const localExtra = d.getUint16(entry.offset + 28, true);
    const localEnd = entry.offset + 30 + localNameLength + localExtra;
    check(localEnd + entry.packed <= start);
    check(utf8Decode(bytes.subarray(entry.offset + 30, entry.offset + 30 + localNameLength)) === name);
    if ((entry.flags & 8) === 0) check(d.getUint32(entry.offset + 14, true) === entry.crc &&
      d.getUint32(entry.offset + 18, true) === entry.packed && d.getUint32(entry.offset + 22, true) === entry.size);
    entries.push(entry); cursor += 46 + nameLength + extra + comment;
  }
  check(cursor === end);
  const ranges = entries.map((e: EntryInfo) => ({ start: e.offset, end: e.offset + 30 +
    d.getUint16(e.offset + 26, true) + d.getUint16(e.offset + 28, true) + e.packed })).sort((a, b) => a.start - b.start);
  check(ranges[0].end <= ranges[1].start);
  return entries;
}
export async function decodeArchive(bytes: Uint8Array, runtime: ArchiveRuntime): Promise<Snapshot> {
  const entries = readEntries(bytes); const contents = new Map<string, Uint8Array>(); const d = dataView(bytes);
  for (const entry of entries) {
    const start = entry.offset + 30 + d.getUint16(entry.offset + 26, true) + d.getUint16(entry.offset + 28, true);
    const source = bytes.slice(start, start + entry.packed);
    const result = entry.method === 0 ? source : await runtime.inflateRaw(source, entry.size);
    check(result.length === entry.size && crc32(result) === entry.crc, '数据包解压长度或 CRC 校验失败');
    contents.set(entry.name, result);
  }
  const manifest = JSON.parse(utf8Decode(contents.get('manifest.json')!)) as Record<string, unknown>;
  check(manifest !== null && typeof manifest === 'object' && manifest.format === FORMAT &&
    (manifest.version === 1 || manifest.version === 2 || manifest.version === 3), '数据包版本不支持');
  const payload = contents.get('data.json')!;
  check(manifest.bytes === payload.length && manifest.sha256 === await runtime.sha256(payload), '数据包 SHA-256 校验失败');
  check(typeof manifest.gauge === 'string' && typeof manifest.createdAt === 'number', '数据包清单无效');
  return fromJson(utf8Decode(payload), manifest.gauge as string, manifest.createdAt as number, manifest.version as number);
}
export async function encodeArchive(snapshot: Snapshot, runtime: ArchiveRuntime): Promise<Uint8Array> {
  const data = utf8Encode(toJson(snapshot)); check(data.length <= MAX_DATA, '数据超过 32 MiB 上限');
  const manifest = utf8Encode(JSON.stringify({ format: FORMAT, version: 3, appVersion: 'harmony-0.3.10',
    createdAt: snapshot.createdAt, gauge: snapshot.gauge, bytes: data.length, sha256: await runtime.sha256(data) }));
  const files = [{ name: 'manifest.json', data: manifest }, { name: 'data.json', data }];
  const total = files.reduce((sum, file) => sum + 30 + file.name.length + file.data.length + 46 + file.name.length, 22);
  const out = new Uint8Array(total); const d = dataView(out); let cursor = 0; const offsets: number[] = [];
  files.forEach((file) => {
    offsets.push(cursor); const name = utf8Encode(file.name);
    d.setUint32(cursor, 0x04034b50, true); d.setUint16(cursor + 4, 20, true); d.setUint16(cursor + 6, 0x800, true);
    d.setUint32(cursor + 14, crc32(file.data), true); d.setUint32(cursor + 18, file.data.length, true);
    d.setUint32(cursor + 22, file.data.length, true); d.setUint16(cursor + 26, name.length, true);
    out.set(name, cursor + 30); out.set(file.data, cursor + 30 + name.length); cursor += 30 + name.length + file.data.length;
  });
  const central = cursor;
  files.forEach((file, i) => {
    const name = utf8Encode(file.name);
    d.setUint32(cursor, 0x02014b50, true); d.setUint16(cursor + 4, 20, true); d.setUint16(cursor + 6, 20, true);
    d.setUint16(cursor + 8, 0x800, true); d.setUint32(cursor + 16, crc32(file.data), true);
    d.setUint32(cursor + 20, file.data.length, true); d.setUint32(cursor + 24, file.data.length, true);
    d.setUint16(cursor + 28, name.length, true); d.setUint32(cursor + 42, offsets[i], true);
    out.set(name, cursor + 46); cursor += 46 + name.length;
  });
  d.setUint32(cursor, 0x06054b50, true); d.setUint16(cursor + 8, 2, true); d.setUint16(cursor + 10, 2, true);
  d.setUint32(cursor + 12, cursor - central, true); d.setUint32(cursor + 16, central, true);
  return out;
}
