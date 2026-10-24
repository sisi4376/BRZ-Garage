"""Execute the production local diagnostic recorder without a connected phone."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(os.environ.get('BRZ_TOOLCHAIN_ROOT', ROOT)) / '.toolchains'
STUBS = {
 'Context.kt': '''package android.content
import java.io.File
class Context(val filesDir:File) {
 val applicationContext get()=this
 val packageName="com.brz.gauge.trips"
 fun <T> getSystemService(type:Class<T>):T = type.cast(android.app.ActivityManager())
}
''',
 'ActivityManager.kt': '''package android.app
class ExitInfo { val reason=6; val status=0; val timestamp=1234L }
class ActivityManager {
 fun getHistoricalProcessExitReasons(name:String,pid:Int,max:Int)=listOf(ExitInfo())
}
''',
 'Build.kt': '''package android.os
object Build { const val MANUFACTURER="samsung";const val MODEL="SM-F7660"
 object VERSION { const val SDK_INT=36;const val RELEASE="16" } }
object Process { fun myPid()=1;fun killProcess(pid:Int){error("Must delegate to installed handler")} }
''',
 'BuildConfig.kt': '''package com.brz.gauge.trips
object BuildConfig { const val VERSION_NAME="4.3.12-diagnostic.1";const val VERSION_CODE=153 }
''',
 'Entry.kt': '''package com.brz.gauge.trips
import java.io.File
import android.content.Context
fun main(args:Array<String>) {
 val dir=File(args[0]); val broken=args.getOrNull(1)=="broken"
 if(broken) dir.writeText("not a directory") else dir.mkdirs()
 val context=Context(dir);var delegated=0
 Thread.setDefaultUncaughtExceptionHandler { _,_ -> delegated++ }
 AppCrashDiagnostics.install(context)
 val installed=Thread.getDefaultUncaughtExceptionHandler()
 AppCrashDiagnostics.install(context)
 check(installed===Thread.getDefaultUncaughtExceptionHandler())
 val e=IllegalStateException("private value AA:BB:CC:DD:EE:FF plate-and-trip")
 e.initCause(NoSuchMethodError("private method message"))
 e.stackTrace=arrayOf(StackTraceElement("example.Binding","select","Binding.kt",7))
 for(i in 0..40) AppCrashDiagnostics.step("binding.test-$i")
 installed!!.uncaughtException(Thread.currentThread(),e)
 check(delegated==1)
 if(broken) {
  check(AppCrashDiagnostics.summary(context).contains("未记录到 Java/Kotlin 崩溃"))
  println("PASS: storage failure still delegates normal crash handling")
  return
 }
 val file=File(dir,"last-app-crash.txt");val saved=file.readText()
 check(saved.contains("IllegalStateException") && saved.contains("NoSuchMethodError"))
 check(saved.contains("Binding.kt:7"))
 check(!saved.contains("AA:BB") && !saved.contains("plate-and-trip") && !saved.contains("private method message"))
 check(!saved.contains("binding.test-0") && saved.contains("binding.test-40"))
 check(saved.length<=12000)
 val summary=AppCrashDiagnostics.summary(context)
 check(summary.contains("API 36") && summary.contains("reason=6"))
 check(file.readText()==saved)
 println("PASS: bounded causes, stack frames and binding stages survive reading")
 println("PASS: original handler invoked exactly once, installation is idempotent")
 println("PASS: exception messages and private values are excluded")
 println("PASS: feedback includes own process exit reason without clearing crash")
}
''',
}

class AppCrashDiagnosticsTest(unittest.TestCase):
 def test_production_crash_recorder(self):
  java=TOOLS/'jdk17/bin/java.exe'
  artifacts=['kotlin-compiler-embeddable/2.2.10','kotlin-stdlib/2.2.10',
   'kotlin-script-runtime/2.2.10','kotlin-reflect/1.6.10','kotlin-daemon-embeddable/2.2.10']
  jars=[p for name in artifacts for p in (TOOLS/'local-maven/org/jetbrains/kotlin'/name).glob('*.jar')]
  jars+=list((TOOLS/'local-maven/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0').glob('*.jar'))
  jars+=[TOOLS/'gradle9.5/lib/annotations-24.0.1.jar']
  if not java.is_file() or len(jars)<6:self.skipTest('Bundled Kotlin compiler and JDK required')
  cp=os.pathsep.join(map(str,jars))
  def run(args):
   p=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf8',errors='replace',timeout=90)
   self.assertEqual(p.returncode,0,p.stdout+p.stderr)
   return p.stdout
  with tempfile.TemporaryDirectory(prefix='brz-crash-test-') as temp:
   scratch=Path(temp);compiled=scratch/'classes';compiled.mkdir()
   for name,text in STUBS.items():(scratch/name).write_text(text,encoding='utf8')
   run([java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
    '-classpath',cp,'-d',compiled,*scratch.glob('*.kt'),ROOT/'android_app/app/src/main/java/com/brz/gauge/trips/AppCrashDiagnostics.kt'])
   command=[java,'-cp',str(compiled)+os.pathsep+cp,'com.brz.gauge.trips.EntryKt']
   self.assertEqual(run(command+[scratch/'normal']).count('PASS:'),4)
   self.assertEqual(run(command+[scratch/'broken','broken']).count('PASS:'),1)

if __name__=='__main__':unittest.main(verbosity=2)
