#include "gnss_time.h"
#include "sdkconfig.h"

#if CONFIG_OBD_GNSS_TIME && CONFIG_OBD_BOARD_AMOLED_175
#include "gnss_nmea.h"
#include "bsp_obd_dsp/nvs_storage.h"
#include "bsp_obd_dsp/i2c_driver/I2C_Driver.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <string.h>

#define GNSS_UART UART_NUM_2
#define GNSS_RX GPIO_NUM_18
#define PROBE_US (6000000LL)
#define SILENCE_US (30000000LL)
#define I2C_TIMEOUT_MS 10
static const char *TAG = "gnss_time";
static bool s_started;

typedef struct {
    i2c_master_dev_handle_t command, data;
    bool uart;
    int baud;
    unsigned received, backlog_resets, read_errors, apply_retries;
    int last_apply_error;
    gnss_nmea_t parser;
    int64_t last_sentence_us;
} receiver_t;

static void close_receiver(receiver_t *r)
{
    if (r->command) i2c_master_bus_rm_device(r->command);
    if (r->data) i2c_master_bus_rm_device(r->data);
    if (r->uart) { uart_driver_delete(GNSS_UART); gpio_reset_pin(GNSS_RX); }
    memset(r, 0, sizeof(*r));
}

static bool open_i2c(receiver_t *r)
{
    i2c_master_bus_handle_t bus = I2C_GetBusHandle();
    /* Probe only the LC76G command address. Never reset the shared bus or
     * touch the expander: an ordinary -B has other devices on that bus. */
    if (!bus || i2c_master_probe(bus, 0x50, I2C_TIMEOUT_MS) != ESP_OK) return false;
    i2c_device_config_t cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = 0x50,
        .scl_speed_hz = 100000,
    };
    if (i2c_master_bus_add_device(bus, &cfg, &r->command) != ESP_OK) return false;
    cfg.device_address = 0x54;
    if (i2c_master_bus_add_device(bus, &cfg, &r->data) != ESP_OK) {
        close_receiver(r);
        return false;
    }
    return true;
}

/* LC76G length/data mailbox protocol from Waveshare 09_LC76G_I2C.
 * Read at most 64 bytes per transaction (~6 ms on the shared touch bus).
 * All protocol waits yield this task; no bus/storage lock spans a wait. */
static int read_i2c(receiver_t *r, uint8_t *buf, bool *backlog)
{
    uint8_t query[] = {0x08,0x00,0x51,0xAA,0x04,0,0,0}, size[4];
    if (i2c_master_transmit(r->command, query, sizeof(query), I2C_TIMEOUT_MS) != ESP_OK) return -1;
    vTaskDelay(pdMS_TO_TICKS(100));
    if (nvs_trip_time_is_valid()) return 0;
    if (i2c_master_receive(r->data, size, sizeof(size), I2C_TIMEOUT_MS) != ESP_OK) return -1;
    uint32_t available = (uint32_t)size[0] | (uint32_t)size[1] << 8 |
                         (uint32_t)size[2] << 16 | (uint32_t)size[3] << 24;
    if (!available) return 0;
    if (available > 16384) return -1;
    unsigned count = available > 512 ? 512 : available;
    *backlog = available > 512;
    uint8_t request[] = {0x00,0x20,0x51,0xAA,(uint8_t)count,(uint8_t)(count >> 8),0,0};
    vTaskDelay(pdMS_TO_TICKS(100));
    if (nvs_trip_time_is_valid()) return 0;
    if (i2c_master_transmit(r->command, request, sizeof(request), I2C_TIMEOUT_MS) != ESP_OK) return -1;
    vTaskDelay(pdMS_TO_TICKS(100));
    for (unsigned off = 0; off < count; ) {
        if (nvs_trip_time_is_valid()) return 0;
        unsigned n = count - off > 64 ? 64 : count - off;
        if (i2c_master_receive(r->data, buf + off, n, I2C_TIMEOUT_MS) != ESP_OK) return -1;
        off += n;
        vTaskDelay(1);
    }
    return (int)count;
}

static bool open_uart(receiver_t *r, int baud)
{
    /* UART1 belongs to brake temperature, UART0 to the console. Passive RX
     * probing never drives GPIO17 or sends commands to an unknown peripheral. */
    if (uart_is_driver_installed(GNSS_UART) ||
        CONFIG_OBD_RS485_TX_GPIO == GNSS_RX || CONFIG_OBD_RS485_RX_GPIO == GNSS_RX ||
        CONFIG_OBD_RS485_DE_RE_GPIO == GNSS_RX) return false;
    uart_config_t cfg = {.baud_rate = baud, .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE, .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE, .source_clk = UART_SCLK_DEFAULT};
    if (uart_driver_install(GNSS_UART, 2048, 0, 0, NULL, 0) != ESP_OK) return false;
    r->uart = true;
    r->baud = baud;
    if (uart_param_config(GNSS_UART, &cfg) != ESP_OK ||
        uart_set_pin(GNSS_UART, UART_PIN_NO_CHANGE, GNSS_RX,
                     UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE) != ESP_OK) {
        close_receiver(r);
        return false;
    }
    ESP_LOGI(TAG, "Probe UART RX=18 baud=%d", baud);
    gpio_set_pull_mode(GNSS_RX, GPIO_PULLUP_ONLY);
    uart_flush_input(GNSS_UART);
    return true;
}

