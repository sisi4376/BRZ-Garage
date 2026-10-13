import { RefuelWindow, RefuelMutation, reconcileRefuel, deleteArchived } from '../core/RefuelHistory';
import { CustomRequest, CustomReceipt, newCustomRequest, sameCustomRequest, archiveCustom, applyCustomReceipt } from '../core/CustomTrip';
import { pref, putPref, need } from '../core/Garage';
import { RefuelMeta } from '../core/Gauge';
import { common } from '@kit.AbilityKit';
import { relationalStore } from '@kit.ArkData';
import { Snapshot, Row, Vehicle, DeviceState, emptySnapshot, cloneSnapshot, rowKey } from '../core/Models';
import { validate, merge, GAUGE, archiveGauge, MAX_DATA, toJson } from '../core/Transfer';
import { mergeGaugeTrip } from '../core/Protocol';
import { decodeArchive, encodeArchive, utf8Encode } from '../core/Archive';
import { HarmonyArchiveRuntime, readFile, writeFile, listBackupFiles } from './ArchiveRuntime';

class LocalMeta {
  revision: number = 0;
  refuelWindows: Record<string, RefuelWindow> = {};
  refuelPending: Record<string, RefuelMutation | null> = {};
  customRequests: Record<string, CustomRequest> = {};
  binding: string = '';
  archiveGauge: string = '';
  consent: boolean = false;
  cursors: Record<string, number> = {};
  lastSync: number = 0;
  backup: string = '';
  automatic: boolean = false;
  refinedHome: boolean = true;
  groupedSettings: boolean = true;
  pendingDisplay: boolean | null = null;
  pendingProfile: number | null = null;
  pendingMileage: number | null = null;
  vehicle: Vehicle | null = null;
  vehicleAt: number = 0;
  lastGaugeFuel: number | null = null;
  lastGaugeFuelAt: number = 0;
  lastGaugeFuelSequence: number | null = null;
  refuelLive: RefuelMeta | null = null;
  refuelAt: number = 0;
}
export class Repository {
  private context: common.Context | undefined = undefined;
  private persistedMeta: string = '';
  private database: relationalStore.RdbStore | undefined = undefined;
  private state: Snapshot = emptySnapshot();
  private meta: LocalMeta = new LocalMeta();
  private filesDir: string = '';
  private tail: Promise<void> = Promise.resolve();
  readonly archive: HarmonyArchiveRuntime = new HarmonyArchiveRuntime();
  async open(context: common.Context): Promise<void> {
    if (this.database) return;
    this.context = context;
    this.filesDir = context.filesDir;
    const db = await relationalStore.getRdbStore(context, { name: 'garage.db', securityLevel: relationalStore.SecurityLevel.S1 });
    await db.executeSql('CREATE TABLE IF NOT EXISTS archive_rows (table_name TEXT NOT NULL, row_key TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(table_name,row_key))');
    await db.executeSql('CREATE TABLE IF NOT EXISTS archive_preferences (name TEXT PRIMARY KEY, payload TEXT NOT NULL)');
    await db.executeSql('CREATE TABLE IF NOT EXISTS archive_devices (device_id TEXT PRIMARY KEY, payload TEXT NOT NULL)');
    await db.executeSql('CREATE TABLE IF NOT EXISTS local_state (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)');
    db.beginTransaction();
    try {
    const state = emptySnapshot();
    let offset = 0;
    while (true) {
      const rows = await db.querySql('SELECT table_name,payload FROM archive_rows ORDER BY table_name,row_key LIMIT 32 OFFSET ?', [offset]);
      let count = 0;
      try {
        while (rows.goToNextRow()) {
          const table = rows.getString(0);
          if (!state.tables[table]) throw new Error('Unknown local data table');
          state.tables[table].push(JSON.parse(rows.getString(1)) as Row); count++;
        }
      } finally { rows.close(); }
      offset += count; if (count < 32) break;
    }
    offset = 0;
    while (true) {
      const prefs = await db.querySql('SELECT name,payload FROM archive_preferences ORDER BY name LIMIT 32 OFFSET ?', [offset]);
      let count = 0;
      try { while (prefs.goToNextRow()) { state.preferences[prefs.getString(0)] = JSON.parse(prefs.getString(1)); count++; } }
      finally { prefs.close(); }
      offset += count; if (count < 32) break;
    }
    offset = 0;
    while (true) {
      const devices = await db.querySql('SELECT device_id,payload FROM archive_devices ORDER BY device_id LIMIT 32 OFFSET ?', [offset]);
      let count = 0;
      try { while (devices.goToNextRow()) { state.devices[devices.getString(0)] = JSON.parse(devices.getString(1)) as DeviceState; count++; } }
      finally { devices.close(); }
      offset += count; if (count < 32) break;
    }
    const meta = await db.querySql('SELECT payload FROM local_state WHERE id=1');
    try { if (meta.goToFirstRow()) { this.persistedMeta = meta.getString(0); this.meta = JSON.parse(this.persistedMeta) as LocalMeta; } }
    finally { meta.close(); }
    // Older local_state payloads predate these caches; JSON parsing does not apply class defaults.
    this.meta.refuelWindows = this.meta.refuelWindows || {};
    this.meta.refuelPending = this.meta.refuelPending || {};
    this.meta.customRequests = this.meta.customRequests || {};
    this.meta.vehicle = this.meta.vehicle ?? null;
    this.meta.vehicleAt = this.meta.vehicleAt ?? 0;
    this.meta.lastGaugeFuel = this.meta.lastGaugeFuel ?? null;
    this.meta.lastGaugeFuelAt = this.meta.lastGaugeFuelAt ?? 0;
    this.meta.lastGaugeFuelSequence = this.meta.lastGaugeFuelSequence ?? null;
    this.meta.refuelLive = this.meta.refuelLive ?? null;
    this.meta.refuelAt = this.meta.refuelAt ?? 0;
    state.gauge = this.meta.binding || this.meta.archiveGauge || ''; state.gauge = archiveGauge(state);
    this.meta.archiveGauge = state.gauge;
    this.hydrateMeta(this.meta, state.devices[state.gauge]);
    validate(state); this.state = state;
    db.commit(); this.database = db;
    } catch (error) { db.rollBack(); throw error as Error; }
  }
  snapshot(): Snapshot {
    const value=cloneSnapshot(this.state),gauge=this.meta.binding||this.meta.archiveGauge||value.gauge;
    value.gauge=gauge || archiveGauge(value); value.createdAt=Date.now();
    if(!value.preferences.refined_home_ui && !this.refinedHome())value.preferences.refined_home_ui={type:'boolean',value:false};
    if(!value.preferences.grouped_settings_ui && !this.groupedSettings())value.preferences.grouped_settings_ui={type:'boolean',value:false};
    if(value.gauge)value.devices[value.gauge]=this.portableDevice(value.gauge,value.devices[value.gauge]); return value;
  }
  refuelLive(): RefuelMeta | null { return this.meta.refuelLive || null; }
  cacheRefuelLive(value: RefuelMeta): Promise<void> { return this.serial(async () => { const meta = this.copyMeta(); meta.refuelLive = value;meta.refuelAt=Date.now();await this.saveMeta(meta); }); }
  vehicle(): Vehicle | null { if(!this.meta.vehicle)return null; const value=JSON.parse(JSON.stringify(this.meta.vehicle)) as Vehicle; value.fuelPercent=value.fuelPercent ?? this.meta.lastGaugeFuel ?? null; return value; }
  vehicleAt(): number { return this.meta.vehicleAt || 0; }
  pendingDisplay(): boolean | null { return this.meta.pendingDisplay ?? null; }
  pendingProfile(): number | null { return this.meta.pendingProfile ?? null; }
  pendingMileage(): number | null { return this.meta.pendingMileage ?? null; }
  queueSetting(kind: string, value: number | boolean | null): Promise<void> { return this.serial(async () => {
    const meta=this.copyMeta(); if(kind==='display') meta.pendingDisplay=value as boolean | null;
    else if(kind==='profile') meta.pendingProfile=value as number | null; else if(kind==='mileage') meta.pendingMileage=value as number | null;
    await this.saveMeta(meta);
  }); }
  groupedSettings(): boolean { return this.meta.groupedSettings !== false; }
  setGroupedSettings(value: boolean): Promise<void> { return this.edit((s:Snapshot)=>{s.preferences.grouped_settings_ui={type:'boolean',value};}); }
  refinedHome(): boolean { return this.meta.refinedHome !== false; }
  setRefinedHome(value: boolean): Promise<void> { return this.edit((s:Snapshot)=>{s.preferences.refined_home_ui={type:'boolean',value};}); }
  automatic(): boolean { return this.meta.automatic === true; }
  private async metaText(db: relationalStore.RdbStore): Promise<string> {
    const row = await db.querySql('SELECT payload FROM local_state WHERE id=1');
    try { return row.goToFirstRow() ? row.getString(0) : ''; } finally { row.close(); }
  }
  private async assertCurrent(db: relationalStore.RdbStore): Promise<void> {
    need(await this.metaText(db) === this.persistedMeta, '后台同步已更新数据，请刷新后重试');
  }
  private async saveMeta(meta: LocalMeta): Promise<void> {
    if (!this.database) throw new Error('数据库未就绪');
    const db = this.database; meta.revision = (this.meta.revision || 0) + 1;
    db.beginTransaction();
    try { await this.assertCurrent(db); await db.executeSql('INSERT OR REPLACE INTO local_state(id,payload) VALUES(1,?)', [JSON.stringify(meta)]); db.commit(); }
    catch (error) { db.rollBack(); throw error as Error; }
    this.meta = meta; this.persistedMeta = JSON.stringify(meta);
  }
  private async refreshExternal(): Promise<void> {
    if (!this.database || !this.context || await this.metaText(this.database) === this.persistedMeta) return;
    const fresh = new Repository(); await fresh.open(this.context);
    this.database = fresh.database; this.meta = fresh.meta; this.state = fresh.state; this.persistedMeta = fresh.persistedMeta;
  }
  reload(): Promise<void> { return this.serial(async () => {}); }
  setAutomatic(enabled: boolean): Promise<void> { return this.serial(async () => { const meta = this.copyMeta(); meta.automatic = enabled; await this.saveMeta(meta); }); }
  cacheDevice(gauge:string,values:DeviceState):Promise<void>{return this.edit((s:Snapshot)=>{
    if(!GAUGE.test(gauge))throw new Error('仪表标识无效');
    const device:DeviceState=s.devices[gauge]?JSON.parse(JSON.stringify(s.devices[gauge])) as DeviceState:this.blankDevice();
    Object.keys(values).forEach((key:string)=>device[key]=values[key]);
    s.devices[gauge]=device;
  });}
  cacheVehicle(vehicle: Vehicle): Promise<void> { return this.serial(async () => { const meta = this.copyMeta(); meta.vehicle = vehicle; if(vehicle.fuelPercent!==null){meta.lastGaugeFuel=vehicle.fuelPercent;meta.lastGaugeFuelAt=Date.now();meta.lastGaugeFuelSequence=vehicle.fuelSequence??null;} meta.vehicleAt = Date.now(); await this.saveMeta(meta); }); }
  edit(action: (snapshot: Snapshot) => void): Promise<void> {
    return this.serial(async () => { const snapshot = this.snapshot(); action(snapshot); snapshot.createdAt = Date.now(); await this.save(snapshot, this.copyMeta()); });
  }
  unbind(): Promise<void> { return this.serial(async () => { const meta = this.copyMeta(); meta.binding = ''; meta.pendingDisplay=null;meta.pendingProfile=null;meta.pendingMileage=null; meta.automatic = false; meta.vehicle = null; meta.lastGaugeFuel=null;meta.lastGaugeFuelAt=0;meta.lastGaugeFuelSequence=null; meta.vehicleAt = 0; meta.refuelLive = null;meta.refuelAt=0; await this.save(this.snapshot(), meta); }); }
  refuelRows(gauge: string): Row[] { return this.snapshot().tables.refuel.filter((r: Row) => r.device_id === gauge); }
  pendingRefuel(gauge: string): RefuelMutation | null { const v = this.meta.refuelPending[gauge]; return v ? JSON.parse(JSON.stringify(v)) as RefuelMutation : null; }
  queueRefuel(gauge: string, mutation: RefuelMutation): Promise<void> { return this.serial(async () => {
    const meta = this.copyMeta(), existing = meta.refuelPending[gauge];
    need(!existing || existing.id === mutation.id && existing.discard === mutation.discard, '另一个加油节点操作等待同步');
    if (existing && mutation.issued === false) return;
    need(this.refuelRows(gauge).some((r: Row) => r.interval_id === mutation.id), '节点已变化，请刷新后重试');
    meta.refuelPending[gauge] = mutation; await this.saveMeta(meta);
  }); }
  clearRefuel(gauge: string, id: number): Promise<void> { return this.serial(async () => {
    const meta = this.copyMeta(); if (meta.refuelPending[gauge]?.id !== id) return;
    meta.refuelPending[gauge] = null; await this.saveMeta(meta);
  }); }
  deleteArchivedRefuel(gauge: string, id: number, discard: boolean): Promise<void> { return this.serial(async () => {
    const s = this.snapshot(), meta = this.copyMeta(), window = meta.refuelWindows[gauge];
    need(!!window && (window.meta.count === 0 || id < window.meta.oldest), '请先核对仪表保留范围');
    s.tables.refuel = s.tables.refuel.filter((r: Row) => r.device_id !== gauge).concat(deleteArchived(this.refuelRows(gauge), id, discard));
    meta.refuelPending[gauge] = null; await this.save(s, meta);
  }); }
  commitRefuel(gauge: string, rows: Row[], window: RefuelWindow): Promise<void> { return this.serial(async () => {
    const s = this.snapshot(), meta = this.copyMeta(), pending = meta.refuelPending[gauge];
    const confirmed = pending && pending.issued !== false && pending.revision !== window.meta.revision && !rows.some((r: Row) => r.interval_id === pending.id) ? pending : null;
    const result = reconcileRefuel(gauge, this.refuelRows(gauge), meta.refuelWindows[gauge], window, confirmed);
    s.tables.refuel = s.tables.refuel.filter((r: Row) => r.device_id !== gauge).concat(result);
    putPref(s, 'refuel_history_revision_' + gauge, window.meta.revision);
    meta.refuelWindows[gauge] = window;
    if (confirmed) meta.refuelPending[gauge] = null;
    if (gauge === (meta.binding || meta.archiveGauge)) { meta.refuelLive = window.meta; meta.refuelAt = Date.now(); }
    await this.save(s, meta);
  }); }
  customGeneration(gauge: string): string { return this.meta.customRequests[gauge]?.generation || ''; }
  customStatus(gauge: string): string { return this.meta.customRequests[gauge]?.status || 'pending'; }
  customRequest(gauge: string): CustomRequest | null { const r = this.meta.customRequests[gauge]; return r ? JSON.parse(JSON.stringify(r)) as CustomRequest : null; }
  ensureCustom(gauge: string, v: Vehicle): Promise<void> { return this.serial(async () => {
    if (this.meta.customRequests[gauge]) return;
    const s = this.snapshot(), meta = this.copyMeta();
    if (!s.preferences['custom_base_distance_' + gauge]) archiveCustom(s, gauge, v, String(pref(s, 'custom_trip_name_' + gauge, '')), '', this.meta.vehicleAt);
    meta.customRequests[gauge] = newCustomRequest(s, gauge); await this.save(s, meta);
  }); }
  archiveCustom(gauge: string, expected: string, v: Vehicle, name: string, id: string, at: number): Promise<void> { return this.serial(async () => {
    need(this.customGeneration(gauge) === expected, '该区间已经变化，请查看最新记录；不会重复归档');
    const s = this.snapshot(), meta = this.copyMeta();
    archiveCustom(s, gauge, v, name, id, at); meta.customRequests[gauge] = newCustomRequest(s, gauge);
    await this.save(s, meta);
  }); }
  updateCustom(gauge: string, request: CustomRequest, receipt: CustomReceipt | null, status: string): Promise<void> { return this.serial(async () => {
    if (!sameCustomRequest(this.meta.customRequests[gauge], request)) return;
    const s = this.snapshot(), meta = this.copyMeta(), current = meta.customRequests[gauge];
    if (receipt) { applyCustomReceipt(s, gauge, request, receipt); current.values = receipt.values.slice(); }
    current.pending = receipt === null; current.status = status; await this.save(s, meta);
  }); }

