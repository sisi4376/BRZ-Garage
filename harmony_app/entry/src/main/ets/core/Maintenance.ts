import { Row, Snapshot } from './Models';
import { dayText, parseDay, today, mileage, Metric, pref } from './Garage';
export interface Service { title: string; action: string; km: number | null; months: number | null; firstKm: number | null; firstMonths: number | null; aliases: string[]; }
export const SERVICES: Service[] = [{"title": "发动机油", "action": "更换", "km": 5000, "months": 6, "firstKm": 5000, "firstMonths": 6, "aliases": ["发动机油及机油滤清器", "机油机滤", "常规保养", "机油"]}, {"title": "机油滤清器", "action": "更换", "km": 5000, "months": 6, "firstKm": 5000, "firstMonths": 6, "aliases": ["发动机油及机油滤清器", "机油机滤", "常规保养", "机油滤芯"]}, {"title": "燃油系统添加剂", "action": "添加", "km": 5000, "months": null, "firstKm": 5000, "firstMonths": null, "aliases": ["燃油添加剂"]}, {"title": "空调滤清器", "action": "更换", "km": 10000, "months": 12, "firstKm": 10000, "firstMonths": 12, "aliases": ["空调滤芯", "空调滤清器", "空调滤", "空气调节滤芯"]}, {"title": "轮胎换位", "action": "执行", "km": 10000, "months": null, "firstKm": 10000, "firstMonths": null, "aliases": ["轮胎换位", "轮胎换位与检查", "轮胎检查"]}, {"title": "轮胎", "action": "更换", "km": 50000, "months": 96, "firstKm": 50000, "firstMonths": 96, "aliases": ["轮胎更换", "换轮胎"]}, {"title": "发动机空气滤芯", "action": "更换", "km": 20000, "months": 24, "firstKm": 20000, "firstMonths": 24, "aliases": ["发动机空气滤芯", "空气滤芯", "空气滤清器", "空滤"]}, {"title": "制动液", "action": "更换", "km": 30000, "months": 24, "firstKm": 30000, "firstMonths": 24, "aliases": ["制动液 / 离合器液", "制动液", "刹车油", "离合器液"]}, {"title": "手动变速箱油", "action": "更换", "km": 40000, "months": 48, "firstKm": 40000, "firstMonths": 48, "aliases": ["更换手动变速器油", "更换齿轮油"]}, {"title": "后差速器油", "action": "更换", "km": 40000, "months": 48, "firstKm": 40000, "firstMonths": 48, "aliases": ["后差速器油", "差速器油", "差速油", "后桥油"]}, {"title": "火花塞", "action": "更换", "km": 60000, "months": null, "firstKm": 60000, "firstMonths": null, "aliases": ["火花塞", "更换火花塞"]}, {"title": "燃油滤清器", "action": "更换", "km": 60000, "months": 36, "firstKm": 60000, "firstMonths": 36, "aliases": ["汽油滤芯", "燃油滤芯"]}, {"title": "驱动皮带", "action": "更换", "km": 100000, "months": 120, "firstKm": 100000, "firstMonths": 120, "aliases": ["更换传动皮带"]}, {"title": "发动机冷却液", "action": "更换", "km": 100000, "months": 60, "firstKm": 220000, "firstMonths": 132, "aliases": ["发动机冷却液", "冷却液", "防冻液"]}];
function normalize(s: string): string { return s.replace(/[ /·]/g, ''); }
export function matches(title: string, service: Service): boolean {
  const tokens = title.split(/[、，,；;]/).concat([title]).map((v: string) => normalize(v));
  return [service.action + service.title].concat(service.aliases).some((s: string) => tokens.includes(normalize(s)));
}
export function addMonths(day: number, months: number): number {
  const d = new Date(day * 86400000), n = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + months, 1));
  const last = new Date(Date.UTC(n.getUTCFullYear(), n.getUTCMonth() + 1, 0)).getUTCDate();
  return Date.UTC(n.getUTCFullYear(), n.getUTCMonth(), Math.min(last, d.getUTCDate())) / 86400000;
}
export interface MaintenanceItem { title:string; subtitle:string; badge:string; color:string; timing:string; detail:string; progress:number; priority:number; days:number; index:number; }
export function recommended(km:number):string[]{return SERVICES.filter((s:Service,i:number)=>{if(i<3)return true;if(s.km===null)return false;const tolerance=s.km<=10000?1500:s.km<=40000?2500:3500;const first=s.firstKm||s.km;if(km<first-tolerance)return false;const target=first+Math.max(0,Math.round((km-first)/s.km))*s.km;return Math.abs(km-target)<=tolerance;}).map((s:Service)=>s.action+s.title);}
export function resolvedMaintenance(row:Row):number{if(row.maintenance_type!==null&&row.maintenance_type!==undefined)return Number(row.maintenance_type);const heading=['A保','B保','轮胎更换','其他维修'].indexOf(String(row.title).trim());if(heading>=0)return heading+1;const kinds=SERVICES.filter((s:Service)=>matches(String(row.title),s));if(kinds.includes(SERVICES[5]))return 3;if(kinds.some((s:Service)=>SERVICES.indexOf(s)>2))return 2;return kinds.length?1:4;}
export function maintenanceItems(s:Snapshot,gauge:string,day:number=parseDay(today())):MaintenanceItem[]{
 const odo=Boolean(pref(s,'odometer_display_'+gauge,true))?mileage(s,gauge)/1000:null;
 return SERVICES.map((service:Service,index:number):MaintenanceItem=>{
  const rows=s.tables.expenses.filter((r:Row)=>(!gauge||r.device_id===gauge||r.device_id==='local')&&r.category===1&&matches(String(r.title),service)).sort((a:Row,b:Row)=>Number(b.date_epoch_day)-Number(a.date_epoch_day));const last=rows[0];
  const targetKm=last?(last.odometer_km!==null&&service.km!==null?Number(last.odometer_km)+service.km:null):service.firstKm;
  const targetDay=last&&service.months!==null?addMonths(Number(last.date_epoch_day),service.months):null;
  const km=odo!==null&&targetKm!==null?targetKm-odo:null,days=targetDay===null?null:targetDay-day;
  const tire=service.km===null&&service.months===null;
  const priority=!last?(km===null?5:km<=0?0:km<=1000?1:3):tire?4:(days!==null&&days<0||km!==null&&km<0)?0:(days!==null&&days<=30||km!==null&&km<=1000)?1:service.months===null&&(last.odometer_km===null||odo===null)?2:3;
  const color=['#C6304B','#E08426','#E08426','#2B8B6F','#DA466F','#7E8694'][priority];
  const badge=!last&&targetKm!==null&&priority!==5?['首次已到','首次将到','缺里程','首次计划'][priority]:['已超期','即将到期','缺里程','正常','已跟踪','未跟踪'][priority];
  const cycle=service.title==='轮胎'?'50000 km / 8年':[service.km===null?'':service.km+' km',service.months===null?'':service.months+'个月'].filter((v:string)=>!!v).join(' / ');
  let timing='',detail='',progress=0;
  if(tire){timing=last?(day-Number(last.date_epoch_day)>=0?'已使用 '+(day-Number(last.date_epoch_day))+' 天':'更换日期晚于今天，请核对'):'暂无轮胎更换记录，录入更换项目后开始追踪';if(last&&odo!==null&&last.odometer_km!==null)timing+=' · 更换后已行驶 '+(odo-Number(last.odometer_km)).toFixed(0)+' km';detail=last?'上次更换 '+dayText(Number(last.date_epoch_day))+(last.odometer_km!==null?' · '+Number(last.odometer_km).toFixed(0)+' km':''):'';}
  else if(!last){timing=km===null?'开启里程显示后，将从 0 km 计算首次保养阈值':km<0?'已超过首次阈值 '+(-km).toFixed(0)+' km':km===0?'已到首次保养阈值':'距首次保养还剩 '+km.toFixed(0)+' km';detail=targetKm!==null?'从 0 km 起算 · 首次阈值 '+targetKm+' km':'';progress=km!==null&&targetKm!==null?(targetKm-km)/Math.max(1,targetKm):0;}
  else{const parts:string[]=[];if(days!==null)parts.push(days<0?'超期 '+(-days)+' 天':days===0?'今天到期':'剩余 '+days+' 天');if(km!==null)parts.push(km<0?'超出 '+(-km).toFixed(0)+' km':'剩余 '+km.toFixed(0)+' km');timing=parts.join(' · ')||(priority===2?'已记录，需要补充当时里程':'已记录');detail='上次 '+dayText(Number(last.date_epoch_day))+(last.odometer_km!==null?' · '+Number(last.odometer_km).toFixed(0)+' km':'')+(targetDay!==null?'    预计 '+dayText(targetDay):'')+(targetKm!==null?' / '+targetKm.toFixed(0)+' km':'');progress=Math.max(days!==null&&targetDay!==null?1-days/Math.max(1,targetDay-Number(last.date_epoch_day)):0,km!==null&&service.km!==null?1-km/service.km:0);}
  if(service.title==='轮胎'){
   const usedKm=odo!==null?(!last?odo:last.odometer_km!==null?odo-Number(last.odometer_km):null):null;
   const usage:string[]=[];
   if(last&&service.title==='轮胎'){const usedDays=day-Number(last.date_epoch_day);usage.push(usedDays>=0?'已使用 '+usedDays+' 天':'更换日期晚于今天，请核对');}
   if(usedKm!==null)usage.push(usedKm>=0?(!last?'已行驶 ':service.title==='轮胎'?'更换后已行驶 ':'换位后已行驶 ')+usedKm.toFixed(0)+' km':'当前里程小于记录里程，请核对');
   if(!last){timing=km===null?'里程未显示':km<0?'超出 '+(-km).toFixed(0)+' km':km===0?'已到期':'剩余 '+km.toFixed(0)+' km';detail=usage.join(' · ');}
   else if(usage.length&&service.title==='轮胎')detail+=' · '+usage.join(' · ');
  }
  return {title:service.title,subtitle:service.action+' · '+(cycle||'按实际情况更换'),badge,color,timing,detail,progress:tire?-1:Math.max(0,Math.min(1,progress)),priority,days:days??1e12,index};
 }).sort((a:MaintenanceItem,b:MaintenanceItem)=>a.priority-b.priority||a.days-b.days||a.index-b.index);
}
export function maintenance(s:Snapshot,gauge:string):Metric[]{return maintenanceItems(s,gauge).map((m:MaintenanceItem):Metric=>({label:m.title+(m.priority===0?' · 已到期':m.priority===1?' · 即将到期':''),value:m.priority===0?2:m.priority===1?1:0,detail:m.detail+' / '+m.timing}));}