/* Bounded summary; no raw coordinates, allocations, or per-sentence logging.
 * Parser counters reset when a corrupt/backlogged stream is discarded. */
static void log_diagnostic(const receiver_t *r)
{
    const gnss_nmea_t *s = &r->parser;
    ESP_LOGI(TAG, "diag %s/%d rx=%u nmea=%u bad=%u malformed=%u last=%s rmc=%u status=%c mode=%c utc=%s date=%s reason=%s backlog=%u ioerr=%u retries=%u err=%d",
        r->uart ? "UART" : "I2C", r->baud, r->received, s->sentences, s->bad_checksum, s->malformed,
        s->last_type[0] ? s->last_type : "-", s->rmc_count, s->status ? s->status : '-', s->mode ? s->mode : '-',
        s->utc[0] ? s->utc : "-", s->date[0] ? s->date : "-", s->reason ? s->reason : "await-rmc",
        r->backlog_resets, r->read_errors, r->apply_retries, r->last_apply_error);
}

static void listen(receiver_t *r)
{
    uint8_t buf[512];
    int64_t start = esp_timer_get_time();
    int64_t last_diag = start;
    bool present = false;
    unsigned failures = 0;
    while (!nvs_trip_time_is_valid()) {
        int64_t now = esp_timer_get_time();
        if (now - last_diag >= 10000000LL) {
            log_diagnostic(r);
            last_diag = now;
        }
        if ((!present && now - start > PROBE_US) ||
            (present && now - r->last_sentence_us > SILENCE_US)) break;
        bool backlog = false;
        int count;
        if (r->uart) {
            size_t buffered = 0;
            uart_get_buffered_data_len(GNSS_UART, &buffered);
            if (buffered > sizeof(buf)) {
                ++r->backlog_resets;
                uart_flush_input(GNSS_UART);
                memset(&r->parser, 0, sizeof(r->parser));
                vTaskDelay(pdMS_TO_TICKS(20));
                continue;
            }
            count = uart_read_bytes(GNSS_UART, buf, sizeof(buf), pdMS_TO_TICKS(20));
        } else count = read_i2c(r, buf, &backlog);
        if (count < 0) {
            ++r->read_errors;
            memset(&r->parser, 0, sizeof(r->parser));
            if (++failures >= 3) break;
        } else {
            failures = 0;
            r->received += (unsigned)count;
            /* Discard old mailbox data, so an old fix cannot set today's clock. */
            if (backlog) { ++r->backlog_resets; memset(&r->parser, 0, sizeof(r->parser)); }
            else for (int i = 0; i < count && !nvs_trip_time_is_valid(); ++i) {
                uint64_t epoch = 0;
                gnss_nmea_result_t result = gnss_nmea_feed(&r->parser, buf[i], esp_timer_get_time(), &epoch);
                if (result != GNSS_NMEA_NONE) {
                    if (!present) ESP_LOGI(TAG, "GNSS detected (%s); background acquisition", r->uart ? "UART" : "I2C");
                    present = true;
                    r->last_sentence_us = esp_timer_get_time();
                }
                if (result == GNSS_NMEA_TIME) {
                    /* A busy recorder wins: no wait for its mutex. Retry with
                     * a NEW sentence rather than applying a stale queued time. */
                    esp_err_t err = nvs_trip_apply_gnss_time(epoch, NULL);
                    if (err == ESP_OK) {
                        log_diagnostic(r);
                        ESP_LOGI(TAG, "GNSS time accepted; acquisition complete for this boot");
                        return;
                    }
                    ++r->apply_retries;
                    r->last_apply_error = (int)err;
                }
            }
        }
        vTaskDelay(pdMS_TO_TICKS(r->uart ? 20 : 50));
    }
    log_diagnostic(r);
}

static void gnss_task(void *arg)
{
    (void)arg;
    receiver_t r = {0};
    /* Core startup has completed before creating this low-priority task. */
    vTaskDelay(pdMS_TO_TICKS(2000));
    if (!nvs_trip_time_is_valid() && open_i2c(&r)) {
        listen(&r);
        close_receiver(&r);
    }
    const int bauds[] = {9600,115200};
    for (unsigned i = 0; i < sizeof(bauds) / sizeof(bauds[0]) && !nvs_trip_time_is_valid(); ++i) {
        if (open_uart(&r, bauds[i])) { listen(&r); close_receiver(&r); }
    }
    ESP_LOGI(TAG, "GNSS task finished (%s)", nvs_trip_time_is_valid() ? "clock synchronized" : "no usable receiver");
    vTaskDelete(NULL);
}

void gnss_time_start(void)
{
    if (s_started || nvs_trip_time_is_valid()) return;
    s_started = true;
    /* GNSS is optional; never consume the reserve needed by BLE/OBD. */
    if (heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT) < 40000 ||
        heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT) < 8192) {
        ESP_LOGW(TAG, "Skipping GNSS: preserve recorder/radio heap");
        return;
    }
    if (xTaskCreate(gnss_task, "gnss_time", 4096, NULL, tskIDLE_PRIORITY + 1, NULL) != pdPASS)
        ESP_LOGW(TAG, "Skipping GNSS: task allocation failed");
}
#else
void gnss_time_start(void) {}
#endif
