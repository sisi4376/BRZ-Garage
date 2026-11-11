from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from obd_telemetry import TripMetrics, decode


class TripTests(unittest.TestCase):
    def test_zc6_complete_numbered_and_flat_oil_responses(self):
        for size in (40, 41):
            payload = bytearray([0x55] * size)
            payload[:2] = b'\x61\x01'
            payload[-5] = 130
            flat = payload.hex(' ')
            rows = [f'{size:03X}', '0: ' + payload[:6].hex(' ')]
            rows += [f'{1+(i-6)//7:X}: ' + payload[i:i+7].hex(' ') for i in range(6, size, 7)]
            response = '\r'.join(rows)
            self.assertEqual(decode('2101', flat)[1], 90)
            self.assertEqual(decode('2101', response)[1], 90)
            self.assertIsNone(decode('2101', '\r'.join(rows[:-1])))
            self.assertIsNone(decode('2101', response.replace('1:', '2:')))
            self.assertIsNone(decode('2101', flat[:-6]))
        self.assertIsNone(decode('2101', 'NO DATA'))

    def test_known_speed_rate_distance_and_consumption(self):
        trip = TripMetrics()
        for t in range(61):
            trip.update('010D', 60, t)
            trip.update('015E', 6, t)
        stats = trip.snapshot()
        self.assertAlmostEqual(stats['distance_km'], 1)
        self.assertAlmostEqual(stats['fuel_l'], .1)
        self.assertAlmostEqual(stats['average_l100km'], 10)
        self.assertAlmostEqual(stats['instant'], 10)
        self.assertEqual(stats['fuel_source'], 'ECU')

    def test_idle_zero_and_ecu_priority(self):
        trip = TripMetrics()
        trip.update('010D', 0, 0)
        trip.update('010C', 1800, 0)
        trip.update('0110', 10, 0)
        trip.update('015E', 0, 0)
        trip.advance(2)
        stats = trip.snapshot()
        self.assertEqual(stats['instant'], 0)
        self.assertEqual(stats['instant_unit'], 'L/h')
        self.assertEqual(stats['fuel_l'], 0)
        self.assertIsNone(stats['average_l100km'])

    def test_gap_expiry_and_invalid_response_do_not_invent_travel(self):
        trip = TripMetrics()
        trip.update('010D', 72, 0)
        trip.update('015E', 3.6, 0)
        trip.advance(3600)
        self.assertAlmostEqual(trip.distance_km, .1)
        self.assertAlmostEqual(trip.fuel_l, .005)
        self.assertIsNone(trip.snapshot()['instant'])
        trip.update('010D', 72, 3600)
        trip.update('010D', None, 3601)
        trip.advance(4000)
        self.assertAlmostEqual(trip.distance_km, .12)

    def test_maf_assumptions_and_engine_off(self):
        trip = TripMetrics()
        trip.update('0110', 14.7, 0)
        self.assertIsNone(trip.snapshot()['fuel_rate_lph'])
        trip.update('010C', 2000, 0)
        self.assertAlmostEqual(trip.snapshot()['fuel_rate_lph'], 3600 / 745)
        self.assertIn('估算', trip.snapshot()['fuel_source'])
        trip.update('0144', 1.1, 0)
        self.assertAlmostEqual(trip.snapshot()['fuel_rate_lph'], 3600 / 745 / 1.1)
        trip.update('010C', 0, 0)
        self.assertEqual(trip.snapshot()['fuel_rate_lph'], 0)

    def test_average_uses_only_paired_coverage(self):
        trip = TripMetrics()
        for t in range(11):
            trip.update('010D', 60, t)
        trip.update('015E', 6, 10)
        for t in range(11, 21):
            trip.update('010D', 60, t)
            trip.update('015E', 6, t)
        self.assertAlmostEqual(trip.snapshot()['average_l100km'], 10)
        self.assertAlmostEqual(trip.paired_seconds, 10)

    def test_replayed_samples_expire_despite_repeated_requests(self):
        trip = TripMetrics()
        for t in range(11):
            trip.update('010D', 36, t, sampled_at=0)
        self.assertAlmostEqual(trip.distance_km, .05)
        self.assertEqual(trip.distance_seconds, 5)
        with self.assertRaises(ValueError):
            trip.advance(0)

    def test_odometer_zero_and_unavailable(self):
        self.assertEqual(decode('01A6', '41 A6 00 01 E2 40')[1], 12345.6)
        self.assertEqual(decode('01A6', '41 A6 00 00 00 00')[1], 0)
        self.assertIsNone(decode('01A6', '41 A6 FF FF FF FF'))


if __name__ == '__main__':
    unittest.main()
