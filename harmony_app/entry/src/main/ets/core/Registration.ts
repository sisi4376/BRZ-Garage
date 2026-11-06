import { Snapshot, Row } from './Models';
import { pref, putPref, need, parseDay, today, dayText } from './Garage';
import { ledgerSection } from './Ledger';
export interface Registration { registeredDay: number; regularPrivateCar: boolean; inspectionDueDay: number | null; estimated: boolean; }
export function monthEnd(day: number): number { const d=new Date(day*86400000);return Date.UTC(d.getUTCFullYear(),d.getUTCMonth()+1,0)/86400000; }
export function plusYears(day: number, years: number): number { const d=new Date(day*86400000),y=d.getUTCFullYear()+years,m=d.getUTCMonth();return Date.UTC(y,m,Math.min(d.getUTCDate(),new Date(Date.UTC(y,m+1,0)).getUTCDate()))/86400000; }
export function annualInsuranceEnd(day: number): number { return plusYears(day,1)-1; }
export function initialInspectionDue(day: number, at: number = parseDay(today())): number {
 const age=Math.max(0,new Date(at*86400000).getUTCFullYear()-new Date(day*86400000).getUTCFullYear());
 return monthEnd(plusYears(day,age<=2?2:age<10?age+age%2:age));
}
export function registration(s: Snapshot, gauge: string): Registration | null {
 try { const value=JSON.parse(String(pref(s,'vehicle_registration_'+(gauge||'local'),'null'))) as Registration | null;
   if(value===null||!Number.isInteger(value.registeredDay)||value.registeredDay<0||value.registeredDay>47482||typeof value.regularPrivateCar!=='boolean'||value.inspectionDueDay!==null&&(!Number.isInteger(value.inspectionDueDay)||value.inspectionDueDay<value.registeredDay||value.inspectionDueDay>47482))return null;
   return value;
 } catch (_) { return null; }
}
export function saveRegistration(s: Snapshot,gauge: string,value: Registration): void {
 need(value.registeredDay>=0&&value.registeredDay<=parseDay(today()),'初次注册日期无效');
 need(value.inspectionDueDay===null||value.inspectionDueDay>=value.registeredDay&&value.inspectionDueDay<=47482,'检验有效期无效');
 putPref(s,'vehicle_registration_'+(gauge||'local'),JSON.stringify(value));
}
export function nextInspectionDue(value: Registration): number | null {
 if(!value.regularPrivateCar||value.inspectionDueDay===null)return null;
 const registered=new Date(value.registeredDay*86400000),due=new Date(value.inspectionDueDay*86400000),age=due.getUTCFullYear()-registered.getUTCFullYear();
 if(due.getUTCMonth()!==registered.getUTCMonth()||age<2||age<10&&age%2!==0)return null;
 return monthEnd(plusYears(value.registeredDay,age+(age<10?2:1)));
}
export interface Deadline { title: string; due: number | null; detail: string; }
export function insuranceDeadlines(rows: Row[],at: number=parseDay(today())): Deadline[] {
 const groups: Record<string,Row[]>={};
 rows.filter((r:Row)=>r.income!==1&&ledgerSection(r)==='insurance').forEach((r:Row)=>{let name=String(r.item_name||'未分类保险').trim();if(['机动车交通事故责任强制保险','机动车交强险','交强保险'].includes(name))name='交强险';if(['商业车险','车辆商业险','商业保险'].includes(name))name='商业险';const key=String(r.device_id)+'|'+name;if(!groups[key])groups[key]=[];groups[key].push(r);});
 return Object.keys(groups).map((key:string):Deadline=>{
   const all=groups[key],dated=all.filter((r:Row)=>r.due_epoch_day!==null&&(r.start_epoch_day===null||Number(r.start_epoch_day)<=Number(r.due_epoch_day)));
   const active=dated.filter((r:Row)=>r.start_epoch_day!==null&&Number(r.start_epoch_day)<=at&&Number(r.due_epoch_day)>=at).sort((a:Row,b:Row)=>Number(b.due_epoch_day)-Number(a.due_epoch_day))[0];
   const past=dated.filter((r:Row)=>Number(r.due_epoch_day)<at).sort((a:Row,b:Row)=>Number(b.due_epoch_day)-Number(a.due_epoch_day))[0];
   const unknown=dated.filter((r:Row)=>r.start_epoch_day===null&&Number(r.due_epoch_day)>=at).sort((a:Row,b:Row)=>Number(a.due_epoch_day)-Number(b.due_epoch_day))[0];
   const future=dated.filter((r:Row)=>r.start_epoch_day!==null&&Number(r.start_epoch_day)>at).sort((a:Row,b:Row)=>Number(a.start_epoch_day)-Number(b.start_epoch_day))[0];
   const current=active||past||unknown||future;
   if(!current)return {title:key.split('|')[1],due:null,detail:'待补充保单保障日期；付款日期不代表保障生效日期'};
   let end=Number(current.due_epoch_day);
   if(current.start_epoch_day!==null)dated.filter((r:Row)=>r.start_epoch_day!==null).sort((a:Row,b:Row)=>Number(a.start_epoch_day)-Number(b.start_epoch_day)).forEach((r:Row)=>{if(Number(r.start_epoch_day)<=end+1&&Number(r.due_epoch_day)>end)end=Number(r.due_epoch_day);});
   const detail=(current.start_epoch_day!==null&&Number(current.start_epoch_day)>at?'待生效 · '+dayText(Number(current.start_epoch_day))+'；':'')+(end>Number(current.due_epoch_day)?'连续续保至 '+dayText(end):future&&future!==current?'后续保障存在空档，请核对':'')+(current.start_epoch_day===null?'生效日期未知；':'')+'按日期追踪，起止时刻及承保状态以保单为准';
   return {title:key.split('|')[1],due:end,detail};
 });
}

export function adoptLocalRegistration(s:Snapshot,gauge:string):boolean {
 const local=s.preferences.vehicle_registration_local,key='vehicle_registration_'+gauge;
 if(!gauge||gauge==='local'||!local||local.value===null||s.preferences[key])return false;
 s.preferences[key]=local;delete s.preferences.vehicle_registration_local;return true;
}
