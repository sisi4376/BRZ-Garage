import { errorManager, common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { APP_VERSION } from '../core/AppVersion';
import { diagnosticReport } from '../core/Diagnostics';

class Diagnostics {
  private path: string = '';
  private observer: number = -1;
  private stages: string[] = [];
  install(context: common.UIAbilityContext): void {
    this.path = context.filesDir + '/last-app-crash.txt';
    if (this.observer >= 0) return;
    try {
      this.observer = errorManager.on('error', {
        onUnhandledException: (message: string): void => { this.save(message); },
        onException: (error: Error): void => { this.save(error.stack || ''); }
      });
    } catch (_) {}
  }
  step(stage: string): void {
    if (!/^[A-Za-z0-9_.:= -]{1,100}$/.test(stage)) return;
    this.stages.push(Date.now() + ': ' + stage);
    this.stages = this.stages.slice(-24);
  }
  private save(stack: string): void {
    if (!this.path) return;
    try {
      const file = fs.openSync(this.path, fs.OpenMode.WRITE_ONLY | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try { fs.writeSync(file.fd, diagnosticReport(APP_VERSION, stack, this.stages)); fs.fsyncSync(file.fd); }
      finally { fs.closeSync(file); }
    } catch (_) {}
  }
  summary(): string {
    try { if (this.path && fs.statSync(this.path).size <= 12000) return fs.readTextSync(this.path); } catch (_) {}
    return '尚未记录到 ArkTS 未处理异常。';
  }
}
export const diagnostics = new Diagnostics();
