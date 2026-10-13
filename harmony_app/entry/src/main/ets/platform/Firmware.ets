import { common } from '@kit.AbilityKit';
import { http } from '@kit.NetworkKit';
import { utf8Decode } from '../core/Archive';
import { FirmwareInfo, parseFirmware, sameHardware, compareVersion } from '../core/Gauge';
import { need } from '../core/Garage';
import { repository } from './Repository';
import { bluetooth } from './Bluetooth';
interface FileInfo { path: string; size: number; sha256: string; }
interface FirmwareFiles { firmware: FileInfo; }
interface PackageManifest { files: FirmwareFiles; }
export interface CatalogEntry { id: string; label: string; manifest: string; rollback: boolean; }
interface Catalog { packages: CatalogEntry[]; }
interface WifiInfo { state: string; message: string; ssid: string; password: string; ip: string; token: string; port: number; }
interface UploadStatus { state: string; received: number; expected: number; nextOffset: number; }
export class Firmware {
  wifi: WifiInfo | null = null;
  remote: FirmwareInfo | null = null;
  selected: CatalogEntry | null = null;
  target: FirmwareInfo | null = null;
  private file: FileInfo | null = null;
  private bytes: Uint8Array = new Uint8Array(0);
  private cancelled: boolean = false;
  private generation: number = 0;
  private request: http.HttpRequest | null = null;
  async catalog(context: common.UIAbilityContext): Promise<CatalogEntry[]> {
    const text = utf8Decode(await context.resourceManager.getRawFileContent('firmware/catalog.json'));
    return (JSON.parse(text) as Catalog).packages;
  }
  async select(context: common.UIAbilityContext, entry: CatalogEntry): Promise<string> {
    this.selected = null; this.bytes = new Uint8Array(0);
    const text = utf8Decode(await context.resourceManager.getRawFileContent(entry.manifest));
    const manifest = JSON.parse(text) as PackageManifest, info = manifest.files.firmware;
    const path = entry.manifest.substring(0, entry.manifest.lastIndexOf('/') + 1) + info.path;
    need(!info.path.includes('..') && info.size > 0 && info.size <= 0x300000, '固件清单大小或路径无效');
    const bytes = await context.resourceManager.getRawFileContent(path);
    need(bytes.length === info.size && bytes[0] === 0xe9 && await repository.archive.sha256(bytes) === info.sha256.toLowerCase(), '固件长度或 SHA-256 校验失败');
    this.target = parseFirmware(text); this.file = info; this.bytes = bytes; this.selected = entry;
    return entry.label + ' · v' + this.target.version + ' · ' + bytes.length + ' 字节 · SHA-256 校验通过';
  }
  async prepare(context: common.UIAbilityContext, address: string, progress: (s: string) => void): Promise<string> {
    need(this.selected !== null, '请先选择固件'); this.wifi = null;
    return bluetooth.session(context, address, progress, async () => {
      this.remote = parseFirmware(utf8Decode(await bluetooth.read(1))); this.assess(this.remote!);
      await bluetooth.startOta();
      for (let i = 0; i < 8; i++) {
        await new Promise<void>((resolve) => setTimeout(resolve, 600));
        const info = JSON.parse(utf8Decode(await bluetooth.readOta()).replace(/\0+$/, '')) as WifiInfo;
        if (info.state === 'wifi-ready') { this.acceptWifi(info); return '请到系统 Wi-Fi 连接 ' + info.ssid + '，密码 ' + info.password + '。返回后点击上传。'; }
      } throw new Error('热点尚未就绪；若已出现 OBD-Gauge-OTA 热点，可连接后恢复会话');
    });
  }
  private assess(remote: FirmwareInfo): void {
    need(!!this.target && !!this.selected && sameHardware(remote, this.target!), '硬件、屏幕或分区不匹配，禁止刷写');
    const delta = compareVersion(this.target!.version, remote.version);
    need(this.selected!.rollback ? delta < 0 : delta > 0, this.selected!.rollback ? '回滚包必须低于当前版本' : '当前已是相同或更高版本');
  }
  private acceptWifi(wifi: WifiInfo): void {
    need(wifi.ip === '192.168.4.1' && wifi.port >= 1 && wifi.port <= 65535 && /^OBD-Gauge-OTA-/.test(wifi.ssid) && wifi.password.length >= 8 && !!wifi.token, '仪表热点信息无效'); this.wifi = wifi;
  }
  private async call(path: string, post: boolean = false, data?: ArrayBuffer, headers: Record<string, string> = {}): Promise<string> {
    need(!this.cancelled, '上传已取消');
    const request = http.createHttp(); this.request = request;
    const base = this.wifi ? 'http://' + this.wifi.ip + ':' + this.wifi.port : 'http://192.168.4.1';
    try {
      const reply = await request.request(base + path, { method: post ? http.RequestMethod.POST : http.RequestMethod.GET,
        header: headers, extraData: data, expectDataType: http.HttpDataType.STRING, connectTimeout: 12000, readTimeout: 45000, usingCache: false });
      need(reply.responseCode === 200, '仪表 HTTP 错误 ' + reply.responseCode); return reply.result as string;
    } finally { request.destroy(); if (this.request === request) this.request = null; }
  }
  async recover(): Promise<string> {
    this.cancelled = false;
    this.acceptWifi(JSON.parse(await this.call('/ota/discover')) as WifiInfo);
    return '已恢复更新会话：' + this.wifi!.ssid;
  }
  async upload(progress: (s: string) => void): Promise<void> {
    need(this.wifi !== null && this.file !== null && this.selected !== null, '请先选择固件并连接仪表热点');
    this.cancelled = false; const token = ++this.generation;
    this.assess(parseFirmware(await this.call('/ota/info')));
    const headers: Record<string, string> = { 'Content-Type': 'application/octet-stream', 'X-OTA-Token': this.wifi!.token,
      'X-OTA-SHA256': this.file!.sha256, 'X-OTA-Size': String(this.bytes.length) };
    let offset = await this.resume(); let failures = 0;
    while (offset < this.bytes.length) {
      need(!this.cancelled && token === this.generation, '上传已取消'); const end = Math.min(offset + 16384, this.bytes.length);
      try {
        headers['X-Offset'] = String(offset); headers['X-Last'] = end === this.bytes.length ? '1' : '0';
        const reply = await this.call('/ota/firmware', true, this.bytes.slice(offset, end).buffer as ArrayBuffer, headers);
        const next = end === this.bytes.length ? end : (JSON.parse(reply) as UploadStatus).nextOffset;
        need(Number.isInteger(next) && next > offset && next <= end, '仪表确认偏移异常'); offset = next; failures = 0;
        progress('固件上传 ' + Math.floor(offset / this.bytes.length * 100) + '%');
      } catch (error) {
        if (this.cancelled || token !== this.generation || ++failures > 3) throw error as Error;
        progress('连接中断，正在检查续传位置'); await new Promise<void>((resolve) => setTimeout(resolve, 1500)); offset = await this.resume();
      }
    }
    progress('固件已提交，等待仪表重启；重新连接后核对版本');
  }
  private async resume(): Promise<number> {
    const status = JSON.parse(await this.call('/ota/status', false, undefined, { 'X-OTA-Token': this.wifi!.token })) as UploadStatus;
    need(Number.isInteger(status.received) && status.received >= 0 && status.received <= this.bytes.length &&
      (status.expected === 0 || status.expected === this.bytes.length) && !(status.state === 'error' && (status.received !== 0 || status.expected !== 0)), '续传状态与固件不一致');
    return status.received;
  }
  async cancel(): Promise<void> {
    this.generation++; this.cancelled = true; this.request?.destroy(); this.request = null;
    if (this.wifi) { this.cancelled = false; try { await this.call('/ota/cancel', true, new ArrayBuffer(0), { 'X-OTA-Token': this.wifi.token }); } finally { this.cancelled = true; } }
  }
}
export const firmware = new Firmware();
