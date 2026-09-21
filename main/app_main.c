/*
 * SPDX-FileCopyrightText: 2023-2024 Espressif Systems (Shanghai) CO LTD
 *
 * SPDX-License-Identifier: CC0-1.0
 */
// Original author: Ray.Zhai
// Date: 2025-09-14
// Adapted for Waveshare ESP32-S3-Touch-LCD-1.85 by adaptation

#include <stdio.h>
#include <inttypes.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_task_wdt.h"
#include "esp_ota_ops.h"
#include "sdkconfig.h"

#include "lvgl.h"

/* Waveshare BSP drivers */
#if CONFIG_OBD_BOARD_AMOLED_175
#include "bsp/esp32_s3_touch_amoled_1_75.h"
#include "bsp/touch.h"
#else
#include "bsp_obd_dsp/exio/TCA9554PWR.h"
#include "bsp_obd_dsp/lcd_driver/ST77916.h"       // internally includes CST816.h & TCA9554PWR.h
#endif
#include "bsp_obd_dsp/i2c_driver/I2C_Driver.h"

/* Application layer */
#include "bsp_obd_dsp/bsp_board.h"
#include "bsp_obd_dsp/display_port.h"
#include "bsp_obd_dsp/elm327_ble_client.h"
#include "bsp_obd_dsp/espnow_link.h"
#include "bsp_obd_dsp/racechrono_ble_diy.h"
#include "app_obd_dsp/boot_media_mount.h"
#include "bsp_obd_dsp/rs485_brake_temp.h"
#include "bsp_obd_dsp/ads1115_oil_pressure.h"
#include "app_obd_dsp/obd_data_cache.h"
#include "app_obd_dsp/vehicle_profiles.h"
#include "export_path/ui_ext.h"
#include "app_obd_dsp/app_event.h"

// ===== Triple-gauge roles =====
// This BRZ build keeps the MULTI-GAUGE page visible but freezes its persisted role to MASTER.
// The macro below is only a development override: uncommenting it ignores that lock and forces this board to slave.
// #define ESPNOW_FORCE_SLAVE

static const char *TAG = "obd_dsp";

static void mark_app_valid_task(void *arg)
{
    (void)arg;

    vTaskDelay(pdMS_TO_TICKS(15000));

    esp_err_t err = esp_ota_mark_app_valid_cancel_rollback();
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "Current firmware marked valid");
    } else if (err != ESP_ERR_OTA_ROLLBACK_INVALID_STATE) {
        ESP_LOGW(TAG, "Failed to mark firmware valid: %s", esp_err_to_name(err));
    }

    vTaskDelete(NULL);
}

extern void ui_init(void);
SemaphoreHandle_t lvgl_mux = NULL; // non-static: used by BLE scan page

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////// LCD & LVGL configuration ///////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

#if CONFIG_OBD_BOARD_AMOLED_175
#define LCD_H_RES               BSP_LCD_H_RES           // 466
#define LCD_V_RES               BSP_LCD_V_RES           // 466
#define LCD_BIT_PER_PIXEL       BSP_LCD_BITS_PER_PIXEL  // 16
static esp_lcd_panel_handle_t s_panel_handle = NULL;
static esp_lcd_panel_io_handle_t s_panel_io_handle = NULL;
static esp_lcd_touch_handle_t s_touch_handle = NULL;
#define ACTIVE_PANEL_HANDLE     s_panel_handle
#define ACTIVE_TOUCH_HANDLE     s_touch_handle
#else
/* Resolution comes directly from the legacy ST77916 driver. */
#define LCD_H_RES               EXAMPLE_LCD_WIDTH       // 360
#define LCD_V_RES               EXAMPLE_LCD_HEIGHT      // 360
#define LCD_BIT_PER_PIXEL       EXAMPLE_LCD_COLOR_BITS  // 16
#define ACTIVE_PANEL_HANDLE     panel_handle
#define ACTIVE_TOUCH_HANDLE     tp
#endif

