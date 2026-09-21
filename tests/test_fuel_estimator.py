import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class FuelEstimatorTests(unittest.TestCase):
    def _compiler(self):
        bundled = ROOT / "tools/.cache/w64devkit/bin/gcc.exe"
        return str(bundled) if bundled.exists() else shutil.which("gcc")

    def test_production_fuel_estimator(self):
        compiler = self._compiler()
        if not compiler:
            self.skipTest("A host C compiler is required")
        env = os.environ.copy()
        env["PATH"] = str(Path(compiler).parent) + os.pathsep + env.get("PATH", "")
        with tempfile.TemporaryDirectory(prefix="fuel-host-") as tmp:
            executable = str(Path(tmp) / "fuel_test.exe")
            subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Imain",
                            "main/app_obd_dsp/fuel_estimator.c", "tests/fuel_estimator_host_test.c",
                            "-o", executable], cwd=ROOT, env=env, check=True, capture_output=True, text=True)
            result = subprocess.run([executable], check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout.count("PASS:"), 6)

    def test_delayed_phone_time_anchor(self):
        compiler = self._compiler()
        if not compiler:
            self.skipTest("A host C compiler is required")
        env = os.environ.copy()
        env["PATH"] = str(Path(compiler).parent) + os.pathsep + env.get("PATH", "")
        with tempfile.TemporaryDirectory(prefix="trip-time-host-") as tmp:
            executable = str(Path(tmp) / "trip_time_test.exe")
            subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Imain",
                            "main/app_obd_dsp/trip_time_anchor.c",
                            "tests/trip_time_anchor_host_test.c", "-o", executable],
                           cwd=ROOT, env=env, check=True, capture_output=True, text=True)
            result = subprocess.run([executable], env=env, check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout.count("PASS:"), 5)

    def test_engine_off_trip_finalizer(self):
        compiler = self._compiler()
        if not compiler:
            self.skipTest("A host C compiler is required")
        env = os.environ.copy()
        env["PATH"] = str(Path(compiler).parent) + os.pathsep + env.get("PATH", "")
        with tempfile.TemporaryDirectory(prefix="trip-idle-host-") as tmp:
            executable = str(Path(tmp) / "trip_idle_test.exe")
            subprocess.run([compiler, "-std=c11", "-Wall", "-Wextra", "-Imain",
                            "main/app_obd_dsp/trip_idle_detector.c",
                            "tests/trip_idle_detector_host_test.c", "-o", executable],
                           cwd=ROOT, env=env, check=True, capture_output=True, text=True)
            result = subprocess.run([executable], env=env, check=True,
                                    capture_output=True, text=True)
            self.assertEqual(result.stdout.count("PASS:"), 4)

    def test_status_poll_parser_and_storage_wiring(self):
        elm = (ROOT / "main/bsp_obd_dsp/elm327_ble_client.c").read_text(encoding="utf-8")
        self.assertIn('elm327_ble_send_ascii_blocking("01 03\\r")', elm)
        self.assertIn("case 0x03:", elm)
        self.assertIn(".on_parsed_fuel_status = default_on_parsed_fuel_status", elm)
        storage = (ROOT / "main/bsp_obd_dsp/nvs_storage.c").read_text(encoding="utf-8")
        self.assertIn("fuel_estimator_step(&s_fuel_estimator, sample, dt_ms)", storage)
        self.assertIn("s_fuel.lifetime_fuel_ul += fuel_ul", storage)
        self.assertIn("trip_time_anchor_observe(&s_trip_time_anchor", storage)
        self.assertIn("s_fuel.pending_duration_ms += s_fuel.active_duration_ms", storage)
        self.assertIn("trip_time_gap_within(s_fuel.pending_last_epoch_s", storage)
        self.assertIn("trip_idle_detector_step(", storage)
        self.assertIn("if (trip_finalized) nvs_fuel_save();", storage)
        self.assertIn("normalized.trip_merge_timeout_min = TRIP_MERGE_TIMEOUT_LOCKED_MIN", storage)
        pending_boot = storage.split("static void fuel_prepare_pending_at_boot(void)", 1)[1]
        pending_boot = pending_boot.split("esp_err_t nvs_storage_init(void)", 1)[0]
        self.assertNotIn("fuel_finish_pending_trip();", pending_boot)
        settings = (ROOT / "main/export_path/screens/ui_ScreenPageSettings.c").read_text(encoding="utf-8")
        self.assertIn("lv_obj_add_state(btn_trip_gap, LV_STATE_DISABLED)", settings)
        header = (ROOT / "main/bsp_obd_dsp/nvs_storage.h").read_text(encoding="utf-8")
        self.assertIn("#define TRIP_MERGE_TIMEOUT_EDITABLE 0", header)
        self.assertIn("#define TRIP_MERGE_TIMEOUT_LOCKED_MIN 15U", header)
        self.assertIn("#define MULTIGAUGE_SETTINGS_EDITABLE 0", header)
        self.assertIn("#define MULTIGAUGE_LOCKED_ROLE 0U", header)
        self.assertIn("#define MULTIGAUGE_LOCKED_POSITION 1U", header)
        self.assertIn("#define MULTIGAUGE_LOCKED_INTRO 2U", header)
        self.assertIn("normalized.device_role = MULTIGAUGE_LOCKED_ROLE", storage)
        self.assertIn("#define CFG_VERSION_CURRENT   12", storage)
        self.assertIn("s_cfg.default_page = 6", storage)
        device_page = (ROOT / "main" / "export_path" / "screens" /
                       "ui_ScreenPageEasterEgg.c").read_text(encoding="utf-8")
        self.assertIn("lv_color_hex(0xD8E0E8)", device_page)
        self.assertIn("lv_obj_move_foreground(ui_LabelEasterEggOdometer);", device_page)
        self.assertIn("vehicle_profile_normalize_index(normalized.vehicle_profile_idx)", storage)
        multi = (ROOT / "main/export_path/screens/ui_ScreenPageMultiGauge.c").read_text(encoding="utf-8")
        self.assertEqual(multi.count("lv_obj_add_state(r, LV_STATE_DISABLED)"), 1)
        self.assertIn("0x3A3A3A", multi)
        self.assertIn("LV_PART_SELECTED | LV_STATE_DISABLED", multi)
        self.assertNotIn("LV_EVENT_VALUE_CHANGED", multi)
        self.assertIn("s_fuel.active_duration_ms += dt_ms", storage)
        self.assertLess(storage.index("s_fuel.active_duration_ms += dt_ms"),
                        storage.index("if (!estimate.integrate)"))
        self.assertIn("sample->rpm > s_fuel.active_detail.max_rpm", storage)
        self.assertIn("sample->speed_kmh > s_fuel.active_detail.max_speed_kmh", storage)
        self.assertIn("(int64_t)delta_kmh * 250000LL", storage)
        self.assertIn("accel_x100 >= -3000 && accel_x100 <= 3000", storage)
        self.assertNotIn("malloc", storage.split("void nvs_fuel_update", 1)[1].split("void nvs_fuel_get_snapshot", 1)[0])
        self.assertIn("nvs_trip_detail_t history_details[NVS_FUEL_TRIP_HISTORY_MAX]", storage)
        cache = (ROOT / "main/app_obd_dsp/obd_data_cache.c").read_text(encoding="utf-8")
        self.assertIn("esp_timer_get_time() / 1000", cache)
        self.assertIn("nvs_fuel_update(&sample, dt_ms)", cache)
        self.assertIn("elapsed_ms > UINT32_MAX ? UINT32_MAX", cache)
        self.assertNotIn("elapsed_ms > 2000 ? 2001", cache)
        self.assertIn("now_ms - periodic_last_ms >= 60000", cache)
        self.assertIn("was_engine_running && sample.rpm == 0", cache)
        self.assertIn("bool checkpoint_edge = stop_edge || rpm_zero_edge", cache)
        self.assertIn("periodic_due || (checkpoint_edge && save_err == ESP_OK)", cache)
        self.assertIn("trip_time_anchor_estimate(&s_trip_time_anchor", storage)
        self.assertIn("s_fuel.active_last_epoch_s = estimated_end", storage)
        self.assertIn("s_phone_time_valid && s_trip_time_anchor.seen", storage)
        self.assertIn("s_fuel.active_last_epoch_s = s_fuel.pending_last_epoch_s", storage)
        self.assertIn("!estimated && !s_active_contains_pending", storage)
        self.assertIn("bool engine_running = sample && sample->rpm_valid", storage)
        ble = (ROOT / "main/bsp_obd_dsp/racechrono_ble_diy.c").read_text(encoding="utf-8")
        self.assertIn("DEVICE_INFO_CHAR_VEHICLE_PROFILE_CONTROL 0x0009", ble)
        self.assertIn("APP_EVT_PHONE_VEHICLE_PROFILE", ble)
        self.assertIn("value[2] == VEHICLE_PROFILE_ZC6_INDEX", ble)
        self.assertIn("DEVICE_INFO_CHAR_ODOMETER_CONFIG 0x000A", ble)
        odometer_write = ble.split("param->write.handle == s_handle_odometer_config", 2)[2]
        odometer_write = odometer_write.split("case ESP_GATTS_READ_EVT", 1)[0]
        self.assertIn("APP_EVT_PHONE_ODOMETER_DISPLAY", odometer_write)
        self.assertIn("APP_EVT_PHONE_ODOMETER_CALIBRATE", odometer_write)
        self.assertNotIn("nvs_odometer_set_display", odometer_write)
        self.assertNotIn("nvs_odometer_calibrate_x10_km", odometer_write)
        ui = (ROOT / "main/export_path/ui.c").read_text(encoding="utf-8")
        self.assertIn("vehicle_profile_set_active(profile)", ui)
        self.assertIn("nvs_odometer_set_display(enabled)", ui)
        self.assertIn("nvs_odometer_calibrate_x10_km(evt.data.u32)", ui)
        profiles = (ROOT / "main/app_obd_dsp/vehicle_profiles.c").read_text(encoding="utf-8")
        zc6 = profiles.split('.name = "ZC6"', 1)[1].split('.name = "ZN/C6 PID"', 1)[0]
        self.assertNotIn("can_broadcast_mode = true", zc6)
        self.assertIn("index = vehicle_profile_normalize_index(index)", profiles)
        self.assertIn("index == VEHICLE_PROFILE_ZC6_INDEX", profiles)
        self.assertIn("index == VEHICLE_PROFILE_ZD8_INDEX", profiles)
        self.assertIn('static const char *vehicle_names = "ZC6\\nZD8"', settings)
        self.assertNotIn("vehicle_profile_get_all(&vehicle_count)", settings)

    def test_android_connection_does_not_recycle_live_vehicle_artwork(self):
        activity = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt").read_text(encoding="utf-8")
        service = (ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/TripSyncService.kt").read_text(encoding="utf-8")
        self.assertIn("private val vehicleHeroBitmaps", activity)
        self.assertNotIn(".recycle()", activity)
        self.assertIn("try { BackgroundBleWake.register(this)", service)
        self.assertIn("private var bluetoothReceiverRegistered = false", service)
        self.assertIn("Status delivery is auxiliary", service)


if __name__ == "__main__":
    unittest.main()
