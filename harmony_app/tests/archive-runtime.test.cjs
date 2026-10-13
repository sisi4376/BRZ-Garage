const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const ts = require((process.env.DEVECO_HOME || 'C:/Program Files/Huawei/DevEco Studio') + '/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
const root = path.join(__dirname, '../test-output');
const A = require(root + '/core/Archive');

// Model the native bridge: setters copy scalar fields into an internal z_stream;
// inflate writes the shared output buffer but does not mutate the argument object.
// Only getZStream returns the updated counters. Use real DEFLATE for the payload.
function harness() {
  const streams = [];
  const native = {ReturnStatus: {OK: 0, STREAM_END: 1}, CompressFlushMode: {FINISH: 4},
    createZip: async () => {
      const state = {ended: false, reads: 0}; streams.push(state);
      return {
        inflateInit2: async (input, bits) => { assert.equal(bits, -15); state.stream = {...input}; state.input = input; return 0; },
        inflate: async (input, flush) => {
          assert.equal(flush, 4); Object.assign(state.stream, input);
          const s = state.stream;
          const result = zlib.inflateRawSync(Buffer.from(s.nextIn, 0, s.availableIn), {info: true, maxOutputLength: s.availableOut});
          new Uint8Array(s.nextOut).set(result.buffer);
          s.totalIn = result.engine.bytesWritten; s.totalOut = result.buffer.length;
          s.availableIn -= s.totalIn; s.availableOut -= s.totalOut;
          return 1;
        },
        getZStream: async () => { state.reads++; return {...state.stream}; },
        inflateEnd: async () => { state.ended = true; return 0; }
      };
    }};
  const source = fs.readFileSync(path.join(__dirname, '../entry/src/main/ets/platform/ArchiveRuntime.ets'), 'utf8');
  const js = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS}}).outputText;
  const exports = {};
  const requireMock = id => id === '@ohos.zlib' ? {default: native} :
    id === '@kit.CryptoArchitectureKit' ? {cryptoFramework: {createMd: () => {
      const hash = crypto.createHash('sha256');
      return {update: async ({data}) => { hash.update(data); }, digest: async () => ({data: new Uint8Array(hash.digest())})};
    }}} : id.startsWith('../core/') ? require(root + '/core/' + id.split('/').pop()) : {};
  vm.runInNewContext(js, {exports, require: requireMock, Uint8Array, ArrayBuffer, Error, Number});
  return {runtime: new exports.HarmonyArchiveRuntime(), streams};
}

test('Harmony runtime imports Android production ZIP using native stream counters', async () => {
  const h = harness();
  const snapshot = await A.decodeArchive(new Uint8Array(fs.readFileSync(root + '/android.zip')), h.runtime);
  assert.equal(Object.keys(snapshot.tables).length, 9);
  assert.equal(snapshot.tables.expenses[0].maintenance_type, 3);
  assert.equal(snapshot.preferences.vehicle_display_name.value, 'BRZ 测试');
  assert.equal(h.streams.length, 2);
  for (const state of h.streams) { assert.ok(state.reads > 0); assert.ok(state.ended); assert.notEqual(state.input.totalOut, state.stream.totalOut); }
});

test('Harmony raw inflate handles empty, Unicode, highly compressed and multi-buffer data', async () => {
  const h = harness();
  for (const data of [Buffer.alloc(0), Buffer.from('中文记录 🏎'), Buffer.alloc(2 * 1024 * 1024, 65), crypto.randomBytes(180000)]) {
    const result = await h.runtime.inflateRaw(new Uint8Array(zlib.deflateRawSync(data)), data.length);
    assert.deepEqual(Buffer.from(result), data);
  }
  assert.ok(h.streams.every(s => s.ended));
});

test('Harmony runtime rejects false declared sizes without disabling the output bound', async () => {
  const h = harness(), data = Buffer.alloc(100000, 65), packed = zlib.deflateRawSync(data);
  for (const size of [1, data.length - 1, data.length + 1]) await assert.rejects(h.runtime.inflateRaw(new Uint8Array(packed), size));
  assert.ok(h.streams.every(s => s.ended));
});

test('Harmony runtime rejects truncated streams and trailing compressed bytes and frees native state', async () => {
  const h = harness(), data = Buffer.from('完整数据校验 '.repeat(300)), packed = zlib.deflateRawSync(data);
  await assert.rejects(h.runtime.inflateRaw(new Uint8Array(packed.subarray(0, -1)), data.length));
  await assert.rejects(h.runtime.inflateRaw(new Uint8Array(Buffer.concat([packed, Buffer.from([0, 1, 2])])), data.length));
  assert.ok(h.streams.every(s => s.ended));
});
