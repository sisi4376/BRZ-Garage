"""Exercise the gauge's handshake and static data contract without Bluetooth."""
from pathlib import Path
from enum import IntEnum
import sys
import unittest
from unittest.mock import patch, PropertyMock, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from obd_ble_simulator import StaticElm, STATIC_PIDS, support_bitmap, wait_for_advertising


class AdvertisingStatus(IntEnum):
    CREATED = 0
    STOPPED = 1
    STARTED = 2
    ABORTED = 3
    STARTED_WITHOUT_ALL_ADVERTISEMENT_DATA = 4


class AdvertisingStartupTest(unittest.IsolatedAsyncioTestCase):
    async def test_transient_aborted_waits_for_started(self):
        provider = Mock()
        type(provider).advertisement_status = PropertyMock(side_effect=[
            AdvertisingStatus.CREATED, AdvertisingStatus.ABORTED, AdvertisingStatus.STARTED])
        with patch('obd_ble_simulator.asyncio.sleep') as sleep:
            status = await wait_for_advertising(provider, AdvertisingStatus)
        self.assertEqual(status, AdvertisingStatus.STARTED)
        self.assertEqual(sleep.await_count, 2)

    async def test_persistent_failure_is_not_reported_ready(self):
        provider = Mock(advertisement_status=AdvertisingStatus.ABORTED)
        with self.assertRaisesRegex(RuntimeError, 'last status: ABORTED'):
            await wait_for_advertising(provider, AdvertisingStatus, timeout=0)

    async def test_reduced_advertisement_is_a_started_state(self):
        provider = Mock(advertisement_status=AdvertisingStatus.STARTED_WITHOUT_ALL_ADVERTISEMENT_DATA)
        self.assertEqual(await wait_for_advertising(provider, AdvertisingStatus),
                         AdvertisingStatus.STARTED_WITHOUT_ALL_ADVERTISEMENT_DATA)


class StaticSimulatorTest(unittest.TestCase):
    def setUp(self):
        self.elm = StaticElm()
        self.elm.feed(b'ATZ\rATE0\rATL0\rATS1\rATH0\rATAT1\rATST19\rATSP6\rATSH7E0\r')

    def reply(self, command):
        return self.elm.feed(command.encode() + b'\r')[0][1]

    def test_gauge_handshake(self):
        elm = StaticElm()
        self.assertIn(b'ELM327 v1.5', elm.feed(b'ATZ\r')[0][1])
        for cmd in ('ATE0', 'ATL0', 'ATS1', 'ATH0', 'ATAT1', 'ATST19', 'ATSP6', 'ATSH7E0'):
            self.assertTrue(elm.feed(cmd.encode() + b'\r')[0][1].endswith(b'OK\r>'))
        self.assertEqual(elm.feed(b'010C\r')[0][1], b'41 0C 00 00\r>')

    def test_zero_values_survive_time_and_reset(self):
        expected = {'010C': b'41 0C 00 00\r>', '010D': b'41 0D 00\r>',
                    '015E': b'41 5E 00 00\r>', '0110': b'41 10 00 00\r>',
                    '0104': b'41 04 00\r>', '0111': b'41 11 00\r>'}
        for timestamp in (0, 10, 10000000):
            with patch('time.time', return_value=timestamp):
                for cmd, response in expected.items():
                    self.assertEqual(self.reply(cmd), response)
        self.reply('ATZ')
        self.reply('ATE0')
        self.assertEqual(self.reply('015E'), expected['015E'])

    def test_static_temperature_voltage_and_ratio_decode(self):
        for pid in (0x05, 0x0F, 0x5C):
            self.assertEqual(STATIC_PIDS[pid][0] - 40, 25)
        self.assertEqual(int.from_bytes(bytes(STATIC_PIDS[0x42]), 'big') / 1000, 12.6)
        self.assertEqual(int.from_bytes(bytes(STATIC_PIDS[0x44]), 'big') / 32768, 1)

    def test_bitmaps_advertise_only_implemented_pids(self):
        discovered = set()
        base = 0
        while True:
            bitmap = int.from_bytes(bytes(support_bitmap(base)), 'big')
            for n in range(1, 32):
                if bitmap & (1 << (32 - n)):
                    discovered.add(base + n)
            if not bitmap & 1:
                break
            base += 32
            self.assertLessEqual(base, 0x40)
        self.assertEqual(discovered, set(STATIC_PIDS))
        self.assertTrue(support_bitmap(0x40)[-1] & 4)  # fuel-rate support

    def test_fragmentation_crlf_and_batching(self):
        self.assertEqual(self.elm.feed(b'01'), [])
        self.assertEqual(self.elm.feed(b'0C\r'), [('010C', b'41 0C 00 00\r>')])
        self.assertEqual(self.elm.feed(b'\n010D\r\n'), [('010D', b'41 0D 00\r>')])
        self.assertEqual(len(self.elm.feed(b'010C\r010D\r')), 2)
        self.assertEqual(self.reply('01 0C 1'), b'41 0C 00 00\r>')
        self.assertEqual(self.reply('010C1'), b'41 0C 00 00\r>')

    def test_headers_spacing_and_multiple_pids(self):
        self.reply('ATH1')
        self.reply('ATS0')
        self.assertEqual(self.reply('010C0D'), b'7E804410C0000\r7E803410D00\r>')
        self.reply('ATL1')
        self.assertEqual(self.reply('010D'), b'7E803410D00\r\n>')

    def test_unsupported_and_oversize_do_not_fabricate_values(self):
        for cmd in ('01FF', '22FFFF', '2101', '0902', 'zzzz'):
            self.assertEqual(self.reply(cmd), b'NO DATA\r>')
        self.assertEqual(self.reply('ATMA'), b'?\r>')
        self.assertEqual(self.elm.feed(b'X' * 300), [])
        self.assertEqual(self.elm.feed(b'\r010D\r')[-1][1], b'41 0D 00\r>')
        self.assertEqual(len(self.elm.pending), 0)

    def test_sessions_are_independent(self):
        other = StaticElm()
        self.reply('ATH1')
        self.assertEqual(other.feed(b'010D\r')[0][1], b'010D\r41 0D 00\r>')


if __name__ == '__main__':
    unittest.main()
