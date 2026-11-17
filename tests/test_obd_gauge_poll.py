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
                slots = [poll.commands(10 + i * .001) for i in range(42)]
                oil = '2101' if key.startswith('zc6') else '015C'
                self.assertEqual(slots[17], [oil, '010D'])
                self.assertEqual(poll.profile.automatic, key.endswith('-at'))
                self.assertIn(oil, poll.COMMANDS)
                slots[17] = ['OIL', '010D']
                if base is None:
                    base = slots
                self.assertEqual(slots, base)

    def test_first_round_production_order(self):
        poll = Zd8Poll()
        actual = []
        for slot in range(6):
            actual += poll.commands(10 + slot * .1)
        self.assertEqual(actual, ['0110', '010C', '0104', '010D',
            '0111', '010C', '0103', '010D', '0144', '010C', '010F', '010D'])
        self.assertEqual(actual.count('010C'), 3)
        self.assertEqual(actual.count('010D'), 3)
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
        self.assertIn('0140', history)
        self.assertIn('015E', history)
        self.assertLess(max(gaps('010D')), 3)
        self.assertLess(max(gaps('010C')), 3)
        self.assertLess(max(gaps('0110')), 5)
        for cmd in ('010F','0105','015C','0142','012F','015E','0140'):
            self.assertGreater(len(history[cmd]), 1)

    def test_slow_rotation_continues_after_no_data_and_unsupported_capability(self):
        poll = Zd8Poll()
        poll.observe('0140', b'41 40 00 00 00 04\r>')
        actual = [c for _ in range(42) for c in poll.commands(1)]
        self.assertEqual(actual.count('015E'), 1)
        self.assertEqual(actual.count('0140'), 1)
        poll = Zd8Poll()
        commands = []
        for slot in range(6 * 7 * 10):
            for cmd in poll.commands(slot):
                commands.append(cmd)
                poll.observe(cmd, b'41 40 FE DC A0 51\r>' if cmd == '0140' else b'NO DATA\r>')
        self.assertEqual(commands.count('0103'), 3)
        for cmd in ('0140','015E','012F'):
            self.assertEqual(commands.count(cmd), 10)

    def test_fast_rounds_have_no_fuel_or_slow_time_gates(self):
        poll = Zd8Poll()
        poll.observe('0100', b'41 00 BE 3F A8 13\r>')
        rounds = [sum((poll.commands(1 + (r * 6 + n) * .001)
                       for n in range(6)), []) for r in range(14)]
        for commands in rounds:
            for cmd in ('0104', '0111', '0103', '0144', '0110'):
                self.assertEqual(commands.count(cmd), 1)
        slow = [next(c for c in commands if c in ('010F','0105','015C','0142','012F','015E','0140'))
                for commands in rounds]
        self.assertEqual(slow, ['010F','0105','015C','0142','012F','015E','0140'] * 2)

    def test_full_inspection_hold_has_no_age_limit(self):
        trip = TripMetrics(None)
        trip.update('010D', 60, 0)
        trip.update('015E', 6, 0)
        trip.advance(60)
        self.assertAlmostEqual(trip.distance_km, 1)
        self.assertAlmostEqual(trip.fuel_l, .1)
        self.assertEqual(trip.snapshot()['instant'], 10)

