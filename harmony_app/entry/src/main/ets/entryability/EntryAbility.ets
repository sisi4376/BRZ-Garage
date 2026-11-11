import { diagnostics } from '../platform/Diagnostics';
import { ledgerNotifications } from '../platform/LedgerNotifications';
import { Want, AbilityConstant } from '@kit.AbilityKit';
import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { automatic } from '../platform/Automatic';
import { bluetooth } from '../platform/Bluetooth';

export default class EntryAbility extends UIAbility {
  onCreate(want:Want,launchParam:AbilityConstant.LaunchParam):void { diagnostics.install(this.context); diagnostics.step('ability.create'); AppStorage.setOrCreate('open_bookkeeping',want.parameters?.open_bookkeeping===true); }
  onNewWant(want:Want,launchParam:AbilityConstant.LaunchParam):void { AppStorage.setOrCreate('open_bookkeeping',want.parameters?.open_bookkeeping===true); }
  onWindowStageCreate(stage: window.WindowStage): void {
    stage.loadContent('pages/Index', (error) => { if (error.code) console.error('Cannot load garage page: ' + error.code); });
  }
  onForeground(): void { ledgerNotifications.schedule().catch((error:Error)=>console.error(error.message)); automatic.foreground = true; automatic.start(this.context); }
  onBackground(): void { automatic.foreground = false; if (!automatic.backgroundHeld) { automatic.stop(); } }
  onDestroy(): void { automatic.stop(); }
}
