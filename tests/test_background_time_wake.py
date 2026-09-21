from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android_app" / "app" / "src" / "main" / "java" / "com" / "brz" / "gauge" / "trips"


class BackgroundTimeWakeTest(unittest.TestCase):
    def test_signal_scan_tracks_appearance_and_loss_aggressively(self):
        source = (JAVA / "BackgroundBleWake.kt").read_text(encoding="utf-8")
        self.assertIn("SCAN_MODE_BALANCED", source)
        self.assertIn("MATCH_MODE_AGGRESSIVE", source)
        self.assertIn("CALLBACK_TYPE_FIRST_MATCH or", source)
        self.assertIn("CALLBACK_TYPE_MATCH_LOST", source)
        self.assertIn("SCAN_FAILED_FEATURE_UNSUPPORTED", source)
        self.assertIn("已启用（兼容模式）", source)
        self.assertIn("已启用（基础兼容模式）", source)
        self.assertIn("CALLBACK_TYPE_ALL_MATCHES", source)
        self.assertIn("consumeBasicCompatibleSignal", source)
        self.assertIn("SCAN_MODE_LOW_POWER", source)
        lost = source.index("callbackType and ScanSettings.CALLBACK_TYPE_MATCH_LOST")
        wake = source.index("TripSyncService.wakeFromGaugeSignal(context)")
        self.assertLess(lost, wake)

    def test_signal_uses_direct_connection_and_clock_is_first(self):
        service = (JAVA / "TripSyncService.kt").read_text(encoding="utf-8")
        self.assertIn('ACTION_GAUGE_SIGNAL = "com.brz.gauge.trips.GAUGE_SIGNAL"', service)
        self.assertIn("if (gaugeSignalWake) connectFromGaugeSignal() else beginReconnect()", service)
        direct = service.split("private fun connectFromGaugeSignal()", 1)[1].split(
            "private val scanCallback", 1)[0]
        self.assertIn("if (destroyed || gatt != null || !state.automatic) return", direct)
        self.assertIn("device.connectGatt(this, false, callback", direct)
        discovered = service.split("override fun onServicesDiscovered", 1)[1].split(
            "override fun onMtuChanged", 1)[0]
        self.assertIn("syncClock()", discovered)
        self.assertNotIn("requestMtu", discovered)
        connected = service.split("BluetoothProfile.STATE_CONNECTED", 1)[1].split(
            "override fun onServicesDiscovered", 1)[0]
        self.assertNotIn("BackgroundBleWake.register", connected)

    def test_boot_and_gauge_wake_have_cold_start_recovery(self):
        manifest = (ROOT / "android_app" / "app" / "src" / "main" /
                    "AndroidManifest.xml").read_text(encoding="utf-8")
        boot = (JAVA / "BootReceiver.kt").read_text(encoding="utf-8")
        wake = (JAVA / "BackgroundBleWake.kt").read_text(encoding="utf-8")
        service = (JAVA / "TripSyncService.kt").read_text(encoding="utf-8")
        watchdog = (JAVA / "ServiceWatchdogReceiver.kt").read_text(encoding="utf-8")
        self.assertIn('android:directBootAware="true"', manifest)
        self.assertIn("android.intent.action.LOCKED_BOOT_COMPLETED", manifest)
        self.assertIn("android.intent.action.USER_UNLOCKED", manifest)
        self.assertIn("com.huawei.intent.action.QUICKBOOT_POWERON", manifest)
        self.assertIn("users?.isUserUnlocked == false", boot)
        self.assertIn("createDeviceProtectedStorageContext()", boot)
        self.assertIn("BackgroundBleWake.register(context, force = true)", boot)
        self.assertIn("TripSyncService.start(context, reason = reason)", boot)
        self.assertIn("BackgroundBleWake.register(context, force = true)", wake)
        self.assertIn("ServiceWatchdogReceiver.schedule(context, 60_000L)", wake)
        self.assertIn("service_started_at", service)
        self.assertIn("service_start_failed_at", service)
        self.assertIn('TripSyncService.start(context, reason = "后台自检")', watchdog)

    def test_completed_trip_is_pulled_without_waiting_for_periodic_history_cycle(self):
        service = (JAVA / "TripSyncService.kt").read_text(encoding="utf-8")
        vehicle_read = service.split("TripBleProtocol.VEHICLE_STATE ->", 1)[1].split(
            "TripBleProtocol.REFUEL_META ->", 1)[0]
        self.assertIn("val previousVehicle = state.vehicle()", vehicle_read)
        self.assertIn("previouslyActive && nowInactive", vehicle_read)
        self.assertIn("read(TripBleProtocol.TRIP_META)", vehicle_read)

    def test_connected_background_sync_holds_cpu_until_disconnect(self):
        manifest = (ROOT / "android_app" / "app" / "src" / "main" /
                    "AndroidManifest.xml").read_text(encoding="utf-8")
        service = (JAVA / "TripSyncService.kt").read_text(encoding="utf-8")
        self.assertIn('android.permission.WAKE_LOCK', manifest)
        self.assertIn("PowerManager.PARTIAL_WAKE_LOCK", service)
        connected = service.split("BluetoothProfile.STATE_CONNECTED", 1)[1].split(
            "override fun onServicesDiscovered", 1)[0]
        self.assertIn("acquireConnectionWakeLock()", connected)
        close = service.split("private fun closeConnection()", 1)[1].split(
            "private fun fail", 1)[0]
        self.assertIn("releaseConnectionWakeLock()", close)
        self.assertLess(close.index("connected = false"),
                        close.index("releaseConnectionWakeLock()"))

    def test_foreground_notification_shows_live_driving_trip(self):
        service = (JAVA / "TripSyncService.kt").read_text(encoding="utf-8")
        notification = service.split("private fun notification(message: String)", 1)[1].split(
            "private fun lowFuelNotification", 1)[0]
        self.assertIn("it.rpm > 0", notification)
        self.assertIn('"驾驶中 %02d:%02d    已行驶 %.1f km"', notification)
        self.assertIn("it.currentDurationS", notification)
        self.assertIn("it.currentDistanceM / 1000.0", notification)
        vehicle_read = service.split("TripBleProtocol.VEHICLE_STATE ->", 1)[1].split(
            "TripBleProtocol.REFUEL_META ->", 1)[0]
        self.assertIn("notificationVehicle = vehicle", vehicle_read)
        close = service.split("private fun closeConnection()", 1)[1].split(
            "private fun fail", 1)[0]
        self.assertIn("notificationVehicle = null", close)


if __name__ == "__main__":
    unittest.main()
