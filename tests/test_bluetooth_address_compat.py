"""Run actual address storage and binding/wake gates with Android's strict MAC rule.

The companion API's lowercase MAC is intentionally distinct from BluetoothDevice's
uppercase address. No phone, controller or Samsung framework is simulated here.
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
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class BluetoothAddressCompatTest(unittest.TestCase):
    def test_companion_mac_and_legacy_binding_use_valid_bluetooth_identity(self):
        state = (JAVA / 'AppState.kt').read_text(encoding='utf8')
        prop = state.split('    var address: String', 1)[1].split('    /** Vehicle selected', 1)[0]
        activity = (JAVA / 'MainActivity.kt').read_text(encoding='utf8')
        save = method(activity, 'private fun saveBinding(').split(
            '        AppCrashDiagnostics.step("binding.check-association")', 1)[0]
        save = save.replace('private fun', 'fun', 1) + ' observedBindingAddress = address\n}'
        wake = method((JAVA / 'BackgroundBleWake.kt').read_text(encoding='utf8'), 'fun register(')
        wake = wake.split('        val intent = pendingIntent(app)', 1)[0] + ' state.prefs.putString("wake_scan_status", "registered"); return true\n}'
        bond = (JAVA / 'GaugeBondManager.kt').read_text(encoding='utf8').split(
            '/** Continues the main-app wake registration', 1)[0]
        fixture = HARNESS.replace('PRODUCTION_ADDRESS', prop).replace('PRODUCTION_SAVE', save).replace('PRODUCTION_WAKE', wake)
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
        with tempfile.TemporaryDirectory(prefix='bluetooth-address-') as temp:
            scratch = Path(temp)
            for name, source in STUBS.items():
                (scratch / name).write_text(source, encoding='utf8')
            (scratch / 'Entry.kt').write_text(fixture, encoding='utf8')
            (scratch / 'GaugeBondManager.kt').write_text(bond, encoding='utf8')
            run([java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                 '-classpath', cp, '-d', scratch / 'classes', JAVA / 'GaugeBluetoothAddress.kt',
                 JAVA / 'GaugeBindingLifecycle.kt', *scratch.glob('*.kt')])
            output = run([java, '-cp', str(scratch / 'classes') + os.pathsep + cp, 'com.brz.gauge.trips.EntryKt'])
            self.assertIn('PASS existing lowercase binding, new companion result, paired status, wake gate, no repeat createBond, locale, invalid MAC', output)


STUBS = {
'Manifest.kt': '''package android
object Manifest { object permission { const val BLUETOOTH_CONNECT="connect" } }
''',
'Build.kt': '''package android.os
object Build { object VERSION { const val SDK_INT=36 } }
''',
'Context.kt': '''package android.content
open class Context {
 val applicationContext get()=this
 fun checkSelfPermission(permission: String)=0
 fun <T> getSystemService(type: Class<T>): T = type.cast(android.bluetooth.BluetoothManager())
}
abstract class BroadcastReceiver
class Intent
''',
'PackageManager.kt': '''package android.content.pm
object PackageManager { const val PERMISSION_GRANTED=0 }
''',
'Bluetooth.kt': '''package android.bluetooth
class BluetoothDevice {
 var bondState=BOND_BONDED
 var bondRequests=0
 fun createBond(): Boolean { bondRequests++; return true }
 fun createBond(transport: Int)=createBond()
 companion object { const val BOND_NONE=10;const val BOND_BONDING=11;const val BOND_BONDED=12;const val TRANSPORT_LE=2 }
}
class BluetoothAdapter {
 val isEnabled=true
 val bluetoothLeScanner=Any()
 fun getRemoteDevice(address: String): BluetoothDevice {
  check(checkBluetoothAddress(address)) { "getRemoteDevice rejected a noncanonical address" }
  return device
 }
 companion object {
  val device=BluetoothDevice()
  fun checkBluetoothAddress(address: String)=Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(address)
 }
}
class BluetoothManager { val adapter=BluetoothAdapter() }
''',
}

HARNESS = '''package com.brz.gauge.trips
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

class Prefs {
 val values=mutableMapOf<String,String>()
 fun edit()=this
 fun putString(key:String,value:String)=apply { values[key]=value }
 fun putLong(key:String,value:Long)=this
 fun getString(key:String,default:String?):String? = values[key] ?: default
 fun apply() {}
}
class AppState(context: Context) {
 val prefs=stored
 var automatic=true
 var address: String PRODUCTION_ADDRESS
 companion object { val stored=Prefs() }
}
object AppCrashDiagnostics { fun step(label: String) {} }
object TripSyncService { fun hasPermissions(context: Context)=true }
object BackgroundBleWake {
 var cancelled=0
 fun cancel(context: Context) { cancelled++; AppState(context).prefs.putString("wake_scan_status","disabled") }
 fun record(state: AppState, text: String) { state.prefs.putString("wake_scan_status",text) }
 PRODUCTION_WAKE
}
internal class ActivityFixture: Context() {
 val state=AppState(this)
 val bindingCompletion=BindingCompletion()
 var observedBindingAddress=""
 fun stopPresence() {}
 PRODUCTION_SAVE
}
fun main() {
 val context=Context()
 val lower="aa:bb:cc:dd:ee:ff"
 val upper="AA:BB:CC:DD:EE:FF"
 // The strict platform validator reproduces why a CDM string fails before normalization.
 check(!BluetoothAdapter.checkBluetoothAddress(lower))
 AppState.stored.putString("address", lower)
 val existing=AppState(context)
 check(existing.address==upper) { "Existing lowercase binding was not recovered" }
 check(GaugeBondManager.isBonded(context)) { "Existing system bond was not recognized" }
 check(GaugeBondManager.status(context)=="已完成系统 LE 配对")
 check(GaugeBondManager.ensureBond(context)==GaugeBondManager.SetupResult.READY)
 check(BluetoothAdapter.device.bondRequests==0) { "Already paired gauge was paired again" }
 check(BackgroundBleWake.register(context)) { "Wake registration was disabled for a valid binding" }
 check(BackgroundBleWake.cancelled==0)
 // Both the stored address and the local callback argument must be normalized.
 val activity=ActivityFixture()
 activity.saveBinding(lower)
 check(activity.observedBindingAddress==upper) { "Companion callback still uses lowercase after saving" }
 check(AppState.stored.getString("address",null)==upper)
 activity.saveBinding(lower)
 check(BluetoothAdapter.device.bondRequests==0)
 val locale=java.util.Locale.getDefault()
 try {
  java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
  existing.address="  aA:bB:cC:dD:eE:fF  "
  check(existing.address==upper)
 } finally { java.util.Locale.setDefault(locale) }
 existing.address=""
 check(existing.address.isEmpty())
 check(!BackgroundBleWake.register(context))
 existing.address="aa:bb:cc:dd:ee:zz"
 check(!BluetoothAdapter.checkBluetoothAddress(existing.address))
 check(!BackgroundBleWake.register(context))
 println("PASS existing lowercase binding, new companion result, paired status, wake gate, no repeat createBond, locale, invalid MAC")
}
'''

if __name__ == '__main__': unittest.main(verbosity=2)