/* LVGL parameters */
#define LVGL_BUFF_SIZE              (LCD_H_RES * 20)
#define LVGL_TICK_PERIOD_MS         2
#define LVGL_TASK_MAX_DELAY_MS      500
#define LVGL_TASK_MIN_DELAY_MS      2
#define LVGL_TASK_STACK_SIZE        (8 * 1024)
#define LVGL_TASK_PRIORITY          4   // raised (was 2): less prone to dropping frames from preemption by BLE/OBD (priority 4) during animations

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////// LVGL callbacks /////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/* Notify LVGL on DMA transfer complete */
static bool notify_lvgl_flush_ready(esp_lcd_panel_io_handle_t panel_io,
                                     esp_lcd_panel_io_event_data_t *edata,
                                     void *user_ctx)
{
    lv_disp_drv_t *disp_driver = (lv_disp_drv_t *)user_ctx;
    lv_disp_flush_ready(disp_driver);
    return false;
}

/* LVGL flush callback */
static void lvgl_flush_cb(lv_disp_drv_t *drv, const lv_area_t *area, lv_color_t *color_map)
{
    esp_lcd_panel_handle_t panel = (esp_lcd_panel_handle_t)drv->user_data;
    esp_lcd_panel_draw_bitmap(panel, area->x1, area->y1,
                              area->x2 + 1, area->y2 + 1, color_map);
}

