#include <assert.h>
#include <stdatomic.h>
#include <string.h>
#include "ota_wifi_server.h"

#define ESP_OK 0
#define ESP_ERR_WIFI_NOT_INIT 1
#define ESP_ERR_WIFI_NOT_STARTED 2
#define WIFI_MODE_NULL 0
#define ESP_LOGE(...) ((void)0)
#define ESP_LOGW(...) ((void)0)
#define ESP_LOGI(...) ((void)0)
#define pdMS_TO_TICKS(ms) (ms)

typedef int esp_err_t;
static _Atomic(ota_wifi_state_t) s_state;
static ota_wifi_status_cb_t s_status_cb;
static atomic_uint s_last_activity_ms;
static atomic_bool s_finishing;
static void *s_httpd, *s_ap_netif;
static bool s_wifi_owned;
static int s_prev_wifi_mode;
static char s_token[2], s_password[2], s_ap_ssid[2];
static struct { uint32_t received_size, expected_size; } s_recv;
static uint64_t now_ms, last_data_ms;
static unsigned joins, releases, restarts, reports;
static bool fail_join, receive_during_poll;

static int64_t esp_timer_get_time(void) { return (int64_t)now_ms * 1000; }
static int esp_wifi_set_mode(int mode) { (void)mode; return ESP_OK; }
static void esp_netif_destroy_default_wifi(void *netif) { (void)netif; }
static int esp_wifi_stop(void) { return ESP_OK; }
static int esp_wifi_deinit(void) { return ESP_OK; }
static void rs485_brake_temp_resume(void) {}
static void vTaskDelete(void *task) { (void)task; }
static void esp_restart(void) { ++restarts; }
static void vTaskDelay(unsigned ms);
static int httpd_stop(void *server);
static void recv_reset(void) { assert(joins == 1 && !s_httpd); ++releases; }

#include "ota_functions.inc"

static int httpd_stop(void *server)
{
    assert(server && !releases);
    ++joins;
    /* A second caller cannot tear down the same transfer even mid-join. */
    assert(!ota_wifi_server_stop());
    record_received_bytes(s_recv.received_size + 100);
    assert(releases == 0);
    return fail_join ? -1 : ESP_OK;
}

static void progress(ota_wifi_state_t state, const char *message, uint32_t received, uint32_t expected)
{
    (void)message;
    assert(state == OTA_WIFI_STATE_RECEIVING && received <= expected);
    ++reports;
}

static void vTaskDelay(unsigned ms)
{
    now_ms += ms;
    if (ms == 2000 && receive_during_poll && s_recv.received_size < 1614000) {
        record_received_bytes(s_recv.received_size + 4096);
        assert(atomic_load(&s_last_activity_ms) == (uint32_t)now_ms);
        last_data_ms = now_ms;
    }
}

int main(void)
{
    /* The observed partial request stopped at 98,656. Advance the watchdog
     * across both three minutes of live traffic and a 32-bit clock rollover. */
    now_ms = UINT32_MAX - 10000;
    s_recv.received_size = 98656;
    s_recv.expected_size = 2167104;
    s_state = OTA_WIFI_STATE_RECEIVING;
    s_status_cb = progress;
    s_httpd = &s_recv;
    record_activity();
    receive_during_poll = true;
    ota_timeout_task(NULL);
    assert(last_data_ms > (uint64_t)UINT32_MAX + OTA_IDLE_TIMEOUT_MS);
    assert(now_ms - last_data_ms > OTA_IDLE_TIMEOUT_MS);
    assert(now_ms - last_data_ms <= OTA_IDLE_TIMEOUT_MS + 2250);
    assert(reports == s_recv.received_size / OTA_PROGRESS_STEP - 98656 / OTA_PROGRESS_STEP);
    assert(joins == 1 && releases == 1 && restarts == 1);
    assert(!ota_wifi_server_stop());

    joins = releases = 0;
    s_state = OTA_WIFI_STATE_RECEIVING;
    s_httpd = &s_recv;
    /* An installer already owns shutdown. */
    assert(!ota_wifi_server_stop() && joins == 0 && releases == 0);
    atomic_store(&s_finishing, false);
    fail_join = true;
    assert(ota_wifi_server_stop());
    assert(joins == 1 && releases == 0 && s_httpd);
    return 0;
}
