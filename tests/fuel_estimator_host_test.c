#include <assert.h>
#include <stdio.h>
#include "app_obd_dsp/fuel_estimator.h"

static fuel_sample_t cruising(void)
{
    return (fuel_sample_t) {
        .rpm = 2500, .speed_kmh = 60, .maf_x100 = 1470, .afr_x100 = 1470,
        .load_pct = 30, .tps_pct = 25, .fuel_status = 2,
        .rpm_valid = true, .speed_valid = true, .maf_valid = true,
    };
}
int main(void)
{
    fuel_estimator_t state = {0};
    fuel_sample_t sample = cruising();
    fuel_estimate_t result = fuel_estimator_step(&state, &sample, 1000);
    assert(result.valid && result.integrate && result.source == FUEL_SOURCE_MAF);
    assert(result.rate_ml_h == 4832 && result.instant_x100 == 805);
    assert(result.fuel_ul == 1342 && result.distance_mm == 16666 && result.duration_ms == 1000);
    sample.speed_kmh = 0;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.instant_is_lph && result.instant_x100 == 483 && result.distance_mm == 0);
    assert(result.fuel_ul > 0 && result.duration_ms == 200); // idle still burns fuel
    puts("PASS: MAF units and idle");

    /* Real ZD8 overrun: PID 03 says open-loop/cut while calculated load is
       still stale at 18%, but airflow has already fallen to 4 g/s. */
    sample = cruising(); sample.maf_x100 = 400;
    sample.fuel_status = 4; sample.tps_pct = 14; sample.load_pct = 18;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_CUT_EST && result.fuel_ul == 0 && result.distance_mm > 0);
    sample.rpm = 1200; sample.tps_pct = 17; sample.load_pct = 14;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_CUT_EST); // bounded hysteresis
    sample.rpm = 950;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_MAF && result.fuel_ul > 0);

    /* PID 03 bit 2 is also used for power enrichment.  High airflow must
       therefore override the apparent cut status. */
    state = (fuel_estimator_t){0};
    sample = cruising(); sample.rpm = 1544; sample.maf_x100 = 1178;
    sample.fuel_status = 4; sample.tps_pct = 14; sample.load_pct = 18;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_MAF && !state.dfco_active);

    /* PID polling is asynchronous on ELM327.  Two corroborated low-load/low-
       airflow samples infer cut even while PID 03 still contains status 2. */
    state = (fuel_estimator_t){0};
    sample = cruising(); sample.rpm = 1500; sample.maf_x100 = 350;
    sample.fuel_status = 2; sample.tps_pct = 14; sample.load_pct = 0;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_MAF && state.dfco_candidate_ms == 200);
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_CUT_EST && state.dfco_active);
    sample.rpm = 1250; sample.maf_x100 = 300;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_CUT_EST); // bridge stale PID 03
    sample.load_pct = 20;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_MAF && !state.dfco_active);

    const uint8_t non_cut_status[] = {0, 1, 2, 8, 16, 0xFF, 6};
    for (unsigned i = 0; i < sizeof(non_cut_status); ++i) {
        state = (fuel_estimator_t){0};
        sample = cruising(); sample.maf_x100 = 400; sample.tps_pct = 14; sample.load_pct = 10;
        sample.fuel_status = 4; fuel_estimator_step(&state, &sample, 200);
        sample.fuel_status = non_cut_status[i];
        result = fuel_estimator_step(&state, &sample, 200);
        assert(result.source == FUEL_SOURCE_MAF && result.rate_ml_h > 0 && !state.dfco_active);
    }
    state = (fuel_estimator_t){0};
    sample = cruising(); sample.fuel_status = 4; sample.load_pct = 70;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_MAF); // open loop can mean enrichment
    sample.load_pct = 10; sample.tps_pct = 40;
    assert(fuel_estimator_step(&state, &sample, 200).source == FUEL_SOURCE_MAF);
    sample.tps_pct = -1;
    assert(fuel_estimator_step(&state, &sample, 200).source == FUEL_SOURCE_MAF);
    sample.tps_pct = 14; sample.load_pct = -1;
    assert(fuel_estimator_step(&state, &sample, 200).source == FUEL_SOURCE_MAF);
    puts("PASS: cutoff, recovery, cold/fault/closed-loop and enrichment guards");

    sample = cruising(); sample.fuel_rate_valid = true; sample.fuel_rate_ml_h = 0;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_ECU && result.rate_ml_h == 0);
    sample.fuel_rate_ml_h = 10000; sample.fuel_status = 4; sample.load_pct = 10; sample.tps_pct = 14;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.source == FUEL_SOURCE_ECU && result.rate_ml_h == 10000);
    sample.fuel_rate_valid = false; sample.fuel_status = 2;
    assert(fuel_estimator_step(&state, &sample, 200).source == FUEL_SOURCE_MAF);
    sample.afr_x100 = 1200;
    assert(fuel_estimator_step(&state, &sample, 200).rate_ml_h > 4832);
    sample.afr_x100 = -1;
    assert(fuel_estimator_step(&state, &sample, 200).rate_ml_h == 4832);
    puts("PASS: ECU zero/positive priority, fallback and AFR");

    sample = cruising(); sample.maf_valid = false;
    assert(!fuel_estimator_step(&state, &sample, 200).valid);
    sample = cruising(); sample.rpm_valid = false;
    assert(!fuel_estimator_step(&state, &sample, 200).integrate);
    sample = cruising(); sample.speed_valid = false;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.integrate && result.fuel_ul > 0 && !result.valid && !result.distance_valid);
    sample = cruising(); sample.rpm = 0;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.valid && !result.integrate && result.source == FUEL_SOURCE_ENGINE_OFF);
    sample = cruising();
    assert(!fuel_estimator_step(&state, &sample, 0).integrate);
    assert(!fuel_estimator_step(&state, &sample, 2001).integrate);
    puts("PASS: stale/missing samples, stopped engine and long gaps");

    fuel_estimator_t whole = {0}, split = {0};
    sample = cruising();
    fuel_estimate_t total = fuel_estimator_step(&whole, &sample, 1000);
    const uint32_t intervals[] = {123, 227, 333, 317};
    uint64_t fuel = 0, distance = 0, duration = 0;
    for (unsigned i = 0; i < 4; ++i) {
        result = fuel_estimator_step(&split, &sample, intervals[i]);
        fuel += result.fuel_ul; distance += result.distance_mm; duration += result.duration_ms;
    }
    assert(fuel == total.fuel_ul && distance == total.distance_mm && duration == total.duration_ms);
    fuel_estimator_t hour = {0}; fuel = 0; distance = 0;
    for (int i = 0; i < 18000; ++i) {
        result = fuel_estimator_step(&hour, &sample, 200);
        fuel += result.fuel_ul; distance += result.distance_mm;
    }
    assert(fuel == 4832000 && distance == 60000000); // 4.832 L and 60 km
    puts("PASS: measured intervals, sub-unit carry and one-hour totals");

    /* Extremes must saturate deterministically instead of wrapping or hanging.
       At 3 km/h both maximum MAF and maximum PID 5E fuel rate exceed the
       uint16 x100 display range. */
    state = (fuel_estimator_t){0};
    sample = cruising(); sample.speed_kmh = 3; sample.maf_x100 = UINT16_MAX;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.valid && result.instant_x100 == UINT16_MAX && result.rate_ml_h > 200000);
    sample.fuel_rate_valid = true; sample.fuel_rate_ml_h = UINT32_MAX;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.valid && result.instant_x100 == UINT16_MAX && result.rate_ml_h == UINT32_MAX);
    for (int i = 0; i < 1000000; ++i) {
        result = fuel_estimator_step(&state, &sample, 200);
        assert(result.instant_x100 == UINT16_MAX);
    }
    puts("PASS: extreme fuel values saturate without wrap or hang");
    sample = cruising(); sample.disable_cut_est = true;
    sample.fuel_status = 4; sample.load_pct = 0; sample.tps_pct = 0; sample.maf_x100 = 500;
    for (int n = 0; n < 10; ++n) { result = fuel_estimator_step(&state, &sample, 200); assert(result.source == FUEL_SOURCE_MAF && result.fuel_ul > 0 && result.distance_mm > 0); }
    sample.fuel_rate_valid = true; sample.fuel_rate_ml_h = 0;
    result = fuel_estimator_step(&state, &sample, 200); assert(result.source == FUEL_SOURCE_ECU && result.rate_ml_h == 0);
    sample.fuel_rate_ml_h = 6000;
    result = fuel_estimator_step(&state, &sample, 200); assert(result.instant_x100 == 1000);
    sample.speed_kmh = 0; result = fuel_estimator_step(&state, &sample, 200);
    assert(result.instant_is_lph && result.fuel_ul > 0 && result.distance_mm == 0);
    puts("PASS: AT MAF accumulation, ECU zero/positive and idle fuel");

    /* Mileage is independent of missing fuel inputs and RPM. Invalid speed
       and long scheduling gaps must still never be extrapolated. */
    state = (fuel_estimator_t){0}; sample = cruising();
    distance = 0; fuel = 0;
    for (int i = 0; i < 18000; ++i) {
        sample.maf_valid = (i % 300) >= 15; // 3 seconds missing per minute
        result = fuel_estimator_step(&state, &sample, 200);
        assert(result.distance_valid);
        if (!sample.maf_valid) assert(!result.valid && !result.integrate && result.fuel_ul == 0);
        distance += result.distance_mm; fuel += result.fuel_ul;
    }
    assert(distance == 60000000 && fuel == 4590400);
    sample.rpm_valid = false;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.distance_valid && result.distance_mm > 0 && !result.integrate);
    sample.rpm_valid = true; sample.rpm = 0;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(result.distance_valid && result.distance_mm > 0 && !result.integrate);
    sample.speed_valid = false;
    result = fuel_estimator_step(&state, &sample, 200);
    assert(!result.distance_valid && result.distance_mm == 0);
    sample = cruising();
    result = fuel_estimator_step(&state, &sample, 2001);
    assert(!result.distance_valid && result.distance_mm == 0 && !result.integrate);
    puts("PASS: independent distance across fuel/RPM gaps, stale speed and long gaps");

    for (int speed = 0; speed <= 3; ++speed) {
        state = (fuel_estimator_t){0}; sample = cruising();
        sample.speed_kmh = speed;
        sample.fuel_rate_valid = true; sample.fuel_rate_ml_h = 6000;
        distance = 0; fuel = 0;
        for (int i = 0; i < 3000; ++i) {
            result = fuel_estimator_step(&state, &sample, 200);
            assert(result.valid && result.instant_is_lph == (speed < 3));
            assert(result.instant_x100 == (speed < 3 ? 600 : 20000));
            distance += result.distance_mm;
            fuel += result.fuel_ul;
        }
        assert(distance == (uint64_t)speed * 600000ULL * 5ULL / 18ULL);
        assert(fuel == 1000000); // 6 L/h for 10 min, including 0/1/2 km/h
        sample.fuel_rate_valid = false; sample.maf_valid = false;
        result = fuel_estimator_step(&state, &sample, 200);
        assert(!result.valid && result.instant_is_lph == (speed < 3));
        assert(result.distance_valid);
    }
    puts("PASS: crawl distance and unchanged L/h to L/100km boundary");
    return 0;
}
