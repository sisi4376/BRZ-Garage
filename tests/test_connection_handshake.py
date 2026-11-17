"""Run production connection callbacks/routing on the JVM with recorded Android I/O."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_foreground_binding_lifecycle import ROOT, JAVA, TOOLS, method


class ConnectionHandshakeTest(unittest.TestCase):
    def test_real_callbacks_and_duplicate_wake_routing(self):
        source = Path(os.environ.get('BRZ_CONNECTION_SOURCE', JAVA / 'TripSyncService.kt')).read_text(encoding='utf8')
        routing = source.split('        val gaugeSignalWake =', 1)[1].split('        if (gaugeSignalWake && connected', 1)[0]
        routing = 'val gaugeSignalWake =' + routing
        methods = '\n'.join(method(source, signature).replace('override fun', 'fun', 1) for signature in (
            'override fun onConnectionStateChange(', 'override fun onServicesDiscovered(',
            'private fun nextCycle()', 'private fun read(uuid:', 'private fun write(uuid:', 'private fun syncClock()'))
        reset = method(source, 'private fun closeConnection()').split('        val old = gatt', 1)[1].split('        completeOperation()', 1)[0]
        fixture = HARNESS.replace('PRODUCTION_ROUTING', routing).replace('PRODUCTION_METHODS', methods).replace('PRODUCTION_RESET', 'val old = gatt' + reset)
        jars = [TOOLS / 'local-maven/org/jetbrains/kotlin' / artifact / version / f'{artifact}-{version}.jar'
                for artifact, version in [('kotlin-compiler-embeddable', '2.2.10'), ('kotlin-stdlib', '2.2.10'),
                                         ('kotlin-script-runtime', '2.2.10'), ('kotlin-reflect', '1.6.10'),
                                         ('kotlin-daemon-embeddable', '2.2.10')]]
        jars += [TOOLS / 'local-maven/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar',
                 TOOLS / 'gradle9.5/lib/annotations-24.0.1.jar']
        java = TOOLS / 'jdk17/bin/java.exe'
        cp = os.pathsep.join(map(str, jars))
        def run(args):
            result = subprocess.run(list(map(str, args)), capture_output=True, text=True, encoding='utf8', errors='replace', timeout=90)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return result.stdout
        with tempfile.TemporaryDirectory(prefix='gatt-handshake-') as temp:
            scratch = Path(temp)
            (scratch / 'Entry.kt').write_text(fixture, encoding='utf8')
            run([java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                 '-classpath', cp, '-d', scratch / 'classes', scratch / 'Entry.kt'])
            output = run([java, '-cp', str(scratch / 'classes') + os.pathsep + cp, 'EntryKt'])
            self.assertIn('PASS handshake isolation and service readiness', output)


HARNESS = '''
import java.util.UUID
object SystemClock { fun elapsedRealtime() = 1_000L }
object BluetoothProfile { const val STATE_DISCONNECTED=0; const val STATE_CONNECTED=2 }
object TripBleProtocol {
 val INFO_SERVICE=UUID(0,1); val OTA_SERVICE=UUID(0,2); val TIME_SYNC=UUID(0,3)
 val VEHICLE_STATE=UUID(0,4); val TRIP_META=UUID(0,5)
 fun timePacket(epoch: Long)=ByteArray(8)
}
class BluetoothGattService(val clock: Boolean=true) {
 fun getCharacteristic(uuid: UUID): Any? = if (uuid==TripBleProtocol.TIME_SYNC && !clock) null else Any()
}
class BluetoothGatt(val service: BluetoothGattService? = BluetoothGattService()) {
 var discoveries=0; var discoveryAccepted=true
 fun discoverServices(): Boolean { discoveries++; return discoveryAccepted }
 fun getService(uuid: UUID): BluetoothGattService? = service
 companion object { const val GATT_SUCCESS=0 }
}
class Intent(val action: String?)
class State(var address: String="gauge")
class Handler { fun removeCallbacks(r: Runnable) {} }
class Fixture {
 val events=mutableListOf<String>(); val handler=Handler(); val state=State()
 var gatt: BluetoothGatt?=null; var connected=false; var servicesReady=false
 var autoConnectPending=false; var busy=false; var destroyed=false; var legacy=false
 var address="gauge"; var info: BluetoothGattService?=null; var ota: BluetoothGattService?=null
 var otaSessionActive=false; var otaStartCommandAttempted=false; var preparedFirmware: Any?=null
 var retryMs=0L; var lastClockMs=0L; var lastHistoryMs=0L
 var linkGeneration=0L
 var operation: UUID?=null; var operationPayload: ByteArray?=null; var operationRetries=0
 val autoConnectFallback=Runnable {}; val scanFallback=Runnable {}
 val ACTION_GAUGE_SIGNAL="signal"
 fun closeConnection() { events+="close"; PRODUCTION_RESET }
 fun stopScan() { events+="stop-scan" }
 fun beginReconnect() { events+="scan" }
 fun connectFromGaugeSignal() { events+="direct"; gatt=BluetoothGatt(); autoConnectPending=false; busy=true }
 fun acquireConnectionWakeLock() {}
 fun releaseSignalConnectWakeLock() {}
 fun releaseConnectionWakeLock() {}
 fun armConnectTimeout() { events+="arm-timeout" }
 fun publish(s: String) {}
 fun fail(s: String) { events+="fail"; closeConnection() }
 fun failFirmwareUpdate(s: String) { events+="ota-fail" }
 fun recoverOtaWifiAfterBleDrop(s: String) { events+="ota-recover" }
 fun issueOperation() { events+="operation" }
 fun idle() { events+="idle" }
 fun signal(action: String?="signal") { val intent=Intent(action); PRODUCTION_ROUTING }
 PRODUCTION_METHODS
 fun cycle()=nextCycle()
 fun readBeforeReady()=read(TripBleProtocol.TRIP_META)
 fun writeBeforeReady()=write(TripBleProtocol.TIME_SYNC,ByteArray(8))
}
fun main() {
 // Both independent wake providers can report the same gauge many times.
 val direct=Fixture(); direct.connectFromGaugeSignal(); val original=direct.gatt
 direct.events.clear(); repeat(20) { direct.signal() }
 check(direct.gatt===original && direct.events.isEmpty()) { "duplicate wake interrupted direct handshake" }
 // A passive controller auto-connect must still be upgraded by a fresh advertisement.
 val waiting=Fixture(); waiting.gatt=BluetoothGatt(); waiting.autoConnectPending=true
 waiting.signal(); check(waiting.events==listOf("close","direct"))
 val upgraded=waiting.gatt; waiting.events.clear(); waiting.signal()
 check(waiting.gatt===upgraded && waiting.events.isEmpty())
 // Even if auto-connect entered with busy=false, link-up must lock the sync queue.
 val f=Fixture(); val g=BluetoothGatt(); f.gatt=g
 f.onConnectionStateChange(g,0,2)
 check(f.connected && f.busy && !f.servicesReady && g.discoveries==1)
 f.events.clear(); f.signal(); f.cycle(); f.readBeforeReady(); f.writeBeforeReady()
 check(f.events.isEmpty() && f.operation==null) { "GATT operation started before service discovery" }
 f.onServicesDiscovered(g,0)
 check(f.servicesReady && f.operation==TripBleProtocol.TIME_SYNC && f.events==listOf("operation"))
 // Duplicate or obsolete callbacks must not restart clock sync or use an old link.
 f.events.clear(); f.onServicesDiscovered(g,0); f.onServicesDiscovered(BluetoothGatt(),0)
 check(f.events.isEmpty())
 f.operation=null; f.busy=false; f.cycle(); check(f.events==listOf("operation"))
 // A genuinely missing characteristic still fails cleanly and can reconnect.
 val missing=Fixture(); val bad=BluetoothGatt(BluetoothGattService(false)); missing.gatt=bad
 missing.onConnectionStateChange(bad,0,2); missing.events.clear(); missing.onServicesDiscovered(bad,0)
 check(missing.events==listOf("fail","close") && !missing.servicesReady)
 // Firmware recovery continues to receive the original disconnect behavior.
 val ota=Fixture(); val og=BluetoothGatt(); ota.gatt=og; ota.otaSessionActive=true
 ota.otaStartCommandAttempted=true; ota.preparedFirmware=Any()
 ota.onConnectionStateChange(og,0,0); check(ota.events==listOf("ota-recover"))
 val updating=Fixture(); updating.otaSessionActive=true; updating.signal()
 check(updating.events.isEmpty())
 f.closeConnection(); check(!f.servicesReady && !f.autoConnectPending && !f.connected && !f.busy)
 println("PASS handshake isolation and service readiness")
}
'''

if __name__ == '__main__':
    unittest.main(verbosity=2)
