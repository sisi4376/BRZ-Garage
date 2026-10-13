import { PartnerAgentExtensionAbility, partnerAgent } from '@kit.ConnectivityKit';
import { repository } from '../platform/Repository';
import { bluetooth } from '../platform/Bluetooth';

export default class PartnerAgentAbility extends PartnerAgentExtensionAbility {
  private busy: boolean = false;
  private stopped: boolean = false;
  onDeviceDiscovered(device: partnerAgent.PartnerDeviceAddress): void {
    const address = device.bluetoothAddress?.address.toUpperCase() || '';
    if (!address || this.busy || this.stopped || bluetooth.isBusy()) return;
    this.busy = true;
    this.synchronize(address).catch(() => { /* Durable pending work remains queued for the next connection. */ }).finally(() => { this.busy = false; });
  }
  private async synchronize(address: string): Promise<void> {
    await repository.open(this.context); await repository.reload();
    if (this.stopped || !repository.consent() || !repository.automatic() || repository.binding().toUpperCase() !== address) return;
    await bluetooth.syncBackground(this.context, address);
  }
  onDestroyWithReason(_reason: partnerAgent.PartnerAgentExtensionAbilityDestroyReason): void { this.stopped = true; bluetooth.cancelBackground(); }
}
