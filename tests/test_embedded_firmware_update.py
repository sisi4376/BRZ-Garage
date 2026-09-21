import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "android_app" / "app" / "src" / "main" / "assets" / "firmware"


class EmbeddedFirmwareUpdateTest(unittest.TestCase):
    def test_catalog_packages_are_unique_and_every_image_is_valid(self):
        catalog = json.loads((ASSET_ROOT / "catalog.json").read_text(encoding="utf-8"))
        packages = catalog["packages"]
        ids = [item["id"] for item in packages]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertIn(catalog["latest"], ids)
        for item in packages:
            manifest_path = ROOT / "android_app" / "app" / "src" / "main" / "assets" / item["manifest"]
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            entry = manifest["files"]["firmware"]
            image = manifest_path.parent / entry["path"]
            payload = image.read_bytes()
            self.assertEqual(payload[0], 0xE9)
            self.assertEqual(len(payload), entry["size"])
            self.assertEqual(hashlib.sha256(payload).hexdigest(), entry["sha256"])
            self.assertLessEqual(len(payload), 0x300000)
            self.assertTrue(entry["ota_only"])

    def test_2_6_rollback_package_declares_current_data_compatibility(self):
        catalog = json.loads((ASSET_ROOT / "catalog.json").read_text(encoding="utf-8"))
        rollback = next(item for item in catalog["packages"] if item["id"] == "2.6.0-safe")
        self.assertTrue(rollback["rollback"])
        manifest_path = ROOT / "android_app" / "app" / "src" / "main" / "assets" / rollback["manifest"]
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["firmware"]["version"], "2.6.0")
        self.assertEqual(manifest["firmware"]["build_tag"], "rollback-safe-2.6.0-r1")
        compatibility = manifest["compatibility"]
        self.assertTrue(compatibility["data_safe"])
        self.assertGreaterEqual(compatibility["preserve_nvs_schema"], 4)
        self.assertTrue(compatibility["preserve_trip_details"])
        self.assertTrue(compatibility["preserve_odometer"])
        self.assertTrue(compatibility["app_ota_recovery"])

    def test_embedded_firmware_matches_manifest_and_ota_partition(self):
        manifest = json.loads((ASSET_ROOT / "latest.json").read_text(encoding="utf-8"))
        entry = manifest["files"]["firmware"]
        image = (ASSET_ROOT / entry["path"]).read_bytes()
        self.assertEqual(image[0], 0xE9)
        self.assertEqual(len(image), entry["size"])
        self.assertEqual(hashlib.sha256(image).hexdigest(), entry["sha256"])
        self.assertLessEqual(len(image), 0x300000)
        self.assertTrue(entry["ota_only"])

    def test_package_is_locked_to_current_amoled_hardware(self):
        manifest = json.loads((ASSET_ROOT / "latest.json").read_text(encoding="utf-8"))
        device = manifest["device"]
        self.assertEqual(device["variant"], "obd_brz_gauge_amoled175")
        self.assertEqual(device["lcd"], "CO5300")
        self.assertEqual(device["screen"], {"w": 466, "h": 466, "bpp": 16})
        self.assertEqual(device["ota_slots"], 2)

    def test_partition_layout_keeps_user_data_outside_ota_slots(self):
        table = (ROOT / "partitions.csv").read_text(encoding="utf-8")
        self.assertIn("nvs,      data, nvs", table)
        self.assertIn("ota_0,    app,  ota_0", table)
        self.assertIn("ota_1,    app,  ota_1", table)
        self.assertIn("bootmedia,data, 0x82", table)

    def test_firmware_verifies_flash_before_switching_boot_slot(self):
        source = (ROOT / "main" / "app_obd_dsp" / "ota_wifi_server.c").read_text(encoding="utf-8")
        verify = source.index("flash_readback_sha_verify(ota_partition")
        switch = source.index("esp_ota_set_boot_partition(ota_partition)")
        self.assertLess(verify, switch)

    def test_ota_final_ack_drains_before_transport_shutdown_and_flash(self):
        source = (ROOT / "main" / "app_obd_dsp" / "ota_wifi_server.c").read_text(encoding="utf-8")
        handler = source.split("static esp_err_t firmware_handler(httpd_req_t *req)", 1)[1].split(
            "// POST /ota/bootmedia/prepare", 1)[0]
        installer = source.split("static void firmware_install_task(void *arg)", 1)[1].split(
            "// POST /ota/firmware", 1)[0]
        self.assertIn("OTA_INSTALL_ACK_DELAY_MS", installer)
        self.assertIn("httpd_stop(server)", installer)
        self.assertIn("esp_wifi_stop()", installer)
        self.assertIn('xTaskCreate(firmware_install_task, "ota_install"', handler)
        self.assertIn("return send_json_response(", handler)
        self.assertNotIn("esp_wifi_stop()", handler)
        self.assertLess(installer.index("httpd_stop(server)"), installer.index("esp_ota_begin("))

    def test_ota_receive_buffers_are_bounded(self):
        source = (ROOT / "main" / "app_obd_dsp" / "ota_wifi_server.c").read_text(encoding="utf-8")
        self.assertIn("#define OTA_RECV_CHUNK           16384", source)
        self.assertIn("#define OTA_RECV_CHUNK_PSRAM     16384", source)
        self.assertIn("#define OTA_SOCKET_RCVBUF        32768", source)
        self.assertNotIn("int rcvbuf_size = 256 * 1024", source)

    def test_scan_cannot_remain_permanently_active(self):
        source = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                  "com" / "brz" / "gauge" / "trips" / "TripSyncService.kt").read_text(encoding="utf-8")
        self.assertIn('state.firmwareUpdateStage == "checking"', source)
        self.assertIn("handler.postDelayed(firmwareScanTimeout, FIRMWARE_SCAN_TIMEOUT_MS)", source)
        self.assertIn("handler.removeCallbacks(firmwareScanTimeout)", source)
        self.assertIn("检查更新已超时", source)
        activity = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                    "com" / "brz" / "gauge" / "trips" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('state.firmwareUpdateStage == "checking" && !TripSyncService.running', activity)

    def test_scan_failure_never_reconnects_or_replays(self):
        source = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                  "com" / "brz" / "gauge" / "trips" / "TripSyncService.kt").read_text(encoding="utf-8")
        self.assertIn("firmwareInfoForConnection", source)
        self.assertIn("private fun startPendingFirmwareScan()", source)
        self.assertIn("private fun finishManifestReadFailure(reason: String)", source)
        self.assertIn("operation == TripBleProtocol.MANIFEST -> retryManifestOperation(message)", source)
        self.assertIn("else if (uuid == TripBleProtocol.MANIFEST)", source)
        self.assertIn('finishManifestReadFailure("固件信息处理异常（${error.javaClass.simpleName}）")', source)
        self.assertIn('Log.w(LOG_TAG, "Firmware cache rejected; using live manifest", error)', source)
        self.assertIn("private fun recordFirmwareUpdateSafely(", source)
        self.assertNotIn("continueAfterGattIdle { read(TripBleProtocol.MANIFEST) }\n            return\n        }\n        if (pendingBrightness", source)

    def test_device_manifest_uses_structured_json_not_regex(self):
        source = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                  "com" / "brz" / "gauge" / "trips" / "TripBleProtocol.kt").read_text(encoding="utf-8")
        parser = source.split("fun parseFirmwareInfo", 1)[1].split("fun otaWifiStartPacket", 1)[0]
        self.assertIn('jsonObject(root, "device")', parser)
        self.assertIn('jsonObject(root, "firmware")', parser)
        self.assertIn('jsonString(firmware, "version")', parser)
        self.assertNotIn("Regex(", parser)

    def test_update_ui_requires_parked_confirmation(self):
        source = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                  "com" / "brz" / "gauge" / "trips" / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("严禁在行驶中使用此功能", source)
        self.assertIn("已停车，开始检查", source)
        self.assertIn("已停车，确认更新", source)
        self.assertIn("选择历史版本回滚", source)
        self.assertIn("固件回滚风险提示", source)
        self.assertIn("已停车并了解风险，确认回滚", source)

    def test_rollback_is_explicitly_selected_and_never_used_by_normal_scan(self):
        embedded = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                    "com" / "brz" / "gauge" / "trips" / "EmbeddedFirmware.kt").read_text(encoding="utf-8")
        service = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                   "com" / "brz" / "gauge" / "trips" / "TripSyncService.kt").read_text(encoding="utf-8")
        self.assertIn("fun historical(context: Context)", embedded)
        self.assertIn("fun assessRollback(", embedded)
        self.assertIn("EXTRA_FIRMWARE_PACKAGE_ID", service)
        self.assertIn("pendingFirmwarePackageId", service)
        self.assertIn("EmbeddedFirmware.assessRollback(device, metadata)", service)
        scan = service.split("private fun finishFirmwareScan", 1)[1].split(
            "private fun startPendingFirmwareUpdate", 1)[0]
        self.assertIn("EmbeddedFirmware.metadata(this)", scan)
        self.assertNotIn("assessRollback", scan)

    def test_ota_is_manual_resumable_and_always_exits(self):
        activity = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                    "com" / "brz" / "gauge" / "trips" / "MainActivity.kt").read_text(encoding="utf-8")
        uploader = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                    "com" / "brz" / "gauge" / "trips" / "FirmwareWifiUploader.kt").read_text(encoding="utf-8")
        server = (ROOT / "main" / "app_obd_dsp" / "ota_wifi_server.c").read_text(encoding="utf-8")
        self.assertIn("手动进入 OTA 并更新", activity)
        self.assertIn("CHUNK_BYTES = 64 * 1024", uploader)
        self.assertIn('get(network, "/ota/status", authenticated = true)', uploader)
        self.assertIn('open(network, "/ota/cancel")', uploader)
        self.assertIn('"firmware waiting for resume"', server)
        self.assertIn('.uri = "/ota/cancel"', server)
        self.assertIn("OTA_ERROR_REBOOT_US", server)
        self.assertIn("esp_restart();", server)

    def test_ota_wifi_loss_reconnects_and_resumes_on_a_fresh_network(self):
        uploader = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                    "com" / "brz" / "gauge" / "trips" / "FirmwareWifiUploader.kt").read_text(encoding="utf-8")
        manifest = (ROOT / "android_app" / "app" / "src" / "main" /
                    "AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn("override fun onLost(network: Network)", uploader)
        self.assertIn("scheduleReconnect(\"更新热点连接短暂中断\")", uploader)
        self.assertIn("connectModern(RECOVERY_NETWORK_TIMEOUT_MS)", uploader)
        self.assertIn("var offset = readResumeOffset(network, bytes.size)", uploader)
        self.assertIn("ensureCurrentNetwork(network, generation)", uploader)
        self.assertIn("activeConnection?.let { runCatching { it.disconnect() } }", uploader)
        self.assertIn("WIFI_MODE_FULL_HIGH_PERF", uploader)
        self.assertIn("PowerManager.PARTIAL_WAKE_LOCK", uploader)
        self.assertIn("android.permission.WAKE_LOCK", manifest)
        self.assertNotIn("uploadStarted", uploader)

    def test_ota_handshake_recovers_without_blocking_gatt_callback(self):
        service = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                   "com" / "brz" / "gauge" / "trips" / "TripSyncService.kt").read_text(encoding="utf-8")
        uploader = (ROOT / "android_app" / "app" / "src" / "main" / "java" /
                    "com" / "brz" / "gauge" / "trips" / "FirmwareWifiUploader.kt").read_text(encoding="utf-8")
        ota = (ROOT / "main" / "app_obd_dsp" / "ota_update_ble.c").read_text(encoding="utf-8")
        control = ota.split("if (command == 4)", 1)[1].split("static void handle_status_cccd_write", 1)[0]
        self.assertIn('xTaskCreateWithCaps(wifi_start_task, "ota_wifi_start"', control)
        self.assertIn("MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT", control)
        self.assertNotIn("start_wifi_ota();", control)
        self.assertIn("duplicate ignored", control)
        start = ota.split("static void start_wifi_ota(void)\n{", 1)[1].split(
            "static void wifi_start_task", 1)[0]
        self.assertLess(start.index("ota_wifi_server_release_bt();"),
                        start.index("ota_wifi_server_start(&info, NULL)"))
        self.assertNotIn("notify_wifi_ready", start)
        self.assertIn("recoverOtaWifiAfterBleDrop", service)
        self.assertIn("FirmwareWifiUploader.recoveryInfo()", service)
        self.assertIn("otaStartWriteQueued", service)
        self.assertIn("otaWifiWorkerRetries", service)
        self.assertIn("wifi start task unavailable", service)
        self.assertIn("Never resend that", service)
        self.assertIn('get(network, "/ota/discover", authenticated = false)', uploader)
        self.assertIn("setSsidPattern", uploader)


if __name__ == "__main__":
    unittest.main()
