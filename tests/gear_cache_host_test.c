// Executes the production obd_data_cache.c, not a Python copy of its algorithm.
#include <assert.h>
#include <stdio.h>
#include "app_obd_dsp/vehicle_profiles.h"
#include "freertos/FreeRTOS.h"

TickType_t test_now;
static const vehicle_profile_t profile = {
    .name = "ZD8", .final_drive_ratio = 4.1f, .tire_rolling_radius_m = 0.315f,
    .gear_count = 6, .gear_ratios = {0, 3.626f, 2.189f, 1.541f, 1.213f, 1, 0.767f},
    .gear_tolerance = 0.15f,
};
const vehicle_profile_t *vehicle_profile_get_active(void) { return &profile; }
float vehicle_profile_calc_constant(const vehicle_profile_t *p)
{ return 1.0f / (0.377f * p->tire_rolling_radius_m); }
void nvs_stat_update_speed(uint8_t speed, uint32_t dt) { (void)speed; (void)dt; }
void nvs_fuel_update(const fuel_sample_t *sample, uint32_t dt)
{
    (void)sample; (void)dt;
}
int nvs_fuel_save(void) { return 0; }
static uint16_t rpm_for(int gear, int speed)
{
    return (uint16_t)(speed * profile.gear_ratios[gear] * profile.final_drive_ratio *
                      vehicle_profile_calc_constant(&profile) + 0.5f);
}
static void expect(int gear)
{
    obd_data_snapshot_t snapshot;
    obd_data_get_snapshot(&snapshot);
    assert(snapshot.gear == gear);
    assert(obd_data_get_gear() == gear);
}
static void reset(void) { obd_data_reset_gear(); test_now = 0; }
static void pair(int gear, int speed, unsigned delay, bool speed_first)
{
    if (speed_first) {
        obd_data_set_speed((uint8_t)speed);
        test_now += delay;
        obd_data_set_rpm(rpm_for(gear, speed));
    } else {
        obd_data_set_rpm(rpm_for(gear, speed));
        test_now += delay;
        obd_data_set_speed((uint8_t)speed);
    }
}
int main(void)
{
    // Regression: serial BLE gaps over 180ms used to suppress every gear.
    const unsigned delays[] = {0, 50, 180, 250, 500, 750};
    for (unsigned d = 0; d < sizeof(delays)/sizeof(delays[0]); ++d) {
        for (int gear = 1; gear <= 6; ++gear) {
            for (int order = 0; order < 2; ++order) {
                reset(); pair(gear, 50, delays[d], order); expect(127);
                test_now += 800;
                pair(gear, 50, delays[d], order); expect(127);
                test_now += 800;
                pair(gear, 50, delays[d], order); expect(gear);
                test_now += 1000; expect(gear); // no 900ms flicker
                test_now += 2001; expect(127); // stale data still expires
            }
        }
    }
    puts("PASS: 72 delayed/order/gear combinations and freshness");

    reset();
    obd_data_set_rpm(rpm_for(3, 50));
    test_now = 900; obd_data_set_speed(50); expect(127);
    test_now = 1150; obd_data_set_rpm(rpm_for(3, 50)); expect(127);
    // New RPM after speed salvages a pending speed, but cannot double-confirm it.
    for (int i = 0; i < 5; ++i) {
        test_now += 30; obd_data_set_rpm(rpm_for(3, 50)); expect(127);
    }
    test_now += 100; obd_data_set_speed(50); expect(127);
    test_now += 100; obd_data_set_rpm(rpm_for(3, 50)); expect(127);
    test_now += 100; obd_data_set_speed(50); expect(3);
    puts("PASS: late RPM retry; no duplicate confirmation using one speed");

    reset(); pair(4, 60, 250, false); test_now += 400; pair(4, 60, 250, false); expect(127);
    test_now += 400; pair(4, 60, 250, false); expect(4);
    // A fresh clutch-like mismatch clears the estimate instead of holding it.
    test_now += 100; obd_data_set_speed(60);
    test_now += 250; obd_data_set_rpm(900); expect(127);
    pair(3, 60, 250, false); expect(127);
    test_now += 400; pair(3, 60, 250, false); expect(127);
    test_now += 400; pair(3, 60, 250, false); expect(3);
    obd_data_set_speed(0); expect(GEAR_NEUTRAL);
    test_now += 3001; obd_data_set_rpm(900); expect(127);
    puts("PASS: clutch mismatch, shift recovery and stale standstill");

    reset(); pair(2, 30, 751, true); expect(127);
    pair(2, 30, 751, true); expect(127);
    reset(); pair(2, 30, 250, false);
    test_now += 4000; pair(2, 30, 250, false); expect(127);
    obd_data_reset_gear(); obd_data_set_speed(30); expect(127);
    puts("PASS: stale pairs/candidates and reconnect invalidation");

    reset(); pair(4, 60, 250, false); test_now += 500; pair(4, 60, 250, false); expect(127);
    test_now += 500; pair(4, 60, 250, false); expect(4);
    obd_data_set_gear_source(5, OBD_GEAR_SOURCE_PID_A4); expect(5);
    obd_data_set_gear_source(6, OBD_GEAR_SOURCE_CAN); expect(6);
    obd_data_set_gear_source(2, OBD_GEAR_SOURCE_PID_A4); expect(6);
    test_now += 2600; expect(4); // direct timeout resumes still-fresh inference
    puts("PASS: direct priority and fallback");

    fuel_sample_t fuel;
    test_now = 0;
    obd_data_reset_fuel_inputs();
    obd_data_get_fuel_sample(&fuel);
    assert(!fuel.rpm_valid && !fuel.speed_valid && !fuel.maf_valid && !fuel.fuel_rate_valid);
    obd_data_set_rpm(2500); obd_data_set_speed(60); obd_data_set_maf_x100(1470);
    obd_data_set_afr_x100(1470); obd_data_set_load_pct(10); obd_data_set_tps(14);
    obd_data_set_fuel_status(4); obd_data_set_fuel_rate_ml_h(0);
    obd_data_get_fuel_sample(&fuel);
    assert(fuel.rpm_valid && fuel.speed_valid && fuel.maf_valid && fuel.fuel_rate_valid);
    assert(fuel.fuel_status == 4 && fuel.fuel_rate_ml_h == 0); // tick 0 is a valid sample
    test_now = 2501; obd_data_get_fuel_sample(&fuel);
    assert(fuel.fuel_status == 0 && fuel.load_pct == -1 && fuel.tps_pct == -1);
    assert(fuel.rpm_valid && fuel.maf_valid);
    test_now = 3001; obd_data_get_fuel_sample(&fuel);
    assert(!fuel.rpm_valid && !fuel.speed_valid);
    test_now = 5001; obd_data_get_fuel_sample(&fuel);
    assert(!fuel.maf_valid && !fuel.fuel_rate_valid && fuel.afr_x100 == -1);
    obd_data_set_fuel_status(0xFF); obd_data_get_fuel_sample(&fuel); assert(fuel.fuel_status == 0);
    obd_data_set_afr_x100(1470); obd_data_set_afr_x100(0);
    obd_data_get_fuel_sample(&fuel); assert(fuel.afr_x100 == -1);
    obd_data_set_fuel_status(4); obd_data_reset_fuel_inputs();
    obd_data_get_fuel_sample(&fuel); assert(fuel.fuel_status == 0 && !fuel.maf_valid);
    puts("PASS: fuel freshness, invalidation, engine signals and reconnect");
    return 0;
}
