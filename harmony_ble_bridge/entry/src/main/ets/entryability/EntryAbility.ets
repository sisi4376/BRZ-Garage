import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { partnerWakeManager } from '../bridge/PartnerWakeManager';

const DOMAIN = 0x4252;
const TAG = 'BrzBridgeAbility';

export default class EntryAbility extends UIAbility {
  onCreate(_want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET);
    partnerWakeManager.attachContext(this.context);
    partnerWakeManager.refresh().catch(() => {
      hilog.error(DOMAIN, TAG, 'Unable to refresh PartnerAgent state');
    });
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (error) => {
      if (error.code) {
        hilog.error(DOMAIN, TAG, 'loadContent failed: %{public}d', error.code);
      }
    });
  }
}
