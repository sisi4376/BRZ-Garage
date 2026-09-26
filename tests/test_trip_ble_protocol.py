import struct
import unittest


META_FORMAT = "<BBBBIIIHH"
RECORD_PREFIX_FORMAT = "<BBHIQQIIIH"
DETAIL_FORMAT = "<HHhh"


def crc16_ccitt(data: bytes) -> int:
    crc = 0xFFFF
    for value in data:
        crc ^= value << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


class TripBleProtocolTest(unittest.TestCase):
    def test_meta_layout_is_exactly_twenty_bytes(self):
        payload = struct.pack(META_FORMAT, 1, 7, 0, 0, 21, 27, 20, 64, 40)
        self.assertEqual(len(payload), 20)
        self.assertEqual(struct.unpack(META_FORMAT, payload), (1, 7, 0, 0, 21, 27, 20, 64, 40))
        payload_v2 = struct.pack(META_FORMAT, 2, 7, 0, 0, 21, 27, 20, 64, 48)
        self.assertEqual(len(payload_v2), 20)
        payload_v3 = struct.pack(META_FORMAT, 3, 0, 0, 0, 21, 27, 27, 64, 48)
        self.assertEqual(len(payload_v3), 20)
        self.assertEqual(struct.unpack(META_FORMAT, payload_v3)[0], 3)

    def test_record_layout_and_crc_match_firmware_contract(self):
        prefix = struct.pack(
            RECORD_PREFIX_FORMAT,
            1,              # protocol version
            1,              # time-valid flag
            40,             # record length
            42,             # trip id
            1_787_882_400,  # start Unix seconds
            1_787_886_000,  # end Unix seconds
            3600,           # duration seconds
            48210,          # distance metres
            4210,           # fuel millilitres
            873,            # 8.73 L/100 km
        )
        payload = prefix + struct.pack("<H", crc16_ccitt(prefix))
        self.assertEqual(len(prefix), 38)
        self.assertEqual(len(payload), 40)
        self.assertEqual(struct.unpack_from("<H", payload, 38)[0], crc16_ccitt(payload[:38]))

    def test_detailed_record_adds_only_eight_bytes_before_crc(self):
        prefix = struct.pack(RECORD_PREFIX_FORMAT, 2, 1, 48, 42, 1_787_882_400,
                             1_787_886_000, 3600, 48210, 4210, 873)
        details = struct.pack(DETAIL_FORMAT, 138, 6840, 326, -714)
        payload_without_crc = prefix + details
        payload = payload_without_crc + struct.pack("<H", crc16_ccitt(payload_without_crc))
        self.assertEqual(len(details), 8)
        self.assertEqual(len(payload), 48)
        self.assertEqual(struct.unpack(DETAIL_FORMAT, payload[38:46]), (138, 6840, 326, -714))

    def test_control_layout_is_command_plus_unsigned_cursor(self):
        cursor = struct.pack("<BI", 1, 0xFEEDBEEF)
        acknowledge = struct.pack("<BI", 2, 0xFEEDBEEF)
        self.assertEqual(cursor, b"\x01\xef\xbe\xed\xfe")
        self.assertEqual(acknowledge, b"\x02\xef\xbe\xed\xfe")

    def test_brightness_command_is_small_versioned_and_crc_protected(self):
        prefix = struct.pack("<BBBB", 1, 1, 55, 6)
        packet = prefix + struct.pack("<H", crc16_ccitt(prefix))
        self.assertEqual(len(packet), 6)
        self.assertEqual(packet[:4], b"\x01\x01\x37\x06")
        self.assertEqual(struct.unpack_from("<H", packet, 4)[0], crc16_ccitt(packet[:4]))

    def test_vehicle_profile_command_uses_same_atomic_crc_contract(self):
        for profile in (1, 4):  # ZC6 CAN, ZD8
            prefix = struct.pack("<BBBB", 1, 1, profile, 6)
            packet = prefix + struct.pack("<H", crc16_ccitt(prefix))
            self.assertEqual(len(packet), 6)
            self.assertEqual(packet[2], profile)
            self.assertEqual(struct.unpack_from("<H", packet, 4)[0], crc16_ccitt(packet[:4]))

    def test_odometer_config_is_fixed_crc_protected_and_tenths_of_km(self):
        display_prefix = struct.pack("<BBBBIH", 1, 1, 0, 12, 0, 0)
        display_packet = display_prefix + struct.pack("<H", crc16_ccitt(display_prefix))
        self.assertEqual(len(display_packet), 12)
        self.assertEqual(display_packet[:4], b"\x01\x01\x00\x0c")

        calibration_prefix = struct.pack("<BBBBIH", 1, 2, 0, 12, 50_001, 0)
        calibration_packet = calibration_prefix + struct.pack("<H", crc16_ccitt(calibration_prefix))
        self.assertEqual(struct.unpack_from("<I", calibration_packet, 4)[0], 50_001)
        self.assertEqual(struct.unpack_from("<H", calibration_packet, 10)[0],
                         crc16_ccitt(calibration_packet[:10]))

        snapshot_prefix = struct.pack("<BBHIH", 1, 0x03, 12, 50_001, 0)
        snapshot = snapshot_prefix + struct.pack("<H", crc16_ccitt(snapshot_prefix))
        self.assertEqual(len(snapshot), 12)
        self.assertEqual(struct.unpack("<BBHIHH", snapshot),
                         (1, 3, 12, 50_001, 0, crc16_ccitt(snapshot[:10])))


if __name__ == "__main__":
    unittest.main()
