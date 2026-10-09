// test_zc6_pid.py injects selected production functions/blocks at the markers.
// Hardware calls are mocked; parser, oil callback and cache execute real C.
#include <assert.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include "app_obd_dsp/vehicle_profiles.h"
#include "app_obd_dsp/vehicle_custom_config.h"
#include "app_obd_dsp/zc6_pid.h"
#include "bsp_obd_dsp/espnow_link.h"
#include "app_obd_dsp/perf_monitor.h"
#include "esp_timer.h"
#define ESP_LOGD(...) ((void)0)
#define ESP_LOGW(...) ((void)0)
#define pdTRUE 1
#define eNoAction 0
#define ACCUM_BUF_SIZE 512
#define MASTER_NAME_LEN 12
#define APP_EVT_ESPNOW_SYNC_SLOT 1
#define APP_EVT_ESPNOW_INTRO_STEP 2
static bool s_rx_linktest;
static bool s_rx_zc6_pid, s_linktest_active;
static uint32_t s_tx_seq;
static const char MASTER_NAME[] = "SkyGauge";
#define ESPNOW_MAGIC 0x4F42
#define ESPNOW_VER 6
static char s_master_name[MASTER_NAME_LEN];
static void app_event_send(int event, int value) { (void)event; (void)value; }
/* ESPNOW_PACKET */
TickType_t test_now;
static int active = 1;
#define s_active_idx active
static bool s_ranges_dirty;
typedef struct { uint8_t vehicle_profile_idx; } nvs_user_cfg_t;
static nvs_user_cfg_t test_cfg;
static const nvs_user_cfg_t *nvs_cfg_get(void) { return &test_cfg; }
static void nvs_cfg_set(const nvs_user_cfg_t *cfg) { test_cfg = *cfg; }
static int8_t s_oil_temp_offset;
static uint8_t s_oil_query_mode;
static int64_t s_can_oil_last_us;
static struct { int mode2_ok, mode2_fail, last_raw_temp, last_filtered_temp; } s_oil_diag;
/* PROFILE_TABLE */
/* PROFILE_SETTER */
/* ELM_GLOBALS */
static bool s_connected = true, s_elm_ready = true, s_expect_mode21;
static bool elm327_ble_is_connected(void) { return s_connected; }
static int ui_sweep_get_step(void) { return 0; }
static int ui_intro_get_step(void) { return 0; }
static char s_accum_buf[ACCUM_BUF_SIZE];
static size_t s_accum_len;
static int64_t s_accum_start_us;
static bool s_accum_discard;
static int s_protocol_detect_idx = -1;
static TaskHandle_t s_poll_task_handle = (void *)1;
static int expected_at_wake = -1000, wakes, parsed_ok, parsed_fail, valid_count;
static bool complete_previous, send_ok = true;
static char sent[32];
static void receive(const char *data, size_t n);

