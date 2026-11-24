import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from obd_gauge_poll import Zd8Poll, GaugePoll, split_group
from obd_telemetry import TripMetrics


class GaugePollTests(unittest.TestCase):
    def test_two_groups_and_full_rotation(self):
        for mode in ('zd8-mt', 'zd8-at', 'zc6-mt', 'zc6-at'):
            poll = GaugePoll(mode)
            for slow in ('0F','05','5C','42','2F','5E','40') * 2:
                self.assertEqual(poll.commands(0), ['010C0D10110403'])
                if slow == '40':
                    expected = ['010C0D10440E','0140']
                elif slow == '5C' and mode.startswith('zc6'):
                    expected = ['010C0D10440E','2101']
                else:
                    expected = ['010C0D10440E' + slow]
                self.assertEqual(poll.commands(0), expected)

    def test_group_failure_is_local_and_support_replaces_status(self):
        poll = Zd8Poll()
        poll.observe('0100', b'41 00 00 00 00 00\r>')
        for _ in range(2):
            cmd, = poll.commands(1)
            self.assertEqual(cmd,'010C0D10110444')
            poll.observe(cmd,b'NO DATA\r>')
            self.assertTrue(poll.commands(1)[0].startswith('010C0D10440E'))
        self.assertEqual(poll.commands(1), ['010C','010D','0110','0111','0104','0144'])
        self.assertEqual(len(poll.commands(1)),1)

    def test_numbered_reply_and_invalid_frames(self):
        cmd = '010C0D10440E0F'
        raw = '010\r0: 41 0C 0C 80 0D 20\r1: 10 01 F4 44 80 00 0E\r2: 7F 0F 44\r>'
        self.assertEqual(split_group(cmd,raw)['010E'], '41 0E 7F\r')
        for invalid in (raw.replace('1:', '2:'),raw.replace('010\r','011\r'), '41 0E 80 0E 80','41 44 01','41 03 00 00'):
            self.assertEqual(split_group(cmd,invalid),{})
        self.assertEqual(set(split_group(cmd,'41 0E 80 0D 00')), {'010D','010E'})

    def test_full_inspection_hold_has_no_age_limit(self):
        trip = TripMetrics(None)
        trip.update('010D', 60, 0)
        trip.update('010C', 2000, 0)
        trip.update('0110', 6*745*14.7/3600, 0)
        trip.advance(60)
        self.assertAlmostEqual(trip.distance_km, 1)
        self.assertAlmostEqual(trip.fuel_l, .1)
        self.assertAlmostEqual(trip.snapshot()['instant'], 10)

