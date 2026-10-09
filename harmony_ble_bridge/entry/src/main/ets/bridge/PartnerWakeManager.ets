import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';
import { ble, connection, common as bluetoothCommon, partnerAgent } from '@kit.ConnectivityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { PartnerWakeListener, PartnerWakeSnapshot } from './PartnerWakeTypes';
import { hasGaugeService } from './GaugeAdvertisement';

const PARTNER_AGENT_ABILITY = 'PartnerAgentAbility';
const SCAN_TIMEOUT_MS = 30000;
const DOMAIN = 0x4252;
const TAG = 'BrzPartnerSetup';

/**
 * One-time PartnerAgent registration only. This class never creates a GATT
 * client and never runs a background scan; after registration HarmonyOS owns
 * device discovery.
 */
export class PartnerWakeManager {
  private static instance: PartnerWakeManager = new PartnerWakeManager();
  private context?: common.UIAbilityContext;
  private scanning: boolean = false;
  private scanTimer: number = -1;
  private listener?: PartnerWakeListener;
  private seenDevices: Set<string> = new Set<string>();
  private knownDiagnostic: string = '';
  private snapshot: PartnerWakeSnapshot = {
    supported: false,
    registered: false,
    scanning: false,
    registering: false,
    status: '正在读取系统状态…',
    deviceAddress: '',
    error: '',
    diagnostics: ''
  };

  static getInstance(): PartnerWakeManager {
    return PartnerWakeManager.instance;
  }

  attachContext(context: common.UIAbilityContext): void {
    this.context = context;
  }

  setListener(listener?: PartnerWakeListener): void {
    this.listener = listener;
    this.publish();
  }

  async refresh(): Promise<void> {
    if (this.scanning || this.snapshot.registering) {
      this.publish();
      return;
    }
    try {
      this.snapshot.supported = partnerAgent.isPartnerAgentSupported();
      if (!this.snapshot.supported) {
        this.snapshot.registered = false;
        this.snapshot.status = '当前系统暂不支持 PartnerAgent';
        this.snapshot.error = '需要 HarmonyOS 6.1（API 23）或更高版本';
        this.publish();
        return;
      }
      const bound = partnerAgent.getBoundDevices();
      this.snapshot.registered = bound.length > 0;
      this.snapshot.deviceAddress = bound.length > 0 ?
        (bound[0].bluetoothAddress?.address ?? '') : '';
      this.snapshot.status = this.snapshot.registered ? '鸿蒙兜底授时已启用' : '尚未注册仪表';
      this.snapshot.error = '';
    } catch (_error) {
      // ACCESS_BLUETOOTH may not have been granted before the first launch.
      this.snapshot.supported = partnerAgent.isPartnerAgentSupported();
      this.snapshot.status = '请授权鸿蒙兜底授时';
    }
    this.publish();
  }

  async enableFromUser(): Promise<boolean> {
    if (this.scanning || this.snapshot.registering) return false;
    if (!this.context) {
      this.fail('应用上下文尚未就绪');
      return false;
    }
    if (!partnerAgent.isPartnerAgentSupported()) {
      this.snapshot.supported = false;
      this.fail('当前系统不支持 PartnerAgent，需要 HarmonyOS 6.1 或更高版本');
      return false;
    }
    try {
      this.snapshot.supported = true;
      this.snapshot.diagnostics = '';
      this.knownDiagnostic = '';
      this.seenDevices.clear();
      this.snapshot.status = '正在请求附近设备权限…';
      this.snapshot.error = '';
      this.publish();
      const manager = abilityAccessCtrl.createAtManager();
      const permission: Permissions = 'ohos.permission.ACCESS_BLUETOOTH';
      const result = await manager.requestPermissionsFromUser(this.context, [permission]);
      if (result.authResults.length === 0 || result.authResults[0] !== 0) {
        this.fail('需要“附近设备”权限才能注册仪表');
        return false;
      }
      const bound = partnerAgent.getBoundDevices();
      if (bound.length > 0) {
        this.snapshot.supported = true;
        this.snapshot.registered = true;
        this.snapshot.deviceAddress = bound[0].bluetoothAddress?.address ?? '';
        this.snapshot.status = '鸿蒙兜底授时已启用';
        this.snapshot.error = '';
        this.publish();
        return true;
      }
      // The gauge stops advertising when Android owns the GATT link.
      // System-known devices allow registration without a second connection.
      const known = this.findKnownGauges();
      if (known.length === 1) {
        try {
          await this.bindDevice(known[0]);
        } catch (error) {
          this.failBind(error as BusinessError);
          return false;
        }
        return true;
      }
      if (known.length > 1) {
        this.fail('系统中有多台 BRZ 仪表，无法确定要注册哪一台');
        return false;
      }
      this.startOneTimeScan();
      return true;
    } catch (error) {
      const businessError = error as BusinessError;
      this.fail(`启用失败（${businessError.code}）`);
      return false;
    }
  }

