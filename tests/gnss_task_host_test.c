/* Execute the production task against deterministic UART/I2C/RTOS mocks. */
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "app_obd_dsp/gnss_nmea.h"
#define UART_NUM_2 2
#define GPIO_NUM_18 18
#define GPIO_PULLUP_ONLY 1
#define CONFIG_OBD_RS485_TX_GPIO 13
#define CONFIG_OBD_RS485_RX_GPIO 12
#define CONFIG_OBD_RS485_DE_RE_GPIO -1
#define UART_DATA_8_BITS 8
#define UART_PARITY_DISABLE 0
#define UART_STOP_BITS_1 1
#define UART_HW_FLOWCTRL_DISABLE 0
#define UART_SCLK_DEFAULT 0
#define UART_PIN_NO_CHANGE -1
#define I2C_ADDR_BIT_LEN_7 0
#define MALLOC_CAP_INTERNAL 1
#define MALLOC_CAP_8BIT 2
#define pdMS_TO_TICKS(x) (x)
#define pdPASS 1
#define tskIDLE_PRIORITY 0
#define ESP_OK 0
#define ESP_LOGI(tag, ...) do { (void)(tag); if (0) printf(__VA_ARGS__); } while (0)
#define ESP_LOGW ESP_LOGI
typedef int esp_err_t;
typedef int i2c_master_bus_handle_t;
typedef int i2c_master_dev_handle_t;
typedef struct { int dev_addr_length, device_address, scl_speed_hz; } i2c_device_config_t;
typedef struct { int baud_rate, data_bits, parity, stop_bits, flow_ctrl, source_clk; } uart_config_t;
static int mode, baud, installed, handles, starts, deleted, applies, attempts, last_second;
static int writes, small_heap;
static bool valid;
static int64_t now_us, record_ms;
static char fifo[256];
static unsigned fifo_size, fifo_offset;
static bool reading_length;
static int64_t esp_timer_get_time(void) { return now_us; }
static bool nvs_trip_time_is_valid(void) { return valid; }
static void vTaskDelay(int ticks) {
    now_us += (int64_t)ticks * 1000; record_ms += ticks;
    if (mode == 4 && now_us >= 14000000) valid = true;
    assert(now_us < 60000000);
}
static void vTaskDelete(void *task) { assert(!task); ++deleted; }
static int xTaskCreate(void (*fn)(void *), const char *name, int size, void *arg, int priority, void *handle) {
    (void)fn; (void)name; (void)arg; (void)handle;
    assert(size == 4096 && priority == 1); ++starts; return pdPASS;
}
static size_t heap_caps_get_free_size(int caps) { (void)caps; return small_heap ? 10000 : 60000; }
static size_t heap_caps_get_largest_free_block(int caps) { (void)caps; return 20000; }
static esp_err_t nvs_trip_apply_gnss_time(uint64_t epoch, void *result) {
    (void)result; assert(!valid && epoch >= 1791763200);
    if (++attempts == 1) return 3;
    ++applies; valid = true; return ESP_OK;
}
static int uart_is_driver_installed(int port) { assert(port == 2); return installed; }
static int uart_driver_install(int p, int rx, int tx, int q, void *queue, int flags) {
    assert(p == 2 && rx == 2048 && !tx && !q && !queue && !flags && !installed);
    installed = 1; return 0;
}
static int uart_driver_delete(int p) { assert(p == 2 && installed); installed = 0; return 0; }
static int uart_param_config(int p, const uart_config_t *cfg) { assert(p == 2); baud = cfg->baud_rate; return 0; }
static int uart_set_pin(int p, int tx, int rx, int rts, int cts) {
    assert(p == 2 && tx == -1 && rx == 18 && rts == -1 && cts == -1); return 0;
}
static void gpio_reset_pin(int pin) { assert(pin == 18); }
static void gpio_set_pull_mode(int pin, int pull) { assert(pin == 18 && pull == 1); }
static void uart_flush_input(int p) { assert(p == 2); }
static int uart_get_buffered_data_len(int p, size_t *n) { assert(p == 2); *n = 0; return 0; }
static void make_sentence(void) {
    int second = now_us / 1000000;
    if (second == last_second) return;
    last_second = second;
    char body[160];
    char status = mode == 4 || second < 9 ? 'V' : 'A';
    snprintf(body, sizeof(body), "GNRMC,0000%02d.00,%c,1,N,1,E,0,0,121026,,,%c", second, status, status == 'A' ? 'A' : 'N');
    unsigned checksum = 0;
    for (char *p = body; *p; ++p) checksum ^= (unsigned char)*p;
    fifo_size = snprintf(fifo, sizeof(fifo), "$%s*%02X\r\n", body, checksum);
    fifo_offset = 0;
}
static int uart_read_bytes(int p, void *buf, unsigned size, int ticks) {
    assert(p == 2 && size == 512); vTaskDelay(ticks);
    if (!((mode == 1 || mode == 4) && baud == 9600) && !(mode == 2 && baud == 115200)) return 0;
    make_sentence();
    unsigned count = fifo_size - fifo_offset;
    memcpy(buf, fifo + fifo_offset, count); fifo_offset += count;
    return count;
}
static i2c_master_bus_handle_t I2C_GetBusHandle(void) { return 1; }
static int i2c_master_probe(int bus, int addr, int timeout) {
    assert(bus == 1 && addr == 0x50 && timeout == 10); return mode == 3 || mode == 5 ? 0 : -1;
}
static int i2c_master_bus_add_device(int bus, const i2c_device_config_t *cfg, int *dev) {
    assert(bus == 1 && cfg->scl_speed_hz == 100000); *dev = cfg->device_address; ++handles; return 0;
}
static void i2c_master_bus_rm_device(int dev) { assert(dev == 0x50 || dev == 0x54); --handles; }
static int i2c_master_transmit(int dev, const uint8_t *data, unsigned n, int timeout) {
    assert(dev == 0x50 && n == 8 && timeout == 10); ++writes;
    if (mode == 5) return -1;
    assert(data[2] == 0x51 && data[3] == 0xAA);
    reading_length = data[0] == 8;
    if (reading_length) make_sentence();
    return 0;
}
static int i2c_master_receive(int dev, uint8_t *buf, unsigned n, int timeout) {
    assert(dev == 0x54 && timeout == 10);
    if (reading_length) {
        assert(n == 4); unsigned count = fifo_size - fifo_offset;
        buf[0] = count; buf[1] = count >> 8; buf[2] = buf[3] = 0;
    } else {
        assert(n <= 64 && fifo_offset + n <= fifo_size);
        memcpy(buf, fifo + fifo_offset, n); fifo_offset += n;
    }
    return 0;
}
#include "gnss_task_body.inc"

int main(void)
{
    for (mode = 0; mode <= 5; ++mode) {
        valid = false; now_us = record_ms = 0; last_second = -1;
        applies = attempts = deleted = writes = 0; fifo_size = fifo_offset = 0;
        gnss_task(NULL);
        assert(!installed && !handles && deleted == 1);
        assert(record_ms == now_us / 1000 && record_ms >= 2000);
        if (mode == 1 || mode == 2 || mode == 3) assert(applies == 1 && valid && attempts == 2);
        else assert(!applies);
        if (mode == 0) assert(now_us < 15000000 && !writes && !valid);
        if (mode == 4) assert(valid && now_us < 15000000);
        if (mode == 5) assert(writes == 3 && !valid);
    }
    valid = true; gnss_time_start(); assert(!starts);
    valid = false; small_heap = 1; gnss_time_start(); assert(!starts);
    small_heap = 0; s_started = false;
    gnss_time_start(); gnss_time_start(); assert(starts == 1);
    puts("PASS: absent hardware, both UART baud rates, onboard I2C, busy recorder, phone cancellation, failed I2C, heap reserve and cleanup");
}
