import zlib from '@ohos.zlib';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { fileIo as fs, picker } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { ArchiveRuntime } from '../core/Archive';
import { MAX_DATA } from '../core/Transfer';

export class HarmonyArchiveRuntime implements ArchiveRuntime {
  async sha256(bytes: Uint8Array): Promise<string> {
    const md = cryptoFramework.createMd('SHA256');
    await md.update({ data: bytes });
    const digest = await md.digest();
    return Array.from(digest.data).map((b: number) => b.toString(16).padStart(2, '0')).join('');
  }
  async inflateRaw(bytes: Uint8Array, expectedSize: number): Promise<Uint8Array> {
    const zip = await zlib.createZip();
    const output = new ArrayBuffer(expectedSize + 1);
    const stream: zlib.ZStream = { nextIn: bytes.slice().buffer as ArrayBuffer,
      availableIn: bytes.length, totalIn: 0, nextOut: output, availableOut: output.byteLength, totalOut: 0 };
    if (await zip.inflateInit2(stream, -15) !== zlib.ReturnStatus.OK) throw new Error('无法初始化解压');
    try {
      const result = await zip.inflate({}, zlib.CompressFlushMode.FINISH);
      // Native zlib updates its own stream, not the input ZStream object.
      const completed = await zip.getZStream();
      if (result !== zlib.ReturnStatus.STREAM_END || completed.totalOut !== expectedSize ||
        completed.totalIn !== bytes.length) throw new Error('压缩内容损坏或超过声明大小');
      return new Uint8Array(output).slice(0, expectedSize);
    } finally { await zip.inflateEnd({}); }
  }
}
export function readFile(path: string): Uint8Array {
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  try {
    const size = fs.statSync(file.fd).size;
    if (size < 22 || size > MAX_DATA + 65536) throw new Error('文件大小不符合数据包要求');
    const result = new Uint8Array(size);
    let offset = 0;
    while (offset < size) {
      const buffer = new ArrayBuffer(Math.min(65536, size - offset));
      const read = fs.readSync(file.fd, buffer);
      if (read <= 0) throw new Error('文件读取不完整');
      result.set(new Uint8Array(buffer, 0, read), offset); offset += read;
    }
    return result;
  } finally { fs.closeSync(file); }
}
export function writeFile(path: string, bytes: Uint8Array): void {
  const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
  try {
    let offset = 0;
    while (offset < bytes.length) {
      const written = fs.writeSync(file.fd, bytes.slice(offset, Math.min(offset + 65536, bytes.length)).buffer as ArrayBuffer);
      if (written <= 0) throw new Error('文件写入不完整');
      offset += written;
    }
    fs.fsyncSync(file.fd);
  } finally { fs.closeSync(file); }
}
export async function chooseImport(context: common.UIAbilityContext): Promise<string> {
  const document = new picker.DocumentViewPicker(context);
  const selected = await document.select({ maxSelectNumber: 1 });
  return selected.length ? selected[0] : '';
}
export async function chooseExport(context: common.UIAbilityContext): Promise<string> {
  const document = new picker.DocumentViewPicker(context);
  const selected = await document.save({ newFileNames: ['BRZ-Garage-' + Date.now() + '.zip'] });
  return selected.length ? selected[0] : '';
}

export function listBackupFiles(directory: string): string[] {
  return fs.listFileSync(directory).filter((name: string) => /^before-import-[0-9]+-[0-9a-f]+\.zip$/.test(name)).sort().reverse().map((name: string) => directory + '/' + name);
}