  private findKnownGauges(): string[] {
    const candidates: string[] = [];
    try {
      const paired = connection.getPairedDevices();
      this.knownDiagnostic = `已配对 ${paired.length} 台`;
      candidates.push(...paired);
      hilog.warn(DOMAIN, TAG, 'Paired device count: %{public}d', paired.length);
    } catch (error) {
      this.knownDiagnostic = `配对列表读取失败 ${(error as BusinessError).code}`;
      hilog.warn(DOMAIN, TAG, 'Paired device lookup failed: %{public}d',
        (error as BusinessError).code);
    }
    try {
      const connected = ble.getConnectedBLEDevices();
      this.knownDiagnostic += `；BLE 已连接 ${connected.length} 台`;
      for (const address of connected) {
        if (!candidates.some((item: string) => item.toUpperCase() === address.toUpperCase())) {
          candidates.push(address);
        }
      }
      hilog.warn(DOMAIN, TAG, 'Connected BLE device count: %{public}d', connected.length);
    } catch (error) {
      this.knownDiagnostic += `；连接列表读取失败 ${(error as BusinessError).code}`;
      hilog.warn(DOMAIN, TAG, 'Connected device lookup failed: %{public}d',
        (error as BusinessError).code);
    }
    const gauges: string[] = [];
    for (const address of candidates) {
      try {
        const name = connection.getRemoteDeviceName(address, false);
        if (name === 'SkyGarageRC' || /^SkyGauge-[0-9a-f]{4}$/i.test(name)) {
          gauges.push(address);
        }
      } catch (error) {
        hilog.warn(DOMAIN, TAG, 'Device name lookup failed: %{public}d',
          (error as BusinessError).code);
      }
    }
    hilog.warn(DOMAIN, TAG, 'Known gauge count: %{public}d', gauges.length);
    this.knownDiagnostic += `；识别仪表 ${gauges.length} 台`;
    this.snapshot.diagnostics = this.knownDiagnostic;
    return gauges;
  }

  async disableFromUser(): Promise<void> {
    this.stopScan();
    try {
      const bound = partnerAgent.getBoundDevices();
      for (const device of bound) {
        await partnerAgent.unbindDevice(device);
      }
      this.snapshot.registered = false;
      this.snapshot.deviceAddress = '';
      this.snapshot.status = '鸿蒙兜底授时已关闭';
      this.snapshot.error = '';
    } catch (error) {
      const businessError = error as BusinessError;
      this.fail(`关闭失败（${businessError.code}）`);
      return;
    }
    this.publish();
  }

  private startOneTimeScan(): void {
    if (this.scanning) {
      return;
    }
    try {
      ble.off('BLEDeviceFind');
    } catch (_) {
    }
    try {
      ble.on('BLEDeviceFind', (results: Array<ble.ScanResult>) => {
        if (!this.scanning) return;
        const previousCount = this.seenDevices.size;
        for (const result of results) this.seenDevices.add(result.deviceId);
        if (previousCount !== this.seenDevices.size) {
          hilog.info(DOMAIN, TAG, 'Distinct scan devices: %{public}d', this.seenDevices.size);
        }
        this.snapshot.diagnostics = `${this.knownDiagnostic}\n广播发现 ${this.seenDevices.size} 台设备`;
        // Registration needs a paired identity, not a connectable advertising flag.
        const match = results.find((result: ble.ScanResult) =>
          hasGaugeService(result.data, result.serviceUuids ?? []));
        if (match) {
          this.snapshot.diagnostics += '；已收到仪表服务广播';
          this.stopScan();
          this.bindDevice(match.deviceId).catch((error: BusinessError) => {
            this.failBind(error);
          });
        } else {
          this.publish();
        }
      });
      this.scanning = true;
      this.snapshot.scanning = true;
      ble.startBLEScan(
        null,
        {
          interval: 0,
          dutyMode: ble.ScanDuty.SCAN_MODE_LOW_LATENCY,
          matchMode: ble.MatchMode.MATCH_MODE_AGGRESSIVE
        }
      );
      this.snapshot.status = '正在查找仪表（最多 30 秒）…';
      this.snapshot.error = '';
      this.publish();
      this.scanTimer = setTimeout(() => {
        if (this.scanning) {
          this.stopScan();
          this.fail(this.seenDevices.size === 0 ?
            '扫描结束：系统没有返回任何蓝牙广播，请提供下方诊断信息' :
            '扫描结束：收到其他蓝牙广播，但没有仪表服务，请提供下方诊断信息');
        }
      }, SCAN_TIMEOUT_MS);
    } catch (error) {
      this.stopScan();
      const businessError = error as BusinessError;
      hilog.warn(DOMAIN, TAG, 'Scan start failed: %{public}d %{public}s',
        businessError.code, businessError.message);
      this.fail(`扫描启动失败（${businessError.code}）`);
    }
  }

