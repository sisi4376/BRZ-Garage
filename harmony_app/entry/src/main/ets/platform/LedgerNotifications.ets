import { notificationManager } from '@kit.NotificationKit';
import { common, wantAgent } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { workScheduler } from '@kit.BackgroundTasksKit';
import { repository } from './Repository';
import { ledgerReminders, reminderStage, Reminder } from '../core/Deadlines';
import { parseDay, today } from '../core/Garage';

interface DeliveryState { sent:string[]; visible:string[]; content:string; }
class LedgerNotifications {
  private running: Promise<void> | null = null;
  private work(): workScheduler.WorkInfo { return {workId:4454,bundleName:'com.brz.gauge.garage',abilityName:'LedgerWorkAbility',isRepeat:true,repeatCycleTime:6*60*60*1000}; }
  async schedule(): Promise<void> {
    try { await workScheduler.getWorkStatus(4454); }
    catch (_) { workScheduler.startWork(this.work()); }
  }
  async permission(context: common.UIAbilityContext): Promise<void> {
    if(await notificationManager.isNotificationEnabled())await notificationManager.openNotificationSettings(context);
    else await notificationManager.requestEnableNotification(context);
    await this.schedule();await this.check(context);
  }
  enabled(): Promise<boolean> { return notificationManager.isNotificationEnabled(); }
  check(context: common.Context): Promise<void> {
    if(this.running)return this.running;
    this.running=this.deliver(context).finally(()=>{this.running=null;});return this.running;
  }
  private async deliver(context: common.Context): Promise<void> {
    await repository.open(context);await repository.reload();
    if(!repository.consent()){await notificationManager.cancel(4454);return;}
    const store=await preferences.getPreferences(context,{name:'ledger-notifications'});
    const previous=JSON.parse(String(await store.get('delivery','{"sent":[],"visible":[],"content":""}'))) as DeliveryState;
    const day=parseDay(today()),reminders=ledgerReminders(repository.snapshot(),day);
    const due=reminders.filter((r:Reminder)=>reminderStage(r,day)!==null);
    const fresh=due.filter((r:Reminder)=>!previous.sent.includes(r.key+'|'+reminderStage(r,day)));
    const visible=reminders.filter((r:Reminder)=>previous.visible.includes(r.key)||fresh.some((f:Reminder)=>f.key===r.key));
    if(!visible.length){await notificationManager.cancel(4454);await store.put('delivery',JSON.stringify({sent:previous.sent,visible:[],content:''} as DeliveryState));await store.flush();return;}
    if(!await notificationManager.isNotificationEnabled())return;
    const content=visible.map((r:Reminder)=>r.title+' · '+(r.due<day?'已到期 '+(day-r.due)+' 天，请核实办理':r.due===day?'今天到期':'还有 '+(r.due-day)+' 天到期')).join('\n');
    if(!fresh.length&&content===previous.content&&visible.length===previous.visible.length&&visible.every((r:Reminder)=>previous.visible.includes(r.key)))return;
    const agent=await wantAgent.getWantAgent({wants:[{bundleName:'com.brz.gauge.garage',abilityName:'EntryAbility',parameters:{open_bookkeeping:true}}],actionType:wantAgent.OperationType.START_ABILITY,requestCode:4454,actionFlags:[wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]});
    await notificationManager.publish({id:4454,slotType:1,wantAgent:agent,tapDismissed:true,isAlertOnce:!fresh.length,content:{contentType:0,normal:{title:'用车到期提醒',text:content}}});
    const sent=previous.sent.filter((key:string)=>reminders.some((r:Reminder)=>key.startsWith(r.key+'|'))).concat(fresh.map((r:Reminder)=>r.key+'|'+reminderStage(r,day)));
    const saved:DeliveryState={sent,visible:visible.map((r:Reminder)=>r.key),content};await store.put('delivery',JSON.stringify(saved));await store.flush();
  }
}
export const ledgerNotifications=new LedgerNotifications();
