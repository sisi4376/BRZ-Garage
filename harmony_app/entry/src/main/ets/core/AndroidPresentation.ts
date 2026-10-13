import { preciseDuration } from './Ui';
import { Row, Snapshot } from './Models';
import { dayText, today, fuelAnalysis, fuelAverage, sequence } from './Garage';
export const INK = '#181D27', MUTED = '#6D7784', PAGE = '#F4F5F7';
export const COLORS: string[] = ['#368FB5', '#DA466F', '#E1912F', '#565CA9'];
export const DAILY: string[] = ['停车', '通行', '车辆洗护', '维修', '配件用品', '保险', '证件手续', '违章', '其他'];
export const DAILY_COLORS: string[] = ['#426DBE','#2E8E77','#2391AA','#CA484E','#DE7A32','#8B58B0','#B85B66','#D34E37','#697382'];
export interface Summary { distance: number; duration: number; fuel: number; count: number; days: number; }
export interface MonthCost { label: string; fuel: number; maintenance: number; daily: number; total: number; }
export interface CalendarCell { date: string; month: string; color: string; detail: string; }
export interface CalendarWeek { id: number; label: string; cells: CalendarCell[]; }
export function soften(hex: string): string { const n = parseInt(hex.substring(1),16); return '#' + [n >> 16, n >> 8 & 255, n & 255].map((x: number) => Math.round(x * .12 + 255 * .88).toString(16).padStart(2,'0')).join(''); }
export function duration(seconds: number): string { return seconds >= 3600 ? Math.floor(seconds / 3600) + ' 小时 ' + Math.floor(seconds / 60 % 60) + ' 分钟' : Math.floor(seconds / 60) + ' 分钟'; }
export function hhmm(seconds: number): string { return String(Math.floor(seconds / 3600)).padStart(2,'0') + ':' + String(Math.floor(seconds / 60 % 60)).padStart(2,'0'); }
export function timeText(seconds: number): string { if (!(seconds >= 1704067200 && seconds <= 4102444800)) return '时间未知'; const d = new Date(seconds * 1000); return dateKey(d) + ' ' + [d.getHours(),d.getMinutes()].map((n: number) => String(n).padStart(2,'0')).join(':'); }
export function dateKey(d: Date): string { return d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0'); }
export function tripOrder(a:Row,b:Row):number {
 return sequence(b)-sequence(a) || (Number(b.trip_id)<0?-Number(b.trip_id):0)-(Number(a.trip_id)<0?-Number(a.trip_id):0) || String(b.device_id).localeCompare(String(a.device_id));
}
export function vehicleRows(s: Snapshot, table: string, gauge: string): Row[] { return s.tables[table].filter((r: Row) => table === 'fuel' || !(gauge || s.gauge) || r.device_id === (gauge || s.gauge) || table === 'expenses' && r.device_id === 'local').slice().sort(table==='trips'?tripOrder:(a: Row,b: Row) => Number(b.date_epoch_day ?? b.end_epoch_s) - Number(a.date_epoch_day ?? a.end_epoch_s)); }
export function summary(rows: Row[]): Summary { const dates: string[] = []; rows.forEach((r: Row) => { if (Number(r.flags)&1) { const d = dateKey(new Date(Number(r.start_epoch_s)*1000)); if (!dates.includes(d)) dates.push(d); } }); return { distance: sum(rows,'distance_m'), duration: sum(rows,'duration_s'), fuel: sum(rows,'fuel_ml'), count: rows.length, days: dates.length }; }
export function sum(rows: Row[], key: string): number { return rows.reduce((v: number,r: Row) => v+Number(r[key] || 0),0); }
export function inMonth(rows: Row[], month: string): Row[] { return rows.filter((r: Row) => dayText(Number(r.date_epoch_day)).startsWith(month)); }
export function monthCosts(s: Snapshot, gauge: string, fill: boolean = false): MonthCost[] {
 const map: Record<string, MonthCost> = {};
 ['fuel','expenses'].forEach((table: string) => vehicleRows(s,table,gauge).forEach((r: Row) => { const month = dayText(Number(r.date_epoch_day)).substring(0,7); if (!map[month]) map[month] = { label: month, fuel:0, maintenance:0, daily:0, total:0 }; const amount=Number(r[table==='fuel'?'cost':'amount']); if(table==='fuel') map[month].fuel+=amount; else if(r.category===1) map[month].maintenance+=amount; else map[month].daily+=amount; map[month].total+=amount; }));
 const current=today().substring(0,7),keys=Object.keys(map).sort();
 const start=fill?new Date(new Date().getFullYear(),new Date().getMonth()-11,1,12):keys.length?new Date(keys[0]+'-01T12:00:00'):null;
 if(!start)return [];const last=fill?current:keys.length&&keys[keys.length-1]>current?keys[keys.length-1]:current;
 const result:MonthCost[]=[];for(const d=start;dateKey(d).substring(0,7)<=last;d.setMonth(d.getMonth()+1)){const m=dateKey(d).substring(0,7);result.push(map[m]||{label:m,fuel:0,maintenance:0,daily:0,total:0});}return result;
}
export function calendar(rows: Row[], year: number): CalendarWeek[] {
 const todayDate=new Date(today()+'T12:00:00'), start=new Date(year,0,1,12); start.setDate(start.getDate()-(start.getDay()+6)%7);
 const end=year===todayDate.getFullYear()?todayDate:new Date(year,11,31,12);end.setDate(end.getDate()+(7-end.getDay())%7);
 const map: Record<string,Row[]>={}; rows.filter((r:Row)=>(Number(r.flags)&1)!==0).forEach((r:Row)=>{const d=dateKey(new Date(Number(r.start_epoch_s)*1000));if(!map[d])map[d]=[];map[d].push(r);});
 const result:CalendarWeek[]=[];let lastMonth='';const d=new Date(start.getTime());
 while(d<=end){const cells:CalendarCell[]=[];let label='';for(let i=0;i<7;i++){const date=dateKey(d),valid=d.getFullYear()===year,records=map[date]||[],stats=summary(records),month=String(d.getMonth()+1)+'月';cells.push({date:valid?date:'',month,color:!valid?'#00000000':!records.length?'#E8ECF1':stats.distance<25000?'#D5EEF6':stats.distance<50000?'#8AD2E5':stats.distance<100000?'#45A6C9':'#196894',detail:date+(records.length?' · '+records.length+' 次 · '+(stats.distance/1000).toFixed(1)+' km · '+preciseDuration(stats.duration)+' · '+(stats.fuel/1000).toFixed(2)+' L':' · 当天没有驾驶记录')});d.setDate(d.getDate()+1);}const first=cells.find((cell:CalendarCell)=>!!cell.date);if(first&&first.month!==lastMonth){label=first.month;lastMonth=first.month;}result.push({id:result.length,label,cells});}return result;
}
export function dailyKind(title: string): number { const i=DAILY.indexOf(title); return i>=0?i:title==='车辆配件'?4:8; }