  private async bindDevice(address: string): Promise<void> {
    this.snapshot.registering = true;
    this.snapshot.deviceAddress = address;
    this.snapshot.error = '';
    try {
      if (connection.getPairState(address) !== connection.BondState.BOND_STATE_BONDED) {
        this.snapshot.status = '正在完成鸿蒙蓝牙配对，请确认系统弹窗…';
        this.publish();
        await this.pairNativeDevice(address);
      }
    hilog.warn(DOMAIN, TAG, 'Registering known/discovered gauge');
    this.snapshot.status = '正在交给鸿蒙系统注册…';
    this.snapshot.deviceAddress = address;
    this.publish();
    const deviceAddress: partnerAgent.PartnerDeviceAddress = {
      bluetoothAddress: {
        address: address,
        addressType: bluetoothCommon.BluetoothAddressType.VIRTUAL
      }
    };
    await partnerAgent.bindDevice(
      deviceAddress,
      { supportBR: false, supportBleAdvertiser: true },
      { supportMediaControl: false, supportTelephonyControl: false },
      PARTNER_AGENT_ABILITY
    );
    this.snapshot.supported = true;
    this.snapshot.registered = true;
    this.snapshot.status = '鸿蒙兜底授时已启用';
    hilog.warn(DOMAIN, TAG, 'Gauge registration completed');
    this.snapshot.error = '';
    } finally {
      this.snapshot.registering = false;
      this.publish();
    }
  }

  private pairNativeDevice(address: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      let finished = false;
      let timer = -1;
      const finish = (message: string): void => {
        if (finished) return;
        finished = true;
        if (timer >= 0) clearTimeout(timer);
        try { connection.off('bondStateChange', onBond); } catch (_) { }
        if (message.length > 0) reject(new Error(message));
        else resolve();
      };
      const onBond = (event: connection.BondStateParam): void => {
        if (event.deviceId.toUpperCase() !== address.toUpperCase()) return;
        hilog.warn(DOMAIN, TAG, 'Native pairing state: %{public}d', event.state);
        if (event.state === connection.BondState.BOND_STATE_BONDED) finish('');
        else if (event.state === connection.BondState.BOND_STATE_INVALID) {
          finish('鸿蒙蓝牙配对未完成，请重试并确认系统配对弹窗');
        }
      };
      try {
        connection.on('bondStateChange', onBond);
        timer = setTimeout(() => finish('鸿蒙蓝牙配对超时，请保持仪表开启并确认系统配对弹窗'), 45000);
        hilog.warn(DOMAIN, TAG, 'Starting native Bluetooth pairing');
        connection.pairDevice({ address: address,
          addressType: bluetoothCommon.BluetoothAddressType.VIRTUAL }).then(() => {
          try {
            if (connection.getPairState(address) === connection.BondState.BOND_STATE_BONDED) finish('');
          } catch (error) { finish(`配对状态读取失败（${(error as BusinessError).code}）`); }
        }).catch((error: BusinessError) => {
          finish(`鸿蒙蓝牙配对失败（${error.code}）：${error.message}`);
        });
      } catch (error) {
        finish(`鸿蒙蓝牙配对启动失败（${(error as BusinessError).code}）`);
      }
    });
  }

  private failBind(error: BusinessError): void {
    hilog.warn(DOMAIN, TAG, 'Gauge registration failed: %{public}d %{public}s',
      error.code, error.message);
    if (error.code === 34900004) {
      this.refresh();
      return;
    }
    if (error.code === 34900003) {
      this.fail('鸿蒙系统尚未认可仪表配对，请重新启用授时桥完成原生配对');
      return;
    }
    if (!error.code) {
      this.fail(error.message || '鸿蒙蓝牙配对未完成');
      return;
    }
    this.fail(`系统注册失败（${error.code}）`);
  }

  private stopScan(): void {
    if (this.scanTimer >= 0) {
      clearTimeout(this.scanTimer);
      this.scanTimer = -1;
    }
    if (this.scanning) {
      try {
        ble.stopBLEScan();
      } catch (_) {
      }
    }
    this.scanning = false;
    this.snapshot.scanning = false;
    try {
      ble.off('BLEDeviceFind');
    } catch (_) {
    }
  }

  private fail(message: string): void {
    hilog.warn(DOMAIN, TAG, '%{public}s', message);
    this.snapshot.status = message;
    this.snapshot.error = message;
    this.publish();
  }

  private publish(): void {
    this.listener?.({
      supported: this.snapshot.supported,
      registered: this.snapshot.registered,
      scanning: this.snapshot.scanning,
      registering: this.snapshot.registering,
      status: this.snapshot.status,
      deviceAddress: this.snapshot.deviceAddress,
      error: this.snapshot.error,
      diagnostics: this.snapshot.diagnostics
    });
  }
}

export const partnerWakeManager = PartnerWakeManager.getInstance();
