import { syncRefuel, syncCustom, mutateRefuel } from '../core/IntervalSync';
import { parseSettings, settingPacket, parseOdometer, odometerPacket } from '../core/Gauge';
import { util } from '@kit.ArkTS';
import { utf8Decode } from '../core/Archive';
import { parseFirmware } from '../core/Gauge';
import { ble, constant } from '@kit.ConnectivityKit';
import { abilityAccessCtrl, common } from '@kit.AbilityKit';
import { Row, Snapshot, ScanItem } from '../core/Models';
import { INFO_SERVICE, characteristic } from '../core/Protocol';
import { GaugeTransport, SyncResult, synchronize } from '../core/Sync';
import { parseRefuelMeta, readRefuel, customPacket } from '../core/Gauge';
import { pref, need } from '../core/Garage';
import { repository } from './Repository';

export class Bluetooth implements GaugeTransport {
  private client: ble.GattClientDevice | undefined = undefined;
  private characteristics: ble.BLECharacteristic[] = [];
  private generation: number = 0;
  private scanListener: ((items: ble.ScanResult[]) => void) | undefined = undefined;
  private stopScanWait: (() => void) | undefined = undefined;
  private disconnectWait: (() => void) | undefined = undefined;
  private cancelled: boolean = false;
  private occupied: boolean = false;
  private background: boolean = false;
  isBusy(): boolean { return this.occupied; }
  isConnected(): boolean { return this.client !== undefined && this.characteristics.length > 0; }
  cancelBackground(): void { if (this.background) this.close(); }
  async syncBackground(context: common.Context, address: string): Promise<void> {
    need(!this.occupied, '蓝牙正在使用'); this.occupied = true; this.background = true;
    const timeout = setTimeout(() => this.cancelBackground(), 120000);
    try { await this.connected(context, address, () => {}, async () => { await this.syncConnected(address, () => {}); }, false); }
    finally { clearTimeout(timeout); this.background = false; this.occupied = false; }
  }
  private services: ble.GattService[] = [];
  async permission(context: common.UIAbilityContext): Promise<void> {
    const result = await abilityAccessCtrl.createAtManager().requestPermissionsFromUser(context, ['ohos.permission.ACCESS_BLUETOOTH']);
    if (result.authResults.some((value: number) => value !== 0)) throw new Error('需要蓝牙权限才能连接仪表，可在系统设置中开启');
  }
  async scan(context: common.UIAbilityContext, update: (items: ScanItem[]) => void): Promise<void> {
    this.close(); this.cancelled = false;
    await this.permission(context);
    if (this.cancelled) return;
    const found = new Map<string, ScanItem>();
    this.scanListener = (results: ble.ScanResult[]) => {
      results.forEach((result: ble.ScanResult) => found.set(result.deviceId, {
        address: result.deviceId.toUpperCase(), name: result.deviceName || 'BRZ 仪表', rssi: result.rssi
      }));
      update(Array.from(found.values()).sort((a: ScanItem, b: ScanItem) => b.rssi - a.rssi));
    };
    ble.on('BLEDeviceFind', this.scanListener);
    try {
      ble.startBLEScan([{ serviceUuid: INFO_SERVICE }], { interval: 0, dutyMode: ble.ScanDuty.SCAN_MODE_LOW_LATENCY });
      await new Promise<void>((resolve) => {
        const timer = setTimeout(() => { this.stopScanWait = undefined; resolve(); }, 12000);
        this.stopScanWait = () => { clearTimeout(timer); resolve(); };
      });
    } finally { this.stopScan(); }
  }
  private stopScan(): void {
    if (this.stopScanWait) { this.stopScanWait(); this.stopScanWait = undefined; }
    if (this.scanListener) {
      try { ble.stopBLEScan(); } catch (_) {}
      try { ble.off('BLEDeviceFind', this.scanListener); } catch (_) {}
      this.scanListener = undefined;
    }
  }
  close(): void {
    this.cancelled = true; this.generation++; this.stopScan();
    if (this.disconnectWait) { this.disconnectWait(); this.disconnectWait = undefined; }
    const client = this.client; this.client = undefined; this.characteristics = [];
    if (client) {
      try { client.off('BLEConnectionStateChange'); } catch (_) {}
      try { client.disconnect(); } catch (_) {}
      try { client.close(); } catch (_) {}
    }
  }
  private bounded<T>(operation: Promise<T>, token: number): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('蓝牙操作超时，请靠近仪表后重试')), 25000);
      operation.then((value: T) => {
        clearTimeout(timer);
        if (token !== this.generation) reject(new Error('连接已取消'));
        else resolve(value);
      }).catch((error: Error) => { clearTimeout(timer); reject(error); });
    });
  }
  async readOta(): Promise<Uint8Array> {
    const service = this.services.find((s: ble.GattService) => s.serviceUuid.toLowerCase() === '00001ffb-0000-1000-8000-00805f9b34fb');
    const item = service?.characteristics.find((c: ble.BLECharacteristic) => c.characteristicUuid.toLowerCase() === characteristic(3));
    if (!item || !this.client) throw new Error('仪表不支持 Wi-Fi 升级');
    return new Uint8Array((await this.bounded(this.client.readCharacteristicValue(item), this.generation)).characteristicValue);
  }
  async startOta(): Promise<void> {
    const service = this.services.find((s: ble.GattService) => s.serviceUuid.toLowerCase() === '00001ffb-0000-1000-8000-00805f9b34fb');
    const item = service?.characteristics.find((c: ble.BLECharacteristic) => c.characteristicUuid.toLowerCase() === characteristic(1));
    if (!item || !this.client) throw new Error('仪表不支持 Wi-Fi 升级');
    item.characteristicValue = Uint8Array.of(79, 84, 65, 49, 4).buffer as ArrayBuffer;
    await this.bounded(this.client.writeCharacteristicValue(item, ble.GattWriteType.WRITE), this.generation);
  }
  has(id: number): boolean { return this.characteristics.some((item: ble.BLECharacteristic) => item.characteristicUuid.toLowerCase() === characteristic(id)); }
  private find(id: number): ble.BLECharacteristic {
    const item = this.characteristics.find((value: ble.BLECharacteristic) => value.characteristicUuid.toLowerCase() === characteristic(id));
    if (!item || !this.client) throw new Error('仪表连接不可用或缺少必要特征');
    return item;
  }
  readable(id: number): boolean { return this.has(id) && this.find(id).properties?.read === true; }
  async read(id: number): Promise<Uint8Array> {
    const item = this.find(id); const client = this.client!;
    const result = await this.bounded(client.readCharacteristicValue(item), this.generation);
    return new Uint8Array(result.characteristicValue);
  }
  async write(id: number, bytes: Uint8Array): Promise<void> {
    const item = this.find(id); const client = this.client!;
    item.characteristicValue = bytes.slice().buffer as ArrayBuffer;
    await this.bounded(client.writeCharacteristicValue(item, ble.GattWriteType.WRITE), this.generation);
  }
  async syncConnected(address: string, progress: (message: string) => void): Promise<SyncResult> {
    const result = await synchronize(address, this, repository, progress);
    if (result.vehicle) await repository.cacheVehicle(result.vehicle);
    if (this.has(11) && this.has(12) && this.has(13)) {
      progress('正在同步加油区间');
      try { await syncRefuel(address, this, repository); }
      catch (error) { result.warning += ' 加油历史保留原记录：' + (error as Error).message; }
    }
    await repository.cacheDevice(address,{time_at:Date.now(),time_verified:result.clockVerified});
    try { await this.cacheGaugeInfo(address); }
    catch (_) { result.warning+=' 仪表设置或固件信息读取失败，已保存的历史与车辆数据不受影响。'; }
    const snapshot = repository.snapshot();
    if (result.vehicle) await repository.ensureCustom(address, result.vehicle);
    try { const warning = await syncCustom(address, this, repository); if (warning) result.warning += ' ' + warning; }
    catch (_) { result.warning += ' 自定义起点已在手机保存，等待下次连接确认。'; }
    const pendingNode = repository.pendingRefuel(address);
    if (pendingNode) await mutateRefuel(address, this, repository, pendingNode.discard ? 5 : 3, pendingNode.id);

    const pendingProfile=repository.pendingProfile();
    if(pendingProfile!==null && this.has(9) && this.has(7)) {
      await this.write(9,settingPacket(pendingProfile,true)); await new Promise<void>((resolve)=>setTimeout(resolve,350));
      if(parseSettings(await this.read(7)).profile!==pendingProfile)throw new Error('车型回读不一致，待下次重试');
      await repository.queueSetting('profile',null);
    }
    const pendingDisplay=repository.pendingDisplay(), pendingMileage=repository.pendingMileage();
    if((pendingDisplay!==null || pendingMileage!==null) && this.has(10)) {
      const enabled=pendingDisplay ?? Boolean(pref(snapshot,'odometer_display_'+address,true));
      await this.write(10,odometerPacket(enabled,pendingMileage));await new Promise<void>((resolve)=>setTimeout(resolve,350));
      const readback=parseOdometer(await this.read(10));
      need(readback.enabled===enabled && (pendingMileage===null || Math.abs(readback.metres-pendingMileage)<=50),'里程设置回读不一致，待下次重试');
      await repository.queueSetting('display',null);await repository.queueSetting('mileage',null);
    }
    return result;
  }
  async readSettingsCached(address:string):Promise<Uint8Array>{
    const bytes=await this.read(7);parseSettings(bytes);
    await repository.cacheDevice(address,{gauge_settings_data:new util.Base64Helper().encodeToStringSync(bytes),gauge_settings_at:Date.now()});
    return bytes;
  }
  async cacheGaugeInfo(address:string):Promise<void>{
    if(this.has(7))await this.readSettingsCached(address);
    if(this.has(1)){
      const text=utf8Decode(await this.read(1)),info=parseFirmware(text);
      await repository.cacheDevice(address,{firmware_version:info.version,firmware_build:info.build||null,firmware_project:info.project,
        firmware_board:info.board,firmware_variant:info.variant,firmware_lcd:info.lcd,firmware_screen_w:info.w,firmware_screen_h:info.h,
        firmware_bpp:info.bpp,firmware_flash_mb:info.flash,firmware_ota_slots:info.slots,firmware_at:Date.now()});
    }
  }
  async sync(context: common.UIAbilityContext, address: string, progress: (message: string) => void): Promise<SyncResult> {
    return this.session(context, address, progress, async () => this.syncConnected(address, progress));
  }
  async session<T>(context: common.UIAbilityContext, address: string, progress: (message: string) => void, action: () => Promise<T>): Promise<T> {
    if (this.occupied) throw new Error('蓝牙任务正在结束，请稍后重试');
    this.occupied = true; try { return await this.connected(context, address, progress, action); } finally { this.occupied = false; }
  }
  private async connected<T>(context: common.Context, address: string, progress: (message: string) => void, action: () => Promise<T>, prompt: boolean = true): Promise<T> {
    this.close(); this.cancelled = false;
    if (prompt) await this.permission(context as common.UIAbilityContext);
    else need(abilityAccessCtrl.createAtManager().checkAccessTokenSync(context.applicationInfo.accessTokenId, 'ohos.permission.ACCESS_BLUETOOTH') === 0, '蓝牙权限未授权');
    if (this.cancelled) throw new Error('连接已取消');
    const token = this.generation;
    const client = ble.createGattClientDevice(address); this.client = client;
    try {
      progress('正在连接 ' + address);
      await this.bounded(new Promise<void>((resolve, reject) => {
        this.disconnectWait = () => reject(new Error('连接已取消'));
        client.on('BLEConnectionStateChange', (event: ble.BLEConnectionChangeState) => {
          if (event.state === constant.ProfileConnectionState.STATE_CONNECTED) { this.disconnectWait = undefined; resolve(); }
          if (event.state === constant.ProfileConnectionState.STATE_DISCONNECTED) {
            this.generation++; reject(new Error('仪表已断开，请检查是否被其他手机占用'));
          }
        });
        client.connect();
      }), token);
      const services = await this.bounded(client.getServices(), token);
      this.services = services;
      const info = services.find((service: ble.GattService) => service.serviceUuid.toLowerCase() === INFO_SERVICE);
      if (!info) throw new Error('该设备不是兼容的 BRZ 仪表');
      this.characteristics = info.characteristics;
      // GATT long reads are supported by the gauge. Do not block on optional MTU negotiation.
      return await action();
    } finally { this.close(); }
  }
}
export const bluetooth = new Bluetooth();