  binding(): string { return this.meta.binding; }
  consent(): boolean { return this.meta.consent; }
  lastSync(): number { return this.meta.lastSync; }
  backupPaths(): string[] { return listBackupFiles(this.filesDir); }
  backupPath(): string { return this.meta.backup; }
  cursor(gauge: string): number { return this.meta.cursors[gauge] || 0; }
  private async save(snapshot: Snapshot, meta: LocalMeta): Promise<void> {
    if (!this.database) throw new Error('数据库未就绪');
    validate(snapshot);
    // Keep portable preferences and the active UI in the same transaction.
    meta.refinedHome=snapshot.preferences.refined_home_ui ? snapshot.preferences.refined_home_ui.value !== false : meta.refinedHome;
    meta.groupedSettings=snapshot.preferences.grouped_settings_ui ? snapshot.preferences.grouped_settings_ui.value !== false : meta.groupedSettings;
    if (utf8Encode(toJson(snapshot)).length > MAX_DATA) throw new Error('Combined data exceeds the 32 MiB archive limit');
    meta.archiveGauge = snapshot.gauge;
    const db = this.database; meta.revision = (this.meta.revision || 0) + 1;
    db.beginTransaction();
    try {
      await this.assertCurrent(db);
      await db.executeSql('DELETE FROM archive_rows');
      await db.executeSql('DELETE FROM archive_preferences');
      await db.executeSql('DELETE FROM archive_devices');
      for (const table of Object.keys(snapshot.tables)) {
        const rows = snapshot.tables[table];
        for (let offset = 0; offset < rows.length; offset += 200) {
          const values: relationalStore.ValuesBucket[] = rows.slice(offset, offset + 200).map((row: Row): relationalStore.ValuesBucket => {
            return { table_name: table, row_key: rowKey(table, row), payload: JSON.stringify(row) };
          });
          if (await db.batchInsert('archive_rows', values) !== values.length) throw new Error('Incomplete record write');
        }
      }
      const names = Object.keys(snapshot.preferences);
      for (let offset = 0; offset < names.length; offset += 200) {
        const values: relationalStore.ValuesBucket[] = names.slice(offset, offset + 200).map((key: string): relationalStore.ValuesBucket => {
          return { name: key, payload: JSON.stringify(snapshot.preferences[key]) };
        });
        if (await db.batchInsert('archive_preferences', values) !== values.length) throw new Error('Incomplete preference write');
      }
      const deviceIds=Object.keys(snapshot.devices);
      for(let offset=0;offset<deviceIds.length;offset+=200){const values:relationalStore.ValuesBucket[]=deviceIds.slice(offset,offset+200).map((deviceId:string):relationalStore.ValuesBucket=>({device_id:deviceId,payload:JSON.stringify(snapshot.devices[deviceId])}));
        if(await db.batchInsert('archive_devices',values)!==values.length)throw new Error('Incomplete device-state write');}
      await db.executeSql('INSERT OR REPLACE INTO local_state(id,payload) VALUES(1,?)', [JSON.stringify(meta)]);
      db.commit();
    } catch (error) { db.rollBack(); throw error as Error; }
    this.state = snapshot; this.meta = meta; this.persistedMeta = JSON.stringify(meta);
  }
  private serial(action: () => Promise<void>): Promise<void> {
    const next = this.tail.then(async () => { await this.refreshExternal(); await action(); });
    this.tail = next.catch(() => {});
    return next;
  }
  private copyMeta(): LocalMeta { return JSON.parse(JSON.stringify(this.meta)) as LocalMeta; }
  revoke():Promise<void>{return this.serial(async()=>{const meta=this.copyMeta();meta.consent=false;meta.automatic=false;await this.saveMeta(meta);});}
  agree(): Promise<void> {
    return this.serial(async () => { const meta = this.copyMeta(); meta.consent = true; await this.save(this.snapshot(), meta); });
  }
  bind(address: string): Promise<void> {
    return this.serial(async () => {
      if (!GAUGE.test(address)) throw new Error('请输入有效的仪表蓝牙地址');
      const meta = this.copyMeta(); if (meta.binding !== address) { meta.pendingDisplay=null;meta.pendingProfile=null;meta.pendingMileage=null; meta.vehicle = null; meta.lastGaugeFuel=null;meta.lastGaugeFuelAt=0;meta.lastGaugeFuelSequence=null; meta.vehicleAt = 0; meta.refuelLive = null;meta.refuelAt=0; this.hydrateMeta(meta,this.state.devices[address]); } meta.binding = address;
      const snapshot = this.snapshot(); snapshot.gauge = address; snapshot.createdAt = Date.now();
      await this.save(snapshot, meta);
    });
  }
  commitTrips(gauge: string, rows: Row[], cursor: number): Promise<void> {
    return this.serial(async () => {
      const snapshot = this.snapshot();
      const tombs = new Set<string>(snapshot.tables['deleted_trips'].map((row: Row) => rowKey('deleted_trips', row)));
      const indices = new Map<string, number>();
      snapshot.tables['trips'].forEach((row: Row, i: number) => indices.set(rowKey('trips', row), i));
      rows.forEach((row: Row) => {
        const key = rowKey('trips', row);
        if (tombs.has(key)) return;
        const index = indices.get(key);
        if (index === undefined) { indices.set(key, snapshot.tables['trips'].length); snapshot.tables['trips'].push(row); }
        else snapshot.tables['trips'][index] = mergeGaugeTrip(snapshot.tables['trips'][index], row);
      });
      snapshot.createdAt = Date.now();
      const meta = this.copyMeta(); meta.cursors[gauge] = cursor; meta.lastSync = Date.now();
      await this.save(snapshot, meta);
    });
  }
  importData(incoming: Snapshot, preferIncoming: boolean): Promise<void> {
    return this.serial(async () => {
      const report = merge(this.snapshot(), incoming, preferIncoming);
      const backup = await encodeArchive(this.snapshot(), this.archive);
      const path = this.filesDir + '/before-import-' + Date.now() + '-' + Math.random().toString(16).substring(2) + '.zip';
      writeFile(path, backup);
      await decodeArchive(readFile(path), this.archive);
      const meta = this.copyMeta(); meta.backup = path; meta.cursors = {};
      meta.refuelWindows = {}; meta.refuelPending = {}; meta.customRequests = {};
      Object.keys(report.snapshot.preferences).filter((key: string) => key.startsWith('custom_base_distance_')).forEach((key: string) => {
        const gauge = key.substring('custom_base_distance_'.length); meta.customRequests[gauge] = newCustomRequest(report.snapshot, gauge);
      });
      this.hydrateMeta(meta,report.snapshot.devices[meta.binding||report.snapshot.gauge]);
      report.snapshot.gauge = meta.binding || report.snapshot.gauge;
      await this.save(report.snapshot, meta);
    });
  }
  private blankDevice(): DeviceState { return {
    vehicle_data:null,vehicle_epoch:0,vehicle_uptime:0,vehicle_fuel_percent:null,vehicle_current_distance:0,vehicle_current_duration:0,
    vehicle_current_fuel:0,vehicle_total_distance:0,vehicle_total_duration:0,vehicle_total_fuel:0,vehicle_rpm:null,
    vehicle_speed:null,vehicle_average:null,vehicle_max_speed:null,vehicle_max_rpm:null,vehicle_max_accel:null,vehicle_max_decel:null,
    vehicle_current_start:0,vehicle_fuel_sequence:null,vehicle_at:0,last_fuel_percent:null,last_fuel_at:0,
    last_fuel_sequence:null,refuel_start:0,refuel_duration:0,refuel_distance:0,refuel_fuel:0,refuel_protocol:1,
    refuel_meta_at:0,gauge_settings_data:null,gauge_settings_at:0,firmware_version:null,firmware_build:null,
    firmware_project:null,firmware_board:null,firmware_variant:null,firmware_lcd:null,firmware_screen_w:null,
    firmware_screen_h:null,firmware_bpp:null,firmware_flash_mb:null,firmware_ota_slots:null,firmware_at:0,time_at:0,time_verified:false };
  }
  private portableDevice(_gauge:string,old:DeviceState|undefined):DeviceState{
    const d:DeviceState=old?JSON.parse(JSON.stringify(old)) as DeviceState:this.blankDevice(),v=this.meta.vehicle,r=this.meta.refuelLive;
    if(v){d.vehicle_epoch=v.epoch;d.vehicle_uptime=v.uptime;d.vehicle_fuel_percent=v.fuelPercent;d.vehicle_current_distance=v.currentDistance;
      d.vehicle_current_duration=v.currentDuration;d.vehicle_current_fuel=v.currentFuel;d.vehicle_total_distance=v.totalDistance;
      d.vehicle_total_duration=v.totalDuration;d.vehicle_total_fuel=v.totalFuel;d.vehicle_rpm=v.rpm;d.vehicle_speed=v.speed;
      d.vehicle_average=v.average;d.vehicle_current_start=v.currentStart;
      d.vehicle_max_speed=v.maxSpeed??null;d.vehicle_max_rpm=v.maxRpm??null;
      d.vehicle_max_accel=v.maxAccel??null;d.vehicle_max_decel=v.maxDecel??null;d.vehicle_fuel_sequence=v.fuelSequence??null;if(this.meta.vehicleAt>Number(d.vehicle_at||0))d.vehicle_data=null;d.vehicle_at=this.meta.vehicleAt;}
    if(this.meta.lastGaugeFuel!==null){d.last_fuel_percent=this.meta.lastGaugeFuel;d.last_fuel_at=this.meta.lastGaugeFuelAt;d.last_fuel_sequence=this.meta.lastGaugeFuelSequence??null;}
    if(r){d.refuel_start=r.start;d.refuel_duration=r.duration;d.refuel_distance=r.distance;d.refuel_fuel=r.fuel;d.refuel_protocol=r.version;d.refuel_meta_at=this.meta.refuelAt;}
    return d;
  }
  private hydrateMeta(meta:LocalMeta,d:DeviceState|undefined):void{if(!d)return;const at=Number(d.vehicle_at||0);if(at>0 && at>=meta.vehicleAt){meta.vehicle={epoch:Number(d.vehicle_epoch),uptime:Number(d.vehicle_uptime),fuelPercent:d.vehicle_fuel_percent===null?null:Number(d.vehicle_fuel_percent),currentDistance:Number(d.vehicle_current_distance),currentDuration:Number(d.vehicle_current_duration),currentFuel:Number(d.vehicle_current_fuel),totalDistance:Number(d.vehicle_total_distance),totalDuration:Number(d.vehicle_total_duration),totalFuel:Number(d.vehicle_total_fuel),rpm:d.vehicle_rpm===null?null:Number(d.vehicle_rpm),speed:d.vehicle_speed===null?null:Number(d.vehicle_speed),average:d.vehicle_average===null?null:Number(d.vehicle_average),currentStart:Number(d.vehicle_current_start),
      maxSpeed:d.vehicle_max_speed===null?null:Number(d.vehicle_max_speed),maxRpm:d.vehicle_max_rpm===null?null:Number(d.vehicle_max_rpm),
      maxAccel:d.vehicle_max_accel===null?null:Number(d.vehicle_max_accel),maxDecel:d.vehicle_max_decel===null?null:Number(d.vehicle_max_decel),
      fuelSequence:d.vehicle_fuel_sequence===null?null:Number(d.vehicle_fuel_sequence)};meta.vehicleAt=at;}
    if(Number(d.last_fuel_at||0)>=meta.lastGaugeFuelAt){meta.lastGaugeFuel=d.last_fuel_percent===null?null:Number(d.last_fuel_percent);meta.lastGaugeFuelAt=Number(d.last_fuel_at||0);meta.lastGaugeFuelSequence=d.last_fuel_sequence===null?null:Number(d.last_fuel_sequence);}
    if(Number(d.refuel_meta_at||0)>0 && Number(d.refuel_meta_at)>=meta.refuelAt){meta.refuelLive={version:Number(d.refuel_protocol),count:0,oldest:0,newest:0,revision:0,start:Number(d.refuel_start),duration:Number(d.refuel_duration),distance:Number(d.refuel_distance),fuel:Number(d.refuel_fuel)};meta.refuelAt=Number(d.refuel_meta_at);}}
}
export const repository = new Repository();