/* Coordinate alignment (even boundaries) */
static void lvgl_rounder_cb(lv_disp_drv_t *disp_drv, lv_area_t *area)
{
    area->x1 = (area->x1 >> 1) << 1;
    area->y1 = (area->y1 >> 1) << 1;
    area->x2 = ((area->x2 >> 1) << 1) + 1;
    area->y2 = ((area->y2 >> 1) << 1) + 1;
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////// Touch input callbacks //////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/* Touch read callback (polling mode) */
static void lvgl_touch_cb(lv_indev_drv_t *drv, lv_indev_data_t *data)
{
    esp_lcd_touch_handle_t touch = (esp_lcd_touch_handle_t)drv->user_data;
    assert(touch);

    uint16_t tp_x, tp_y;
    uint8_t tp_cnt = 0;

    esp_lcd_touch_read_data(touch);

    bool pressed = esp_lcd_touch_get_coordinates(touch, &tp_x, &tp_y, NULL, &tp_cnt, 1);
    if (pressed && tp_cnt > 0) {
        data->point.x = tp_x;
        data->point.y = tp_y;
        data->state = LV_INDEV_STATE_PRESSED;
    } else {
        data->state = LV_INDEV_STATE_RELEASED;
    }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////// LVGL timers & tasks ////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

static void increase_lvgl_tick(void *arg)
{
    lv_tick_inc(LVGL_TICK_PERIOD_MS);
}

static bool lvgl_lock(int timeout_ms)
{
    assert(lvgl_mux && "lvgl_mux not created");
    const TickType_t timeout_ticks = (timeout_ms == -1) ? portMAX_DELAY : pdMS_TO_TICKS(timeout_ms);
    return xSemaphoreTake(lvgl_mux, timeout_ticks) == pdTRUE;
}

static void lvgl_unlock(void)
{
    assert(lvgl_mux && "lvgl_mux not created");
    xSemaphoreGive(lvgl_mux);
}

static void lvgl_port_task(void *arg)
{
    uint32_t task_delay_ms = LVGL_TASK_MAX_DELAY_MS;
    while (1) {
        if (lvgl_lock(-1)) {
            task_delay_ms = lv_timer_handler();
            lvgl_unlock();
        }
        if (task_delay_ms > LVGL_TASK_MAX_DELAY_MS) {
            task_delay_ms = LVGL_TASK_MAX_DELAY_MS;
        } else if (task_delay_ms < LVGL_TASK_MIN_DELAY_MS) {
            task_delay_ms = LVGL_TASK_MIN_DELAY_MS;
        }
        vTaskDelay(pdMS_TO_TICKS(task_delay_ms));
    }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////// Main function ////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

void app_main(void)
{
    static lv_disp_draw_buf_t disp_buf;
    static lv_disp_drv_t disp_drv;

    /* 1. NVS init (must be first) */
    nvs_storage_init();

    /* 1.5 Task watchdog: 10s timeout, idle task not subscribed (avoids false triggers when BLE blocks) */
    esp_task_wdt_config_t wdt_cfg = {
        .timeout_ms = 10000,
        .idle_core_mask = 0,  // do not monitor idle task
        .trigger_panic = true,
    };
    esp_task_wdt_reconfigure(&wdt_cfg);

    /* 1.5 Vehicle profile init (loads the saved vehicle index from NVS) */
    const nvs_user_cfg_t *user_cfg = nvs_cfg_get();
    vehicle_profile_set_active(user_cfg->vehicle_profile_idx);
    const nvs_stat_t *stat = nvs_stat_get();
    ESP_LOGI("NVS", "cfg: proto=%u theme=%u profile=%u(%s) odo=%" PRIu64 " trip=%" PRIu64 " max=%d avg=%d run=%" PRIu64,
             user_cfg->protocol, user_cfg->theme_cfg.theme,
             user_cfg->vehicle_profile_idx, vehicle_profile_get_active()->name,
             stat->odometer_m, stat->trip_m, stat->max_speed_kmh, stat->avg_speed_kmh, stat->run_time_s);

    ESP_LOGI(TAG, "Board target: %s (%dx%d)", gauge_display_board_name(), LCD_H_RES, LCD_V_RES);

#if CONFIG_OBD_BOARD_LCD_185
    /* 2. I2C bus 0 init (used by the TCA9554 IO expander, SCL=10 SDA=11) */
    I2C_Init();

    /* 3. IO expander init (TCA9554PWR, I2C address 0x20) */
    EXIO_Init();

    /* 4. LCD + backlight + touch combined init
     *    LCD_Init() internally calls, in order:
     *      ST77916_Init() → TCA9554 EXIO2 reset → QSPI SPI bus & ST77916 panel driver
     *      Backlight_Init() → LEDC PWM backlight (GPIO 5)
     *      Touch_Init() → I2C_NUM_1 (SDA=1, SCL=3) CST816 touch driver
     *    After completion panel_handle / tp are both globally valid variables
     */
    LCD_SetFlushCallback(notify_lvgl_flush_ready, &disp_drv);
    LCD_Backlight = 0;  // set to 0 before LCD_Init to prevent Backlight_Init from lighting an uninitialized panel
    LCD_Init();
#else
    /* CO5300 AMOLED + CST9217 touch through Waveshare's maintained BSP.
     * The -B SKU uses the same PCB/BSP as the standard 1.75-inch board; the
     * suffix only denotes the supplied enclosure. */
    const bsp_display_config_t panel_cfg = {
        .max_transfer_sz = LCD_H_RES * 40 * (LCD_BIT_PER_PIXEL / 8) + 64,
    };
    ESP_ERROR_CHECK(bsp_display_new(&panel_cfg, &s_panel_handle, &s_panel_io_handle));

    /* The gauge is installed upside down in the vehicle.  Mirror both panel
     * axes for a 180-degree hardware rotation; this also covers the boot
     * artwork because every framebuffer write goes through the panel. */
    ESP_ERROR_CHECK(esp_lcd_panel_mirror(s_panel_handle, true, true));

    const esp_lcd_panel_io_callbacks_t io_cbs = {
        .on_color_trans_done = notify_lvgl_flush_ready,
    };
    ESP_ERROR_CHECK(esp_lcd_panel_io_register_event_callbacks(s_panel_io_handle, &io_cbs, &disp_drv));

    const bsp_display_cfg_t touch_cfg = {
        .touch_flags = {
            .swap_xy = 0,
            /* The CST9217 raw orientation is opposite the panel at 0 degrees.
             * Removing its original X/Y mirrors keeps touch aligned with the
             * panel after the 180-degree display rotation above. */
            .mirror_x = 0,
            .mirror_y = 0,
        },
    };
    ESP_ERROR_CHECK(bsp_touch_new(&touch_cfg, &s_touch_handle));
    I2C_SetBusHandle(bsp_i2c_get_handle());
    ESP_ERROR_CHECK(bsp_display_brightness_init());
    gauge_display_set_brightness(0);
#endif

    /* 5. LVGL init */
    lv_init();

    /* Allocate double buffers (DMA memory). Larger buffers -> full-screen render strips halved -> higher frame rate.
       Only affects LVGL render chunking, not the SPI single-transfer size (still chunked by max_transfer_sz), so no screen corruption.
       Falls back automatically to the original 20 lines when internal DMA RAM is insufficient, avoiding boot-time OOM. */
    size_t buf_px = LCD_H_RES * 40;
    lv_color_t *buf1 = heap_caps_malloc(buf_px * sizeof(lv_color_t), MALLOC_CAP_DMA);
    lv_color_t *buf2 = heap_caps_malloc(buf_px * sizeof(lv_color_t), MALLOC_CAP_DMA);
    if (!buf1 || !buf2) {
        heap_caps_free(buf1); heap_caps_free(buf2);
        buf_px = LVGL_BUFF_SIZE;   // fall back to 20 lines
        buf1 = heap_caps_malloc(buf_px * sizeof(lv_color_t), MALLOC_CAP_DMA);
        buf2 = heap_caps_malloc(buf_px * sizeof(lv_color_t), MALLOC_CAP_DMA);
    }
    assert(buf1 && buf2);
    lv_disp_draw_buf_init(&disp_buf, buf1, buf2, buf_px);

    /* Register display driver */
    lv_disp_drv_init(&disp_drv);
    disp_drv.hor_res = LCD_H_RES;
    disp_drv.ver_res = LCD_V_RES;
    disp_drv.flush_cb = lvgl_flush_cb;
    disp_drv.rounder_cb = lvgl_rounder_cb;
    disp_drv.draw_buf = &disp_buf;
    disp_drv.user_data = ACTIVE_PANEL_HANDLE;
    lv_disp_t *disp = lv_disp_drv_register(&disp_drv);

    /* LVGL tick timer (2ms period) */
    const esp_timer_create_args_t lvgl_tick_timer_args = {
        .callback = &increase_lvgl_tick,
        .name = "lvgl_tick"
    };
    esp_timer_handle_t lvgl_tick_timer = NULL;
    ESP_ERROR_CHECK(esp_timer_create(&lvgl_tick_timer_args, &lvgl_tick_timer));
    ESP_ERROR_CHECK(esp_timer_start_periodic(lvgl_tick_timer, LVGL_TICK_PERIOD_MS * 1000));

    /* Register touch input device (polling mode, uses the global tp created by Touch_Init) */
    static lv_indev_drv_t indev_drv;
    lv_indev_drv_init(&indev_drv);
    indev_drv.type = LV_INDEV_TYPE_POINTER;
    indev_drv.disp = disp;
    indev_drv.read_cb = lvgl_touch_cb;
    indev_drv.user_data = ACTIVE_TOUCH_HANDLE;
    lv_indev_drv_register(&indev_drv);

    /* 6. Start LVGL task */
    lvgl_mux = xSemaphoreCreateMutex();
    assert(lvgl_mux);
    static TaskHandle_t s_lvgl_task_handle = NULL;
    xTaskCreate(lvgl_port_task, "LVGL", LVGL_TASK_STACK_SIZE, NULL, LVGL_TASK_PRIORITY, &s_lvgl_task_handle);
    // exposed to ui.c for temporarily raising priority during flashing
    extern TaskHandle_t g_lvgl_task_handle;
    g_lvgl_task_handle = s_lvgl_task_handle;

    /* 7. Start UI */
    if (lvgl_lock(-1)) {
        ui_init();
        ui_ext_init();
        lvgl_unlock();
    }
    app_event_init();

    /* 7.5 Mount bootmedia SPIFFS early (saves ~300ms of black screen) */
    boot_media_mount();
    boot_media_recover_previous_if_needed();
    elm327_ble_ensure_stack_init();

    /* 8. Branch by role: master (connects to ELM327 for readings + ESP-NOW broadcast) / slave (only receives and displays the master's data) */
    uint8_t dev_role = user_cfg->device_role;
#ifdef ESPNOW_FORCE_SLAVE
    dev_role = ESPNOW_ROLE_SLAVE;   // step 1 test: force slave
#endif

    /* Build every local GATT service before connecting to ELM327. Creating a
       service after the outbound ELM link is live makes Bluedroid send a
       Service Changed indication to the adapter; common ELM clones never ACK
       it and are disconnected by the 45 s GATT response timeout. */
    if (dev_role != ESPNOW_ROLE_SLAVE) {
        racechrono_ble_diy_start(user_cfg->rc_enabled);
        uint32_t waited_ms = 0;
        while (!racechrono_ble_diy_services_ready() && waited_ms < 2500) {
            vTaskDelay(pdMS_TO_TICKS(20));
            waited_ms += 20;
        }
        if (!racechrono_ble_diy_services_ready()) {
            ESP_LOGW(TAG, "Phone GATT services not ready after %lu ms; continuing OBD startup",
                     (unsigned long)waited_ms);
        }
    }

    if (dev_role == ESPNOW_ROLE_SLAVE) {
        /* ---- Slave: does not connect to ELM327, only starts ESP-NOW receive; UI displays the data broadcast by the master ----
           Screen navigation (not bound to a master → BLE pairing page; bound → normal boot flow) is handled
           centrally by boot_enter_default_page() in ui.c; nothing to do here. */
        ESP_LOGI(TAG, "Device role: SLAVE (ESP-NOW receiver, no BLE/OBD)");
        espnow_link_start_slave();
        {
            const uint8_t *bm = espnow_link_get_bound_master_mac();
            ESP_LOGD(TAG, "Bound master MAC at boot: %02x:%02x:%02x:%02x:%02x:%02x (0=unbound)",
                     bm[0], bm[1], bm[2], bm[3], bm[4], bm[5]);
        }
    } else {
        /* ---- Master / standalone: both run the full BLE OBD chain; the only difference is whether ESP-NOW (=WiFi) starts ----
           STANDALONE: does not start WiFi/ESP-NOW; existing devices (already set to master/slave) are unaffected. */
        bool espnow_on = (dev_role == ESPNOW_ROLE_MASTER);
        ESP_LOGI(TAG, "Device role: %s (BLE/OBD%s)",
                 espnow_on ? "MASTER" : "STANDALONE",
                 espnow_on ? " + ESP-NOW broadcast" : ", no WiFi/ESP-NOW");

        /* 8.1 Start BLE OBD - auto-connect only when a MAC is already bound in NVS (exact MAC match only, no fuzzy name matching);
               legacy configs with a name but no MAC no longer auto-connect; the user must re-select on the scan page to bind the MAC.
               MASTER starts the Bluetooth stack even without a configured OBD device, so it can broadcast the SkyGauge pairing signal. */
        bool has_obd_mac = (user_cfg->ble_obd_mac[0] | user_cfg->ble_obd_mac[1] |
                            user_cfg->ble_obd_mac[2] | user_cfg->ble_obd_mac[3] |
                            user_cfg->ble_obd_mac[4] | user_cfg->ble_obd_mac[5]) != 0;
        if (has_obd_mac) {
            ESP_LOGD(TAG, "BLE target device: %s (mac=%02x:%02x:%02x:%02x:%02x:%02x, MAC-only match)",
                     user_cfg->ble_device_name,
                     user_cfg->ble_obd_mac[0], user_cfg->ble_obd_mac[1], user_cfg->ble_obd_mac[2],
                     user_cfg->ble_obd_mac[3], user_cfg->ble_obd_mac[4], user_cfg->ble_obd_mac[5]);
            elm327_ble_start_default(user_cfg->ble_device_name, user_cfg->ble_obd_mac);
        } else if (user_cfg->ble_device_name[0] != '\0') {
            ESP_LOGD(TAG, "Saved device '%s' has no bound MAC, auto-connect disabled; re-select it on BLE SCAN page to bind MAC",
                     user_cfg->ble_device_name);
            elm327_ble_ensure_stack_init();   // the stack must still be started for RaceChrono/the scan page
        } else if (espnow_on) {
            ESP_LOGD(TAG, "No saved BLE device, but MASTER needs BLE stack for SkyGauge pairing broadcast");
            elm327_ble_ensure_stack_init();
        } else {
            ESP_LOGD(TAG, "No saved BLE device; start BLE stack for phone time sync and device selection");
            elm327_ble_ensure_stack_init();
        }

        /* 9. Start RS485 brake temperature acquisition */
        rs485_brake_temp_start();

        /* 9.5 Start oil pressure acquisition (direct ESP32 ADC connection).
           Skip the external ADS1115 ADC for profiles that read oil pressure over OBD
           (e.g. Supra A90 — DID 4436, BMW E — DID 586F), to avoid the ADC overwriting OBD data. */
        {
            const vehicle_profile_t *vp = vehicle_profile_get_active();
            if (!(vp && vp->obd_oil_pressure_did != 0)) {
                oil_pressure_start();
            }
        }

        /* 9.8 Start ESP-NOW broadcast (sends this unit's OBD data cache to the slave) -- MASTER only;
               STANDALONE skips it; WiFi is never initialized (saves RF/power and does not interfere with BLE). */
        if (espnow_on) {
            espnow_link_start_master();
        }

        /* 10. Mileage statistics task (only the master counts, to avoid double counting by the slave) */
        vMileageDataStatisticTask();
    }

    BaseType_t valid_task_started = xTaskCreate(mark_app_valid_task, "ota_valid", 4096, NULL, tskIDLE_PRIORITY + 1, NULL);
    if (valid_task_started != pdPASS) {
        ESP_LOGW(TAG, "Failed to create OTA validity task");
    }
}
