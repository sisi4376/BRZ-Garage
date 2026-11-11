import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from obd_gauge_poll import Zd8Poll, GaugePoll, GAUGE_PROFILES
from obd_telemetry import TripMetrics


class GaugePollTests(unittest.TestCase):
    def test_four_vehicle_schedules_differ_only_at_oil_slot(self):
        base = None
        for key in ('zd8-mt', 'zd8-at', 'zc6-mt', 'zc6-at'):
            with self.subTest(profile=key):
                poll = GaugePoll(key)
                slots = [poll.commands(10 + i * .1) for i in range(14)]
                oil = '2101' if key.startswith('zc6') else '015C'
                self.assertEqual(slots[6], [oil, '010C'])
                self.assertEqual(poll.profile.automatic, key.endswith('-at'))
                self.assertIn(oil, poll.COMMANDS)
                slots[6] = ['OIL', '010C']
                if base is None:
                    base = slots
                self.assertEqual(slots, base)

    def test_first_round_production_order(self):
        poll = Zd8Poll()
        actual = []
        for slot in range(14):
            actual += poll.commands(10 + slot * .1)
        self.assertEqual(actual, ['010C', '010F', '010C', '010D', '010C', '0105', '010C',
            '0104', '010D', '0111', '010C', '015C', '010C', '0142', '010C',
            '0103', '010D', '0144', '010C', '0110', '010C', '010C', '012F', '010D', '0140', '010C'])
        self.assertNotIn('01A4', actual)
        self.assertNotIn('01A6', actual)

    def test_live_cadence_priorities_and_capabilities(self):
        poll = Zd8Poll()
        poll.observe('0100', b'41 00 BE 3F A8 13\r>')
        history = {}
        now = 10
        while now < 140:
            for cmd in poll.commands(now):
                history.setdefault(cmd, []).append(now)
                now += .125  # median response time in user's real recording
                if cmd == '0140':
                    poll.observe(cmd, b'41 40 FE DC A0 51\r>')
                if cmd == '012F':
                    poll.observe(cmd, b'41 2F 6D\r>')
            now += .001
        def gaps(cmd):
            return [b-a for a,b in zip(history[cmd], history[cmd][1:])]
        self.assertEqual(len(history['0140']), 1)
        self.assertNotIn('015E', history)
        self.assertLess(max(gaps('010D')), 3)
        self.assertLess(max(gaps('010C')), 3)
        self.assertLess(max(gaps('0110')), 5)
        self.assertGreaterEqual(min(gaps('012F')), 30)
        for cmd in ('010F','0105','015C','0142'):
            self.assertGreaterEqual(min(gaps(cmd)), 4.999)

    def test_rate_present_and_failed_probe_limits(self):
        poll = Zd8Poll()
        poll.observe('0140', b'41 40 00 00 00 04\r>')
        poll.slot = 13
        self.assertEqual(poll.commands(1), ['015E', '010C'])
        poll = Zd8Poll()
        commands = []
        for slot in range(14 * 100):
            for cmd in poll.commands(slot):
                commands.append(cmd)
                poll.observe(cmd, b'NO DATA\r>')
        self.assertEqual(commands.count('0103'), 3)
        self.assertEqual(commands.count('0140'), 3)
        self.assertEqual(commands.count('012F'), 3)

    def test_full_inspection_hold_has_no_age_limit(self):
        trip = TripMetrics(None)
        trip.update('010D', 60, 0)
        trip.update('015E', 6, 0)
        trip.advance(60)
        self.assertAlmostEqual(trip.distance_km, 1)
        self.assertAlmostEqual(trip.fuel_l, .1)
        self.assertEqual(trip.snapshot()['instant'], 10)

