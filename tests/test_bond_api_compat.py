"""Execute the production bond manager with pre-37 and API-37 device classes.

Compile against createBond(int), then remove that overload from the runtime
class, as on an older Android public API. An incorrect SDK gate must fail with
NoSuchMethodError rather than being hidden by a source-text assertion.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(os.environ.get('BRZ_TOOLCHAIN_ROOT', ROOT)) / '.toolchains'

DEVICE = '''package android.bluetooth;
public class BluetoothDevice {
 public static final int BOND_NONE=10, BOND_BONDING=11, BOND_BONDED=12, TRANSPORT_LE=2;
 public int legacyCalls=0, transportCalls=0;
 public int getBondState() { return BOND_NONE; }
 public String getAddress() { return "AA:BB:CC:DD:EE:FF"; }
 public boolean createBond() { legacyCalls++; return true; }
 MODERN_METHOD
}
'''
STUBS = {
 'Manifest.kt': '''package android
object Manifest { object permission { const val BLUETOOTH_CONNECT="connect" } }
''',
 'Build.kt': '''package android.os
object Build { object VERSION { @JvmField var SDK_INT=36 } }
''',
 'Context.kt': '''package android.content
open class Context {
 val applicationContext: Context get()=this
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
class BluetoothAdapter {
 val isEnabled=true
 fun getRemoteDevice(address: String)=device
 companion object {
  @JvmField val device=BluetoothDevice()
  fun checkBluetoothAddress(address: String)=address=="AA:BB:CC:DD:EE:FF"
 }
}
class BluetoothManager { val adapter=BluetoothAdapter() }
''',
 'AppState.kt': '''package com.brz.gauge.trips
class AppState(context: android.content.Context) {
 val address="AA:BB:CC:DD:EE:FF"
 val prefs=Prefs()
}
class Prefs { fun edit()=this; fun putString(key:String,value:String)=this
 fun putLong(key:String,value:Long)=this; fun apply(){}; fun getString(key:String,value:String)=value }
''',
 'Entry.kt': '''package com.brz.gauge.trips
fun main(args:Array<String>) {
 val sdk=args[0].toInt(); android.os.Build.VERSION.SDK_INT=sdk
 val result=GaugeBondManager.ensureBond(android.content.Context())
 check(result==GaugeBondManager.SetupResult.STARTED)
 val d=android.bluetooth.BluetoothAdapter.device
 check(d.legacyCalls==if(sdk<37) 1 else 0)
 check(d.transportCalls==if(sdk>=37) 1 else 0)
 println("PASS API $sdk: selected available bond API")
}
''',
}

class BondApiCompatTest(unittest.TestCase):
 def test_actual_bond_manager_on_runtime_without_new_overload(self):
  java=TOOLS/'jdk17/bin/java.exe'; javac=TOOLS/'jdk17/bin/javac.exe'
  artifacts=['kotlin-compiler-embeddable/2.2.10','kotlin-stdlib/2.2.10',
   'kotlin-script-runtime/2.2.10','kotlin-reflect/1.6.10','kotlin-daemon-embeddable/2.2.10']
  jars=[p for name in artifacts for p in (TOOLS/'local-maven/org/jetbrains/kotlin'/name).glob('*.jar')]
  jars+=list((TOOLS/'local-maven/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0').glob('*.jar'))
  jars+=[TOOLS/'gradle9.5/lib/annotations-24.0.1.jar']
  if not java.is_file() or len(jars)<6:
   self.skipTest('Bundled Kotlin compiler and JDK required')
  cp=os.pathsep.join(map(str,jars))
  def run(args):
   p=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf8',errors='replace',timeout=90)
   self.assertEqual(p.returncode,0,p.stdout+p.stderr)
   return p.stdout
  with tempfile.TemporaryDirectory(prefix='bond-api-') as temp:
   scratch=Path(temp); modern=scratch/'modern'; legacy=scratch/'legacy'; compiled=scratch/'classes'
   for p in [modern,legacy,compiled]:p.mkdir()
   device=scratch/'BluetoothDevice.java'
   device.write_text(DEVICE.replace('MODERN_METHOD','public boolean createBond(int transport) { transportCalls++; return true; }'),encoding='utf8')
   run([javac,'-d',modern,device])
   device.write_text(DEVICE.replace('MODERN_METHOD',''),encoding='utf8')
   run([javac,'-d',legacy,device])
   for name,body in STUBS.items():(scratch/name).write_text(body,encoding='utf8')
   source=(ROOT/'android_app/app/src/main/java/com/brz/gauge/trips/GaugeBondManager.kt').read_text(encoding='utf8')
   # Receiver callbacks are unrelated to choosing the available pairing overload.
   (scratch/'GaugeBondManager.kt').write_text(source.split('/** Continues the main-app wake registration',1)[0],encoding='utf8')
   run([java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
       '-classpath',str(modern)+os.pathsep+cp,'-d',compiled,*scratch.glob('*.kt')])
   for sdk in [26,31,36,37]:
    with self.subTest(sdk=sdk):
     runtime=modern if sdk>=37 else legacy
     output=run([java,'-cp',os.pathsep.join([str(runtime),str(compiled),cp]),'com.brz.gauge.trips.EntryKt',sdk])
     self.assertIn('PASS API '+str(sdk),output)

if __name__=='__main__':unittest.main(verbosity=2)
