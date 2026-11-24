"""Execute Android production protocol/storage against desktop-generated oracles."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from obd_gauge_poll import GaugePoll
from obd_recorder import ReplayData, ReplayElm
from test_foreground_binding_lifecycle import method


class AndroidStudioTest(unittest.TestCase):
    def test_desktop_android_protocol_and_storage_parity(self):
        java = ROOT / '.toolchains/jdk17/bin/java.exe'
        maven = ROOT / '.toolchains/local-maven'
        artifacts = ['org/jetbrains/kotlin/kotlin-compiler-embeddable/2.2.10',
                     'org/jetbrains/kotlin/kotlin-stdlib/2.2.10',
                     'org/jetbrains/kotlin/kotlin-script-runtime/2.2.10',
                     'org/jetbrains/kotlin/kotlin-reflect/1.6.10',
                     'org/jetbrains/kotlin/kotlin-daemon-embeddable/2.2.10',
                     'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0']
        jars = [str(p) for a in artifacts for p in (maven / a).rglob('*.jar')]
        jars += [str(ROOT / '.toolchains/gradle9.5/lib/annotations-24.0.1.jar'),
                 str(ROOT / '.toolchains/obd-studio-test/json-20250517.jar')]
        cp = os.pathsep.join(jars)
        sources = ROOT / 'android_app/obd_studio/src/main/java/com/brz/obdstudio'
        with tempfile.TemporaryDirectory(prefix='obd-studio-') as temp:
            folder = Path(temp)
            for mode in ('zd8-mt', 'zd8-at', 'zc6-mt', 'zc6-at'):
                poll = GaugePoll(mode)
                rows = []
                for n in range(1000):
                    now = 10 + n * .17
                    commands = poll.commands(now)
                    rows.append(f'{now}\t' + ','.join(commands))
                    for command in commands:
                        if command == '0140':
                            poll.observe(command, b'41 40 FE DC A0 51\r>')
                        elif command == '012F':
                            poll.observe(command, b'41 2F 6D\r>')
                (folder / f'{mode}.tsv').write_text('\n'.join(rows), encoding='utf-8')
            rows = [dict(kind='session', t=0, schema=1, headers=False, profile='all')]
            for t, command, text, phase in [(1,'0100','41 00 BE 3F A8 13\r>','discovery'),
                    (10,'010C','41 0C 1F 40\r>','poll'),(11,'010D','41 0D 32\r>','poll'),
                    (12,'010C','NO DATA\r>','poll'),(13,'015E','41 5E 00 78\r>','poll')]:
                rows.append(dict(kind='reply', t=t, command=command, header='7E0',
                                 phase=phase, hex=text.encode().hex(), complete=True, started=t-.1))
            rows.append(dict(kind='end',t=14))
            fixture = folder / 'synthetic.jsonl'
            fixture.write_text(''.join(json.dumps(r)+'\n' for r in rows), encoding='utf-8')
            data = ReplayData(fixture)
            commands = [(0,'ATZ'),(0,'ATE0'),(0,'0100'),(0,'010C'),(.5,'010D'),(1,'010D'),
                        (2,'010C'),(3,'015E'),(3,'ATS0'),(3,'010D'),(3,'ATH1'),(3,'010D'),
                        (3,'ATH0'),(3,'ATSH7DF'),(3,'010D'),(3,'ATSH7E0'),(3,'ATL1'),
                        (3,'010D'),(5,'010C'),(9,'010C1'),(10,'ATZ'),(10,'010D')]
            for name, speed, loop in [('normal',1,False),('fast-loop',2,True)]:
                clock = [0]
                elm = ReplayElm(data, speed, loop, clock=lambda:clock[0])
                lines = []
                for stamp, command in commands:
                    clock[0] = stamp
                    lines.append(f'{stamp}\t{command}\t{elm.command(command).hex()}')
                (folder / f'{name}.tsv').write_text('\n'.join(lines), encoding='utf-8')
            output = folder / 'classes'
            output.mkdir()
            service = (sources/'StudioService.kt').read_text(encoding='utf-8')
            fixture = (ROOT/'tests/studio_quick_capture_fixture.kt').read_text(encoding='utf-8')
            fixture = fixture.replace('PRODUCTION_RECORD',method(service,'private fun record(')).replace('PRODUCTION_SAVE',method(service,'private fun save('))
            (folder/'QuickFixture.kt').write_text(fixture,encoding='utf-8')
            activity = (sources/'StudioActivity.kt').read_text(encoding='utf-8')
            selection = (ROOT/'tests/studio_device_selection_fixture.kt').read_text(encoding='utf-8')
            selection = selection.replace('PRODUCTION_CHOOSE_OBD',method(activity,'private fun chooseObd(')).replace('PRODUCTION_BEGIN_QUICK',method(activity,'private fun beginQuickRecord('))
            (folder/'DeviceSelectionFixture.kt').write_text(selection,encoding='utf-8')
            result = subprocess.run([str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib','-no-reflect','-classpath',cp,'-d',str(output),str(sources/'Protocol.kt'),
                str(sources/'Storage.kt'),str(sources/'CapturePlan.kt'),str(folder/'QuickFixture.kt'),str(folder/'DeviceSelectionFixture.kt'),
                str(ROOT/'tests/studio_dialog_fixture.kt'),str(ROOT/'tests/obd_studio_android_host_test.kt')],capture_output=True,text=True,encoding='utf-8',errors='replace')
            self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
            result = subprocess.run([str(java),'-cp',str(output)+os.pathsep+cp,
                'com.brz.obdstudio.Obd_studio_android_host_testKt',temp,*([os.environ['STUDIO_TEST_RECORDING']] if os.environ.get('STUDIO_TEST_RECORDING') else [])],capture_output=True,text=True,encoding='utf-8',errors='replace')
            self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
            self.assertIn('cross-platform checks',result.stdout)
            print(result.stdout.strip())
            imported = ReplayData(folder/'android-roundtrip.jsonl')
            self.assertEqual(imported.profile,'zc6-at')
            grouped = ReplayElm(imported, clock=lambda: 0)
            grouped.command('ATE0')
            self.assertEqual(grouped.command('010E'), b'41 0E 7F\r>')
            self.assertIn(b'1: 10 01 F4', grouped.command('010C0D10440E0F'))


if __name__ == '__main__':
    unittest.main()