const vehicle_profile_t *vehicle_profile_get_active(void) { return &s_profiles[active]; }
const oil_temp_strategy_t *vehicle_profile_get_oil_temp_strategy(void)
{ return &s_profiles[active].oil_temp_strategy; }
const vehicle_override_t *vehicle_profile_get_override(void)
{
    for (unsigned i=0; i<sizeof(s_vehicle_overrides)/sizeof(s_vehicle_overrides[0]); ++i)
        if (!strcmp(s_vehicle_overrides[i].match_name, s_profiles[active].name))
            return &s_vehicle_overrides[i];
    return NULL;
}
float vehicle_profile_calc_constant(const vehicle_profile_t *p)
{ return 1.0f / (0.377f * p->tire_rolling_radius_m); }
void nvs_stat_update_speed(uint8_t v, uint32_t d) { (void)v; (void)d; }
void nvs_fuel_update(const fuel_sample_t *s, uint32_t d) { (void)s; (void)d; }
int nvs_fuel_save(void) { return 0; }
static inline int16_t oil_f2i(float f) { return (int16_t)(f + 0.5f); }
static void xTaskNotify(TaskHandle_t task, int value, int action)
{
    (void)task; (void)value; (void)action;
    assert(s_elm_ready);
    if (zc6_response_guarded()) assert(s_accum_len == 0 && !s_accum_discard);
    if (expected_at_wake != -1000) assert(obd_data_get_oil_temp() == expected_at_wake);
    ++wakes;
}
static void ulTaskNotifyTake(int clear, TickType_t ticks)
{
    (void)clear; (void)ticks;
    if (complete_previous) { complete_previous = false; receive(">", 1); }
}
static void esp_task_wdt_reset(void) {}
bool elm327_ble_send_command(const uint8_t *bytes, size_t n)
{
    assert(n < sizeof(sent)); memcpy(sent, bytes, n); sent[n] = 0;
    if (!send_ok) s_elm_ready = true;
    return send_ok;
}
static void record_oil_temp_success(oil_temp_query_mode_t mode)
{ assert(mode == OIL_TEMP_MODE_TOYOTA_21_01); ++parsed_ok; }
static void record_oil_temp_failure(oil_temp_query_mode_t mode)
{ assert(mode == OIL_TEMP_MODE_TOYOTA_21_01); ++parsed_fail; }
static void mark_obd_data_valid(void) { ++valid_count; }
/* ELM_FUNCTIONS */
/* ESPNOW_APPLY */
/* ESPNOW_MASTER */
static struct { void (*on_parsed_oil_temp)(uint32_t); } s_cbs = { default_on_parsed_oil_temp };
static void receive(const char *data, size_t length)
{
    const bool guarded = zc6_response_guarded();
    const uint8_t *v = (const uint8_t *)data;
    int n = (int)length;
    default_on_raw_notify(v, length);
    /* ELM_ACCUMULATE */
    char *buf = s_accum_buf;
    /* ELM_DISPATCH */
    finish_elm_response(guarded);
}
static void init_profile(int index)
{
    vehicle_profile_set_active((uint8_t)index);
    s_elm_profile = vehicle_profile_get_active();
    s_accept_obd_responses = true;
    s_zc6_request = vehicle_profile_is_zc6_pid(s_elm_profile);
    s_profile_transition = false;
    init_oil_temp_strategy();
    obd_data_reset_temp_cache();
}
static void expect_oil(int expected)
{
    obd_data_snapshot_t snapshot;
    obd_data_get_snapshot(&snapshot);
    assert(snapshot.oil_temp == expected);
    assert(obd_data_get_oil_temp() == expected);
}
static size_t make_packet(char *out, unsigned raw)
{
    int pos = sprintf(out, "028\r0: 61 01"), row = 1;
    for (int i=0; i<38; ++i) {
        if (i==4 || (i>4 && (i-4)%7==0)) pos += sprintf(out+pos, "\r%X:", row++);
        pos += sprintf(out+pos, " %02X", i==33 ? raw : 0x21);
    }
    return (size_t)(pos + sprintf(out+pos, "\r>"));
}
int main(void)
{
    char response[256]; size_t length = make_packet(response, 140);
    init_profile(1);
    assert(!s_elm_profile->can_broadcast_mode);
    assert(s_oil_formula_pri->type == OIL_SPECIAL && s_oil_formula_pri->special_id == 0);
    assert(!s_ov->can_rules && !s_ov->can_rule_count);
    // All BLE split points: no publication or release until the final prompt.
    for (size_t split=1; split<length; ++split) {
        obd_data_set_oil_temp_invalid(); expected_at_wake = 100;
        s_elm_ready = false; s_expect_mode21 = true; int old_wakes = wakes;
        receive(response, split); expect_oil(-100);
        assert(!s_elm_ready && wakes == old_wakes);
        receive(response+split, length-split); expect_oil(100);
        assert(wakes == old_wakes+1 && !s_expect_mode21);
    }
    // Previous Mode21 context must survive waiting for its final prompt.
    s_expect_mode21 = true; s_elm_ready = false;
    receive(response, length-1); complete_previous = true;
    assert(elm327_ble_send_ascii_blocking("01 0C\r"));
    assert(!strcmp(sent, "010C\r") && !s_expect_mode21 && !s_elm_ready);
    receive("41 0C 10 00\r>", 13);
    assert(elm327_ble_send_ascii_blocking("21 01\r"));
    assert(!strcmp(sent, "2101\r") && s_expect_mode21);
    receive(response, length);
    expected_at_wake = -1000;

    // NO DATA and malformed frames do not refresh oil freshness.
    test_now = 100; default_on_parsed_oil_temp(100);
    test_now = 15100; expect_oil(100);
    s_expect_mode21 = true; receive("NO DATA\r>", 9);
    test_now = 15101; expect_oil(-100);
    assert(parsed_fail == 1);
    // Tick rollover, reset and generic setter are separate lifetime paths.
    test_now = UINT32_MAX-1000; default_on_parsed_oil_temp(117); expect_oil(117);
    test_now += 15001; expect_oil(-100);
    default_on_parsed_oil_temp(100); default_on_parsed_oil_temp(117); expect_oil(117);
    for (int raw=-40; raw<=215; ++raw) {
        default_on_parsed_oil_temp((uint32_t)raw); expect_oil(raw);
    }
    s_oil_temp_offset = -5; default_on_parsed_oil_temp((uint32_t)-40); expect_oil(-40);
    s_oil_temp_offset = 5; default_on_parsed_oil_temp(215); expect_oil(215);
    s_oil_temp_offset = 0;
    obd_data_reset_temp_cache(); expect_oil(-100);

    // Old model response and init handshake must not publish temperatures.
    init_profile(1); active = 4; s_expect_mode21 = true;
    receive(response, length); expect_oil(-100);
    init_profile(1); s_accept_obd_responses = false; s_expect_mode21 = true;
    receive(response, length); expect_oil(-100);
    s_accept_obd_responses = true;

    // Overflow, timed-out partial frame and forced-send timeout drain through
    // prompt, then recover on the next request; no valid prefix is published.
    char noise[600]; memset(noise, ' ', sizeof(noise));
    s_expect_mode21 = true; receive(response, length-1);
    receive(noise, sizeof(noise)); receive(">", 1); expect_oil(-100);
    s_expect_mode21 = true; receive(response, length-1);
    test_now += 10001; receive(">", 1); expect_oil(-100);
    s_expect_mode21 = true; s_elm_ready = false; receive(response, length-1);
    assert(elm327_ble_send_ascii_blocking("21 01\r"));
    receive(">", 1); expect_oil(-100);
    assert(elm327_ble_send_ascii_blocking("21 01\r"));
    receive(response, length); expect_oil(100);
    s_elm_ready = true; send_ok = false;
    assert(!elm327_ble_send_ascii_blocking("21 01\r") && !s_expect_mode21);
    send_ok = true;

    // ZD8 keeps its standard formula/filter and existing cache lifetime.
    init_profile(4);
    assert(!s_elm_profile->can_broadcast_mode && s_oil_formula_pri->type == OIL_STD_PID);
    default_on_parsed_oil_temp(100); expect_oil(100);
    default_on_parsed_oil_temp(102); expect_oil(101);
    test_now += 20000; expect_oil(101);
    init_profile(1); default_on_parsed_oil_temp(117); expect_oil(117);
    init_profile(4); expect_oil(-100);
    assert(parsed_ok > 0 && parsed_ok == valid_count);

    // Slave packet application preserves the master's temperature and expired
    // sentinel, without applying local offsets/filtering a second time.
    espnow_obd_packet_t packed;
    init_profile(4); master_pack(&packed); assert(packed.flags == 0x01);
    init_profile(1); master_pack(&packed); assert(packed.flags == (0x01 | ESPNOW_FLAG_ZC6_PID));
    s_linktest_active = true;
    init_profile(4); master_pack(&packed); assert(packed.flags == 0x03);
    s_linktest_active = false;
    espnow_obd_packet_t remote = {.flags = ESPNOW_FLAG_ZC6_PID};
    const int remote_values[] = {-40, -25, 0, 100, 151, 215, -100};
    for (unsigned i=0; i<sizeof(remote_values)/sizeof(remote_values[0]); ++i) {
        remote.oil_temp = remote_values[i]; apply_packet(&remote);
        expect_oil(remote_values[i]);
    }
    remote.oil_temp = 300; apply_packet(&remote); expect_oil(-100);

    remote.oil_temp = 117; apply_packet(&remote); expect_oil(117);
    remote.flags = 0; remote.oil_temp = -100; apply_packet(&remote); expect_oil(-100);

    // Unmarked (ZD8 / older v6) packets use the RELEASED setter even when the
    // slave's local profile is ZC6. Do not extend its range or invalid handling.
    init_profile(1);
    remote.flags = 0; remote.oil_temp = 100; apply_packet(&remote); expect_oil(100);
    remote.oil_temp = -100; apply_packet(&remote); expect_oil(100);
    remote.oil_temp = -25; apply_packet(&remote); expect_oil(100);
    remote.oil_temp = 151; apply_packet(&remote); expect_oil(100);
    test_now += 20000; expect_oil(100);

    // ZD8 retains early prompt wake-up and never gets a second wake after parse.
    init_profile(4); s_elm_ready = false;
    receive("41 5C 8C\r", 9); int old_wakes = wakes;
    default_on_raw_notify((const uint8_t *)">", 1);
    assert(s_elm_ready && wakes == old_wakes + 1 && s_accum_len == 9);
    finish_elm_response(false); assert(wakes == old_wakes + 1);
    // ZD8 timeout still forces send without ZC6's discard flag.
    s_elm_ready = false; s_accum_discard = false;
    assert(elm327_ble_send_ascii_blocking("01 0D\r"));
    assert(!s_accum_discard && !s_zc6_request && !s_expect_mode21);
    // Warm-up is also unchanged for ZD8: accept=false alone does not gate it.
    s_accept_obd_responses = false; assert(!zc6_response_guarded());
    receive(">", 1); s_accept_obd_responses = true;

    // The production gear cache uses the ZC6 profile with the same serial-pair
    // timing and confirmation rules as ZD8; no second final-drive multiplier.
    init_profile(1);
    obd_data_set_gear_source(3, OBD_GEAR_SOURCE_PID_A4);
    assert(obd_data_get_gear() == 3);
    init_profile(4); assert(obd_data_get_gear() == 127);
    obd_data_set_gear_source(4, OBD_GEAR_SOURCE_PID_A4);
    init_profile(1); assert(obd_data_get_gear() == 127);
    for (int speed=30; speed<=90; speed+=30) {
        for (int gear=1; gear<=6; ++gear) {
            obd_data_reset_gear();
            uint16_t rpm = (uint16_t)(speed * s_elm_profile->gear_ratios[gear] *
                s_elm_profile->final_drive_ratio * vehicle_profile_calc_constant(s_elm_profile) + 0.5f);
            for (int sample=0; sample<3; ++sample) {
                test_now += 800; obd_data_set_rpm(rpm);
                test_now += 250; obd_data_set_speed((uint8_t)speed);
            }
            assert(obd_data_get_gear() == gear);
            test_now += 3001; assert(obd_data_get_gear() == 127);
        }
    }
    puts("PASS: ZC6 transport, switching, callback and cache");
    return 0;
}
