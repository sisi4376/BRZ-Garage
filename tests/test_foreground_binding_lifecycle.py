"""Execute production binding gate and service start preamble on the JVM.

Android calls are recorded by host stubs; this cannot emulate Samsung's OS,
but does check every foreground acknowledgement and the pause/resume order.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'android_app/app/src/main/java/com/brz/gauge/trips'
TOOLS = Path(os.environ.get('BRZ_TOOLCHAIN_ROOT', ROOT)) / '.toolchains'


def method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class ForegroundBindingLifecycleTest(unittest.TestCase):
    def test_real_start_preamble_and_binding_gate(self):
        service = (JAVA / 'TripSyncService.kt').read_text(encoding='utf8')
        activity = (JAVA / 'MainActivity.kt').read_text(encoding='utf8')
        start = method(service, 'override fun onStartCommand(').split(
            '        state.prefs.edit().putLong("service_started_at"', 1)[0]
        start = start.replace('override fun', 'fun', 1) + ' events += "work"; return START_STICKY\n}'
        promote = method(service, 'private fun promoteForeground()')
        save = method(activity, 'private fun saveBinding(')
        gate = save.split('        AppCrashDiagnostics.step("binding.save.begin")', 1)[0]
        gate = gate.replace('private fun', 'fun', 1) + ' bindings++\n}'
        fixture = HARNESS.replace('PRODUCTION_START', start).replace('PRODUCTION_PROMOTE', promote).replace('PRODUCTION_GATE', gate)
        jars = [TOOLS / 'local-maven/org/jetbrains/kotlin' / artifact / version / f'{artifact}-{version}.jar'
                for artifact, version in [('kotlin-compiler-embeddable', '2.2.10'), ('kotlin-stdlib', '2.2.10'),
                                         ('kotlin-script-runtime', '2.2.10'), ('kotlin-reflect', '1.6.10'),
                                         ('kotlin-daemon-embeddable', '2.2.10')]]
        jars += [TOOLS / 'local-maven/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar',
                 TOOLS / 'gradle9.5/lib/annotations-24.0.1.jar']
        java = TOOLS / 'jdk17/bin/java.exe'
        if not java.exists() or not all(p.exists() for p in jars):
            self.skipTest('Local Kotlin compiler and JDK required')
        cp = os.pathsep.join(map(str, jars))
        def run(args):
            p = subprocess.run(list(map(str, args)), capture_output=True, text=True, encoding='utf8', errors='replace', timeout=90)
            self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
            return p.stdout
        with tempfile.TemporaryDirectory(prefix='fgs-binding-') as temp:
            scratch = Path(temp)
            (scratch / 'Entry.kt').write_text(fixture, encoding='utf8')
            run([java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                 '-classpath', cp, '-d', scratch / 'classes', JAVA / 'GaugeBindingLifecycle.kt', JAVA / 'GaugeBluetoothAddress.kt', scratch / 'Entry.kt'])
            output = run([java, '-cp', str(scratch / 'classes') + os.pathsep + cp, 'com.brz.gauge.trips.EntryKt'])
            self.assertIn('PASS foreground reuse, reject, pause, background wake, manual resume, timeout, OTA, duplicate binding', output)

    def test_pause_keeps_service_alive_and_guards_all_connection_entries(self):
        activity = (JAVA / 'MainActivity.kt').read_text(encoding='utf8')
        service = (JAVA / 'TripSyncService.kt').read_text(encoding='utf8')
        save = method(activity, 'private fun saveBinding(')
        self.assertNotIn('stopService(', save)
        self.assertIn('TripSyncService.pauseForBond(this)', save)
        self.assertLess(save.index('bindingCompletion.claim()'), save.index('state.address = address'))
        self.assertIn('bindingCompletion.begin()', method(activity, 'private fun bindGauge('))
        create = method(service, 'override fun onCreate()')
        self.assertLess(create.index('createNotificationChannels'), create.index('promoteForeground()'))
        self.assertLess(create.index('promoteForeground()'), create.index('database = TripDatabase'))
        for name in ('startScan', 'startAutoConnect', 'connectFromGaugeSignal'):
            body = method(service, 'private fun ' + name + '()')
            self.assertIn('if (bondPause.isPaused(SystemClock.elapsedRealtime())) return', body)
        expired = service.split('private val bondPauseExpired = Runnable {', 1)[1].split('\n    }', 1)[0]
        self.assertIn('bondPause.resume()', expired)
        self.assertIn('!destroyed && !otaSessionActive', expired)
        self.assertIn('beginReconnect()', expired)


HARNESS = '''package com.brz.gauge.trips
object SystemClock { var now=100L; fun elapsedRealtime()=now }
object AppCrashDiagnostics { fun step(label: String) {} }
class Intent(val action: String?)
class State(var automatic: Boolean=true, var address: String="gauge")
class FakeHandler {
 val pending=mutableSetOf<Runnable>()
 fun removeCallbacks(r: Runnable) { pending.remove(r) }
 fun postDelayed(r: Runnable, delay: Long) { check(delay==60_000L); pending.add(r) }
}
internal class Fixture {
 val events=mutableListOf<String>()
 val state=State()
 var permitted=true
 var connected=false
 var otaSessionActive=false
 val bondPause=BondConnectionPause()
 val handler=FakeHandler()
 val retry=Runnable {}
 val bondPauseExpired=Runnable { bondPause.resume() }
 val bindingCompletion=BindingCompletion()
 var bindings=0
 fun hasPermissions(any: Any)=permitted
 fun notification(s: String)=s
 fun startForeground(id: Int, text: String) { events += "foreground" }
 fun stopSelf(id: Int) { events += "stop:$id" }
 fun stopScan() { events += "stop-scan" }
 fun closeConnection() { events += "close-gatt" }
 fun publish(s: String) { events += "waiting-bond" }
 PRODUCTION_PROMOTE
 PRODUCTION_START
 PRODUCTION_GATE
 companion object {
  const val NOTIFICATION=86
  const val START_NOT_STICKY=2
  const val START_STICKY=1
  const val ACTION_PAUSE_FOR_BOND="pause"
  const val ACTION_SYNC_NOW="manual"
 }
}
fun main() {
 val f=Fixture()
 // The same instance must acknowledge ALL start requests, including an early reject.
 repeat(3) { f.onStartCommand(Intent("auto"), 0, it+1) }
 check(f.events==listOf("foreground","work","foreground","work","foreground","work"))
 f.events.clear(); f.state.automatic=false
 check(f.onStartCommand(null,0,4)==Fixture.START_NOT_STICKY)
 check(f.events==listOf("foreground","stop:4"))
 f.events.clear(); f.state.automatic=true
 f.onStartCommand(Intent("pause"),0,5)
 check(f.events==listOf("foreground","stop-scan","close-gatt","waiting-bond"))
 check(f.handler.pending.contains(f.bondPauseExpired))
 check(f.bondPause.isPaused(60_099L))
 // Already-queued watchdog and gauge-presence starts must not reconnect during bonding.
 f.events.clear()
 for (action in listOf("auto","gauge-signal",null)) f.onStartCommand(Intent(action),0,6)
 check(f.events==listOf("foreground","foreground","foreground"))
 f.events.clear(); f.onStartCommand(Intent("manual"),0,7)
 check(f.events==listOf("foreground","work"))
 check(!f.bondPause.isPaused(SystemClock.now))
 check(!f.handler.pending.contains(f.bondPauseExpired))
 // Vendor stacks that omit the bond result cannot leave the service paused forever.
 f.onStartCommand(Intent("pause"),0,8)
 SystemClock.now+=60_000L
 f.events.clear(); f.onStartCommand(Intent("auto"),0,9)
 check(f.events==listOf("foreground","work"))
 // A pending firmware upload must survive a late binding-related command.
 f.otaSessionActive=true; f.events.clear()
 f.onStartCommand(Intent("pause"),0,10)
 check(f.events==listOf("foreground"))
 check(!f.bondPause.isPaused(SystemClock.now))
 // Exact callback + activity-result sequence from the remote Samsung report.
 f.saveBinding("gauge"); f.saveBinding("gauge")
 check(f.bindings==1)
 f.bindingCompletion.begin(); f.saveBinding("gauge"); f.saveBinding("gauge")
 check(f.bindings==2) // Explicit rebind of the same gauge must still work.
 println("PASS foreground reuse, reject, pause, background wake, manual resume, timeout, OTA, duplicate binding")
}
'''

if __name__ == '__main__':
    unittest.main(verbosity=2)
