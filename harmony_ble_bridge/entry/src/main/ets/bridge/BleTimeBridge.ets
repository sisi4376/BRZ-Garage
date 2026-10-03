import { ble, constant } from '@kit.ConnectivityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN = 0x4252;
const TAG = 'BrzBleTimeBridge';
const INFO_SERVICE = '00001ffa-0000-1000-8000-00805f9b34fb';
const HARMONY_TIME_SYNC = '0000000f-0000-1000-8000-00805f9b34fb';
const ANDROID_PRIORITY_GRACE_MS = 8000;
const CONNECT_TIMEOUT_MS = 12000;
const RETRY_COOLDOWN_MS = 120000;
const MIN_VALID_EPOCH_S = 1704067200;
const MAX_VALID_EPOCH_S = 4102444800;

/**
 * One-shot fallback clock sync used only when Android did not connect first.
 * It never reads trip data, subscribes to notifications, scans continuously,
 * or keeps a GATT link after the acknowledged 8-byte clock write.
 */
export class BleTimeBridge {
  private static instance: BleTimeBridge = new BleTimeBridge();
  private client?: ble.GattClientDevice;
  private graceTimer: number = -1;
  private timeoutTimer: number = -1;
  private busy: boolean = false;
  private connected: boolean = false;
  private targetAddress: string = '';
  private lastAttemptMs: number = 0;

  static getInstance(): BleTimeBridge {
    return BleTimeBridge.instance;
  }

  syncWhenDiscovered(address: string): void {
    const normalized = this.normalizeAddress(address);
    const now = Date.now();
    if (normalized.length === 0 || this.busy || now - this.lastAttemptMs < RETRY_COOLDOWN_MS) {
      return;
    }

    this.busy = true;
    this.targetAddress = normalized;
    this.lastAttemptMs = now;
    hilog.info(DOMAIN, TAG, 'Gauge discovered; Android has priority for %{public}d ms',
      ANDROID_PRIORITY_GRACE_MS);
    this.graceTimer = setTimeout(() => {
      this.graceTimer = -1;
      this.connectIfAndroidIsAbsent(normalized);
    }, ANDROID_PRIORITY_GRACE_MS);
  }

  cancel(): void {
    this.finish();
  }

  private connectIfAndroidIsAbsent(address: string): void {
    if (this.isAlreadyConnected(address)) {
      hilog.info(DOMAIN, TAG, 'Gauge already connected; leaving the link to Android');
      this.finish();
      return;
    }

    try {
      this.client = ble.createGattClientDevice(address);
      this.client.on('BLEConnectionStateChange', this.connectionChanged);
      this.timeoutTimer = setTimeout(() => {
        hilog.warn(DOMAIN, TAG, 'One-shot clock connection timed out');
        this.finish();
      }, CONNECT_TIMEOUT_MS);
      this.client.connect();
    } catch (error) {
      const businessError = error as BusinessError;
      hilog.warn(DOMAIN, TAG, 'Unable to start one-shot clock connection: %{public}d',
        businessError.code);
      this.finish();
    }
  }

  private connectionChanged = (state: ble.BLEConnectionChangeState): void => {
    if (this.normalizeAddress(state.deviceId) !== this.targetAddress) {
      return;
    }
    if (state.state === constant.ProfileConnectionState.STATE_CONNECTED && !this.connected) {
      this.connected = true;
      this.writeClockAndRelease();
    } else if (state.state === constant.ProfileConnectionState.STATE_DISCONNECTED && this.busy) {
      this.finish();
    }
  };

  private async writeClockAndRelease(): Promise<void> {
    const client = this.client;
    if (!client) {
      this.finish();
      return;
    }

    try {
      const services = await client.getServices();
      const service = services.find((item: ble.GattService) =>
        item.serviceUuid.toLowerCase() === INFO_SERVICE);
      const characteristic = service?.characteristics.find((item: ble.BLECharacteristic) =>
        item.characteristicUuid.toLowerCase() === HARMONY_TIME_SYNC);
      if (!characteristic) {
        hilog.warn(DOMAIN, TAG, 'Gauge firmware does not expose the isolated Harmony clock path');
        this.finish();
        return;
      }

      const epochSeconds = Math.floor(Date.now() / 1000);
      if (epochSeconds < MIN_VALID_EPOCH_S || epochSeconds > MAX_VALID_EPOCH_S) {
        hilog.warn(DOMAIN, TAG, 'System clock is outside the accepted range');
        this.finish();
        return;
      }

      characteristic.characteristicValue = this.timePacket(epochSeconds);
      await client.writeCharacteristicValue(characteristic, ble.GattWriteType.WRITE);
      hilog.info(DOMAIN, TAG, 'One-shot gauge clock sync completed');
    } catch (error) {
      const businessError = error as BusinessError;
      hilog.warn(DOMAIN, TAG, 'One-shot gauge clock sync failed: %{public}d', businessError.code);
    }
    this.finish();
  }

  private timePacket(epochSeconds: number): ArrayBuffer {
    const packet = new ArrayBuffer(8);
    const view = new DataView(packet);
    view.setUint32(0, epochSeconds, true);
    view.setUint32(4, 0, true);
    return packet;
  }

  private isAlreadyConnected(address: string): boolean {
    try {
      return ble.getConnectedBLEDevices().some((deviceId: string) =>
        this.normalizeAddress(deviceId) === address);
    } catch (_) {
      return false;
    }
  }

  private normalizeAddress(address: string): string {
    return address.trim().toUpperCase();
  }

  private finish(): void {
    if (this.graceTimer >= 0) {
      clearTimeout(this.graceTimer);
      this.graceTimer = -1;
    }
    if (this.timeoutTimer >= 0) {
      clearTimeout(this.timeoutTimer);
      this.timeoutTimer = -1;
    }
    const client = this.client;
    this.client = undefined;
    if (client) {
      try {
        client.off('BLEConnectionStateChange', this.connectionChanged);
      } catch (_) {
      }
      try {
        client.disconnect();
      } catch (_) {
      }
      try {
        client.close();
      } catch (_) {
      }
    }
    this.connected = false;
    this.targetAddress = '';
    this.busy = false;
  }
}

export const bleTimeBridge = BleTimeBridge.getInstance();
