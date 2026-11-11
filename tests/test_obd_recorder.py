import asyncio
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from obd_recorder import (Recording, ElmConnection, ReplayData, ReplayElm,
                          bitmap_pids, select_chars, uuid16, read_request, capture)
from obd_ble_simulator import StaticElm


class RecorderTests(unittest.IsolatedAsyncioTestCase):
    async def test_capture_to_replay_with_fake_ble_adapter(self):
        class Client:
            def __init__(self, *args, **kwargs):
                self.elm = StaticElm()
                self.is_connected = True
                chars = [SimpleNamespace(uuid=uuid16(0xFFF1), properties=['write', 'notify'])]
                self.services = SimpleNamespace(get_service=lambda uuid:
                    SimpleNamespace(characteristics=chars) if uuid == uuid16(0xFFF0) else None)

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

            async def start_notify(self, characteristic, callback):
                self.callback = callback

            async def write_gatt_char(self, characteristic, packet, response):
                for command, reply in self.elm.feed(packet):
                    for offset in range(0, len(reply), 5):
                        self.callback(None, reply[offset:offset + 5])

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'capture.jsonl'
            args = SimpleNamespace(address='FAKE', output=path, profile='all', extra=[],
                                   duration=.02, interval=0, verbose=False)
            with patch.dict(sys.modules, bleak=SimpleNamespace(BleakClient=Client)):
                await capture(args)
            data = ReplayData(path)
            elm = ReplayElm(data)
            elm.command('ATE0')
            self.assertIn(b'41 00', elm.command('0100'))
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(rows[-1]['kind'], 'end')
            self.assertTrue(data.samples)
            for profile in ('zd8', 'zd8-mt', 'zd8-at', 'zc6-mt', 'zc6-at'):
                args.profile = profile
                args.output = Path(folder) / (profile + '.jsonl')
                with patch.dict(sys.modules, bleak=SimpleNamespace(BleakClient=Client)):
                    await capture(args)
                gauge_rows = [json.loads(line) for line in args.output.read_text().splitlines()]
                tx = [r['command'] for r in gauge_rows if r['kind'] == 'tx']
                self.assertIn('ATST0A', tx)
                self.assertEqual(tx.count('0100'), 3)
                self.assertNotIn('0120', tx)
                self.assertEqual(ReplayData(args.output).profile, profile)
                plan = next(r for r in gauge_rows if r['kind'] == 'poll_plan')
                self.assertEqual(plan['automatic'], profile.endswith('-at'))
                self.assertEqual(plan['oil_command'], '2101' if profile.startswith('zc6') else '015C')
                self.assertTrue(any(r['kind'] == 'poll_slot' for r in gauge_rows))

    async def test_fragmented_reply_and_raw_bytes_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'test.jsonl'
            recording = Recording(path, headers=False)
            class Client:
                async def write_gatt_char(self, characteristic, packet, response):
                    self.packet = packet
                    elm.notify(None, b'41 0C 1')
                    elm.notify(None, b'F 40\r>')
            client = Client()
            elm = ElmConnection(client, SimpleNamespace(properties=['write']), recording)
            self.assertEqual(await elm.query('01 0C 1'), b'41 0C 1F 40\r>')
            recording.close()
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(client.packet, b'010C\r')
            self.assertEqual(b''.join(bytes.fromhex(r['hex']) for r in rows if r['kind'] == 'rx'),
                             b'41 0C 1F 40\r>')
            self.assertTrue(next(r for r in rows if r['kind'] == 'reply')['complete'])

    async def test_timeout_saves_partial_without_followup(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'test.jsonl'
            recording = Recording(path, headers=False)
            class Client:
                async def write_gatt_char(self, *args, **kwargs):
                    elm.notify(None, b'41 0C')
            elm = ElmConnection(Client(), SimpleNamespace(properties=['write']), recording, .01)
            with self.assertRaises(TimeoutError):
                await elm.query('010C')
            recording.close()
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            reply = next(r for r in rows if r['kind'] == 'reply')
            self.assertFalse(reply['complete'])
            self.assertEqual(bytes.fromhex(reply['hex']), b'41 0C')
            self.assertEqual(sum(r['kind'] == 'tx' for r in rows), 1)

    def test_support_bitmap_and_service_fallback(self):
        self.assertEqual(bitmap_pids(b'41 00 00 18 00 01\r> ', 0), {12, 13, 32})
        chars = [SimpleNamespace(uuid=uuid16(0xFFF1), properties=['write-without-response', 'notify'])]
        services = SimpleNamespace(get_service=lambda uuid: SimpleNamespace(characteristics=chars)
                                   if uuid == uuid16(0xFFF0) else None)
        self.assertEqual(select_chars(services), (chars[0], chars[0]))

    def test_replay_timeline_errors_eof_and_header_isolation(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'test.jsonl'
            rows = [dict(kind='session', t=0, schema=1, headers=False)]
            for t, cmd, response, phase in [(1, '0100', '41 00 00 18 00 00\r>', 'discovery'),
                    (10, '010C', '41 0C 1F 40\r>', 'poll'),
                    (11, '010D', '41 0D 32\r>', 'poll'),
                    (12, '010C', 'NO DATA\r>', 'poll')]:
                rows.append(dict(kind='reply', t=t, command=cmd, header='7E0',
                                 hex=response.encode().hex(), phase=phase, complete=True))
            rows.append(dict(kind='end', t=14))
            path.write_text(''.join(json.dumps(r) + '\n' for r in rows), encoding='utf-8')
            data = ReplayData(path)
            now = [100]
            elm = ReplayElm(data, clock=lambda: now[0])
            elm.command('ATE0')
            self.assertIn(b'41 00', elm.command('0100'))
            self.assertIsNone(elm.started)
            self.assertEqual(elm.command('01 0C 1'), b'41 0C 1F 40\r>')
            self.assertEqual(elm.command('010D'), b'NO DATA\r>')
            now[0] += 1
            self.assertEqual(elm.command('010D'), b'41 0D 32\r>')
            batch = elm.feed(b'010C\r010D\r')
            self.assertEqual(len(batch), 2)
            self.assertEqual([item['sampled_at'] for item in elm.reply_telemetry], [10, 11])
            now[0] += 1
            self.assertEqual(elm.command('010C'), b'NO DATA\r>')
            self.assertEqual(elm.command('015C'), b'NO DATA\r>')
            elm.command('ATSH7DF')
            self.assertEqual(elm.command('010D'), b'NO DATA\r>')
            elm.command('ATSH7E0')
            elm.command('ATH1')
            self.assertEqual(elm.command('010D'), b'NO DATA\r>')
            elm.command('ATH0')
            elm.command('ATS0')
            self.assertEqual(elm.command('010D'), b'410D32\r>')
            now[0] += 3
            self.assertEqual(elm.command('010D'), b'NO DATA\r>')
            elm = ReplayElm(data, speed=2, loop=True, clock=lambda: now[0])
            elm.command('ATE0')
            elm.command('010C')
            now[0] += 2
            self.assertEqual(elm.command('010C'), b'41 0C 1F 40\r>')

    def test_read_only_request_validation(self):
        import argparse
        self.assertEqual(read_request('22 10 17'), '221017')
        for command in ('04', 'ATMA', '2E101700', '1101'):
            with self.assertRaises(argparse.ArgumentTypeError):
                read_request(command)


if __name__ == '__main__':
    unittest.main()
