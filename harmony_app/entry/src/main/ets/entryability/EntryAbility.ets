import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { automatic } from '../platform/Automatic';
import { bluetooth } from '../platform/Bluetooth';

export default class EntryAbility extends UIAbility {
  onWindowStageCreate(stage: window.WindowStage): void {
    stage.loadContent('pages/Index', (error) => { if (error.code) console.error('Cannot load garage page: ' + error.code); });
  }
  onForeground(): void { automatic.foreground = true; automatic.start(this.context); }
  onBackground(): void { automatic.foreground = false; if (!automatic.backgroundHeld) { automatic.stop(); } }
  onDestroy(): void { automatic.stop(); }
}
