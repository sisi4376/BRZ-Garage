import { PartnerAgentExtensionAbility, partnerAgent } from '@kit.ConnectivityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { bleTimeBridge } from '../bridge/BleTimeBridge';

const DOMAIN = 0x4252;
const TAG = 'BrzPartnerAgent';
/** System-launched discovery callback for the isolated one-shot clock path. */
export default class PartnerAgentAbility extends PartnerAgentExtensionAbility {
  onDeviceDiscovered(deviceAddress: partnerAgent.PartnerDeviceAddress): void {
    const address = deviceAddress.bluetoothAddress?.address ?? '';
    hilog.info(DOMAIN, TAG, 'Partner gauge discovered: %{private}s', address);
    bleTimeBridge.syncWhenDiscovered(address);
  }

  onDestroyWithReason(reason: partnerAgent.PartnerAgentExtensionAbilityDestroyReason): void {
    bleTimeBridge.cancel();
    hilog.info(DOMAIN, TAG, 'PartnerAgent destroyed: %{public}d', reason);
  }
}
