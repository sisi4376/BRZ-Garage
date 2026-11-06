import { Row, Snapshot } from './Models';
import { ledgerSection } from './Ledger';
import { Registration, registration, monthEnd } from './Registration';
import { dayText, parseDay, today } from './Garage';
import { SERVICES, matches, addMonths } from './Maintenance';
export interface DeadlineTrack { record: Row; due: number | null; reminder: number | null; status: string; detail: string; profile: boolean; }
export function insuranceName(value: string): string {
 const name=value.trim();
 if(['机动车交通事故责任强制保险','机动车交强险','交强保险','交强险'].includes(name))return '交强险';
 if(['商业车险','车辆商业险','商业保险','商业险'].includes(name))return '商业险';
 return name||'未分类保险';
}
export function inspectionWindow(day: number): number { const d=new Date(day*86400000);return Date.UTC(d.getUTCFullYear(),d.getUTCMonth()-2,1)/86400000; }
export function inspectionAction(start: number|null,due: number,regular: boolean): string {
 if(!regular||start===null)return '办理方式待核实 · 以交管12123为准';
 const a=new Date(start*86400000),b=new Date(due*86400000),age=b.getUTCFullYear()-a.getUTCFullYear();
 if(a.getUTCMonth()!==b.getUTCMonth()||age<2||age<10&&age%2!==0)return '周期与常规规则不一致 · 请核对交管12123';
 return [2,4,8].includes(age)?'免上线 · 仍须申领检验标志':'需上线安全技术检验';
}
export function deadlineTracks(records: Row[],profiles: Record<string,Registration>,day: number=parseDay(today())): DeadlineTrack[] {
 const result:DeadlineTrack[]=[],groups:Record<string,Row[]>={};
 const status=(due:number|null):string=>due===null?'待补充到期日期':due<day?'已到期 '+(day-due)+' 天':due===day?'今天到期':'剩余 '+(due-day)+' 天';
 const outgoing=records.filter((r:Row)=>r.income!==1);
 outgoing.filter((r:Row)=>ledgerSection(r)==='insurance').forEach((r:Row)=>{const key=r.device_id+'|'+insuranceName(String(r.item_name||''));if(!groups[key])groups[key]=[];groups[key].push(r);});
 Object.keys(groups).forEach((key:string)=>{
  const rows=groups[key],dated=rows.filter((r:Row)=>r.due_epoch_day!=null&&(r.start_epoch_day==null||Number(r.start_epoch_day)<=Number(r.due_epoch_day)));
  const active=dated.filter((r:Row)=>r.start_epoch_day!=null&&Number(r.start_epoch_day)<=day&&Number(r.due_epoch_day)>=day).sort((a:Row,b:Row)=>Number(b.due_epoch_day)-Number(a.due_epoch_day))[0];
  const past=dated.filter((r:Row)=>Number(r.due_epoch_day)<day).sort((a:Row,b:Row)=>Number(b.due_epoch_day)-Number(a.due_epoch_day))[0];
  const unknown=dated.filter((r:Row)=>r.start_epoch_day==null&&Number(r.due_epoch_day)>=day).sort((a:Row,b:Row)=>Number(a.due_epoch_day)-Number(b.due_epoch_day))[0];
  const future=dated.filter((r:Row)=>r.start_epoch_day!=null&&Number(r.start_epoch_day)>day).sort((a:Row,b:Row)=>Number(a.start_epoch_day)-Number(b.start_epoch_day))[0];
  const current=active||past||unknown||future;
  if(!current){result.push({record:rows.slice().sort((a:Row,b:Row)=>Number(b.date_epoch_day)-Number(a.date_epoch_day))[0],due:null,reminder:null,status:'待补充保单保障日期',detail:'付款日期不代表保障生效日期',profile:false});return;}
  let end=Number(current.due_epoch_day);const detail:string[]=[];
  if(current.start_epoch_day!=null)dated.filter((r:Row)=>r.start_epoch_day!=null).sort((a:Row,b:Row)=>Number(a.start_epoch_day)-Number(b.start_epoch_day)).forEach((r:Row)=>{if(Number(r.start_epoch_day)<=end+1&&Number(r.due_epoch_day)>end)end=Number(r.due_epoch_day);});
  if(end>Number(current.due_epoch_day))detail.push('已记录连续续保，后续保障至 '+dayText(end));else if(future&&future!==current)detail.push('后续保单 '+dayText(Number(future.start_epoch_day))+' 生效；日期存在空档，请核对');
  if(current.start_epoch_day==null)detail.push('生效日期未知，不能确认当前保障状态');
  if(rows.some((r:Row)=>r.due_epoch_day==null))detail.push('另有保单缺少到期日，未用于取消已有提醒');
  if(current.start_epoch_day!=null&&rows.some((r:Row)=>r!==current&&r.start_epoch_day==null))detail.push('另有保单缺少生效日，未认定为连续续保');
  detail.push('按日期追踪，起止时刻及承保状态以保单为准');
  const pending=current.start_epoch_day!=null&&Number(current.start_epoch_day)>day;
  result.push({record:current,due:Number(current.due_epoch_day),reminder:pending?null:end,status:pending?'待生效 · '+dayText(Number(current.start_epoch_day)):(current.start_epoch_day!=null&&Number(current.due_epoch_day)>=day?'保障日期内 · ':'')+status(Number(current.due_epoch_day)),detail:detail.join('；'),profile:false});
 });
 const documents=outgoing.filter((r:Row)=>ledgerSection(r)==='inspection'&&profiles[String(r.device_id)]?.inspectionDueDay==null);
 Object.keys(profiles).forEach((device:string)=>{const p=profiles[device];documents.push({device_id:device,transfer_id:'profile:'+device,date_epoch_day:p.registeredDay,title:'年审',ledger_kind:'inspection',start_epoch_day:p.registeredDay,due_epoch_day:p.inspectionDueDay,item_name:p.regularPrivateCar?'常规私家车检验':'其他 / 特殊车辆检验'});});
 const docGroups:Record<string,Row[]>={};documents.forEach((r:Row)=>{const key=String(r.device_id);if(!docGroups[key])docGroups[key]=[];docGroups[key].push(r);});
 Object.keys(docGroups).forEach((device:string)=>{const current=docGroups[device].sort((a:Row,b:Row)=>Number(b.due_epoch_day??-1e12)-Number(a.due_epoch_day??-1e12)||Number(b.date_epoch_day)-Number(a.date_epoch_day))[0],profile=String(current.transfer_id).startsWith('profile:'),due=current.due_epoch_day==null?null:monthEnd(Number(current.due_epoch_day));
  const detail=due===null?(profile?'特殊车辆请按交管12123填写实际有效期':''):(profile&&profiles[device].estimated?'按规则预计，初次设置假定往期已办结，请核对12123；':'')+inspectionAction(current.start_epoch_day==null?null:Number(current.start_epoch_day),due,current.item_name==='常规私家车检验')+'；可办理窗口 '+dayText(inspectionWindow(due))+' 至 '+dayText(due)+'；请先处理违法和事故，核对有效交强险、车船税。以交管12123为准';
  result.push({record:current,due,reminder:due,status:status(due),detail,profile});
 });return result.sort((a:DeadlineTrack,b:DeadlineTrack)=>(a.due??1e12)-(b.due??1e12));
}
export function vehicleDeadlines(s:Snapshot,gauge:string,day:number=parseDay(today())):DeadlineTrack[]{
 const device=gauge||'local',profiles:Record<string,Registration>={},p=registration(s,device);if(p)profiles[device]=p;
 const rows=s.tables.expenses.filter((r:Row)=>r.device_id===device||r.device_id==='local').map((r:Row):Row=>({...r,device_id:device}));return deadlineTracks(rows,profiles,day);
}
export interface Reminder { key:string; title:string; due:number; window:number|null; }
export function reminderStage(r:Reminder,day:number):number|null { const left=r.due-day;return left<0?-1:left===0?0:left<=3?3:left<=7?7:left<=30?30:r.window!==null&&day>=r.window?90:null; }
export function ledgerReminders(s:Snapshot,day:number=parseDay(today())):Reminder[]{
 const all=s.tables.expenses.map((r:Row):Row=>({...r,device_id:r.device_id==='local'&&s.gauge?s.gauge:r.device_id}));
 const devices=Array.from(new Set(all.map((r:Row)=>String(r.device_id)).concat(Object.keys(s.preferences).filter((k:string)=>k.startsWith('vehicle_registration_')).map((k:string)=>k.substring(21))))),result:Reminder[]=[];
 devices.forEach((device:string)=>{
  const profiles:Record<string,Registration>={},p=registration(s,device);if(p)profiles[device]=p;
  const rows=all.filter((r:Row)=>r.device_id===device);
  deadlineTracks(rows,profiles,day).forEach((t:DeadlineTrack)=>{if(t.reminder===null)return;const inspection=ledgerSection(t.record)==='inspection',name=inspection?'机动车年检':insuranceName(String(t.record.item_name||''));result.push({key:(inspection?'inspection':'insurance')+'|'+device+'|'+name+'|'+t.reminder,title:inspection?inspectionAction(t.record.start_epoch_day==null?null:Number(t.record.start_epoch_day),t.reminder,t.record.item_name==='常规私家车检验'):'保险续保 · '+name,due:t.reminder,window:inspection?inspectionWindow(t.reminder):null});});
  SERVICES.forEach((service,index:number)=>{if(service.months===null)return;const last=rows.filter((r:Row)=>r.income!==1&&r.category===1&&matches(String(r.title),service)).sort((a:Row,b:Row)=>Number(b.date_epoch_day)-Number(a.date_epoch_day))[0];if(last){const due=addMonths(Number(last.date_epoch_day),service.months);result.push({key:'maintenance|'+device+'|'+index+'|'+due,title:'保养 · '+service.title,due,window:null});}});
 });return result;
}
