import { common, wantAgent } from '@kit.AbilityKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { bluetooth } from './Bluetooth';
import { repository } from './Repository';
import { parseVehicle } from '../core/Protocol';

export class Automatic {
  running: boolean = false;
  suspended: boolean = false;
  foreground: boolean = true;
  backgroundHeld: boolean = false;
  status: string = '';
  private job: Promise<void> | null = null;
  private wake: (() => void) | null = null;
  private async pause(ms: number): Promise<void> {
    await new Promise<void>((resolve) => { const timer = setTimeout(() => { this.wake = null; resolve(); }, ms);
      this.wake = () => { clearTimeout(timer); this.wake = null; resolve(); }; });
  }
  async stop(): Promise<void> { this.running = false; if (this.wake) this.wake(); bluetooth.close(); if (this.job) await this.job; }
  start(context: common.UIAbilityContext): void {
    if (this.suspended) return;
    if (this.job && !this.running) { this.job.then(() => { if (this.foreground) this.start(context); }); return; }
    if (this.running || !repository.automatic() || !repository.binding() || !repository.consent()) return;
    this.running = true;
    this.job = this.loop(context).finally(() => { this.running = false; this.job = null; });
  }
  private async loop(context: common.UIAbilityContext): Promise<void> {
    let failures = 0;
    while (this.running) {
      try {
        await bluetooth.session(context, repository.binding(), (s: string) => this.status = s, async () => {

          try {
            const agent = await wantAgent.getWantAgent({ wants: [{ bundleName: 'com.brz.gauge.garage', abilityName: 'EntryAbility' }],
              actionType: wantAgent.OperationType.START_ABILITY, requestCode: 1, actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG] });
            await backgroundTaskManager.startBackgroundRunning(context, backgroundTaskManager.BackgroundMode.BLUETOOTH_INTERACTION, agent);
            this.backgroundHeld = true;
          } catch (_) { this.status = '系统未允许后台运行，保持前台可继续同步'; }
          let tick = 0;
          while (this.running) {
            if (tick % 10 === 0) {
              const result = await bluetooth.syncConnected(repository.binding(), (s: string) => this.status = s);
              failures = 0; this.status = result.warning || '自动连接中 · 行程已保存';
            } else if (bluetooth.has(6)) await repository.cacheVehicle(parseVehicle(await bluetooth.read(6)));
            tick++; await this.pause(3000);
          }
        });
      } catch (error) { this.status = '连接中断：' + (error as Error).message; failures++; }
      finally {
        if (this.backgroundHeld) { try { await backgroundTaskManager.stopBackgroundRunning(context); } catch (_) {} this.backgroundHeld = false; }
      }
      if (!this.running || !this.foreground && failures >= 3) break;
      this.status += ' · 等待重连'; await this.pause(this.foreground ? 10000 : 3000);
    }
  }
}
export const automatic = new Automatic();
