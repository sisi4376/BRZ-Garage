#include "device_identity.h"

#include <stdio.h>
#include <string.h>

#include "sdkconfig.h"
#if CONFIG_OBD_BOARD_AMOLED_175
#include "bsp/esp32_s3_touch_amoled_1_75.h"
#else
#include "bsp_obd_dsp/lcd_driver/ST77916.h"
#endif
#include "esp_app_format.h"
#include "esp_partition.h"
#include "esp_ota_ops.h"

#ifndef OBD_GAUGE_GIT_HASH
#define OBD_GAUGE_GIT_HASH "unknown"
#endif

#ifndef OBD_GAUGE_GIT_BRANCH
#define OBD_GAUGE_GIT_BRANCH "unknown"
#endif

#ifndef OBD_GAUGE_GIT_COUNT
#define OBD_GAUGE_GIT_COUNT 0
#endif

#ifndef OBD_GAUGE_BUILD_TAG
#define OBD_GAUGE_BUILD_TAG "local"
#endif

#ifndef OBD_GAUGE_FIRMWARE_VERSION
#define OBD_GAUGE_FIRMWARE_VERSION "3.2.9"
#endif

#if CONFIG_OBD_BOARD_AMOLED_175
#define OBD_GAUGE_BOARD_NAME        "Waveshare ESP32-S3-Touch-AMOLED-1.75-B"
#define OBD_GAUGE_BOARD_VARIANT     "obd_brz_gauge_amoled175"
#define OBD_GAUGE_LCD_NAME          "CO5300"
#define OBD_GAUGE_SCREEN_WIDTH      BSP_LCD_H_RES
#define OBD_GAUGE_SCREEN_HEIGHT     BSP_LCD_V_RES
#define OBD_GAUGE_COLOR_BITS        BSP_LCD_BITS_PER_PIXEL
#else
#define OBD_GAUGE_BOARD_NAME        "Waveshare ESP32-S3-Touch-LCD-1.85"
#define OBD_GAUGE_BOARD_VARIANT     "obd_brz_gauge"
#define OBD_GAUGE_LCD_NAME          "ST77916"
#define OBD_GAUGE_SCREEN_WIDTH      EXAMPLE_LCD_WIDTH
#define OBD_GAUGE_SCREEN_HEIGHT     EXAMPLE_LCD_HEIGHT
#define OBD_GAUGE_COLOR_BITS        EXAMPLE_LCD_COLOR_BITS
#endif
#define OBD_GAUGE_FLASH_MB          16u
#define OBD_GAUGE_PSRAM_MB          8u
#define OBD_GAUGE_OTA_SLOTS         2u
#define OBD_GAUGE_BOOTMEDIA_SLOTS   1u
#define OBD_GAUGE_BOOTMEDIA_FORMAT   1u

static const device_identity_t s_identity = {
    .board_name = OBD_GAUGE_BOARD_NAME,
    .board_variant = OBD_GAUGE_BOARD_VARIANT,
    .lcd_name = OBD_GAUGE_LCD_NAME,
    .screen_width = OBD_GAUGE_SCREEN_WIDTH,
    .screen_height = OBD_GAUGE_SCREEN_HEIGHT,
    .color_bits = OBD_GAUGE_COLOR_BITS,
    .flash_mb = OBD_GAUGE_FLASH_MB,
    .psram_mb = OBD_GAUGE_PSRAM_MB,
    .ota_slots = OBD_GAUGE_OTA_SLOTS,
    .bootmedia_slots = OBD_GAUGE_BOOTMEDIA_SLOTS,
    .bootmedia_format_version = OBD_GAUGE_BOOTMEDIA_FORMAT,
};

static char s_manifest_json[512];

const device_identity_t *device_identity_get(void)
{
    return &s_identity;
}

const char *device_identity_manifest_json(void)
{
    if (s_manifest_json[0] != '\0') {
        return s_manifest_json;
    }

    const esp_app_desc_t *app = esp_app_get_description();
    const esp_partition_t *running = esp_ota_get_running_partition();

    const char *project_name = (app && app->project_name[0] != '\0') ? app->project_name : s_identity.board_variant;
    const char *project_version = (app && app->version[0] != '\0') ? app->version : OBD_GAUGE_FIRMWARE_VERSION;
    const char *running_label = running ? running->label : "unknown";
    const char *idf_version = (app && app->idf_ver[0] != '\0') ? app->idf_ver : "unknown";

    snprintf(s_manifest_json, sizeof(s_manifest_json),
             "{"
             "\"device\":{"
             "\"board\":\"%s\","
             "\"variant\":\"%s\","
             "\"lcd\":\"%s\","
             "\"screen\":{\"w\":%u,\"h\":%u,\"bpp\":%u},"
             "\"flash_mb\":%u,"
             "\"psram_mb\":%u,"
             "\"ota_slots\":%u,"
             "\"bootmedia_slots\":%u,"
             "\"bootmedia_format\":%u"
             "},"
             "\"firmware\":{"
             "\"project\":\"%s\","
             "\"version\":\"%s\","
             "\"build_tag\":\"%s\","
             "\"git\":\"%s\","
             "\"branch\":\"%s\","
             "\"count\":%u,"
             "\"built\":\"%s %s\","
             "\"idf\":\"%s\","
             "\"slot\":\"%s\""
             "}"
             "}",
             s_identity.board_name,
             s_identity.board_variant,
             s_identity.lcd_name,
             s_identity.screen_width,
             s_identity.screen_height,
             s_identity.color_bits,
             s_identity.flash_mb,
             s_identity.psram_mb,
             s_identity.ota_slots,
             s_identity.bootmedia_slots,
             s_identity.bootmedia_format_version,
             project_name,
             project_version,
             OBD_GAUGE_BUILD_TAG,
             OBD_GAUGE_GIT_HASH,
             OBD_GAUGE_GIT_BRANCH,
             (unsigned)OBD_GAUGE_GIT_COUNT,
             __DATE__,
             __TIME__,
             idf_version,
             running_label);

    s_manifest_json[sizeof(s_manifest_json) - 1] = '\0';
    return s_manifest_json;
}
