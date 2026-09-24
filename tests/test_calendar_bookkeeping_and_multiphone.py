import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android_app/app/src/main/java/com/brz/gauge/trips"


class CalendarBookkeepingAndMultiPhoneTests(unittest.TestCase):
    def test_driving_calendar_and_total_overview_are_present(self):
        main = (ANDROID / "MainActivity.kt").read_text(encoding="utf-8")
        calendar = (ANDROID / "DrivingCalendarView.kt").read_text(encoding="utf-8")
        self.assertIn('card(body, "驾驶总览")', main)
        self.assertIn('card(body, "驾驶日历 · 活动越多颜色越深")', main)
        self.assertIn("DrivingCalendarView(this)", main)
        self.assertIn("data class DrivingDayActivity", calendar)
        self.assertIn("repeat(weeks)", calendar)
        self.assertIn("setOnDaySelected", calendar)

    def test_bookkeeping_has_swipe_sections_and_visual_statistics(self):
        main = (ANDROID / "MainActivity.kt").read_text(encoding="utf-8")
        database = (ANDROID / "ExpenseDatabase.kt").read_text(encoding="utf-8")
        chart = (ANDROID / "BookkeepingChartView.kt").read_text(encoding="utf-8")
        self.assertIn('listOf("加油", "保养", "日常", "统计")', main)
        self.assertIn("attachAccountingSwipe()", main)
        self.assertIn("override fun onFling", main)
        self.assertIn("5000 km / 6个月，以先到为准", main)
        self.assertIn("state.odometerDisplayEnabled", main)
        self.assertIn("lastDate.plusMonths(6)", main)
        self.assertIn("MonthlyExpenseSummary", main)
        self.assertIn("BookkeepingChartView(this)", main)
        self.assertIn("CREATE TABLE expenses", database)
        self.assertIn("item.maintenance", chart)
        self.assertIn("item.daily", chart)

    def test_protocol_three_keeps_history_for_independent_phone_cursors(self):
        storage = (ROOT / "main/bsp_obd_dsp/nvs_storage.c").read_text(encoding="utf-8")
        database = (ANDROID / "TripDatabase.kt").read_text(encoding="utf-8")
        service = (ANDROID / "TripSyncService.kt").read_text(encoding="utf-8")
        protocol = (ANDROID / "TripBleProtocol.kt").read_text(encoding="utf-8")

        self.assertIn("TRIP_SYNC_PROTOCOL_VERSION 3", storage)
        ack = storage.split("esp_err_t nvs_trip_sync_ack", 1)[1].split("/* Helpers */", 1)[0]
        self.assertNotIn("memmove", ack)
        self.assertIn("Do not delete acknowledged records", ack)
        self.assertIn("fun latestKnownId(deviceId: String)", database)
        self.assertIn("database.latestKnownId(address)", service)
        self.assertIn("if (m.version >= 3)", service)
        self.assertIn("it.version in 2..3", protocol)


if __name__ == "__main__":
    unittest.main()
