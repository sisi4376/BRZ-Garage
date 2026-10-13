import { partnerWake } from '../platform/PartnerWake';
import { Vehicle3D } from '../components/Vehicle3D';
import { mutateRefuel } from '../core/IntervalSync';
import { StatisticsCard } from '../components/StatisticsCard';
import { KeyboardAvoidMode } from '@kit.ArkUI';
import { readable, darken, preciseDuration, isLowFuel } from '../core/Ui';
import { CalendarHeatmap } from '../components/CalendarHeatmap';
import { homeMetrics } from '../core/Home';
import { NOTICES } from '../core/Notice';
import { VehicleArtwork } from '../components/VehicleArtwork';
import { PlateView } from '../components/PlateView';
import { util } from '@kit.ArkTS';
import { pasteboard } from '@kit.BasicServicesKit';
import { INK, MUTED, PAGE, COLORS, DAILY, DAILY_COLORS, soften, duration, hhmm, timeText, vehicleRows, summary, sum, inMonth, monthCosts, calendar, CalendarWeek, CalendarCell, MonthCost, dailyKind } from '../core/AndroidPresentation';
import { ReplicaChart } from '../components/ReplicaChart';
import { exportPlate } from '../platform/Artwork';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { automatic } from '../platform/Automatic';
import { firmware, CatalogEntry } from '../platform/Firmware';
import { Field, fields, applyEdit } from '../core/Editor';
import { Metric, effectiveFuel, dayText, fuelAverage, pref, putPref, need, numeric, mileage, rangeKm, rangeConsumption, monthlyCosts, fuelAnalysis, drivingDays, today, removeRow, resetCustom, customStats, calibrate, plateText, expenseBreakdown, reconcileDeletedNode } from '../core/Garage';
import { maintenance, maintenanceItems, MaintenanceItem, recommended, resolvedMaintenance, SERVICES, Service } from '../core/Maintenance';
import { GaugeSettings, parseSettings, settingPacket, parseOdometer, odometerPacket, refuelPacket, parseRefuelMeta, parseFirmware } from '../core/Gauge';
import { utf8Decode } from '../core/Archive';
import { recordDetails } from '../core/Presentation';
import { common } from '@kit.AbilityKit';
import { Row, Snapshot, ScanItem, emptySnapshot, cloneSnapshot, displayTripId, TABLES } from '../core/Models';
import { merge } from '../core/Transfer';
import { decodeArchive, encodeArchive } from '../core/Archive';
import { repository } from '../platform/Repository';
import { bluetooth } from '../platform/Bluetooth';
import { chooseExport, chooseImport, readFile, writeFile } from '../platform/ArchiveRuntime';

@Entry
@Component
struct Index {
  @State private tab: number = 0;
  @State private ready: boolean = false;
  @State private busy: boolean = false;
  @State private consent: boolean = false;
  @State private safetyAccepted: boolean = false;
  @State private copyrightAccepted: boolean = false;
  @State private status: string = '正在读取本机数据';
  @State private warning: string = '';
  @State private address: string = '';
  @State private bound: string = '';
  @State private scans: ScanItem[] = [];
  @State private snapshot: Snapshot = emptySnapshot();
  @State private incoming: Snapshot | null = null;
  @State private preferIncoming: boolean = true;
  @State private preview: string = '';
  @State private lastSync: string = '尚未同步';
  @State private sampleTime: string = '';
  @State private vehicleText: string = '';
  @State private visibleTrips: Row[] = [];
  @State private visibleRecords: Row[] = [];
  @State private tripLimit: number = 100;
  @State private recordLimit: number = 100;
  @State private recordKind: number = 0;
  @State private hasBackup: boolean = false;
  @State private backups: string[] = [];
  @State private editKind: string = '';
  @State private editFields: Field[] = [];
  @State private selected: Row | null = null;
  @State private selectedTable: string = '';
  @State private search: string = '';
  @State private month: string = today().substring(0, 7);
  @State private wakeEnabled: boolean = false;
  @State private automaticEnabled: boolean = false;
  @State private autoText: string = '';
  @State private settingsInput: string = '';
  @State private settingsKey: string = '';
  @State private gaugeSettings: GaugeSettings | null = null;
  @State private gaugeText: string = '';
  @State private firmwareEntries: CatalogEntry[] = [];
  @State private firmwareText: string = '';
  @State private otaReady: boolean = false;
  @State private statistics: boolean = false;
  private refreshTimer: number = -1;
  @State private plateInput: string = '';
  @State private platePreview: string = '';
  @State private plateMessage: string = '可输入空格或间隔点，生成时会自动规范化。';
  @State private refinedHome: boolean = true;
  @State private groupedSettings: boolean = true;
  @State private route: string = '';
  @State private settingsSection: number = 0;
  @State private accountingSection: number = 0;
  @State private revisionMode: boolean = false;
  @State private refuelRevision: boolean = false;
  @State private customRevision: boolean = false;
  @State private calendarYear: number = new Date().getFullYear();
  @State private calendarDetail: string = '点击日期方格查看当天统计';
  @State private calendarWeeks: CalendarWeek[] = [];
  @State private timeOnly: boolean = false;
  @State private editorError: string = '';
  @State private selectedServices: string[] = [];
  @State private currentDetail: boolean = false;
  private pageScroller: Scroller = new Scroller();
  private calendarScroller: Scroller = new Scroller();
  private parentRoutes: string[] = [];
  private scrollPositions: Record<string, number> = {};
  private recordTables: string[] = ['fuel', 'expenses', 'refuel', 'custom'];
  private recordNames: string[] = ['加油账本', '费用保养', '加油区间', '自定义行程'];

