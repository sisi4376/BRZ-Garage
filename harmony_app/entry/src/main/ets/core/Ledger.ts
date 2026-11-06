import { Row, Snapshot, effectiveAssetRows } from './Models';
import { dayText, need, upsert } from './Garage';
import { LEDGER_DEFAULTS } from './Transfer';

export function ledgerSection(r: Row): string {
  if (['asset_sale', 'asset_retire', 'asset_cancel'].includes(String(r.ledger_kind))) return 'asset';
  if (r.income === 1) return 'income';
  if (r.category === 1 || r.ledger_kind === 'repair' || r.category === 2 && r.title === '维修') return 'maintenance';
  if (r.ledger_kind) return String(r.ledger_kind);
  if (r.title === '年审') return 'inspection';
  if (r.title === '保险') return 'insurance';
  if (r.title === '证件手续') return 'paperwork';
  if (r.title === '配件用品' || r.title === '车辆配件') return 'asset';
  return 'daily';
}
export function heldAssets(rows: Row[]): Row[] {
  const active = effectiveAssetRows(rows);
  return active.filter((r: Row) => ledgerSection(r) === 'asset' && !['asset_sale', 'asset_retire'].includes(String(r.ledger_kind)) && r.income !== 1 &&
    !active.some((exit: Row) => ['asset_sale', 'asset_retire'].includes(String(exit.ledger_kind)) && exit.asset_ref === r.transfer_id));
}
export function assetLabel(r: Row): string { return String(r.item_name || r.note || '配件用品 · ' + dayText(Number(r.date_epoch_day))); }
export function disposeAsset(s: Snapshot, rootId: string, id: string, day: number, amount: number, sale: boolean, note: string): void {
  const root = heldAssets(s.tables.expenses).find((r: Row) => r.transfer_id === rootId);
  need(root !== undefined, '资产已处置或登记已不存在');
  need(day >= Number(root!.date_epoch_day), '处置日期不能早于登记日期');
  need(Number.isFinite(amount) && amount >= 0 && amount <= 1e12, '出售收入无效');
  upsert(s, 'expenses', { ...LEDGER_DEFAULTS, transfer_id: id, device_id: root!.device_id, date_epoch_day: day,
    category: 2, title: sale ? '配件出售' : '资产移除', amount: sale ? amount : 0, note, odometer_km: null,
    maintenance_type: null, ledger_kind: sale ? 'asset_sale' : 'asset_retire', income: sale ? 1 : 0,
    item_name: assetLabel(root!), asset_ref: rootId });
}
export interface LedgerTotal { fuel: number; fixed: number; assets: number; daily: number; income: number; total: number; net: number; }
export function ledgerTotal(fuel: Row[], expenses: Row[], period: string, refined: boolean): LedgerTotal {
  const result: LedgerTotal = { fuel: 0, fixed: 0, assets: 0, daily: 0, income: 0, total: 0, net: 0 };
  fuel.filter((r: Row) => dayText(Number(r.date_epoch_day)).startsWith(period)).forEach((r: Row) => result.fuel += Number(r.cost));
  effectiveAssetRows(expenses).filter((r: Row) => dayText(Number(r.date_epoch_day)).startsWith(period)).forEach((r: Row) => {
    const amount = Number(r.amount), section = ledgerSection(r);
    if (r.income === 1) result.income += amount;
    else if (!['asset_sale', 'asset_retire'].includes(String(r.ledger_kind))) {
      if (refined && section === 'asset') result.assets += amount;
      else if (section === 'maintenance' || refined && ['insurance', 'inspection', 'paperwork'].includes(section)) result.fixed += amount;
      else result.daily += amount;
    }
  });
  result.total = result.fuel + result.fixed + result.assets + result.daily; result.net = result.total - result.income;
  return result;
}

export interface LedgerRoute { section: number; page: string; income: boolean; }
export function switchLedgerMode(section: number,page: string,income: boolean,fromRefined: boolean): LedgerRoute {
 if(page==='income')return {section:fromRefined?2:3,page:fromRefined?'income':'',income:true};
 if(page==='assets'||fromRefined&&section===2&&!page)return {section:2,page:fromRefined?'assets':'',income:false};
 if(page==='statistics'||!fromRefined&&section===3)return {section:fromRefined?3:0,page:fromRefined?'':'statistics',income:false};
 if(fromRefined&&page)return {section:page==='maintenance'?1:2,page:'',income:false};
 if(fromRefined&&section===3)return {section:2,page:income?'income':'',income};
 return {section:!fromRefined&&section===2?3:section,page:'',income};
}
export function ledgerHeading(row: Row,history: Row[]): string {
 if(ledgerSection(row)==='asset')return (row.ledger_kind==='asset_cancel'?'撤销':row.ledger_kind==='asset_sale'?'出售':row.ledger_kind==='asset_retire'?'移除':'登记')+' · '+assetLabel(row)+(history.some((r:Row)=>r.ledger_kind==='asset_cancel'&&r.asset_ref===row.transfer_id)?'（已撤销）':'');
 return row.category===1?['A保','B保','轮胎更换','其他维修'][Number(row.maintenance_type||4)-1]:String(row.title);
}
export function ledgerMonthNet(rows: Row[],history: Row[],month: string): number {
 const ids=new Set(effectiveAssetRows(history).map((r:Row)=>r.transfer_id));
 return rows.filter((r:Row)=>dayText(Number(r.date_epoch_day)).startsWith(month)&&ids.has(r.transfer_id)).reduce((total:number,r:Row)=>total+Number(r.amount)*(r.income===1?-1:1),0);
}
