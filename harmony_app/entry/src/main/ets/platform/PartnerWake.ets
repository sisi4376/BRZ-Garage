import { common } from '@kit.AbilityKit';
import { connection, common as bluetoothCommon, partnerAgent } from '@kit.ConnectivityKit';
import { bluetooth } from './Bluetooth';
import { repository } from './Repository';
import { need } from '../core/Garage';

export class PartnerWake {
  supported(): boolean { try { return partnerAgent.isPartnerAgentSupported(); } catch (_) { return false; } }
  registered(address: string): boolean {
    try { return this.supported() && partnerAgent.getBoundDevices().some((d: partnerAgent.PartnerDeviceAddress) => d.bluetoothAddress?.address.toUpperCase() === address.toUpperCase()); } catch (_) { return false; }
  }
  async enable(context: common.UIAbilityContext, address: string): Promise<void> {
    need(this.supported(), '系统设备唤醒需要 HarmonyOS 6.1（API 23）或更高版本');
    need(repository.consent() && repository.binding() === address && !!address, '请先确认使用须知并绑定仪表');
    await bluetooth.permission(context);
    if (connection.getPairState(address) !== connection.BondState.BOND_STATE_BONDED) await this.pair(address);
    const device: partnerAgent.PartnerDeviceAddress = { bluetoothAddress: {address, addressType: bluetoothCommon.BluetoothAddressType.VIRTUAL} };
    if (!this.registered(address)) await partnerAgent.bindDevice(device, { supportBR: false, supportBleAdvertiser: true }, { supportMediaControl: false, supportTelephonyControl: false }, 'PartnerAgentAbility');
    await repository.setAutomatic(true);
  }
  async disable(): Promise<void> {
    if (!this.supported()) return;
    for (const d of partnerAgent.getBoundDevices()) await partnerAgent.unbindDevice(d);
  }
  private pair(address: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      let finished = false, timer = -1;
      const finish = (error: string): void => { if (finished) return; finished = true; clearTimeout(timer); try { connection.off('bondStateChange', listener); } catch (_) {} if (error) reject(new Error(error)); else resolve(); };
      const listener = (event: connection.BondStateParam): void => {
        if (event.deviceId.toUpperCase() !== address.toUpperCase()) return;
        if (event.state === connection.BondState.BOND_STATE_BONDED) finish('');
        else if (event.state === connection.BondState.BOND_STATE_INVALID) finish('配对未完成，请重试并确认系统弹窗');
      };
      try {
        connection.on('bondStateChange', listener); timer = setTimeout(() => finish('配对超时，请保持仪表开启'), 45000);
        connection.pairDevice({address, addressType: bluetoothCommon.BluetoothAddressType.VIRTUAL}).then(() => {
          if (connection.getPairState(address) === connection.BondState.BOND_STATE_BONDED) finish('');
        }).catch(() => finish('配对失败，请检查系统蓝牙设置'));
      } catch (_) { finish('系统无法开始配对'); }
    });
  }
}
export const partnerWake = new PartnerWake();
