// Run with Node and the DevEco TypeScript module path as the first argument.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(process.argv[2]);
const source = fs.readFileSync(path.join(__dirname,
  '../harmony_ble_bridge/entry/src/main/ets/bridge/PartnerWakeManager.ets'), 'utf8');
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText;
const advContext = { exports: {} };
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname,
  '../harmony_ble_bridge/entry/src/main/ets/bridge/GaugeAdvertisement.ets'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS }
}).outputText, advContext);
const matches = (bytes) => advContext.exports.hasGaugeService(Uint8Array.from(bytes).buffer);
assert.equal(matches([2, 1, 6, 7, 3, 0xf8, 0x1f, 0xf9, 0x1f, 0xfa, 0x1f]), true);
assert.equal(matches([5, 3, 0xfa, 0x1f, 0xfb, 0x1f]), true);
assert.equal(matches([3, 0xff, 0xfa, 0x1f]), false);
assert.equal(matches([5, 3, 0xfa]), false);
assert.equal(matches([]), false);
assert.equal(matches([17, 7, 0xfb, 0x34, 0x9b, 0x5f, 0x80, 0, 0, 0x80,
  0, 0x10, 0, 0, 0xfa, 0x1f, 0, 0]), true);

function setup({ paired = [], connected = [], names = {}, bindError, permission = 0 } = {}) {
  const state = { scans: 0, stops: 0, registrations: [], timers: new Map(), listener: null };
  let timerId = 0;
  const ble = {
    getConnectedBLEDevices: () => connected,
    on: (_, listener) => { state.listener = listener; },
    off: () => { state.listener = null; },
    startBLEScan: (filters) => {
      // HarmonyOS requires null for unfiltered scanning; [] fails with 401.
      assert.equal(filters, null);
      state.scans++;
    },
    stopBLEScan: () => { state.stops++; },
    ScanDuty: { SCAN_MODE_LOW_LATENCY: 2 }, MatchMode: { MATCH_MODE_AGGRESSIVE: 1 }
  };
  const kits = {
    './GaugeAdvertisement': advContext.exports,
    '@kit.AbilityKit': { abilityAccessCtrl: { createAtManager: () => ({
      requestPermissionsFromUser: async () => ({ authResults: [permission] })
    }) } },
    '@kit.ConnectivityKit': { ble, connection: {
      getPairedDevices: () => paired, getRemoteDeviceName: (id) => names[id] || ''
    }, common: { BluetoothAddressType: { VIRTUAL: 1 } }, partnerAgent: {
      isPartnerAgentSupported: () => true, getBoundDevices: () => [],
      bindDevice: async (device) => {
        state.registrations.push(device.bluetoothAddress.address);
        if (bindError) throw bindError;
      }
    } },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } }
  };
  const context = { exports: {}, require: (name) => {
    assert.ok(kits[name], `Unexpected runtime dependency ${name}`);
    return kits[name];
  }, setTimeout: (fn) => { state.timers.set(++timerId, fn); return timerId; },
  clearTimeout: (id) => state.timers.delete(id) };
  vm.runInNewContext(code, context);
  const manager = context.exports.partnerWakeManager;
  manager.attachContext({});
  manager.setListener((snapshot) => { state.snapshot = snapshot; });
  return { manager, state };
}

(async () => {
  const paired = setup({ paired: ['A'], names: { A: 'SkyGarageRC' } });
  assert.equal(await paired.manager.enableFromUser(), true);
  assert.deepEqual(paired.state.registrations, ['A']);
  assert.equal(paired.state.scans, 0);
  assert.equal(paired.state.snapshot.registered, true);

  const connected = setup({ connected: ['A'], names: { A: 'SkyGauge-12AF' } });
  await connected.manager.enableFromUser();
  assert.deepEqual(connected.state.registrations, ['A']);
  assert.equal(connected.state.scans, 0);

  const duplicate = setup({ paired: ['A'], connected: ['A'], names: { A: 'SkyGarageRC' } });
  await duplicate.manager.enableFromUser();
  assert.equal(duplicate.state.registrations.length, 1);

  const ambiguous = setup({ paired: ['A', 'B'], names: { A: 'SkyGarageRC', B: 'SkyGauge-1111' } });
  assert.equal(await ambiguous.manager.enableFromUser(), false);
  assert.equal(ambiguous.state.registrations.length, 0);

  const scan = setup({ paired: ['H'], names: { H: 'Headphones' } });
  await scan.manager.enableFromUser();
  assert.equal(scan.state.scans, 1);
  assert.equal(scan.state.registrations.length, 0);
  await scan.manager.refresh();
  assert.match(scan.state.snapshot.status, /30/);
  [...scan.state.timers.values()][0]();
  assert.equal(scan.state.snapshot.scanning, false);
  assert.match(scan.state.snapshot.error, /没有返回任何蓝牙广播/);
  assert.equal(scan.state.listener, null);

  const failure = setup({ connected: ['A'], names: { A: 'SkyGarageRC' },
    bindError: { code: 34900003, message: 'not paired' } });
  assert.equal(await failure.manager.enableFromUser(), false);
  assert.equal(failure.state.snapshot.registered, false);
  assert.match(failure.state.snapshot.error, /配对/);

  const denied = setup({ permission: -1 });
  assert.equal(await denied.manager.enableFromUser(), false);
  assert.equal(denied.state.scans, 0);
  assert.equal(denied.state.registrations.length, 0);
  const broadcast = setup();
  await broadcast.manager.enableFromUser();
  broadcast.state.listener([{deviceId: 'A', connectable: false,
    data: Uint8Array.from([3, 3, 0xfa, 0x1f]).buffer}]);
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(broadcast.state.registrations, ['A']);
  assert.equal(broadcast.state.snapshot.registered, true);
  const unrelated = setup();
  await unrelated.manager.enableFromUser();
  unrelated.state.listener([{ deviceId: 'H', data: Uint8Array.from([3, 0xff, 0xfa, 0x1f]).buffer }]);
  assert.equal(unrelated.state.registrations.length, 0);
  [...unrelated.state.timers.values()][0]();
  assert.match(unrelated.state.snapshot.error, /收到其他蓝牙广播/);
  console.log('PASS: 9 registration scenarios and 6 advertisement cases; no GATT connection API used');
})().catch((error) => { console.error(error); process.exitCode = 1; });
