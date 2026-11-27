"""Run production BleCapture with deterministic GATT callbacks, without a phone."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest
import json

ROOT=Path(__file__).resolve().parents[1]

class StudioBleConnectionTest(unittest.TestCase):
    def test_gatt_connection_path(self):
        java=ROOT/'.toolchains/jdk17/bin/java.exe'
        maven=ROOT/'.toolchains/local-maven'
        artifacts=['org/jetbrains/kotlin/kotlin-compiler-embeddable/2.2.10',
            'org/jetbrains/kotlin/kotlin-stdlib/2.2.10','org/jetbrains/kotlin/kotlin-script-runtime/2.2.10',
            'org/jetbrains/kotlin/kotlin-reflect/1.6.10','org/jetbrains/kotlin/kotlin-daemon-embeddable/2.2.10',
            'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0']
        jars=[str(p) for a in artifacts for p in (maven/a).rglob('*.jar')]
        jars += [str(ROOT/'.toolchains/gradle9.5/lib/annotations-24.0.1.jar'),str(ROOT/'.toolchains/obd-studio-test/json-20250517.jar')]
        cp=os.pathsep.join(jars);source=ROOT/'android_app/obd_studio/src/main/java/com/brz/obdstudio'
        with tempfile.TemporaryDirectory(prefix='studio-ble-') as temp:
            folder=Path(temp)
            code=(source/'BleCapture.kt').read_text(encoding='utf-8')
            code=re.sub(r'^import android\..*\n','',code,flags=re.M)
            (folder/'BleCapture.kt').write_text(code,encoding='utf-8')
            result=subprocess.run([str(java),'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(folder/'classes'),str(folder/'BleCapture.kt'),str(source/'BleChannelPolicy.kt'),str(source/'StudioCrashLog.kt'),str(source/'Protocol.kt'),str(source/'Storage.kt'),str(ROOT/'tests/studio_ble_connection_fixture.kt')],capture_output=True,text=True,encoding='utf-8',errors='replace')
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            result=subprocess.run([str(java),'-cp',str(folder/'classes')+os.pathsep+cp,'com.brz.obdstudio.Studio_ble_connection_fixtureKt',temp],capture_output=True,text=True,encoding='utf-8',errors='replace')
            self.assertEqual(result.returncode,0,result.stdout+result.stderr);print(result.stdout.strip())
            rows=[json.loads(s) for s in (folder/'retry.jsonl').read_text(encoding='utf-8').splitlines()]
            self.assertTrue(any(r['kind']=='ble_event' and r.get('stage')=='connect_failed' and r['status']==133 for r in rows))
            rows=[json.loads(s) for s in (folder/'write-error.jsonl').read_text(encoding='utf-8').splitlines()]
            self.assertFalse(next(r for r in rows if r['kind']=='reply')['complete'])
            for name in ('write-fallback','notify-fallback'):
                rows=[json.loads(s) for s in (folder/(name+'.jsonl')).read_text(encoding='utf-8').splitlines()]
                self.assertEqual([r['probe'] for r in rows if r['kind']=='transport'],[1,2])
                self.assertTrue(any(r.get('stage')=='handshake_failed' for r in rows))
            rows=[json.loads(s) for s in (folder/'probe-all.jsonl').read_text(encoding='utf-8').splitlines()]
            probe=[r for r in rows if r['kind']=='reply' and r['phase'] in ('probe','discovery')]
            self.assertEqual([r['command'] for r in probe], [f'01{pid:02X}' for pid in range(256)])
            self.assertTrue(all(r['complete'] for r in probe))
            self.assertEqual(bytes.fromhex(probe[5]['hex']), b'NO DATA\r>')
            result=next(r for r in rows if r['kind']=='pid_probe_end')
            self.assertEqual(result['tested'], 256)
            self.assertEqual(result['valid'], ['010C', '016D', '01FF'])
            self.assertEqual(next(r for r in rows if r['kind']=='poll_plan')['commands'], result['valid'])
            rows=[json.loads(s) for s in (folder/'crashes/last-app-crash.jsonl').read_text(encoding='utf-8').splitlines()]
            self.assertEqual([r['kind'] for r in rows],['session','app_crash','end'])
            self.assertIn('IllegalArgumentException',rows[1]['stack'])

if __name__=='__main__':unittest.main()
