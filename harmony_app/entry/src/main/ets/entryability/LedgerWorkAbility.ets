import { WorkSchedulerExtensionAbility, workScheduler } from '@kit.BackgroundTasksKit';
import { ledgerNotifications } from '../platform/LedgerNotifications';
export default class LedgerWorkAbility extends WorkSchedulerExtensionAbility {
  onWorkStart(work:workScheduler.WorkInfo):void { ledgerNotifications.check(this.context).catch((error:Error)=>console.error('Ledger reminder check failed: '+error.message)); }
  onWorkStop(work:workScheduler.WorkInfo):void {}
}