  aboutToAppear(): void { this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE); try { this.getUIContext().getFont().registerFont({familyName:'GarageSans',familySrc:'file:///system/fonts/HarmonyOS_Sans_SC.ttf'}); } catch (_) {} this.run(async () => { await repository.open(this.context()); this.refresh(); this.ready = true; this.firmwareEntries = await firmware.catalog(this.context()); this.status = '本机数据已就绪'; }); this.refreshTimer = setInterval(() => { this.autoText = automatic.status; if (this.ready && !this.busy && !this.editKind) repository.reload().then(() => { if (!this.busy && !this.editKind) this.refresh(); }).catch(() => {}); }, 2000); }
  aboutToDisappear(): void { clearInterval(this.refreshTimer); if (!automatic.running) bluetooth.close(); }
  private context(): common.UIAbilityContext { return this.getUIContext().getHostContext() as common.UIAbilityContext; }
  private async run(action: () => Promise<void>): Promise<void> {
    if (this.busy) return;
    this.busy = true; automatic.suspended = true;
    try { await automatic.stop(); await action(); }
    catch (error) { const failure = error as Error; this.status = failure.message || '操作失败，请重试'; this.editorError = this.status; this.getUIContext().getPromptAction().showToast({ message: this.status }); }
    finally { this.busy = false; automatic.suspended = this.otaReady; if (this.ready && !this.otaReady && automatic.foreground) automatic.start(this.context()); }
  }
  private refresh(): void {
    this.snapshot = repository.snapshot(); this.bound = repository.binding(); this.address = this.bound;
    this.wakeEnabled = partnerWake.registered(this.bound);
    this.automaticEnabled = repository.automatic(); this.refinedHome = repository.refinedHome(); this.groupedSettings=repository.groupedSettings();
    this.consent = repository.consent(); this.hasBackup = repository.backupPath().length > 0;
    this.lastSync = repository.lastSync() ? new Date(repository.lastSync()).toLocaleString() : '尚未同步';
    const vehicle = repository.vehicle();
    if (vehicle) { this.vehicleText = '本次 ' + (vehicle.currentDistance / 1000).toFixed(1) + ' km · ' + Math.round(vehicle.currentDuration / 60) + ' 分钟 · ' + (vehicle.currentFuel / 1000).toFixed(2) + ' L\n油量 ' + (vehicle.fuelPercent === null ? '未知' : vehicle.fuelPercent.toFixed(1) + '%') + ' · 转速 ' + (vehicle.rpm ?? '未知') + ' · 车速 ' + (vehicle.speed ?? '未知'); this.sampleTime = timeText(repository.vehicleAt()/1000); }
    const cached=this.snapshot.devices[this.gauge()];
    this.gaugeSettings=null;this.gaugeText='';
    if(cached){
      if(cached.gauge_settings_data){try{this.gaugeSettings=parseSettings(new util.Base64Helper().decodeSync(String(cached.gauge_settings_data)));}catch(_) {}}
      if(cached.firmware_version)this.gaugeText='固件 v'+cached.firmware_version+'\n'+(cached.firmware_board||'')+' · 上次读取 '+timeText(Number(cached.firmware_at)/1000);
    }
    this.filterLists(); const years=this.historyYears();if(years.length&&!years.includes(this.calendarYear))this.calendarYear=years[years.length-1];this.calendarWeeks = calendar(this.rows('trips'), this.calendarYear);
    if (this.incoming) this.updatePreview();
  }
  private filterLists(): void {
    this.visibleTrips = vehicleRows(this.snapshot, 'trips', this.gauge())
      .filter((r: Row) => !this.search || this.tripDate(r).includes(this.search) || String(r.trip_id).includes(this.search) || String(r.device_id).includes(this.search))
      .slice(0, this.tripLimit);
    const table = this.recordTables[this.recordKind];
    this.visibleRecords = this.snapshot.tables[table].slice().sort((a: Row, b: Row) => Number(b.date_epoch_day ?? b.end_epoch_s) - Number(a.date_epoch_day ?? a.end_epoch_s)).slice(0, this.recordLimit);
  }
  private recordCount(): number { let count = 0; TABLES.forEach((name: string) => { count += this.snapshot.tables[name].length; }); return count; }
  private date(seconds: number): string { return seconds > 0 ? new Date(seconds * 1000).toLocaleString() : '时间未校准'; }
  private tripDate(row: Row): string { return (Number(row.flags) & 1) ? this.date(Number(row.start_epoch_s)) : '未授时行程 · #' + displayTripId(Number(row.trip_id)); }
  private metric(row: Row): string { return (Number(row.distance_m) / 1000).toFixed(1) + ' km  ·  ' + Math.round(Number(row.duration_s) / 60) + ' 分钟  ·  ' + (Number(row.fuel_ml) / 1000).toFixed(2) + ' L'; }
  private recordTitle(row: Row): string {
    if (this.recordKind === 0) return new Date(Number(row.date_epoch_day) * 86400000).toISOString().substring(0, 10) + ' · 加油';
    if (this.recordKind === 1) return String(row.title || '费用记录');
    if (this.recordKind === 2) return '区间 #' + row.interval_id;
    return String(row.name || '自定义行程');
  }
  private recordSummary(row: Row): string {
    if (this.recordKind === 0) return row.litres + ' L · 金额 ' + row.cost + ' · ' + row.odometer_km + ' km';
    if (this.recordKind === 1) return '金额 ' + row.amount + ' · ' + String(row.note || '');
    return this.metric(row);
  }
  private details(row: Row, table: string): void { this.selected = row; this.selectedTable = table; this.currentDetail = false; this.navigate('trip'); }
  private makeId(): string { return Array.from(cryptoFramework.createRandom().generateRandomSync(16).data).map((b: number) => b.toString(16).padStart(2, '0')).join(''); }
  private edit(table: string, row: Row | null): void {
    this.selected = row; this.selectedTable = table; this.editFields = fields(table, row); this.editKind = table; this.timeOnly=false; this.editorError = '';
    if (table === 'expenses') {
      this.setField('category', String(row?.category ?? (this.accountingSection === 1 ? 1 : 2)));
      if (row && row.category===1) this.setField('maintenance',String(resolvedMaintenance(row)));
      if (!row) { this.setField('title', this.accountingSection === 1 ? recommended(mileage(this.snapshot,this.gauge())/1000).join('、') : '其他'); this.setField('maintenance', String(resolvedMaintenance({title:this.fieldValue('title')})));  }
      this.selectedServices = SERVICES.filter((s: Service) => this.fieldValue('title').split('、').some((t: string) => t === s.action + s.title || s.aliases.includes(t))).map((s: Service) => s.action+s.title);
    }
    if (!row && (table === 'fuel' || table === 'expenses' && this.fieldValue('category') === '1')) { if(this.prefEnabled('odometer_display_'+this.gauge(),true))this.setField('odometer', (mileage(this.snapshot, this.gauge()) / 1000).toFixed(table === 'fuel' ? 0 : 1)); else if(table==='fuel'&&this.rows('fuel').length)this.setField('odometer',String(Math.max(...this.rows('fuel').map((r:Row)=>Number(r.odometer_km))))); }
  }
  private saveEdit(confirmed: boolean = false): void {
    if(this.editKind==='split'&&!confirmed){
      try { const copy=cloneSnapshot(this.snapshot); applyEdit(copy,'split',this.selected,this.editFields,this.makeId(),this.gauge());
        const children=copy.tables.trips.filter((r:Row)=>r.device_id===this.selected!.device_id && [-(Number(this.selected!.trip_id)*2+1),-(Number(this.selected!.trip_id)*2+2)].includes(Number(r.trip_id)));
        this.getUIContext().showAlertDialog({title:'确认拆分行程 #'+this.selected!.trip_id,message:children.map((r:Row)=>'#'+displayTripId(Number(r.trip_id))+'：'+this.metric(r)).join('\n')+'\n\n两段合计与原行程完全一致。拆分后不能自动还原，请确认。',primaryButton:{value:'返回修改',action:()=>{}},secondaryButton:{value:'确认拆分',action:()=>this.saveEdit(true)}});
      }catch(error){this.editorError=(error as Error).message;} return;
    }
    this.run(async () => {
    const kind = this.editKind, old = this.selected, values = this.editFields, id = this.makeId(), gauge = this.gauge() ;
    await repository.edit((s: Snapshot) => applyEdit(s, kind, old, values, id, gauge, this.timeOnly?'time':'data'));
    this.editKind = ''; this.refresh(); if (kind === 'trips' && old) this.selected = this.snapshot.tables.trips.find((r: Row) => r.device_id === old.device_id && r.trip_id === old.trip_id) || null; else this.selected = null; if (kind === 'split') this.navigate(''); this.status = '记录已保存';
  }); }
  private confirmDelete(): void {
    if (!this.selected) return; const row = this.selected, table = this.selectedTable;
    this.getUIContext().showAlertDialog({ title: '删除记录', message: '将保存删除标记，避免再次同步时恢复。删除后可通过先前数据包恢复。',
      primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '删除', action: () => this.run(async () => {
        await repository.edit((s: Snapshot) => removeRow(s, table, row)); this.selected = null; this.editKind = ''; if (this.route === 'trip') this.navigate(''); this.refresh(); this.status = '记录已删除';
      }) } });
  }
  private settingLabel(key: string): string {
    const keys = ['vehicle_display_name', 'custom_license_plate', 'tank', 'range_correction_factor', 'manual_fuel_percent', 'custom_trip_name'];
    const labels = ['车辆名称', '车牌', '油箱容积（L）', '油耗修正系数', '手动油量（%）', '自定义行程名称'];
    return labels[keys.indexOf(key)] || key;
  }
  private setting(key: string, value: string): void { this.settingsKey = key; this.settingsInput = value; this.editorError = ''; }
  private saveSetting(): void { this.run(async () => {
    const key = this.settingsKey, text = this.settingsInput.trim(), gauge = this.bound || this.snapshot.gauge;
    await repository.edit((s: Snapshot) => {
      if (key === '里程校准') calibrate(s, gauge, Math.round(numeric(text, '里程', 999999.9) * 1000));
      else if (key === 'custom_trip_name') putPref(s, key + '_' + gauge, Array.from(text).slice(0, 8).join(''));
      else if (key === 'custom_license_plate') putPref(s, key, plateText(text));
      else if (key === 'tank') { need(numeric(text, '油箱容积', 150) >= 1, '油箱容积至少 1 L'); putPref(s, key, text); }
      else if (key === 'range_correction_factor') { need(numeric(text, '修正系数', 2) >= 0.5, '修正系数为 0.5 至 2'); putPref(s, key, text); }
      else if (key === 'manual_fuel_percent') { numeric(text, '油量百分比', 100); putPref(s, key, text); putPref(s, 'manual_fuel_baseline_ml', repository.vehicle()?.totalFuel ?? -1); }
      else { need(text.length > 0 && text.length <= 9, '请输入 1 至 9 个字符'); putPref(s, key, text); }
    }); if(key==='里程校准'&&this.bound)await repository.queueSetting('mileage',Math.round(Number(text)*1000)); this.settingsKey = ''; this.refresh(); this.status = '设置已保存';
  }); }
  private readGauge(): void { this.run(async () => {
    await bluetooth.session(this.context(), this.bound, (text: string) => this.status = text, async () => {
      this.gaugeSettings = bluetooth.has(7) ? parseSettings(await bluetooth.readSettingsCached(this.bound)) : null;
      await bluetooth.cacheGaugeInfo(this.bound);
      const info = parseFirmware(utf8Decode(await bluetooth.read(1))); this.gaugeText = '固件 v' + info.version + '\n' + info.board;
      if (bluetooth.has(10)) { const odo = parseOdometer(await bluetooth.read(10)); this.gaugeText += '\n仪表总里程 ' + (odo.metres / 1000).toFixed(1) + ' km · ' + (odo.enabled ? '显示开启' : '显示关闭'); }
    }); this.status = '仪表信息已读取';
  }); }
  private writeGauge(kind: string, value: number): void { this.run(async () => {
    await bluetooth.session(this.context(), this.bound, (text: string) => this.status = text, async () => {
      if (kind === 'brightness' || kind === 'profile') {
        await bluetooth.write(kind === 'brightness' ? 8 : 9, settingPacket(value, kind === 'profile'));
        let verified = false;
        for (let i = 0; i < 4; i++) { await new Promise<void>((resolve) => setTimeout(resolve, 300)); const settings = parseSettings(await bluetooth.readSettingsCached(this.bound));
          if ((kind === 'brightness' ? settings.brightness : settings.profile) === value) { this.gaugeSettings = settings; verified = true; break; } }
        need(verified, '设置回读不一致，请重新读取确认');
        if (kind === 'profile') await repository.edit((s: Snapshot) => putPref(s, 'selected_vehicle_profile', value));
      } else if (kind === 'threshold') {
        await bluetooth.write(12, refuelPacket(4, value)); await new Promise<void>((resolve) => setTimeout(resolve, 500));
        this.gaugeSettings = parseSettings(await bluetooth.readSettingsCached(this.bound)); need(this.gaugeSettings.threshold === value, '加油阈值回读不一致');
      } else {
        await bluetooth.write(10, odometerPacket(value === 1, kind === 'calibrate' ? mileage(this.snapshot, this.bound) : null));
        const odo = parseOdometer(await bluetooth.read(10));
        need(kind === 'calibrate' ? Math.abs(odo.metres - mileage(this.snapshot, this.bound)) <= 50 : odo.enabled === (value === 1), '总里程回读不一致');
        if (kind !== 'calibrate') await repository.edit((s: Snapshot) => putPref(s, 'odometer_display_' + this.bound, value === 1));
      }
    }); this.refresh(); this.status = '仪表设置已写入并回读确认';
  }); }
  private refuelAction(command: number, id: number = 0): void {
    const gauge = this.gauge();
    this.getUIContext().showAlertDialog({ title: '修改加油区间', message: command === 2 ? '手动创建加油节点并重置当前区间？' : command === 3 ? '连接后先核对仪表保留范围；更早的历史只在本机合并，仪表保留的节点同步删除并合并到下一段。' : '删除第一个节点以前的数据，不合并到下一段。连接后会先核对仪表保留范围。',
      primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '确认', action: () => this.run(async () => {
        need(gauge === this.bound, '请先绑定这块仪表，连接后核对最新保留范围');
        if (command !== 2) await repository.queueRefuel(gauge, {id, discard: command === 5, revision: -1, issued: false});
        try {
          await bluetooth.session(this.context(), gauge, (text: string) => this.status = text, async () => { await mutateRefuel(gauge, bluetooth, repository, command, id); });
          this.status = '加油区间已更新';
        } catch (error) { throw new Error((command === 2 ? '' : '删除请求已保存，等待连接后核对并执行：') + (error as Error).message); } finally { this.selected = null; this.refresh(); }
      }) } });
  }
  private resetCustomTrip(): void {
    const gauge = this.gauge(), vehicle = repository.vehicle(), expected = repository.customGeneration(gauge), at = repository.vehicleAt();
    if (!vehicle) { this.notice('尚未收到车辆数据'); return; }
    this.getUIContext().showAlertDialog({ title: '归档并重置自定义行程', message: '以最近一次车辆快照（'+timeText(at/1000)+'）为分界保存当前区间；离线时不是点击当下的实时数据。保存后立即开始新统计，下次连接时同步到仪表。', primaryButton: { value: '取消', action: () => {} },
      secondaryButton: { value: '确认', action: () => this.run(async () => {
        need(gauge === this.gauge(), '当前仪表已切换，请重新打开');
        await repository.archiveCustom(gauge, expected, vehicle, String(pref(this.snapshot,'custom_trip_name_'+gauge,'')), this.makeId(), at);
        this.refresh(); this.status = '自定义行程已归档并重置，等待仪表确认';
        if (this.bound === gauge) { try { await bluetooth.sync(this.context(), gauge, (text: string) => this.status = text); }
          catch (_) { this.status = '自定义行程已在手机保存，下次连接时继续同步'; } finally { this.refresh(); } }
      }) } });
  }

  private updatePreview(): boolean {
    if (!this.incoming) return false;
    try {
    const report = merge(this.snapshot, this.incoming, this.preferIncoming);
    this.preview = '新增 ' + report.added + ' · 更新 ' + report.updated + ' · 删除 ' + report.removed +
      ' · 保持 ' + report.unchanged + '\n冲突 ' + report.conflicts + ' 处，' + (this.preferIncoming ? '采用数据包内容' : '保留本机内容');
    return true;
    } catch (error) { this.incoming = null; this.preview = ''; this.status = (error as Error).message; return false; }
  }
  private sync(): void {
    this.run(async () => {
      this.warning = ''; this.vehicleText = ''; this.sampleTime = '';
      const result = await bluetooth.sync(this.context(), this.bound, (message: string) => { this.status = message; });
      this.refresh();
      this.status = '同步完成 · 读取 ' + result.saved + ' 条行程 · ' + (result.clockVerified ? '仪表时间已校验' : '授时已写入，旧固件无回读');
      this.warning = result.warning;
      if (result.vehicle) {
        const v = result.vehicle;
        this.vehicleText = '本次 ' + (v.currentDistance / 1000).toFixed(1) + ' km · ' + Math.round(v.currentDuration / 60) + ' 分钟\n' +
          '累计 ' + (v.totalDistance / 1000).toFixed(1) + ' km · 油量 ' + (v.fuelPercent === null ? '未知' : v.fuelPercent.toFixed(1) + '%');
        this.sampleTime = timeText(Date.now()/1000);
      }
    });
  }
  private importPackage(): void {
    this.run(async () => {
      const path = await chooseImport(this.context()); if (!path) return;
      this.status = '正在校验数据包';
      const incoming = await decodeArchive(readFile(path), repository.archive);
      this.incoming = incoming; this.preferIncoming = true; if (this.updatePreview()) this.status = Object.keys(incoming.devices).length > 0 ?
        '校验通过，完整仪表缓存将随历史记录一并合并。' : '校验通过。旧版数据包不含仪表缓存，导入后需连接仪表更新首页。';
    });
  }
  private exportPackage(backup: boolean = false, backupPath: string = ''): void {
    this.run(async () => {
      const bytes = backup ? readFile(backupPath || repository.backupPath()) : await encodeArchive(repository.snapshot(), repository.archive);
      await decodeArchive(bytes, repository.archive);
      const path = await chooseExport(this.context()); if (!path) { this.status='已取消导出'; return; }
      writeFile(path, bytes); this.status = backup ? '导入前备份已导出' : '数据包已导出，可在另一设备导入';
    });
  }

  private gauge(): string { return this.bound || this.snapshot.gauge; }
  private rows(table: string): Row[] { return vehicleRows(this.snapshot, table, this.gauge()); }
  private expenses(category: number): Row[] { return this.rows('expenses').filter((r: Row) => r.category === category); }
  private name(): string { return String(pref(this.snapshot, 'vehicle_display_name', '我的BRZ STI')); }
  private model(): string { return Number(pref(this.snapshot, 'selected_vehicle_profile', 4)) === 1 ? 'ZC6' : 'ZD8'; }
  private prefEnabled(key: string, fallback: boolean = true): boolean { return key === 'refined_home_ui' ? this.refinedHome : Boolean(pref(this.snapshot, key, fallback)); }
  private pageKey(): string { return this.route || String(this.tab) + ':' + this.settingsSection + ':' + this.accountingSection; }
  private navigate(route: string, tab: number = this.tab): void {
    if (route !== this.route) { this.refuelRevision = false; this.customRevision = false; }
    if (route && route !== this.route) this.parentRoutes.push(this.route); if (!route) this.parentRoutes = [];
    this.scrollPositions[this.pageKey()] = this.pageScroller.currentOffset().yOffset; this.route = route; this.tab = tab;
    const y = this.scrollPositions[this.pageKey()] || 0; setTimeout((): void => { this.pageScroller.scrollTo({xOffset:0,yOffset:y}); }, 30);
  }
  onBackPress(): boolean { if (this.editKind) { this.editKind = ''; return true; } if(this.settingsKey){this.settingsKey='';return true;} if(this.route){this.goBack();return true;} if(this.tab!==0){this.navigate('',0);return true;}return false; }
  private openRoute(route: string): void { if(route==='legacy'){this.run(async()=>{await repository.setGroupedSettings(false);this.refresh();this.navigate('',3);setTimeout(():void=>this.pageScroller.scrollTo({xOffset:0,yOffset:0}),50);});return;}  if (route==='plate') { this.plateInput=String(pref(this.snapshot,'custom_license_plate',''));this.platePreview=this.plateInput; } this.navigate(route); if(route==='gauge' && this.bound && this.consent && !this.busy) this.readGauge(); }
  private togglePref(key: string, value: boolean): void { this.run(async () => { if(key === 'refined_home_ui') await repository.setRefinedHome(value); else { await repository.edit((s: Snapshot) => putPref(s,key,value)); if(key.startsWith('odometer_display_')&&this.bound)await repository.queueSetting('display',value); } this.refresh(); }); }
  private fieldValue(key: string): string { return this.editFields.find((f: Field) => f.key===key)?.value || ''; }
  private setField(key: string, value: string): void { this.editFields=this.editFields.map((f: Field):Field=>({key:f.key,label:f.label,value:f.key===key?value:f.value})); }
  private editorTitle(): string { if(this.timeOnly)return '修订行程时间'; if(this.editKind==='fuel')return this.selected?'编辑加油记录':'记录加油';if(this.editKind==='expenses')return (this.selected?'编辑':'记录')+(this.fieldValue('category')==='1'?'保养':'日常花费');if(this.editKind==='split')return '手动拆分这条行程';return '修订测试数据'; }
  private pickDate(): void { const d=new Date(this.fieldValue('date')+'T12:00:00'); this.getUIContext().showDatePickerDialog({selected:d,onDateAccept:(date:Date)=>{this.setField('date',date.getFullYear()+'-'+String(date.getMonth()+1).padStart(2,'0')+'-'+String(date.getDate()).padStart(2,'0'));}}); }
  private currentMetrics(kind: string): number[] {
    const r=repository.refuelLive();return homeMetrics(this.snapshot,this.gauge(),repository.vehicle(),kind,r?[r.distance,r.duration,r.fuel]:[]);
  }
  private tripMetrics(row: Row): number[] { return [Number(row.distance_m),Number(row.duration_s),Number(row.fuel_ml)]; }
  private showCurrent(): void { const v=repository.vehicle();if(!v)return;this.selected={device_id:this.gauge(),trip_id:0,start_epoch_s:v.currentStart,end_epoch_s:v.epoch,distance_m:v.currentDistance,duration_s:v.currentDuration,fuel_ml:v.currentFuel,avg_l100_x100:v.currentDistance?Math.round(v.currentFuel/v.currentDistance*10000):0,flags:v.currentStart>0?1:0};this.currentDetail=true;this.navigate('trip'); }
  private fuelPercent():number|null{return effectiveFuel(this.snapshot,repository.vehicle());}
  private monthLabel(row: Row): string { const d=dayText(Number(row.date_epoch_day));return d.substring(0,4)+'年'+Number(d.substring(5,7))+'月'; }
  private tripMonth(row: Row): string { return (Number(row.flags)&1)?timeText(Number(row.start_epoch_s)).substring(0,7):'时间未知'; }
  private average(values: number[]): string { return values.length && values[0]>0?(values[2]/values[0]*100).toFixed(1)+' L/100km':'—'; }
  private notice(text: string): void { this.getUIContext().showAlertDialog({title:'提示',message:text,confirm:{value:'知道了',action:()=>{}}}); }
  private setModel(value: number): void { this.run(async()=>{await repository.edit((s:Snapshot)=>putPref(s,'selected_vehicle_profile',value));if(this.bound)await repository.queueSetting('profile',value);this.refresh();this.status='车型已保存';}); }
  private openLink(url: string): void { this.run(async()=>this.context().startAbility({action:'ohos.want.action.viewData',uri:url})); }
  private automaticToggle(value: boolean): void { this.run(async()=>{if(value)need(!!this.bound&&this.consent,'请先绑定仪表');await repository.setAutomatic(value);this.refresh();}); }
  private cash(value: number): string { return '¥'+value.toFixed(2); }
  private historyYears():number[]{return Array.from(new Set(this.rows('trips').filter((r:Row)=>(Number(r.flags)&1)!==0).map((r:Row)=>new Date(Number(r.start_epoch_s)*1000).getFullYear()))).sort((a:number,b:number)=>a-b);}
  private previousYear():number{return this.historyYears().filter((y:number)=>y<this.calendarYear).pop()??this.calendarYear;}
  private nextYear():number{return this.historyYears().find((y:number)=>y>this.calendarYear)??this.calendarYear;}
  private setYear(value: number): void { this.calendarYear=value;this.calendarWeeks=calendar(this.rows('trips'),value);this.calendarDetail='点击日期方格查看当天统计';setTimeout(():void=>{this.calendarScroller.scrollEdge(Edge.End);},50); }
  private costRows(): MonthCost[] { return monthCosts(this.snapshot,this.gauge(),true); }
  private currentMonthCost(): MonthCost { return this.costRows().find((m:MonthCost)=>m.label===today().substring(0,7)) || {label:today().substring(0,7),fuel:0,maintenance:0,daily:0,total:0}; }
  private annualCost(key: string): number { return this.costRows().filter((m:MonthCost)=>m.label.startsWith(today().substring(0,4))).reduce((n:number,m:MonthCost)=>n+(key==='fuel'?m.fuel:key==='maintenance'?m.maintenance:key==='daily'?m.daily:m.total),0); }

  private iconSource(name: string): Resource {
    if(name==='vehicle')return $r('app.media.nav_vehicle');
    if(name==='trip')return $r('app.media.nav_trip');
    if(name==='accounting')return $r('app.media.nav_accounting');
    if(name==='settings')return $r('app.media.nav_settings');
    if(name==='fuel')return $r('app.media.nav_fuel');
    if(name==='gauge')return $r('app.media.nav_gauge');
    if(name==='plate')return $r('app.media.nav_plate');
    if(name==='display')return $r('app.media.nav_display');
    if(name==='bluetooth')return $r('app.media.nav_bluetooth');
    if(name==='autostart')return $r('app.media.nav_autostart');
    if(name==='update')return $r('app.media.nav_update');
    if(name==='legacy')return $r('app.media.nav_legacy');
    if(name==='legal')return $r('app.media.nav_legal');
    if(name==='feedback')return $r('app.media.nav_feedback');
    if(name==='mileage')return $r('app.media.nav_mileage');
    if(name==='firmware')return $r('app.media.nav_firmware');
    return $r('app.media.nav_vehicle');
  }
  @Builder private icon(name: string, color: string, size: number = 24) { Image($rawfile('icons/'+name+'-'+color.substring(1).toUpperCase()+'.svg')).width(size).height(size) }
  @Builder private title(text: string, subtitle: string) {
    Column({space:5}) { Text(text).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(30).fontWeight(FontWeight.Medium).fontColor(INK).maxLines(1).textOverflow({overflow:TextOverflow.Ellipsis}); Text(subtitle).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).lineHeight(17) }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder private sectionTitle(text: string) { Text(text).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontWeight(FontWeight.Medium).fontColor(MUTED).width('100%') }
  @Builder private note(text: string) { Text(text).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(MUTED).lineHeight(17).width('100%').margin({top:9}) }
  @Builder private action(label: string, color: string, callback: () => void, filled: boolean = false, enabled: boolean = true) {
    Text(label).fontFamily('GarageSans').fontSize(12).fontWeight(FontWeight.Medium).fontColor(enabled?(filled?'#FFFFFF':readable(color)):'#616B79').textAlign(TextAlign.Center).width('100%').constraintSize({minHeight:44}).padding({left:13,right:13,top:10,bottom:10}).backgroundColor(enabled?(filled?readable(color):soften(color)):'#E9EDF2').borderRadius(14).enabled(enabled&&!this.busy).accessibilityText(label+(enabled?'':'，暂不可用')).onClick(callback)
  }
  @Builder private primaryAction(label: string, color: string, callback: () => void) {
    Text(label).fontFamily('GarageSans').fontSize(15).fontWeight(FontWeight.Medium).fontColor('#FFFFFF').textAlign(TextAlign.Center).width('100%').constraintSize({minHeight:52}).padding({left:18,right:18,top:14,bottom:14}).backgroundColor(readable(color)).borderRadius(17).enabled(!this.busy).onClick(callback)
  }
  @Builder private back(label: string = '返回设置', color: string = COLORS[3]) {
    Text('‹  '+(label==='返回首页'&&this.tab===3?'返回设置':label==='返回车辆设置'&&this.parentRoutes[this.parentRoutes.length-1]!=='vehicle'?'返回设置':label)).fontFamily('GarageSans').fontSize(12).fontWeight(FontWeight.Medium).fontColor(readable(color)).textAlign(TextAlign.Center).width('100%').constraintSize({minHeight:38}).padding({left:13,right:13,top:8,bottom:8}).backgroundColor(soften(color)).borderRadius(13).margin({bottom:16}).onClick(()=>{this.goBack();})
  }
  @Builder private metricBox(title: string,value: string,color: string=INK,hint: string='') {
    Column({space:5}) { Text(title).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontWeight(FontWeight.Medium).fontColor(MUTED).width('100%'); Text(value).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(20).minFontSize(12).maxFontSize(20).maxLines(value.includes('\n') ? 2 : 1).heightAdaptivePolicy(TextHeightAdaptivePolicy.MIN_FONT_SIZE_FIRST).fontColor(readable(color)).fontWeight(FontWeight.Medium).width('100%'); if(hint){Text(hint).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor(MUTED).width('100%')} }.padding({left:14,right:14,top:13,bottom:13}).width('100%').alignItems(HorizontalAlign.Start).backgroundColor('#F7F8FB').borderRadius(15)
  }
  @Builder private metricPair(a: string,av: string,b: string,bv: string,color: string=COLORS[0],hintA: string='',hintB: string='',rightColor: string=INK) {
    Row({space:10}) { Column(){this.metricBox(a,av,color,hintA)}.layoutWeight(1); Column(){this.metricBox(b,bv,rightColor,hintB)}.layoutWeight(1) }.width('100%').alignItems(VerticalAlign.Top).margin({top:10})
  }
  @Builder private miniMetric(title:string,value:string,hint:string,color:string) {
    Column() {
      Text(title).fontFamily('GarageSans').fontSize(10).fontWeight(FontWeight.Medium).fontColor(MUTED).maxLines(1).width('100%').textAlign(TextAlign.Center)
      Text(value).fontFamily('GarageSans').fontSize(13).minFontSize(9).maxFontSize(13).heightAdaptivePolicy(TextHeightAdaptivePolicy.MIN_FONT_SIZE_FIRST).maxLines(1).fontWeight(FontWeight.Medium).fontColor(readable(color)).width('100%').textAlign(TextAlign.Center).margin({top:4})
      Text(hint||' ').fontFamily('GarageSans').fontSize(9).minFontSize(8).maxFontSize(9).heightAdaptivePolicy(TextHeightAdaptivePolicy.MIN_FONT_SIZE_FIRST).fontColor(MUTED).maxLines(1).width('100%').textAlign(TextAlign.Center).margin({top:2})
    }.width('100%').padding({left:6,right:6,top:9,bottom:9}).backgroundColor('#F7F8FB').borderRadius(12)
  }
  @Builder private listRow(icon:string,title:string,detail:string,color:string,callback:()=>void,value:string='') {
    Row() {
      Row(){this.icon(icon,color,24)}.width(38).height(38).justifyContent(FlexAlign.Center).backgroundColor(soften(color)).borderRadius(12).margin({right:12})
      Column({space:4}){Text(title).fontFamily('GarageSans').fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK).width('100%');Text(detail).fontFamily('GarageSans').fontSize(11).fontColor(MUTED).lineHeight(16).width('100%')}.layoutWeight(1).alignItems(HorizontalAlign.Start)
      if(value){Text(value).fontFamily('GarageSans').fontSize(11).fontWeight(FontWeight.Medium).fontColor(readable(color)).textAlign(TextAlign.End).constraintSize({maxWidth:85}).margin({left:8})}
      Text('›').fontFamily('GarageSans').fontSize(22).fontColor('#687486').width(22).textAlign(TextAlign.Center).margin({left:4})
    }.width('100%').constraintSize({minHeight:68}).padding({left:14,right:14,top:8,bottom:8}).enabled(!this.busy).accessibilityText(title+'，'+detail+(value?'，'+value:'')).onClick(callback)
  }
  @Builder private switchRow(icon:string,title:string,detail:string,color:string,value:boolean,callback:(v:boolean)=>void) {
    Row() {
      Row(){this.icon(icon,color,24)}.width(38).height(38).justifyContent(FlexAlign.Center).backgroundColor(soften(color)).borderRadius(12).margin({right:12})
      Column({space:4}){Text(title).fontFamily('GarageSans').fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK).width('100%');if(detail){Text(detail).fontFamily('GarageSans').fontSize(11).fontColor(MUTED).lineHeight(16).width('100%')}}.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Toggle({type:ToggleType.Switch,isOn:value}).width(48).height(36).margin({left:8}).selectedColor(readable(color)).switchPointColor('#FFFFFF').enabled(!this.busy).accessibilityText(title).onChange(callback)
    }.width('100%').constraintSize({minHeight:68}).padding({left:14,right:14,top:8,bottom:8})
  }
  @Builder private divider(){Divider().color('#E9EDF2').margin({left:64,right:14})}
  @Builder private readRow(name:string,value:string,color:string=INK){
    Row(){Text(name).fontFamily('GarageSans').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1);Text(value||'等待仪表数据').fontFamily('GarageSans').fontSize(12).fontColor(value?color:MUTED).textAlign(TextAlign.End).constraintSize({maxWidth:'62%'}).margin({left:14})}.width('100%').constraintSize({minHeight:52}).padding({top:8,bottom:8})
    Divider().color('#ECEFF4')
  }
  @Builder private vehicleArtwork(){
    if(this.prefEnabled('show_3d_vehicle',false)){
      Vehicle3D({model:this.model(),plate:this.prefEnabled('show_custom_license_plate')?String(pref(this.snapshot,'custom_license_plate','')):'',color:String(pref(this.snapshot,'appearance.'+this.model()+'_color','#155dcc')),finish:String(pref(this.snapshot,'appearance.'+this.model()+'_finish','gloss')),onAppearance:(color:string,finish:string)=>this.run(async()=>{const model=this.model();await repository.edit((s:Snapshot)=>{putPref(s,'appearance.'+model+'_color',color);putPref(s,'appearance.'+model+'_finish',finish);});this.refresh();})})
    }else{VehicleArtwork({model:this.model(),plate:this.prefEnabled('show_custom_license_plate')?String(pref(this.snapshot,'custom_license_plate','')):''}).margin({top:2,bottom:1})}
  }
  @Builder private hero(icon:string,title:string,subtitle:string,status:string,color:string,overview:boolean=false) {
    Row({space:14}) {
      Row(){this.icon(icon,'#FFFFFF',32)}.width(overview?54:52).height(overview?54:52).justifyContent(FlexAlign.Center).backgroundColor('#2AFFFFFF').borderRadius(overview?16:15)
      Column(){
        Text(title).fontFamily('GarageSans').fontSize(18).fontWeight(FontWeight.Medium).fontColor('#FFFFFF').width('100%')
        Text(subtitle).fontFamily('GarageSans').fontSize(11).fontColor('#E1E0F6').lineHeight(16).width('100%').margin({top:overview?6:5})
        Text(status).fontFamily('GarageSans').fontSize(overview?11:10).fontWeight(FontWeight.Medium).fontColor('#FFFFFF').padding(overview?0:{left:9,right:9,top:5,bottom:5}).backgroundColor(overview?'#00000000':'#23FFFFFF').borderRadius(11).margin({top:7})
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
    }.width('100%').padding({left:18,right:overview?14:18,top:17,bottom:17}).linearGradient({angle:90,colors:[[overview?color:readable(color),0],[overview?'#6D529F':darken(readable(color)),1]]}).borderRadius(overview?24:22).margin({top:overview?18:12}).shadow({radius:2,color:'#16000000',offsetY:1})
  }
  @Builder private home() {
    this.title(this.name(),'SUBARU  /  '+this.model()+' · 6MT')
    if(this.prefEnabled('odometer_display_'+this.gauge(),true)){Text((mileage(this.snapshot,this.gauge())/1000).toFixed(1)+' km').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK).width('100%').margin({top:9})}
    Column() {
      Row(){Text(this.model()+' · 6MT').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor('#C9EDF8').padding({left:10,right:10,top:6,bottom:6}).backgroundColor('#30368FB5').borderRadius(13);Blank();Text(automatic.running?'正在连接 / 同步':'仪表未连接').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor('#D1DDEA').padding({left:10,right:10,top:6,bottom:6}).backgroundColor('#16FFFFFF').borderRadius(13)}.width('100%')
      this.vehicleArtwork()
      Row(){Column({space:1}){Text('预估剩余续航').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor('#AEBDD0');Text((rangeKm(this.snapshot,this.gauge(),repository.vehicle())??'—')+' km').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(42).fontWeight(FontWeight.Medium).fontColor(isLowFuel(this.fuelPercent())?'#FFD166':'#FFFFFF')}.alignItems(HorizontalAlign.Start).layoutWeight(1);Text(this.fuelPercent()===null?'剩余油量暂不可用':this.fuelPercent()!.toFixed(1)+'% · '+(this.fuelPercent()!*Number(pref(this.snapshot,'tank','50'))/100).toFixed(1)+' L').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor('#D1DDEA')}.width('100%').alignItems(VerticalAlign.Bottom)
      Progress({value:this.fuelPercent()||0,total:100,type:ProgressType.Linear}).color(COLORS[0]).backgroundColor('#3C4C60').width('100%').height(6).margin({top:10})
      Text(repository.vehicle()?this.homeCaption():this.rows('trips').length?'已导入历史记录；连接仪表补充油量与当前行程':'连接仪表后显示车辆数据').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor('#AEBDD0').width('100%').margin({top:9})
    }.width('100%').padding({left:18,right:18,top:14,bottom:17}).linearGradient({angle:135,colors:[['#253246',0],['#0F1520',1]]}).borderRadius(24).margin({top:17})
    StatisticsCard({title:'本次行程 · 仪表统计',color:COLORS[0],values:this.currentMetrics('current'),target:'current',onOpen:(target:string)=>{if(target==='current')this.showCurrent();else if(target)this.navigate(target);}})
    StatisticsCard({title:repository.vehicle()?'累计驾驶 · 自仪表开始记录':'已导入行程 · 手机历史合计',color:COLORS[3],values:this.currentMetrics('total'),onOpen:(target:string)=>{if(target==='current')this.showCurrent();else if(target)this.navigate(target);}})
    if(this.prefEnabled('show_since_refuel_'+this.gauge(),false)){StatisticsCard({title:'上次加油以来 · 仪表自动判断',color:COLORS[1],values:this.currentMetrics('refuel'),target:'refuel',onOpen:(target:string)=>{if(target==='current')this.showCurrent();else if(target)this.navigate(target);}})}
    if(this.prefEnabled('show_custom_trip_'+this.gauge(),false)){StatisticsCard({title:String(pref(this.snapshot,'custom_trip_name_'+this.gauge(),'自定义行程'))||'自定义行程',color:COLORS[2],values:this.currentMetrics('custom'),target:'custom',onOpen:(target:string)=>{if(target==='current')this.showCurrent();else if(target)this.navigate(target);}})}
    Row({space:12}){Row().width(10).height(10).borderRadius(5).backgroundColor(automatic.running?'#28B978':'#AEB7C3');Column({space:4}){Text(this.autoText||this.status).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK);Text('上次同步 '+this.lastSync).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor(MUTED)}.layoutWeight(1).alignItems(HorizontalAlign.Start);Text(this.bound?'立即同步':'绑定仪表').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(INK).padding(12).backgroundColor('#E9EDF2').borderRadius(12).enabled(!this.busy).onClick(()=>{if(this.bound)this.sync();else this.navigate('binding',3);})}.width('100%').padding({left:16,right:12,top:14,bottom:14}).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:10})
  }
  @Builder private classicStat(title:string,values:number[],target:string=''){
    Column(){this.sectionTitle(title);Text(values.length?(values[0]/1000).toFixed(1)+' km · '+preciseDuration(values[1])+'\n消耗 '+(values[2]/1000).toFixed(2)+' L · '+this.average(values):'等待仪表数据').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).lineHeight(24).width('100%').margin({top:12})}.width('100%').padding(20).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14}).onClick(()=>{if(target==='current')this.showCurrent();else if(target)this.navigate(target);})
  }
  @Builder private classicHome(){
    this.title(this.name(),'SUBARU  /  '+this.model()+' · 6MT');if(this.prefEnabled('odometer_display_'+this.gauge(),true)){Text((mileage(this.snapshot,this.gauge())/1000).toFixed(1)+' km').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(INK).width('100%').margin({top:8})}
    Column(){this.vehicleArtwork();Text('预估剩余续航').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor('#ADBED1').width('100%');Text((rangeKm(this.snapshot,this.gauge(),repository.vehicle())??'—')+' km').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(48).fontWeight(FontWeight.Medium).fontColor(isLowFuel(this.fuelPercent())?'#FFD166':'#FFFFFF').width('100%').margin({top:2});Text(this.fuelPercent()===null?'剩余油量暂不可用':'剩余油量 '+this.fuelPercent()!.toFixed(1)+'%').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor('#D1DDEA').width('100%').margin({top:8});Progress({value:this.fuelPercent()||0,total:100,type:ProgressType.Linear}).color('#EE597E').backgroundColor('#3C4C60').width('100%').height(6).margin({top:13});Text(repository.vehicle()?this.homeCaption():this.rows('trips').length?'已导入历史记录；连接仪表补充油量与当前行程':'连接仪表后显示车辆数据').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor('#ADBED1').width('100%').margin({top:12});Text('续航仅为估计结果，请合理规划加油。').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor('#ADBED1').width('100%').margin({top:10})}.width('100%').padding(22).linearGradient({angle:135,colors:[['#253246',0],['#0F1520',1]]}).borderRadius(24).margin({top:22})
    this.classicStat('本次行程 · 仪表统计',this.currentMetrics('current'),'current');this.classicStat(repository.vehicle()?'累计驾驶 · 自仪表开始记录':'已导入行程 · 手机历史合计',this.currentMetrics('total'));if(this.prefEnabled('show_since_refuel_'+this.gauge(),false)){this.classicStat('上次加油以来 · 仪表自动判断',this.currentMetrics('refuel'),'refuel')}if(this.prefEnabled('show_custom_trip_'+this.gauge(),false)){this.classicStat(String(pref(this.snapshot,'custom_trip_name_'+this.gauge(),'自定义行程')),this.currentMetrics('custom'),'custom')}
    Column({space:12}){this.sectionTitle('仪表状态');Text(this.autoText||this.status).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(INK).width('100%');Text(this.lastSync).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%');this.action(this.bound?'立即连接 / 同步':'绑定我的仪表',INK,()=>{if(this.bound)this.sync();else this.navigate('binding',3);})}.width('100%').padding(20).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }
  private inconsistentTime(r:Row):boolean{return (Number(r.flags)&1)!==0 && (Number(r.end_epoch_s)<Number(r.start_epoch_s)||Number(r.duration_s)>Number(r.end_epoch_s)-Number(r.start_epoch_s)+59);}
  private tripStatus(r:Row):string{return Number(r.trip_id)<0?'手动拆分':this.inconsistentTime(r)?'待核实':r.data_revised===1?'测试数据':r.time_revised===1?'已修订':(Number(r.flags)&1)?'已同步':'时间未知';}
  private tripStatusColor(r:Row):string{return this.tripStatus(r)==='已修订'?COLORS[1]:['待核实','测试数据','时间未知'].includes(this.tripStatus(r))?'#B56F23':COLORS[0];}
  private tripTimeLabel(r:Row):string{if(!(Number(r.flags)&1))return '升级前记录或本次行程未完成手机授时';const d=new Date(Number(r.start_epoch_s)*1000),start=timeText(Number(r.start_epoch_s)),end=timeText(Number(r.end_epoch_s));return start.substring(0,10).replace(/-/g,'.')+' 星期'+['日','一','二','三','四','五','六'][d.getDay()]+' · '+start.substring(11)+' → '+(start.substring(0,10)===end.substring(0,10)?end.substring(11):end.substring(5));}
  @Builder private tripStripMetric(title:string,value:string,color:string,size:number){Column({space:3}){Text(title).fontFamily('GarageSans').fontSize(10).fontWeight(FontWeight.Medium).fontColor(MUTED);Text(value).fontFamily('GarageSans').fontSize(size).minFontSize(12).maxFontSize(size).heightAdaptivePolicy(TextHeightAdaptivePolicy.MIN_FONT_SIZE_FIRST).maxLines(1).fontWeight(FontWeight.Medium).fontColor(readable(color))}.width('100%').alignItems(HorizontalAlign.Start)}
  @Builder private history() {
    Row(){Column(){this.title('驾驶足迹','按每日里程回看驾驶节奏 · 数据仅保存在本机')}.layoutWeight(1);Text(this.revisionMode?'完成修订':'修订记录').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(this.revisionMode?'#FFFFFF':COLORS[1]).padding(12).backgroundColor(this.revisionMode?COLORS[1]:soften(COLORS[1])).borderRadius(14).onClick(()=>this.revisionMode=!this.revisionMode)}.width('100%')
    Text(this.rows('trips').length+' 次行程 · '+this.rows('trips').filter((r:Row)=>(Number(r.flags)&1)!==0&&!this.inconsistentTime(r)).length+' 次时间完整 · 记录仅保存在本机').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%').margin({top:7})
    Column(){this.sectionTitle('驾驶总览');this.metricPair('累计里程',(summary(this.rows('trips')).distance/1000).toFixed(1)+' km','驾驶日',summary(this.rows('trips')).days+' 天');this.metricPair('行程记录',this.rows('trips').length+' 次','驾驶时长',preciseDuration(summary(this.rows('trips')).duration),INK);this.metricPair('平均每次行程',this.rows('trips').length?(summary(this.rows('trips')).distance/1000/this.rows('trips').length).toFixed(1)+' km':'—','总平均油耗',this.average([summary(this.rows('trips')).distance,summary(this.rows('trips')).duration,summary(this.rows('trips')).fuel]),COLORS[0],'','累计消耗 '+(summary(this.rows('trips')).fuel/1000).toFixed(2)+' L',COLORS[0])}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){
      this.sectionTitle('驾驶日历')
      Row(){Text('‹').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(22).fontColor(this.previousYear()===this.calendarYear?'#616B79':readable(COLORS[0])).textAlign(TextAlign.Center).width(48).height(40).backgroundColor(this.previousYear()===this.calendarYear?'#F4F6F9':soften(COLORS[0])).borderRadius(14).enabled(this.previousYear()!==this.calendarYear).onClick(()=>this.setYear(this.previousYear()));Text(this.calendarYear+' 年').layoutWeight(1).textAlign(TextAlign.Center).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(16).fontWeight(FontWeight.Medium);Text('›').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(22).fontColor(this.nextYear()===this.calendarYear?'#616B79':readable(COLORS[0])).textAlign(TextAlign.Center).width(48).height(40).backgroundColor(this.nextYear()===this.calendarYear?'#F4F6F9':soften(COLORS[0])).borderRadius(14).enabled(this.nextYear()!==this.calendarYear).onClick(()=>this.setYear(this.nextYear()))}.width('100%').margin({top:7})
      Scroll(this.calendarScroller){CalendarHeatmap({weeks:this.calendarWeeks,onSelect:(detail:string)=>{this.calendarDetail=detail;}})}.width('100%').scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).margin({top:9}).onAppear(()=>{setTimeout(():void=>{this.calendarScroller.scrollEdge(Edge.End);},100);})
      Row({space:5}){Text('0km').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(MUTED);ForEach(['#E8ECF1','#D5EEF6','#8AD2E5','#45A6C9','#196894'],(c:string)=>{Rect().width(13).height(13).radius(3).fill(c)});Text('100km+').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(readable(COLORS[0]))}.width('100%').justifyContent(FlexAlign.End)
      Text(this.calendarDetail).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%').padding(12).backgroundColor('#F7F8FB').borderRadius(13).margin({top:6})
      this.note('仅使用具有可靠开始时间的行程；时间未知的旧记录仍计入上方总览。')
    }.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Text('历史驾驶记录').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(19).fontWeight(FontWeight.Medium).fontColor(INK).width('100%').margin({top:22});this.note('点击记录卡查看里程、油耗与驾驶表现')
    ForEach(this.visibleTrips,(r:Row,index:number)=>{
      Column(){
        if(index===0||this.tripMonth(r)!==this.tripMonth(this.visibleTrips[index-1])){Text((this.tripMonth(r)==='时间未知'?'时间未知':this.tripMonth(r).replace('-','年')+'月')+' · '+this.rows('trips').filter((t:Row)=>this.tripMonth(t)===this.tripMonth(r)).length+' 次行程').fontFamily('GarageSans').fontSize(13).fontWeight(FontWeight.Medium).fontColor(readable(COLORS[0])).width('100%').padding({left:11,right:11,top:8,bottom:8}).backgroundColor('#EEF1F5').margin({bottom:12})}
        Row({space:12}){
          Column(){Text((Number(r.flags)&1)?Number(timeText(Number(r.start_epoch_s)).substring(5,7))+'月':'时间').fontFamily('GarageSans').fontSize(10).fontColor(readable(COLORS[0]));Text((Number(r.flags)&1)?String(Number(timeText(Number(r.start_epoch_s)).substring(8,10))):'?').fontFamily('GarageSans').fontSize(21).fontColor(readable(COLORS[0])).fontWeight(FontWeight.Medium)}.width(52).height(58).justifyContent(FlexAlign.Center).padding(5).backgroundColor('#E9F4F8').borderRadius(14)
          Column({space:5}){Row(){Text('行程 #'+displayTripId(Number(r.trip_id))).fontFamily('GarageSans').fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1).maxLines(1).textOverflow({overflow:TextOverflow.Ellipsis});Text(this.tripStatus(r)).fontFamily('GarageSans').fontSize(10).fontWeight(FontWeight.Medium).fontColor(readable(this.tripStatusColor(r))).padding({left:9,right:9,top:4,bottom:4}).constraintSize({minHeight:26}).backgroundColor(soften(this.tripStatusColor(r))).borderRadius(12)}.width('100%');Text(this.tripTimeLabel(r)).fontFamily('GarageSans').fontSize(12).fontColor(MUTED).maxLines(2).width('100%')}.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Text('›').fontFamily('GarageSans').fontSize(24).fontColor('#687486').width(24).textAlign(TextAlign.End)
        }.width('100%')
        Row({space:8}){Column(){this.tripStripMetric('行程里程',(Number(r.distance_m)/1000).toFixed(1)+' km',COLORS[0],17)}.layoutWeight(1);Column(){this.tripStripMetric('驾驶时长',hhmm(Number(r.duration_s)),INK,16)}.layoutWeight(1);Column(){this.tripStripMetric('平均油耗',(Number(r.avg_l100_x100)/100).toFixed(1)+' L/100km',INK,16)}.layoutWeight(1)}.width('100%').padding({left:12,right:12,top:10,bottom:10}).backgroundColor('#F7F8FB').borderRadius(15).margin({top:12})
        Text('消耗燃油 '+(Number(r.fuel_ml)/1000).toFixed(2)+' L'+(Number(r.duration_s)>0?' · 平均 '+(Number(r.distance_m)/Number(r.duration_s)*3.6).toFixed(1)+' km/h':'')).fontFamily('GarageSans').fontSize(11).fontColor(MUTED).width('100%').margin({top:10})
        if(this.revisionMode){Row({space:8}){Blank();Text('修改时间').fontFamily('GarageSans').fontSize(12).fontWeight(FontWeight.Medium).fontColor(readable(COLORS[0])).height(40).constraintSize({minWidth:92}).textAlign(TextAlign.Center).padding({left:14,right:14}).backgroundColor('#E9F4F8').borderRadius(13).onClick(()=>{this.edit('trips',r);this.timeOnly=true;});Text('删除').fontFamily('GarageSans').fontSize(12).fontWeight(FontWeight.Medium).fontColor('#B3261E').height(40).constraintSize({minWidth:72}).textAlign(TextAlign.Center).padding({left:14,right:14}).backgroundColor('#FCEBF0').borderRadius(13).onClick(()=>{this.selected=r;this.selectedTable='trips';this.confirmDelete();})}.width('100%').margin({top:12})}
      }.padding(14).width('100%').backgroundColor('#FFFFFF').borderRadius(18).shadow({radius:2,color:'#12000000',offsetY:1}).margin({bottom:10}).onClick(()=>this.details(r,'trips'))
    },(r:Row)=>String(r.device_id)+'|'+r.trip_id)
    if(!this.visibleTrips.length){Text('还没有同步行程\n仪表连接后会自动保存到手机').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(15).fontColor(MUTED).textAlign(TextAlign.Center).padding(34).width('100%').backgroundColor('#FFFFFF').borderRadius(18).shadow({radius:2,color:'#12000000',offsetY:1})}
    if(this.visibleTrips.length<this.rows('trips').length){this.action('加载更多',COLORS[0],()=>{this.tripLimit+=100;this.filterLists();})}
  }

  @Builder private accounting() {
    this.title('用车记账','轻扫或点击切换栏目 · 数据仅保存在本机')
    Row({space:2}){ForEach(['加油','保养','日常','统计'],(name:string,i:number)=>{Text(name).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontWeight(FontWeight.Medium).fontColor(this.accountingSection===i?'#FFFFFF':MUTED).height(42).layoutWeight(1).textAlign(TextAlign.Center).backgroundColor(this.accountingSection===i?readable(COLORS[i]):'#00000000').borderRadius(14).onClick(()=>{this.accountingSection=i;this.pageScroller.scrollTo({xOffset:0,yOffset:0});})})}.width('100%').padding(4).backgroundColor('#E8EBF1').borderRadius(18).margin({top:14})
    Row({space:5}){ForEach([0,1,2,3],(i:number)=>{Rect().width(this.accountingSection===i?20:6).height(4).radius(3).fill(this.accountingSection===i?COLORS[i]:'#D3D8E1')})}.width('100%').justifyContent(FlexAlign.Center).margin({top:8})
    Column(){if(this.accountingSection===0){this.fuelSection()}else if(this.accountingSection===1){this.maintenanceSection()}else if(this.accountingSection===2){this.dailySection()}else{this.accountingStats()}}.width('100%').gesture(PanGesture({direction:PanDirection.Horizontal,distance:70}).onActionEnd((event:GestureEvent)=>{this.accountingSection=Math.max(0,Math.min(3,this.accountingSection+(event.offsetX<0?1:-1)));this.pageScroller.scrollTo({xOffset:0,yOffset:0});}))
  }
  @Builder private fuelSection() {
    Row(){this.primaryAction('＋  记录一次加油',COLORS[0],()=>this.edit('fuel',null))}.width('100%').margin({top:16})
    Column(){this.sectionTitle('加油概览');this.metricPair('本月花费',this.cash(sum(inMonth(this.rows('fuel'),today().substring(0,7)),'cost')),'本月加油',sum(inMonth(this.rows('fuel'),today().substring(0,7)).filter((r:Row)=>!r.draft),'litres').toFixed(2)+' L',COLORS[0],inMonth(this.rows('fuel'),today().substring(0,7)).length+' 条记录',inMonth(this.rows('fuel'),today().substring(0,7)).some((r:Row)=>r.draft===1)?'含暂存记录':'已计入口径');this.metricPair('累计花费',this.cash(sum(this.rows('fuel'),'cost')),'累计加油',sum(this.rows('fuel').filter((r:Row)=>!r.draft),'litres').toFixed(2)+' L',INK);
      Column({space:4}){Text('历史平均油耗').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(readable(COLORS[0]));Text(fuelAverage(this.rows('fuel'))===null?'等待有效区间':fuelAverage(this.rows('fuel'))!.toFixed(2)+' L/100km').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(23).fontWeight(FontWeight.Medium).fontColor(fuelAverage(this.rows('fuel'))===null?MUTED:readable(COLORS[0]));if(fuelAverage(this.rows('fuel'))!==null){Text(this.measuredFuel()).fontFamily('GarageSans').fontSize(11).fontColor(MUTED).width('100%')}}.width('100%').padding({left:15,right:15,top:13,bottom:13}).alignItems(HorizontalAlign.Start).backgroundColor(soften(COLORS[0])).borderRadius(15).margin({top:10})
      this.note('第一次加满只建立里程基准，不计算油耗。从第二次开始，本次加油量视为此前区间的消耗量；未满时同时累计油量和里程，到下一次加满再合并计算。最新一条若已加满，也正常计入历史油耗。')
    }.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('平均油耗变化 · 蓝色虚线为历史均值');ReplicaChart({points:fuelAnalysis(this.rows('fuel')),average:fuelAverage(this.rows('fuel'))||0}).margin({top:10})}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('加油记录');ForEach(this.rows('fuel'),(r:Row,i:number)=>{
      if(i===0||this.monthLabel(r)!==this.monthLabel(this.rows('fuel')[i-1])){this.monthDivider(r,this.rows('fuel'),'cost',COLORS[0])}
      Row({space:12}){
        this.dateBadge(r,COLORS[0])
        Column({space:4}){Text(r.draft?'暂存，不参与统计':r.full_tank?'已加满':'未加满 · 等待合并').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK);Text(Number(r.odometer_km).toFixed(1)+' km · '+(Number(r.litres)>0?Number(r.litres).toFixed(2)+' L':'加油量未填')).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(MUTED);if(this.fuelInterval(r)){Text(this.fuelInterval(r)).fontFamily('GarageSans').fontSize(11).fontColor(readable(COLORS[0]))}}.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Column({space:2}){Text(r.draft===1&&Number(r.cost)<=0?'花费未填':this.cash(Number(r.cost))).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontWeight(FontWeight.Medium).fontColor(readable(COLORS[0]));Text('›').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(22).fontColor('#687486')}.alignItems(HorizontalAlign.End)
      }.width('100%').padding(12).backgroundColor('#F7F8FB').borderRadius(16).margin({top:9}).onClick(()=>this.edit('fuel',r))
    },(r:Row)=>String(r.transfer_id));if(!this.rows('fuel').length){this.empty('还没有加油记录','点击上方按钮，录入第一次加油')}}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    this.note('加油记录保存在手机本机，不写入仪表，也不会影响主页使用仪表 lifetime 数据计算的续航。')
  }
  @Builder private empty(title:string,detail:string){Column({space:7}){Text('—').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(28).fontColor('#C4CAD4');Text(title).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(15).fontColor(INK);Text(detail).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED)}.width('100%').padding({top:25,bottom:25})}
  @Builder private dateBadge(row:Row,color:string){Column({space:1}){Text(Number(dayText(Number(row.date_epoch_day)).substring(5,7))+'月').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor(readable(color));Text(String(Number(dayText(Number(row.date_epoch_day)).substring(8,10)))).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(19).fontWeight(FontWeight.Medium).fontColor(readable(color))}.width(50).padding({top:7,bottom:7}).backgroundColor(soften(color)).borderRadius(13)}
  @Builder private monthDivider(row:Row,rows:Row[],key:string,color:string){
    Row(){Text(this.monthLabel(row)).fontFamily('GarageSans').fontSize(13).fontWeight(FontWeight.Medium).fontColor(readable(color));Divider().layoutWeight(1).color('#E0E4EB').margin({left:10,right:10});Text(rows.filter((r:Row)=>this.monthLabel(r)===this.monthLabel(row)).length+' 笔 · '+this.cash(sum(rows.filter((r:Row)=>this.monthLabel(r)===this.monthLabel(row)),key))).fontFamily('GarageSans').fontSize(10).fontColor(MUTED).fontWeight(FontWeight.Medium)}.width('100%').padding({left:2,right:2,top:6,bottom:3}).margin({top:9})
  }
  private fuelInterval(row:Row):string {const point=fuelAnalysis(this.rows('fuel')).find((p:Metric)=>p.recordId===String(row.transfer_id));return point?'区间油耗 '+point.value.toFixed(2)+' L/100km':'';}
  private measuredFuel():string {
    let litres=0,distance=0;fuelAnalysis(this.rows('fuel')).forEach((point:Metric)=>{const pair=point.detail.split('|');litres+=Number(pair[0]);distance+=Number(pair[1]);});
    return '统计用油 '+litres.toFixed(2)+' L · 统计里程 '+distance.toFixed(1)+' km';
  }
  private trackingItems(tires:boolean):MaintenanceItem[] {
    return maintenanceItems(this.snapshot,this.gauge()).filter((m:MaintenanceItem)=>(m.title==='轮胎')===tires);
  }
  private maintenanceHeading(tires:boolean):string {
    const items=this.trackingItems(tires),overdue=items.filter((m:MaintenanceItem)=>m.priority===0).length,soon=items.filter((m:MaintenanceItem)=>m.priority===1).length,missing=items.filter((m:MaintenanceItem)=>m.priority===2).length;
    return overdue?overdue+' 项已到或超期':soon?soon+' 项即将到期':missing?missing+' 项缺里程':'暂无到期提醒';
  }
  @Builder private maintenanceTracking(title:string,tires:boolean){
    Column(){this.sectionTitle(title);Text(this.maintenanceHeading(tires)).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(18).fontWeight(FontWeight.Medium).fontColor(readable(COLORS[1])).width('100%').margin({top:11});ForEach(this.trackingItems(tires),(m:MaintenanceItem)=>{Column({space:0}){Row(){Column({space:3}){Text(m.title).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK);Text(m.subtitle).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor(MUTED)}.alignItems(HorizontalAlign.Start).layoutWeight(1);Text(m.badge).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor(readable(m.color)).padding({left:9,right:9,top:5,bottom:5}).backgroundColor(soften(m.color)).borderRadius(11)}.width('100%');Text(m.timing).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontWeight(FontWeight.Medium).fontColor(readable(m.color)).width('100%').margin({top:8});Text(m.detail).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor(MUTED).width('100%').margin({top:4});if(m.progress>=0){Progress({value:m.progress,total:1,type:ProgressType.Linear}).color(m.color).backgroundColor('#E2E7ED').width('100%').margin({top:7})}}.width('100%').padding({left:13,right:13,top:12,bottom:12}).backgroundColor('#F7F8FB').borderRadius(15).margin({top:9})})}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }
  @Builder private maintenanceSection(){
    Row(){this.primaryAction('＋  记录保养',COLORS[1],()=>this.edit('expenses',null))}.width('100%').margin({top:16})
    Column(){this.sectionTitle('保养概览');this.metricPair(today().substring(0,4)+' 年保养花费',this.cash(sum(inMonth(this.expenses(1),today().substring(0,4)),'amount')),'累计保养记录',this.expenses(1).length+' 笔',COLORS[1])}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    this.maintenanceTracking('保养项目跟踪',false)
    this.maintenanceTracking('轮胎寿命',true)
    this.expenseList('保养历史',1)
  }
  @Builder private dailySection(){
    Row(){this.primaryAction('＋  记录日常花费',COLORS[2],()=>this.edit('expenses',null))}.width('100%').margin({top:16})
    Column(){this.sectionTitle('本月概览');this.metricPair('本月日常花费',this.cash(sum(inMonth(this.expenses(2),today().substring(0,7)),'amount')),today().substring(0,4)+' 年日常花费',this.cash(sum(inMonth(this.expenses(2),today().substring(0,4)),'amount')),COLORS[2]);this.note('从停车、通行、车辆洗护、维修、配件用品、保险、证件手续、违章或其他中选择分类。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle(today().substring(0,4)+' 年小类花费');ReplicaChart({kind:'daily',points:this.dailySlices()}).margin({top:6});ForEach(DAILY,(name:string,i:number)=>{Row(){Image($rawfile('icons/expense-'+i+'.svg')).width(20).height(20).margin({right:8});Text(name+' · '+inMonth(this.expenses(2),today().substring(0,4)).filter((r:Row)=>dailyKind(String(r.title))===i).length+' 笔').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(INK).layoutWeight(1);Text(this.cash(sum(inMonth(this.expenses(2),today().substring(0,4)).filter((r:Row)=>dailyKind(String(r.title))===i),'amount'))+' · '+Math.round(sum(inMonth(this.expenses(2),today().substring(0,4)).filter((r:Row)=>dailyKind(String(r.title))===i),'amount')/Math.max(1,sum(inMonth(this.expenses(2),today().substring(0,4)),'amount'))*100)+'%').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(readable(DAILY_COLORS[i]))}.width('100%').margin({top:11});Progress({value:sum(inMonth(this.expenses(2),today().substring(0,4)).filter((r:Row)=>dailyKind(String(r.title))===i),'amount'),total:Math.max(1,sum(inMonth(this.expenses(2),today().substring(0,4)),'amount')),type:ProgressType.Linear}).width('100%').color(DAILY_COLORS[i]).backgroundColor('#E8ECF1').margin({top:3})})}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    this.expenseList('最近记录',2)
  }
  private expenseNote(row:Row,category:number):string {const parts:string[]=[];if(row.odometer_km!==null&&row.odometer_km!==undefined)parts.push(Number(row.odometer_km).toFixed(1)+' km');if(row.note)parts.push(String(row.note));return parts.join(' · ')||(category===1?'未填写备注':'未填写详细');}
  @Builder private expenseList(title:string,category:number){
    Column(){this.sectionTitle(title);ForEach(this.expenses(category),(r:Row,i:number)=>{if(i===0||this.monthLabel(r)!==this.monthLabel(this.expenses(category)[i-1])){this.monthDivider(r,this.expenses(category),'amount',COLORS[category===1?1:2])}
      Row({space:12}){this.dateBadge(r,category===1?COLORS[1]:DAILY_COLORS[dailyKind(String(r.title))]);Column({space:4}){Row({space:7}){Image($rawfile('icons/expense-'+(category===1?4:dailyKind(String(r.title)))+'.svg')).width(18).height(18);Text(category===1?['A保','B保','轮胎更换','其他维修'][resolvedMaintenance(r)-1]:String(r.title)).fontFamily('GarageSans').fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)}.width('100%');if(category===1){Text(String(r.title)).fontFamily('GarageSans').fontSize(12).fontColor(MUTED).lineHeight(17)}Text(this.expenseNote(r,category)).fontFamily('GarageSans').fontSize(11).fontColor(MUTED).lineHeight(16)}.alignItems(HorizontalAlign.Start).layoutWeight(1);Column(){Text(this.cash(Number(r.amount))).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontWeight(FontWeight.Medium).fontColor(readable(category===1?COLORS[1]:DAILY_COLORS[dailyKind(String(r.title))]));Text('›').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(22).fontColor('#687486')}.alignItems(HorizontalAlign.End)}.padding(12).width('100%').backgroundColor('#F7F8FB').borderRadius(16).margin({top:9}).onClick(()=>this.edit('expenses',r))
    },(r:Row)=>String(r.transfer_id));if(!this.expenses(category).length){this.empty(category===1?'还没有保养记录':'还没有日常花费记录','点击上方按钮添加记录')}}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }
  @Builder private accountingStats(){
    Column({space:6}){Text('本月总开销').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor('#E0E1F6');Text(this.cash(this.currentMonthCost().total)).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(31).fontWeight(FontWeight.Medium).fontColor('#FFFFFF');Text(today().substring(0,4)+' 年累计  '+this.cash(this.annualCost('total'))).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor('#EFECFA');Text('加油、保养与日常花费统一统计').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor('#D3D0ED')}.width('100%').alignItems(HorizontalAlign.Start).padding(20).linearGradient({angle:90,colors:[['#5258A4',0],['#6D529F',1]]}).borderRadius(22).margin({top:16})
    Column(){this.sectionTitle('月度趋势');ReplicaChart({kind:'cost',months:this.costRows().slice(-12)});Row({space:18}){ForEach(['加油','保养','日常'],(n:string,i:number)=>{Text('●  '+n).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(readable(COLORS[i]))})}.width('100%').justifyContent(FlexAlign.Center)}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle(today().substring(0,4)+' 年分类构成');ForEach(['加油','保养','日常'],(n:string,i:number)=>{Row(){Text('●  '+n).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(readable(COLORS[i])).layoutWeight(1);Text(this.cash(this.annualCost(['fuel','maintenance','daily'][i]))+' · '+Math.round(this.annualCost(['fuel','maintenance','daily'][i])/Math.max(1,this.annualCost('total'))*100)+'%').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK)}.width('100%').margin({top:12});Progress({value:this.annualCost(['fuel','maintenance','daily'][i]),total:Math.max(1,this.annualCost('total')),type:ProgressType.Linear}).width('100%').color(COLORS[i]).margin({top:4})})}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('全部月度明细');if(!monthCosts(this.snapshot,this.gauge()).length){this.empty('还没有记账记录','添加记录后会从首个记账月份开始统计')}ForEach(monthCosts(this.snapshot,this.gauge()).reverse(),(m:MonthCost,index:number)=>{Column({space:9}){Row(){Text(m.label.replace('-','年')+'月').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).layoutWeight(1);Text('合计  '+this.cash(m.total)).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14)}.width('100%');Row(){ForEach(['加油','保养','日常'],(n:string,i:number)=>{Column({space:3}){Text('● '+n).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(10).fontColor(readable(COLORS[i]));Text(this.cash([m.fuel,m.maintenance,m.daily][i])).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(INK)}.layoutWeight(1)})}.width('100%')}.width('100%').padding(13).backgroundColor(index===0?soften(COLORS[3]):'#F7F8FB').borderRadius(15).margin({top:index===0?10:8})})}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }

  @Builder private settings(){
    this.title('设置','连接、显示与仪表功能')
    this.hero('vehicle',this.name(),'Subaru BRZ '+this.model()+' 6MT · '+(mileage(this.snapshot,this.gauge())/1000).toFixed(0)+' km',automatic.running?'●  正在连接 / 同步':'○  仪表未连接',COLORS[3],true)
    Row({space:2}){ForEach(['车辆','驾驶','应用'],(n:string,i:number)=>{Text(n).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontWeight(FontWeight.Medium).height(42).layoutWeight(1).textAlign(TextAlign.Center).fontColor(this.settingsSection===i?'#FFFFFF':MUTED).backgroundColor(this.settingsSection===i?readable([COLORS[0],COLORS[2],COLORS[1]][i]):'#00000000').borderRadius(14).onClick(()=>{this.settingsSection=i;this.pageScroller.scrollTo({xOffset:0,yOffset:0});})})}.width('100%').padding(4).backgroundColor('#E8EBF1').borderRadius(18).margin({top:13})
    if(this.settingsSection===0){
      Column(){
        this.listRow('vehicle','车型与车辆名称','Subaru BRZ '+this.model()+' 6MT',COLORS[0],()=>this.openRoute('vehicle'));this.divider()
        this.listRow('gauge','我的仪表',this.bound?this.bound+' · 点击更换或重新绑定':'尚未绑定',COLORS[1],()=>this.openRoute('binding'));this.divider()
        this.listRow('plate','自定义车牌','生成车牌并安装到首页车辆',COLORS[0],()=>this.openRoute('plate'));this.divider()
        this.listRow('mileage','手动校准里程','当前估算 '+(mileage(this.snapshot,this.gauge())/1000).toFixed(1)+' km',COLORS[3],()=>this.setting('里程校准',(mileage(this.snapshot,this.gauge())/1000).toFixed(1)));this.divider()
        this.switchRow('display','新版首页界面','关闭后恢复经典首页布局',COLORS[0],this.prefEnabled('refined_home_ui'),(v:boolean)=>this.togglePref('refined_home_ui',v));this.divider()
        this.switchRow('display','显示车辆里程','同步到仪表设备信息页和 App 首页',COLORS[3],this.prefEnabled('odometer_display_'+this.gauge(),true),(v:boolean)=>{this.togglePref('odometer_display_'+this.gauge(),v);})
      }.width('100%').padding({top:3,bottom:3}).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:12})
      Column(){this.listRow('fuel','油箱与续航估算','油箱 '+pref(this.snapshot,'tank','50')+' L · '+this.sourceLabel(),COLORS[0],()=>this.openRoute('vehicle'))}.width('100%').padding({top:3,bottom:3}).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:12})
      this.note('常用入口已经归入对应栏目，无需先经过“常用”中转。')
    }else if(this.settingsSection===1){
      Column(){this.switchRow('fuel','显示上次加油以来','首页行程卡片',COLORS[2],this.prefEnabled('show_since_refuel_'+this.gauge(),false),(v:boolean)=>this.togglePref('show_since_refuel_'+this.gauge(),v));this.divider();this.switchRow('trip','显示自定义行程','首页行程卡片',COLORS[2],this.prefEnabled('show_custom_trip_'+this.gauge(),false),(v:boolean)=>this.togglePref('show_custom_trip_'+this.gauge(),v));this.divider();this.listRow('trip','自定义行程名称',String(pref(this.snapshot,'custom_trip_name_'+this.gauge(),'自定义行程')),COLORS[2],()=>this.setting('custom_trip_name',String(pref(this.snapshot,'custom_trip_name_'+this.gauge(),''))));this.divider();this.listRow('trip','自定义行程与历史','查看、归档或重置当前区间',COLORS[2],()=>this.openRoute('custom'))}.width('100%').padding({top:3,bottom:3}).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:12})
      Column(){this.listRow('gauge','仪表设置','日间亮度与当前仪表参数',COLORS[1],()=>this.openRoute('gauge'));this.divider();this.listRow('fuel','自动加油识别阈值','在车辆设置中调整',COLORS[0],()=>this.openRoute('vehicle'))}.width('100%').padding({top:3,bottom:3}).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:12})
    }else{
      Column(){this.listRow('update','App 更新','当前 v0.4.0 · 查看版本与下载安装',COLORS[3],()=>this.openRoute('appUpdate'));this.divider();this.listRow('legacy','数据迁移','导出完整数据包，或导入并合并旧设备记录',COLORS[3],()=>this.openRoute('transfer'));this.divider();this.listRow('firmware','仪表固件更新','查看更新与回滚',COLORS[1],()=>this.openRoute('firmware'));this.divider();this.listRow('gauge','仪表设置','查看固件信息和显示参数',COLORS[1],()=>this.openRoute('gauge'))}.width('100%').padding({top:3,bottom:3}).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:12})
      Column(){this.switchRow('autostart','开机自启 / 自动连接','打开 App 后恢复连接，连接期间保持后台同步',COLORS[0],this.automaticEnabled,(v:boolean)=>this.automaticToggle(v));this.divider();this.listRow('bluetooth','系统级 BLE 唤醒','查看鸿蒙后台连接状态',COLORS[0],()=>this.openRoute('background'));this.divider();this.listRow('feedback','问题反馈','报告异常、提出建议并附带脱敏诊断信息',COLORS[3],()=>this.openRoute('feedback'));this.divider();this.listRow('legal','使用须知与版权','安全边界、数据权限、开源许可与非官方声明',COLORS[3],()=>this.openRoute('legal'));this.divider();this.listRow('legacy','切换到经典设置页','完整保留原有设置界面与全部入口',MUTED,()=>this.openRoute('legacy'))}.width('100%').padding({top:3,bottom:3}).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:12})
      this.note('新版与经典版共用同一份设置数据，切换界面不会重置车辆、行程或记账记录。')
    }
  }
  private dailySlices():Metric[]{return DAILY.map((name:string,i:number):Metric=>({label:name,value:sum(inMonth(this.expenses(2),today().substring(0,4)).filter((r:Row)=>dailyKind(String(r.title))===i),'amount'),detail:''}));}
  private homeCaption():string {const at=Number(this.snapshot.devices[this.gauge()]?.last_fuel_at||0),age=Date.now()-at;const freshness=at>0?(age>=0&&age<=20000?'已更新 · ':'未更新 · 上次更新 ')+timeText(at/1000):'未更新 · 尚未收到油量';const consumption=rangeConsumption(this.snapshot,this.gauge(),repository.vehicle());const rawFactor=Number(pref(this.snapshot,'range_correction_factor','1.06')),factor=rawFactor>=.5&&rawFactor<=2?rawFactor:1.06;const source=this.sourceLabel()+(consumption!==null&&consumption>0?' '+consumption.toFixed(2)+' L/100km'+(String(pref(this.snapshot,'range_consumption_source','gauge_history'))==='fuel_records'?'':'，续航计算 ×'+factor.toFixed(2)):'暂无有效样本');return freshness+'\n按 '+Number(pref(this.snapshot,'tank','50')).toFixed(0)+' L油箱 · '+source+(this.refinedHome?'\n续航仅为估计结果，请合理规划加油。':'');}
  private sourceLabel():string {return ['最近五次驾驶','仪表历史油耗','加油记录平均油耗'][Math.max(0,['recent_five','gauge_history','fuel_records'].indexOf(String(pref(this.snapshot,'range_consumption_source','gauge_history'))))];}
  @Builder private vehicleSettings(){
    this.back();this.title('车辆设置','车辆资料、里程与续航');this.hero('vehicle',this.name(),'Subaru BRZ '+this.model()+' 6MT · '+(mileage(this.snapshot,this.gauge())/1000).toFixed(0)+' km',automatic.running?'● 仪表连接中':'○ 仪表未连接',COLORS[0])
    Column(){this.listRow('vehicle','首页车辆名称','最多 9 个字符，仅保存在手机本地',COLORS[0],()=>this.setting('vehicle_display_name',this.name()),this.name());this.divider();this.listRow('plate','首页车牌','生成并安装自定义车牌图案',COLORS[0],()=>this.openRoute('plate'))}.width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('车型设置');ForEach(['Subaru BRZ ZD8 6MT','Subaru BRZ ZC6 6MT'],(name:string,i:number)=>{Row(){Radio({value:name,group:'vehicle'}).checked(this.model()===(i===0?'ZD8':'ZC6')).radioStyle({checkedBackgroundColor:COLORS[0]}).onChange((checked:boolean)=>{if(checked)this.setModel(i===0?4:1);});Text(name).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(INK)}.width('100%').height(46)});this.note('车型可以先保存在手机。连接仪表后可将选择同步到仪表。');if(this.bound){this.action('同步车型到仪表',COLORS[0],()=>this.writeGauge('profile',this.model()==='ZD8'?4:1))}}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('自动加油识别');this.readRow('识别阈值',this.gaugeSettings?.threshold?this.gaugeSettings.threshold/1000+' L':'');Slider({value:(this.gaugeSettings?.threshold||10000)/1000,min:5,max:20,step:1}).enabled(!!this.bound&&!this.busy).onChange((value:number,mode:SliderChangeMode)=>{if(mode===SliderChangeMode.End)this.writeGauge('threshold',value*1000);});if(!this.gaugeSettings){this.action('读取仪表设置',COLORS[0],()=>this.readGauge())}this.note('仪表需要两次独立油位样本确认油量上升。阈值需连接支持该功能的仪表后调整。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.switchRow('vehicle','显示 3D 车辆','默认使用 2D 图片；开启后可旋转、缩放及调整车漆',COLORS[0],this.prefEnabled('show_3d_vehicle',false),(v:boolean)=>this.togglePref('show_3d_vehicle',v))}.width('100%').backgroundColor('#FFFFFF').borderRadius(22).margin({top:14})
    Column(){this.sectionTitle('油箱与续航');this.readRow('油箱容量',pref(this.snapshot,'tank','50')+' L',COLORS[0]);this.readRow('油耗来源',this.sourceLabel());this.action('调整油箱容量',COLORS[0],()=>this.setting('tank',String(pref(this.snapshot,'tank','50'))));Text('续航估算油耗来源').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%').margin({top:14});ForEach(['最近五次驾驶','仪表历史油耗','加油记录平均油耗'],(name:string,i:number)=>{Row(){Radio({value:name,group:'range'}).checked(pref(this.snapshot,'range_consumption_source','gauge_history')===['recent_five','gauge_history','fuel_records'][i]).radioStyle({checkedBackgroundColor:COLORS[0]}).onChange((checked:boolean)=>{if(checked)this.run(async()=>{await repository.edit((s:Snapshot)=>putPref(s,'range_consumption_source',['recent_five','gauge_history','fuel_records'][i]));this.refresh();});});Text(name).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(INK)}.width('100%').height(46)});if(pref(this.snapshot,'range_consumption_source','gauge_history')!=='fuel_records'){this.readRow('续航修正系数',String(pref(this.snapshot,'range_correction_factor','1.06')));this.action('调整续航修正系数',COLORS[0],()=>this.setting('range_correction_factor',String(pref(this.snapshot,'range_correction_factor','1.06'))))}this.note('设置直接在本页完成，不再跳转到经典设置。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }
  @Builder private bindingPage(){
    this.back();this.title('我的仪表','选择或重新绑定 SkyGauge')
    Column(){this.switchRow('gauge','系统发现仪表后自动同步',partnerWake.supported()?'由鸿蒙系统发现已配对仪表并唤醒同步':'需要 HarmonyOS 6.1 或更高版本',COLORS[0],this.wakeEnabled,(v:boolean)=>this.run(async()=>{if(v)await partnerWake.enable(this.context(),this.bound);else await partnerWake.disable();this.refresh();}))}.width('100%').backgroundColor('#FFFFFF').borderRadius(22).margin({top:14})
    Column({space:12}){Text(this.bound?'当前仪表 '+this.bound:'尚未绑定仪表').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(INK).width('100%');if(!this.consent){this.note('蓝牙用于查找和连接仪表、授时及同步车辆数据。');this.action('了解并启用蓝牙功能',COLORS[0],()=>this.run(async()=>{await repository.agree();this.refresh();}))}
      this.action('扫描仪表',COLORS[0],()=>this.run(async()=>{need(this.consent,'请先启用蓝牙功能');this.scans=[];this.status='扫描中';await bluetooth.scan(this.context(),(items:ScanItem[])=>this.scans=items);this.status=this.scans.length?'请选择仪表':'未发现仪表，请检查仪表供电';}),true)
      ForEach(this.scans,(item:ScanItem)=>{this.listRow('gauge',item.name,item.address+' · '+item.rssi+' dBm',COLORS[0],()=>{this.address=item.address;})})
      TextInput({text:this.address,placeholder:'手动输入仪表地址'}).fontFamily('GarageSans').placeholderFont({size:15,family:'GarageSans'}).placeholderColor('#616B79').fontSize(15).fontColor(INK).height(52).backgroundColor('#F7F8FB').borderRadius(13).maxLength(17).onChange((v:string)=>this.address=v.toUpperCase());this.action('保存绑定',COLORS[0],()=>this.run(async()=>{need(this.consent,'请先启用蓝牙功能');await repository.bind(this.address.trim());this.refresh();this.status='已绑定仪表';this.navigate('');}))
      if(this.bound){this.action('解除绑定',COLORS[1],()=>this.getUIContext().showAlertDialog({title:'解除仪表绑定？',message:'本机保存的历史记录会保留。',primaryButton:{value:'取消',action:()=>{}},secondaryButton:{value:'解除',action:()=>this.run(async()=>{await partnerWake.disable();await repository.unbind();this.refresh();})}}))}
      this.note(this.status)
    }.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }
  @Builder private gaugePage(){
    this.back();this.title('仪表设置','亮度、显示参数与同步状态');this.hero('gauge','SkyGauge',this.gaugeText||'等待读取固件版本',automatic.running?'● 连接中':'○ 当前未连接',COLORS[1])
    Column(){this.sectionTitle('当前可调整');Text('手机端可调整日间亮度；其余参数以整洁的只读行展示。命令会等待车辆与行程同步空闲后发送，并由仪表回读确认。').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).lineHeight(20).margin({top:8});this.note('车型与自动加油识别阈值已归入车辆设置。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('同步状态');Text(this.gaugeText||'等待仪表设置数据。当前先显示完整参数结构；请保持仪表上电。').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%');this.action('连接并读取仪表信息',COLORS[1],()=>this.readGauge())}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    ForEach(['基本设置','提醒设置','页面显示','图表报警阈值','联动与启动'],(group:string)=>{Column(){this.sectionTitle(group);ForEach(this.gaugeRows(group),(row:Field)=>{this.readRow(row.label,row.value)});if(group==='基本设置'){Text('日间亮度 '+(this.gaugeSettings?.brightness??'—')+'%').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).width('100%');Slider({value:this.gaugeSettings?.brightness||50,min:10,max:100,step:1}).enabled(!!this.bound&&!this.busy).onChange((v:number,mode:SliderChangeMode)=>{if(mode===SliderChangeMode.End)this.writeGauge('brightness',v);})}}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})})
  }
  private gaugeRows(group:string):Field[]{
    const names: string[] = group==='基本设置'?['OBD 协议','ELM327 名称','ELM327 MAC','主题','默认页面']:group==='提醒设置'?['转速黄线','转速闪烁','多表联动闪烁','刹车温度提醒','机油压力提醒']:group==='页面显示'?['温度页项目 1','温度页项目 2','温度页项目 3','信息页项目 1','信息页项目 2','信息页项目 3','信息页项目 4','信息页项目 5','指针页数据','图表页数据']:group==='图表报警阈值'?['CLT 水温','IAT 进气温度','OIL 机油温度','LOD 发动机负荷','TPS 节气门','RPM 转速','SPD 车速','BAT 电压','OIP 机油压力','BKT 刹车温度','BST 增压压力','AFR 空燃比']:['仪表角色','仪表位置','联动开机动画','启动画面','RaceChrono 服务','主表 MAC','行程合并间隔'];
    const v=this.gaugeSettings?.values;return names.map((name:string,i:number):Field=>({key:name,label:name,value:v?this.gaugeValue(group,i,v):''}));
  }
  private gaugeValue(group:string,i:number,v:Row):string{
    const items=['CLT 水温','IAT 进气温度','OIL 机油温度','LOD 发动机负荷','TPS 节气门','RPM 转速','SPD 车速','BAT 电压','OIP 机油压力','BKT 刹车温度','BST 增压压力','AFR 空燃比'];
    if(group==='基本设置'){const keys=['协议','OBD 名称','OBD 地址','主题','默认页面'];const n=Number(v[keys[i]]);return i===0?['自动','SAE J1850 PWM','SAE J1850 VPW','ISO 9141-2','ISO 14230-4 KWP（慢）','ISO 14230-4 KWP（快）','CAN 11bit / 500k','CAN 29bit / 500k','CAN 11bit / 250k','CAN 29bit / 250k'][n]||String(n):i===3?['DEFAULT','AMBER','OCEAN'][n]||String(n):i===4?['温度','信息','图表','指针','挡位 + 转速','车速','油量','行程历史','行程总览'][n]||String(n):String(v[keys[i]]||'未设置');}
    if(group==='提醒设置'){const values=[String(v['转速报警'])+' rpm',v['转速报警动画']?'开启':'关闭',v['联动报警']?'开启':'关闭',v['刹车温度报警']+' °C',(Number(v['机油压力报警×10'])/10).toFixed(1)+' bar'];return values[i];}
    if(group==='页面显示'){const n=i<3?Number(String(v['温度页显示项目']).split(',')[i]):i<8?Number(String(v['信息页显示项目']).split(',')[i-3]):Number(v[i===8?'指针项目':'图表项目']);return items[n]||String(n);}
    if(group==='图表报警阈值'){const raw=Number(String(v['图表报警值']).split(',')[i]);return raw===32767?'关闭':i<=2?raw+' °C':i<=4?raw+'%':i===5?raw+' rpm':i===6?raw+' km/h':i===7?(raw/1000).toFixed(1)+' V':i===8||i===10?(raw/10).toFixed(1)+' bar':i===9?(raw/10).toFixed(1)+' °C':(raw/100).toFixed(1);}
    return [ ['MASTER','SLAVE','STANDALONE'][Number(v['设备角色'])], '第 '+v['设备位置']+' 表', ['关闭','RACE','VIDEO'][Number(v['开机动画'])],['默认动画','自定义图片','视频动画'][Number(v['启动模式'])],v['RaceChrono']?'开启':'关闭',String(v['主机地址']),v['行程合并分钟']+' 分钟'][i]||'—';
  }

  private detailedTime(seconds:number):string {const d=new Date(seconds*1000);return timeText(seconds).substring(0,10)+'\n'+[d.getHours(),d.getMinutes(),d.getSeconds()].map((v:number)=>String(v).padStart(2,'0')).join(':');}
  @Builder private tripDetail(){
    this.back(this.currentDetail?'返回首页':'返回驾驶足迹',COLORS[0]);this.title('行程详情',this.selected?(this.currentDetail?'正在进行 · 数据来自仪表实时快照':timeText(Number(this.selected.start_epoch_s)))+' · #'+displayTripId(Number(this.selected.trip_id)):'')
    if(this.selected){
      this.hero('trip',this.currentDetail?'本次行程':'行程 #'+displayTripId(Number(this.selected.trip_id)),(Number(this.selected.flags)&1)?timeText(Number(this.selected.start_epoch_s)):'时间信息尚不完整',(Number(this.selected.distance_m)/1000).toFixed(1)+' km · '+preciseDuration(Number(this.selected.duration_s)),COLORS[0])
      if(!this.currentDetail){Row({space:8}){Column(){this.action('修订测试数据',COLORS[1],()=>this.edit('trips',this.selected))}.layoutWeight(1);if(Number(this.selected.trip_id)>0){Column(){this.action('手动拆分这条行程',COLORS[0],()=>this.edit('split',this.selected))}.layoutWeight(1)}}.width('100%').margin({top:8})}
      if(!this.currentDetail&&this.selected.data_revised===1){Text(Number(this.selected.trip_id)<0?'由行程 #'+Math.floor((-Number(this.selected.trip_id)-1)/2)+' 手动拆分 · 不会写回仪表':'测试数据已手动修订 · 不会写回仪表').fontFamily('GarageSans').fontSize(11).fontWeight(FontWeight.Medium).fontColor(readable(COLORS[1])).textAlign(TextAlign.Center).width('100%').padding({left:12,right:12,top:9,bottom:9}).backgroundColor(soften(COLORS[1])).borderRadius(13).margin({top:10})}
      StatisticsCard({title:this.currentDetail?'本次行程 · 实时快照':'行程概览',color:COLORS[0],values:this.tripMetrics(this.selected),target:'',note:'平均时速 '+(Number(this.selected.duration_s)>0?(Number(this.selected.distance_m)/Number(this.selected.duration_s)*3.6).toFixed(1)+' km/h':'旧记录未采集'),onOpen:(target:string)=>{if(target==='current')this.showCurrent();else if(target)this.navigate(target);}})
      Column(){this.sectionTitle('速度与转速');this.metricPair('最高速度',this.selected.max_speed_kmh==null?'旧记录未采集':this.selected.max_speed_kmh+' km/h','最高转速',this.selected.max_rpm==null?'旧记录未采集':this.selected.max_rpm+' rpm');this.note(this.selected.max_speed_kmh!==null||this.selected.max_rpm!==null?'极值由仪表在行程中实时统计。':'该行程来自旧版记录，仪表当时尚未采集速度与转速极值。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
      Column(){this.sectionTitle('燃油表现');this.metricPair('平均油耗',(Number(this.selected.avg_l100_x100)/100).toFixed(1)+' L/100km','消耗燃油',(Number(this.selected.fuel_ml)/1000).toFixed(2)+' L')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
      Column(){this.sectionTitle('时间记录');if(Number(this.selected.flags)&1){this.metricBox('开始',this.detailedTime(Number(this.selected.start_epoch_s)));Column(){this.metricBox('结束',this.detailedTime(Number(this.selected.end_epoch_s)))}.width('100%').margin({top:8});if(this.selected.time_revised===1){this.note('时间由用户手动修订')}else if(this.inconsistentTime(this.selected)){this.note('时间跨度与驾驶时长不一致，请核实')}}else{this.note('升级前记录或该次行程未完成手机授时，因此没有可靠的开始/结束时间。')}if(this.currentDetail){this.note('本次行程结束后，最终记录会同步到驾驶足迹。')}}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    }
  }
  @Builder private intervalPage(kind:string){
    this.back('返回首页',COLORS[kind==='refuel'?1:2]);this.title(kind==='refuel'?'上次加油以来':'自定义行程',kind==='refuel'?'按当前识别阈值连续确认并独立记录':'可独立重置并保留历史区间')
    this.hero(kind==='refuel'?'fuel':'trip',kind==='refuel'?'上次加油以来':String(pref(this.snapshot,'custom_trip_name_'+this.gauge(),'自定义行程')),kind==='refuel'?'由仪表自动识别或手动重置':'独立统计，可命名与归档',this.currentMetrics(kind).length?(this.currentMetrics(kind)[0]/1000).toFixed(1)+' km · '+preciseDuration(this.currentMetrics(kind)[1]):'等待仪表数据',COLORS[kind==='refuel'?1:2])
    StatisticsCard({title:kind==='refuel'?'当前 · 上次重置以来':'当前 · 自定义行程',color:COLORS[kind==='refuel'?1:2],values:this.currentMetrics(kind),target:'',note:kind==='custom'?(Number(pref(this.snapshot,'custom_reset_at_'+this.gauge(),0))>0?'开始于 '+timeText(Number(pref(this.snapshot,'custom_reset_at_'+this.gauge(),0))/1000):'等待起始时间'):(Number(repository.refuelLive()?.start||0)>0?'开始于 '+timeText(Number(repository.refuelLive()?.start)):'等待起始时间'),onOpen:(target:string)=>{if(target==='current')this.showCurrent();else if(target)this.navigate(target);}})
    Row(){this.action((kind==='refuel'?this.refuelRevision:this.customRevision)?'完成修订':'修订记录',COLORS[kind==='refuel'?1:2],()=>{if(kind==='refuel')this.refuelRevision=!this.refuelRevision;else this.customRevision=!this.customRevision;})}.width('100%').margin({top:12})
    if(kind==='refuel'?this.refuelRevision:this.customRevision){
      Row({space:8}){
        if(kind==='custom'){Column(){this.action('修改名称',COLORS[2],()=>this.setting('custom_trip_name',String(pref(this.snapshot,'custom_trip_name_'+this.gauge(),''))))}.layoutWeight(1)}
        Column(){this.action(kind==='refuel'?'手动重置':'重置并归档',COLORS[kind==='refuel'?1:2],()=>{if(kind==='refuel')this.refuelAction(2);else this.resetCustomTrip();},true)}.layoutWeight(1)
      }.width('100%').margin({top:12})
    }
    if(kind==='refuel'&&repository.pendingRefuel(this.gauge())){this.note('节点删除请求已保存，等待连接仪表核对范围并确认结果')}
    if(kind==='custom'){this.note(repository.customStatus(this.gauge())==='confirmed'?'当前起点已由仪表保存并确认':repository.customStatus(this.gauge())==='legacy_sent'?'当前起点已发送，旧固件不支持保存确认':'手机起点已保存，等待连接仪表确认')}
    this.note(kind==='refuel'?'仪表需要两次独立油位样本均确认油量上升达到自动加油识别阈值，才会建立新重置点。这里的记录与手动加油记录完全独立。':'重置时会先把当前区间保存到下方历史，再从仪表当前累计值开始新的区间；自定义名称不会发送到仪表。')
    if(kind==='refuel'&&this.refuelRevision&&this.rows(kind).length){Row(){this.action('删除第一个节点以前的数据',COLORS[1],()=>this.refuelAction(5,Math.min(...this.rows(kind).map((r:Row)=>Number(r.interval_id)))))}.margin({top:10}).width('100%')}
    Text(kind==='refuel'?'历次加油以来':'历次自定义行程').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(19).fontWeight(FontWeight.Medium).fontColor(INK).width('100%').margin({top:22});this.note(kind==='refuel'?'每个加油节点之间的独立统计':'已完成并归档的自定义统计区间')
    ForEach(this.rows(kind),(r:Row,i:number)=>{StatisticsCard({title:kind==='refuel'?((Number(r.flags)&2)?'手动重置':'自动识别加油'+(Number(r.detected_added_ml)>0?' · 约 +'+(Number(r.detected_added_ml)/1000).toFixed(1)+' L':'')):String(r.name||'自定义行程'),color:COLORS[kind==='refuel'?1:2],values:this.tripMetrics(r),target:'',note:timeText(Number(r.start_epoch_s))+'\n至 '+timeText(Number(r.end_epoch_s)),onOpen:(target:string)=>{if(target==='current')this.showCurrent();else if(target)this.navigate(target);}});if(kind==='refuel'){if(this.refuelRevision){this.action('修订测试数据',COLORS[1],()=>this.edit('refuel',r))}if(this.refuelRevision){this.action(i===0?'删除最近加油节点':'删除此加油节点',COLORS[1],()=>this.refuelAction(3,Number(r.interval_id)))}}},(r:Row)=>String(r.transfer_id||r.interval_id))
    if(!this.rows(kind).length){this.note(kind==='refuel'?'暂无已完成的加油以来区间':'暂无已完成的自定义行程')}
  }
  @Builder private transferPage(){
    this.back();this.title('数据迁移','切换设备前先同步、导出，再在新设备导入')
    Text('包含九类历史记录与删除标记、全部可迁移车辆参数、车牌与外观、每块仪表的首页快照、剩余油量基线、当前加油区间、仪表设置、固件与授时缓存。系统权限、蓝牙配对与待执行仪表命令需要在新手机重新设置。').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).lineHeight(22).fontColor(INK).margin({top:16})
    Column({space:12}){this.action('导出完整数据包',COLORS[3],()=>this.exportPackage(),true);this.action('导入并合并数据包',COLORS[3],()=>this.importPackage());this.action('导入前备份',COLORS[3],()=>{this.backups=repository.backupPaths();this.navigate('backups');});if(this.incoming){Text('来源仪表：'+(this.incoming.gauge||'未绑定')+'\n导出时间：'+timeText(this.incoming.createdAt/1000)+' · 仪表缓存 '+Object.keys(this.incoming.devices).length+' 份').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED);this.switchRow('legacy','冲突时采用数据包','关闭时保留本机',COLORS[3],this.preferIncoming,(v:boolean)=>{this.preferIncoming=v;this.updatePreview();});Text(this.preview).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(readable(COLORS[3]));this.action('确认合并',COLORS[3],()=>this.run(async()=>{if(!this.incoming)return;await repository.importData(this.incoming,this.preferIncoming);this.incoming=null;this.refresh();this.status='历史记录、车辆配置与仪表缓存已合并。重新绑定同一仪表后会继续核对并补齐新数据。';}),true);this.action('取消',MUTED,()=>this.incoming=null)}this.note('导入前会自动保留本机数据包。冲突可选择以数据包为准或保留本机；不会仅因数据包缺少某条记录就删除本机记录。手动拆分和加油历史版本冲突按整组处理。');this.note('操作期间暂时断开手机与仪表的数据连接，完成后恢复。仪表继续自行记录。已被覆盖且未导出的记录无法补回。');Text(this.status).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(INK).width('100%')}.width('100%').margin({top:20})
  }
  private goBack():void { this.refuelRevision=false;this.customRevision=false;const parent=this.parentRoutes.pop()||'';this.route=parent;const y=this.scrollPositions[this.pageKey()]||0;setTimeout(():void=>{this.pageScroller.scrollTo({xOffset:0,yOffset:y});},30); }
  private generatePlate():void{this.run(async()=>{this.platePreview=plateText(this.plateInput);await repository.edit((s:Snapshot)=>putPref(s,'custom_license_plate',this.platePreview));this.refresh();this.plateMessage='车牌已生成并保存';});}
  @Builder private platePage(){
    this.back('返回车辆设置',COLORS[0]);this.title('自定义车牌','小型燃油汽车 · GA 36-2018');this.hero('plate','首页车牌','生成、预览并安装到当前车型','仅保存在本机',COLORS[0])
    Column(){this.sectionTitle('号牌预览');PlateView({value:this.platePreview}).id('plate-preview').margin({top:14});this.note('按 440 mm × 140 mm 版面、45 mm × 90 mm 字符槽位绘制；“·”为号牌版面间隔符。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('输入完整车牌号');TextInput({text:this.plateInput,placeholder:'例如：粤B12345'}).placeholderFont({size:22,family:'GarageSans'}).placeholderColor('#616B79').fontColor(INK).maxLength(12).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(22).height(52).backgroundColor('#F5F7FA').borderRadius(12).margin({top:12}).onChange((v:string)=>{this.plateInput=v;this.plateMessage='可输入空格或间隔点，生成时会自动规范化。';});this.note(this.plateMessage);Row({space:12}){Column(){this.action('生成预览',COLORS[0],()=>this.generatePlate(),true)}.layoutWeight(1);Column(){this.action('复制号码',COLORS[0],()=>this.run(async()=>{need(!!this.platePreview,'请先生成预览');await pasteboard.getSystemPasteboard().setData(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN,this.platePreview.replace('·','')));this.status='车牌号码已复制';}))}.layoutWeight(1)}.width('100%').margin({top:12})}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('当前支持的规则');Text('• 首位：31 个省、自治区、直辖市简称之一\n• 第二位：发牌机关英文字母代号 A～Z\n• 序号：必须为 5 位数字或英文字母，字母不超过 2 位\n• 序号中的英文字母 I、O 不使用').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).lineHeight(22).width('100%').margin({top:10});this.note('此工具只生成小型燃油汽车蓝牌的屏幕示意，不生成新能源汽车、摩托车、警用、使领馆或临时号牌。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('首页车辆');this.switchRow('plate','将此车牌安装在首页车辆示意图上','',COLORS[0],this.prefEnabled('show_custom_license_plate'),(v:boolean)=>this.togglePref('show_custom_license_plate',v));this.action('导出 PNG',COLORS[0],()=>this.run(async()=>{need(!!this.platePreview,'请先生成预览');await exportPlate(this.context());this.status='车牌图片已导出';}));this.note('仅用于界面展示和本项目内测试，不可作为正式号牌制作依据。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }
  @Builder private backupPage(){
    this.back('返回数据迁移');this.title('导入前备份','可以导出备份，或按相同规则再次合并')
    if(!this.backups.length){this.note('暂无导入前备份。首次导入时会自动创建。')}
    ForEach(this.backups,(path:string)=>{Column({space:12}){Text(timeText(Number(path.substring(path.lastIndexOf('/')+1).split('-')[2])/1000)).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(16).fontColor(INK).width('100%');this.note('合并备份不会清除之后新增的记录。');this.action('导出备份',COLORS[3],()=>this.exportPackage(true,path));this.action('合并此备份',COLORS[0],()=>this.run(async()=>{this.incoming=await decodeArchive(readFile(path),repository.archive);this.preferIncoming=false;if(this.updatePreview()){this.goBack();this.status='校验通过，请核对合并预览';}}))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})})
  }
  @Builder private appUpdatePage(){
    this.back();this.title('App 更新','版本信息与下载安装');this.hero('update','BRZ Garage','当前版本 v0.4.0','鸿蒙原生版',COLORS[3]);Column({space:12}){this.sectionTitle('应用更新');Text('当前使用本机授权的私有调试签名。新版本使用同签名安装包覆盖安装，保留本机数据。').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).lineHeight(21);this.action('查看发布版本',COLORS[3],()=>this.openLink('https://github.com/sisi4376/BRZ-Garage/releases'));this.note('Android APK 不能更新此鸿蒙应用。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }
  @Builder private backgroundPage(){
    this.back();this.title('系统级 BLE 唤醒','自动连接与后台状态');Column({space:12}){this.switchRow('autostart','自动连接','连接期间申请蓝牙长时任务',COLORS[0],this.automaticEnabled,(v:boolean)=>this.automaticToggle(v));this.readRow('连接状态',automatic.status);this.note('鸿蒙使用蓝牙长时任务保持连接。被系统结束或手机重启后，需要打开 App 恢复。');this.note('此处是鸿蒙系统能力差异，无法直接使用 Android 的伴生设备唤醒服务。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }
  @Builder private feedbackPage(){this.back();this.title('问题反馈','报告异常、提出建议并附带脱敏诊断信息');Column({space:12}){Text('BRZ Garage 鸿蒙 v0.4.0\n行程 '+this.rows('trips').length+' 条\n'+this.status).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).width('100%').copyOption(CopyOptions.LocalDevice);this.action('复制反馈模板',COLORS[3],()=>this.run(async()=>{await pasteboard.getSystemPasteboard().setData(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN,'BRZ Garage 鸿蒙 v0.4.0\n问题描述：\n复现步骤：\n预期表现：\n实际表现：\n已保存行程：'+this.rows('trips').length));this.status='反馈模板已复制';}));this.action('打开问题反馈',COLORS[3],()=>this.openLink('https://github.com/sisi4376/BRZ-Garage/issues'));this.note('可长按复制诊断信息。请勿在公开问题中上传完整车辆数据包或个人信息。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})}
  @Builder private legalPage(){
    if(this.consent){this.back()}
    this.title('使用须知与版权','请先阅读功能边界、数据权限与开源声明')
    ForEach(['安全与使用边界','数据与权限','版权与开源许可','非官方声明','确认说明'],(title:string,i:number)=>{Column(){this.sectionTitle(title);Text(NOTICES[i]).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).lineHeight(21).width('100%').margin({top:10})}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})})
    Column({space:10}){this.action('项目源代码',COLORS[3],()=>this.openLink('https://github.com/sisi4376/BRZ-Garage'));this.action('开源许可证',COLORS[3],()=>this.openLink('https://github.com/sisi4376/BRZ-Garage/blob/main/LICENSE'));
    if(!this.consent){this.switchRow('legacy','我已阅读安全与数据说明','',COLORS[0],this.safetyAccepted,(v:boolean)=>this.safetyAccepted=v);this.switchRow('legacy','我已阅读版权与许可说明','',COLORS[0],this.copyrightAccepted,(v:boolean)=>this.copyrightAccepted=v);this.action('同意并继续',COLORS[0],()=>this.run(async()=>{need(this.safetyAccepted&&this.copyrightAccepted,'请分别确认安全数据说明与版权许可说明');await repository.agree();this.refresh();}),true);this.action('不同意并退出',MUTED,()=>this.context().terminateSelf())}
    else{this.action('撤回确认并停止使用',COLORS[1],()=>this.getUIContext().showAlertDialog({title:'撤回确认？',message:'停止自动连接与同步；本机已有数据会保留。',primaryButton:{value:'取消',action:()=>{}},secondaryButton:{value:'撤回',action:()=>this.run(async()=>{await repository.revoke();await partnerWake.disable();this.safetyAccepted=false;this.copyrightAccepted=false;this.refresh();this.navigate('');})}}))}
    }.width('100%').margin({top:16})
  }
  @Builder private legacyPage(){
    this.title('设置','连接、显示与仪表功能')
    Column({space:12}){this.sectionTitle('设置页样式');this.note('当前使用经典设置页。车辆、行程、记账和全部设置数据不会因切换界面而改变。');this.action('切换到新版分组设置页',COLORS[0],()=>this.run(async()=>{await repository.setGroupedSettings(true);this.refresh();this.navigate('',3);setTimeout(():void=>this.pageScroller.scrollTo({xOffset:0,yOffset:0}),50);}))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('数据迁移');this.action('导出完整数据包 / 导入并合并',COLORS[3],()=>this.openRoute('transfer'))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('我的车辆');Text(this.name()).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(18).fontColor(INK).width('100%');Text(this.model()).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(MUTED).width('100%');this.action('车辆设置',COLORS[0],()=>this.openRoute('vehicle'));this.note('在车辆设置中修改首页名称、车型和自动加油识别阈值。');this.action('自定义车牌',COLORS[0],()=>this.openRoute('plate'));this.note('生成的蓝牌可安装在首页车辆示意图。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('里程估算');Text((mileage(this.snapshot,this.gauge())/1000).toFixed(1)+' km').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(18).fontColor(INK).width('100%');this.switchRow('mileage','在仪表设备信息页和 App 首页显示里程','',COLORS[3],this.prefEnabled('odometer_display_'+this.gauge(),true),(v:boolean)=>this.togglePref('odometer_display_'+this.gauge(),v));this.action('手动校准里程',COLORS[3],()=>this.setting('里程校准',(mileage(this.snapshot,this.gauge())/1000).toFixed(1)));this.note('校准前为已记录行程的累计估算；显示开关和校准命令在正常数据同步空闲时发送。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('首页行程');this.switchRow('display','使用新版首页界面','',COLORS[0],this.refinedHome,(v:boolean)=>this.togglePref('refined_home_ui',v));this.switchRow('fuel','显示上次加油以来','',COLORS[1],this.prefEnabled('show_since_refuel_'+this.gauge(),false),(v:boolean)=>this.togglePref('show_since_refuel_'+this.gauge(),v));this.switchRow('trip','显示自定义行程','',COLORS[2],this.prefEnabled('show_custom_trip_'+this.gauge(),false),(v:boolean)=>this.togglePref('show_custom_trip_'+this.gauge(),v));Row({space:8}){Column(){this.action('修改名称',COLORS[2],()=>this.setting('custom_trip_name',String(pref(this.snapshot,'custom_trip_name_'+this.gauge(),''))))}.layoutWeight(1);Column(){this.action('重置并归档',COLORS[2],()=>this.resetCustomTrip())}.layoutWeight(1)}.width('100%');this.action('查看自定义行程与历史',COLORS[2],()=>this.openRoute('custom'));this.note('重置前会把当前自定义区间和名称保存到手机历史；名称最多 8 个字符，两个开关仅控制首页显示。')}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('我的仪表');Text(this.bound||'尚未绑定').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(18).fontColor(INK).width('100%');this.action(this.bound?'更换 / 重新绑定':'搜索并绑定仪表',COLORS[1],()=>this.openRoute('binding'))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('App 更新');Text('当前版本 v0.4.0').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(INK).width('100%');this.action('版本检查与安装',COLORS[3],()=>this.openRoute('appUpdate'))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('仪表固件更新');this.note('版本检查、安全 OTA 与历史版本回滚');this.action('仪表固件更新',COLORS[1],()=>this.openRoute('firmware'))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('仪表设置');this.note('连接仪表后读取当前参数。目前可调整日间亮度，其余仪表参数保持冻结；车型与自动加油识别阈值位于车辆设置。');this.action('查看仪表设置',COLORS[1],()=>this.openRoute('gauge'))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column({space:10}){this.sectionTitle('开机自启与自动连接');this.switchRow('autostart','自动连接','连接时使用鸿蒙蓝牙长时任务',COLORS[0],this.automaticEnabled,(v:boolean)=>this.automaticToggle(v));this.note('鸿蒙被系统结束或手机重启后，需要打开 App 恢复连接。');this.action('查看后台运行状态',COLORS[0],()=>this.openRoute('background'))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('问题反馈');this.action('问题反馈',COLORS[3],()=>this.openRoute('feedback'))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
    Column(){this.sectionTitle('使用须知与版权');this.action('查看使用须知与版权',COLORS[3],()=>this.openRoute('legal'))}.padding({left:20,right:20,top:18,bottom:18}).width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:14})
  }

  @Builder private firmwarePage(){
    this.back();this.title('仪表固件更新','版本检查、安全 OTA 与历史版本回滚');this.hero('firmware','SkyGauge',this.gaugeText||'等待读取固件版本',automatic.running?'● 仪表已连接':'○ 仪表未连接',COLORS[1])
    Column({ space: 12 }) {
      this.sectionTitle('仪表固件升级与回滚')
      Text('停车并保持仪表供电。选择固件后校验文件，通过蓝牙开启热点，再到系统 Wi-Fi 连接仪表热点。上传前会再次核对硬件。').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).lineHeight(22)
      ForEach(this.firmwareEntries, (entry: CatalogEntry) => {
        this.action(entry.label,COLORS[1],()=>this.run(async()=>{this.firmwareText=await firmware.select(this.context(),entry);}))
      }, (entry: CatalogEntry) => entry.id)
      Text(this.firmwareText).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).lineHeight(22).width('100%')
      this.action('开启仪表更新热点',COLORS[1],()=>this.run(async () => {
        this.otaReady = true; this.firmwareText = await firmware.prepare(this.context(), this.bound, (text: string) => this.status = text);
      }),false,!!this.bound&&firmware.selected!==null)
      this.action('已连接热点，恢复会话',COLORS[1],()=>this.run(async()=>{this.otaReady=true;this.firmwareText=await firmware.recover();}),false,firmware.selected!==null)
      this.action('确认上传所选固件',COLORS[1],()=>this.getUIContext().showAlertDialog({
        title: '确认刷写仪表', message: '请确认已停车、供电稳定，并已连接仪表更新热点。所选固件：' + this.firmwareText,
        primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '开始上传', action: () => this.run(async () => {
          await firmware.upload((text: string) => this.status = text); this.firmwareText = this.status;
        }) }
      }),true,this.otaReady)
      this.action('退出升级模式',COLORS[3],()=>this.run(async()=>{await firmware.cancel();this.otaReady=false;this.status='已退出升级模式';}))
    }.padding(20).width('100%').backgroundColor('#FFFFFF').borderRadius(22).margin({ top: 16 })

  }

  @Builder private inputField(key:string,label:string,numeric:boolean=false){
    Text(label).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).fontWeight(FontWeight.Medium).width('100%').margin({top:12,bottom:6})
    TextInput({text:this.fieldValue(key),placeholder:label}).type(numeric?InputType.NUMBER_DECIMAL:InputType.Normal).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').placeholderFont({size:15,family:'GarageSans'}).placeholderColor('#616B79').fontSize(15).fontColor(INK).height(52).backgroundColor('#F7F8FB').borderRadius(13).border({width:1,color:'#DADFE7'}).padding({left:14,right:14,top:11,bottom:11}).onChange((v:string)=>this.setField(key,v))
  }
  private pickTimeField(key:string,datePart:boolean):void {
    const selected=new Date(this.fieldValue(key));
    if(datePart)this.getUIContext().showDatePickerDialog({selected,onDateAccept:(d:Date)=>{selected.setFullYear(d.getFullYear(),d.getMonth(),d.getDate());this.setField(key,this.localDateTime(selected));}});
    else this.getUIContext().showTimePickerDialog({selected,useMilitaryTime:true,onAccept:(v:TimePickerResult)=>{selected.setHours(v.hour??0,v.minute??0,0,0);this.setField(key,this.localDateTime(selected));}});
  }
  private localDateTime(d:Date):string{return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().substring(0,19);}
  @Builder private dateTimeField(key:string,label:string){
    Text(label).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(INK).fontWeight(FontWeight.Medium).width('100%').margin({top:16})
    Row({space:10}){Column(){this.action(this.fieldValue(key).substring(0,10),COLORS[0],()=>this.pickTimeField(key,true))}.layoutWeight(1);Column(){this.action(this.fieldValue(key).substring(11,16),COLORS[0],()=>this.pickTimeField(key,false))}.layoutWeight(1)}.width('100%')
  }
  @Builder private editorForm(){
    if(this.editKind==='fuel'||this.editKind==='expenses'){
      Text('日期').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%').margin({top:10,bottom:4});this.action(this.fieldValue('date'),INK,()=>this.pickDate())
    }
    if(this.editKind==='fuel'){
      this.inputField('odometer','车辆里程（km）',true);this.inputField('litres','加油量（L，可暂不填写）',true);this.inputField('cost','花费（元，可暂不填写）',true)
      Row({space:8}){Checkbox({name:'notFull',group:'fuel'}).select(this.fieldValue('full')==='0').selectedColor(COLORS[0]).onChange((v:boolean)=>this.setField('full',v?'0':'1'));Text('未满（本次只记录，等下次加满后再计算区间油耗）').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).layoutWeight(1)}.width('100%').margin({top:10})
    }else if(this.editKind==='expenses'){
      if(this.fieldValue('category')==='1'){
        this.inputField('odometer','当时车辆里程（km；用于推荐项目和里程提醒）',true)
        Text('记录类型（列表标题）').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%').margin({top:12,bottom:6})
        Select([{value:'A保'},{value:'B保'},{value:'轮胎更换'},{value:'其他维修'}]).selected(Math.max(0,Number(this.fieldValue('maintenance')||1)-1)).value(['A保','B保','轮胎更换','其他维修'][Math.max(0,Number(this.fieldValue('maintenance')||1)-1)]).font({size:15,family:'GarageSans'}).optionFont({size:15,family:'GarageSans'}).fontColor(INK).optionFontColor(INK).width('100%').onSelect((i:number)=>this.setField('maintenance',String(i+1)))
        Text('保养项目（可多选）').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%').margin({top:12})
        ForEach(SERVICES,(service:Service)=>{Row({space:6}){Checkbox({name:service.title,group:'maintenance'}).select(this.selectedServices.includes(service.action+service.title)).selectedColor(COLORS[1]).onChange((v:boolean)=>{const name=service.action+service.title;this.selectedServices=v?this.selectedServices.concat(name):this.selectedServices.filter((n:string)=>n!==name);this.setField('title',this.selectedServices.join('、'));});Text(service.action+' · '+service.title).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(13).fontColor(INK).layoutWeight(1)}.width('100%')})
        this.inputField('title','项目名称（可补充其他项目）')
      }else{
        Text('花费分类').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(MUTED).width('100%').margin({top:12,bottom:6})
        Select(DAILY.map((n:string):SelectOption=>({value:n}))).selected(dailyKind(this.fieldValue('title'))).value(DAILY[dailyKind(this.fieldValue('title'))]).font({size:15,family:'GarageSans'}).optionFont({size:15,family:'GarageSans'}).fontColor(INK).optionFontColor(INK).width('100%').onSelect((i:number)=>this.setField('title',DAILY[i]))
      }
      this.inputField('amount','金额（元，可填 0）',true);this.inputField('note',this.fieldValue('category')==='1'?'备注（可选）':'详细（可选）')
    }else{
      if(this.editKind==='trips'&&!this.timeOnly&&this.selected){this.action('OBD 轮询自检',COLORS[0],()=>this.notice(recordDetails(this.selected!)))}
      this.note(this.editKind==='split'?'拆分后两段合计保持原行程的里程、时长与耗油；原记录保存删除标记。':'数据仅在手机端修订，不会写回仪表。')
      if(this.editKind==='split'){
        this.inputField('distance','第一段里程（km）',true);this.inputField('duration','第一段驾驶时长（秒）',true);this.inputField('fuel','第一段消耗燃油（L）',true)
        this.dateTimeField('firstStart','第一段开始');this.dateTimeField('firstEnd','第一段结束');this.dateTimeField('secondStart','第二段开始');this.dateTimeField('secondEnd','第二段结束')
      }else if(this.timeOnly){this.dateTimeField('start','开始');this.dateTimeField('end','结束')}
      else {ForEach(this.editFields.filter((f:Field)=>this.editKind!=='trips'||f.key!=='start'&&f.key!=='end'&&f.key!=='accel'&&f.key!=='decel'),(field:Field)=>{this.inputField(field.key,field.label)})}
    }
  }
  @Builder private editorOverlay(){
    Column(){
      Text(this.editorTitle()).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(20).fontWeight(FontWeight.Medium).fontColor(INK).width('100%').padding({left:22,right:22,top:20,bottom:12})
      Scroll(){Column(){this.editorForm();if(this.editorError){Text(this.editorError).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(readable(COLORS[1])).width('100%').padding(8)}}.width('100%').padding({left:22,right:22,bottom:16})}.width('100%').layoutWeight(1).align(Alignment.Top)
      Row({space:12}){if(this.selected&&(this.editKind==='fuel'||this.editKind==='expenses')){Text('删除').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(readable(COLORS[1])).padding(12).onClick(()=>this.confirmDelete())}Blank();Text('取消').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(MUTED).padding(12).onClick(()=>{this.editKind='';this.editorError='';});Text(this.editKind==='split'?'确定拆分':'保存').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(readable(COLORS[0])).padding(12).enabled(!this.busy).onClick(()=>this.saveEdit())}.width('100%').padding({left:14,right:14,bottom:12})
    }.width('90%').constraintSize({maxWidth:520}).height('80%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1})
  }
  @Builder private settingOverlay(){
    Column({space:14}){Text(this.settingLabel(this.settingsKey)).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(20).fontWeight(FontWeight.Medium).fontColor(INK).width('100%');TextInput({text:this.settingsInput,placeholder:'请输入'}).placeholderFont({size:15,family:'GarageSans'}).placeholderColor('#616B79').fontColor(INK).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(15).height(52).backgroundColor('#F7F8FB').borderRadius(13).onChange((v:string)=>this.settingsInput=v);if(this.editorError){Text(this.editorError).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(readable(COLORS[1]))}Row({space:16}){Blank();Text('取消').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(MUTED).padding(12).onClick(()=>this.settingsKey='');Text('保存').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(14).fontColor(readable(COLORS[0])).padding(12).enabled(!this.busy).onClick(()=>this.saveSetting())}.width('100%')}.width('90%').constraintSize({maxWidth:500}).padding(22).backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1})
  }
  @Builder private content(){
    if(!this.ready){Column({space:12}){LoadingProgress().width(32).height(32);Text('正在读取本机数据').fontFamily('GarageSans').fontSize(14).fontColor(MUTED)}.width('100%').padding(32)}else if(!this.consent){this.legalPage()}
    else if(this.route==='trip'){this.tripDetail()}
    else if(this.route==='custom'||this.route==='refuel'){this.intervalPage(this.route)}
    else if(this.route==='vehicle'){this.vehicleSettings()}
    else if(this.route==='binding'){this.bindingPage()}
    else if(this.route==='gauge'){this.gaugePage()}
    else if(this.route==='transfer'){this.transferPage()}
    else if(this.route==='backups'){this.backupPage()}
    else if(this.route==='plate'){this.platePage()}
    else if(this.route==='appUpdate'){this.appUpdatePage()}
    else if(this.route==='firmware'){this.firmwarePage()}
    else if(this.route==='background'){this.backgroundPage()}
    else if(this.route==='feedback'){this.feedbackPage()}
    else if(this.route==='legal'){this.legalPage()}
    else if(this.route==='legacy'){this.legacyPage()}
    else if(this.tab===0){if(this.refinedHome){this.home()}else{this.classicHome()}}
    else if(this.tab===1){this.history()}
    else if(this.tab===2){this.accounting()}
    else{if(this.groupedSettings){this.settings()}else{this.legacyPage()}}
  }
  build(){
    Stack({alignContent:Alignment.Center}){
      Column(){
        Scroll(this.pageScroller){Column(){this.content()}.width('100%').constraintSize({maxWidth:760}).padding({left:22,right:22,top:22,bottom:24}).alignItems(HorizontalAlign.Start)}.align(Alignment.Top).width('100%').layoutWeight(1).scrollBar(BarState.Off)
        if(this.busy){Row({space:10}){LoadingProgress().width(18).height(18);Text(this.status).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(MUTED).layoutWeight(1);Text('取消').fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(12).fontColor(readable(COLORS[1])).onClick(()=>{automatic.stop();bluetooth.close();if(this.otaReady)firmware.cancel();})}.width('100%').padding(10).backgroundColor('#FFFFFF')}
        if(!this.route&&this.consent){Row(){ForEach(['车辆','行程','记账','设置'].map((entry:string)=>this.tab+'|'+entry),(name:string,i:number)=>{Column({space:2}){Row(){this.icon(['vehicle','trip','accounting','settings'][i],this.tab===i?[COLORS[0],COLORS[3],COLORS[2],COLORS[1]][i]:MUTED,24)}.width(50).height(30).justifyContent(FlexAlign.Center).backgroundColor(this.tab===i?soften([COLORS[0],COLORS[3],COLORS[2],COLORS[1]][i]):'#00000000').borderRadius(15);Text(name.split('|')[1]).fontFamily('GarageSans, HarmonyOS Sans SC, sans-serif').fontSize(11).fontColor(this.tab===i?readable([COLORS[0],COLORS[3],COLORS[2],COLORS[1]][i]):MUTED).fontWeight(this.tab===i?FontWeight.Medium:FontWeight.Normal)}.layoutWeight(1).height(58).justifyContent(FlexAlign.Center).onClick(()=>{this.selected=null;this.navigate('',i);this.filterLists();})},(name:string)=>name)}.width('100%').padding({left:12,right:12,top:6,bottom:10}).backgroundColor('#FFFFFF')}
      }.width('100%').height('100%').backgroundColor(PAGE)
      if(this.editKind||this.settingsKey){Column().width('100%').height('100%').backgroundColor('#66000000');if(this.editKind){this.editorOverlay()}else{this.settingOverlay()}}
    }.width('100%').height('100%')
  }
}
