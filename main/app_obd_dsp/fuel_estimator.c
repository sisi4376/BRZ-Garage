#include "fuel_estimator.h"
#include <limits.h>

#define DFCO_ENTER_RPM             1050U
#define DFCO_EXIT_RPM               950U
#define DFCO_ENTER_SPEED_KMH           5U
#define DFCO_EXIT_SPEED_KMH            3U
#define DFCO_ENTER_TPS_PCT             16
#define DFCO_EXIT_TPS_PCT              18
#define DFCO_ZERO_LOAD_PCT              3
#define DFCO_HOLD_LOAD_PCT              5
#define DFCO_AIRFLOW_PER_KRPM_X100    500U
#define DFCO_INFER_CONFIRM_MS          400U

static bool dfco_airflow_low(const fuel_sample_t *sample)
{
    if (!sample->maf_valid || sample->rpm == 0) return false;
    /* maf_x100 / (rpm / 1000): closed-throttle airflow in g/s per krpm.
       The real ZD8 log put confirmed overrun samples around 2.0-4.3 while
       powered/shift transients were normally above 5.0. */
    return (uint64_t)sample->maf_x100 * 1000ULL <=
           (uint64_t)sample->rpm * DFCO_AIRFLOW_PER_KRPM_X100;
}

static bool dfco_update(fuel_estimator_t *state, const fuel_sample_t *sample,
                        uint32_t dt_ms)
{
    bool throttle_closed = sample->tps_pct >= 0 &&
                           sample->tps_pct <= (state->dfco_active
                                               ? DFCO_EXIT_TPS_PCT
                                               : DFCO_ENTER_TPS_PCT);
    bool status_cut_or_load = sample->fuel_status == 4;
    bool load_zero = sample->load_pct >= 0 &&
                     sample->load_pct <= DFCO_ZERO_LOAD_PCT;
    bool airflow_low = dfco_airflow_low(sample);

    if (state->dfco_active) {
        bool operating_range = sample->rpm > DFCO_EXIT_RPM &&
                               sample->speed_kmh >= DFCO_EXIT_SPEED_KMH;
        bool evidence_remains =
            (status_cut_or_load && (airflow_low || load_zero)) ||
            (load_zero && airflow_low) ||
            (sample->fuel_status == 0 && airflow_low &&
             sample->load_pct >= 0 && sample->load_pct <= DFCO_HOLD_LOAD_PCT);
        if (!operating_range || !throttle_closed || !evidence_remains) {
            state->dfco_active = false;
            state->dfco_candidate_ms = 0;
        }
        return state->dfco_active;
    }

    bool operating_range = sample->rpm >= DFCO_ENTER_RPM &&
                           sample->speed_kmh >= DFCO_ENTER_SPEED_KMH;
    bool ecu_cut_evidence = status_cut_or_load && throttle_closed &&
                            (airflow_low || load_zero);
    bool inferred_cut_evidence = throttle_closed && load_zero && airflow_low;

    if (!operating_range || (!ecu_cut_evidence && !inferred_cut_evidence)) {
        state->dfco_candidate_ms = 0;
        return false;
    }

    if (ecu_cut_evidence) {
        /* PID 03 is direct ECU evidence, after throttle/airflow corroboration. */
        state->dfco_active = true;
        state->dfco_candidate_ms = 0;
        return true;
    }

    /* The ZD8 updates PID 03, load and MAF at different points in the ELM
       polling round. Two low-load samples bridge that skew without treating a
       single zero-load glitch or an ordinary gear change as fuel cut. */
    uint32_t candidate_ms = state->dfco_candidate_ms + dt_ms;
    state->dfco_candidate_ms = candidate_ms > UINT16_MAX
                             ? UINT16_MAX : (uint16_t)candidate_ms;
    if (state->dfco_candidate_ms >= DFCO_INFER_CONFIRM_MS) {
        state->dfco_active = true;
        state->dfco_candidate_ms = 0;
    }
    return state->dfco_active;
}

fuel_estimate_t fuel_estimator_step(fuel_estimator_t *state,
                                     const fuel_sample_t *sample, uint32_t dt_ms)
{
    fuel_estimate_t out = {0};
    if (!state) return out;
    if (!sample || !dt_ms || dt_ms > 2000 || !sample->rpm_valid || !sample->speed_valid) {
        state->dfco_active = false;
        state->dfco_candidate_ms = 0;
        return out;
    }
    out.instant_is_lph = sample->speed_kmh < 3;
    if (sample->rpm == 0) {
        state->dfco_active = false;
        state->dfco_candidate_ms = 0;
        out.valid = true;
        out.source = FUEL_SOURCE_ENGINE_OFF;
        return out; // no driving time, distance or stale MAF fuel after engine stop
    }

    uint64_t rate;
    if (sample->fuel_rate_valid) {
        rate = sample->fuel_rate_ml_h; // ECU zero is valid; never override with a heuristic
        out.source = FUEL_SOURCE_ECU;
        state->dfco_active = false;
        state->dfco_candidate_ms = 0;
    } else {
        /* PID 03 value 4 means high-load open loop OR deceleration fuel cut.
           Distinguish the two with closed throttle plus either zero calculated
           load or the low airflow/RPM relationship observed on this ZD8. */
        if (dfco_update(state, sample, dt_ms)) {
            rate = 0;
            out.source = FUEL_SOURCE_CUT_EST;
        } else {
            if (!sample->maf_valid || sample->maf_x100 == 0) return out;
            uint16_t afr = sample->afr_x100 >= 800 && sample->afr_x100 <= 2200
                         ? (uint16_t)sample->afr_x100 : 1470;
            // Gasoline assumption: 745 g/L. MAF/AFR -> g/s -> mL/h.
            rate = ((uint64_t)sample->maf_x100 * 3600000ULL) / ((uint64_t)afr * 745ULL);
            out.source = FUEL_SOURCE_MAF;
        }
    }
    out.valid = true;
    out.integrate = true;
    out.rate_ml_h = (uint32_t)rate;
    uint64_t instant = out.instant_is_lph ? rate / 10ULL : rate * 10ULL / sample->speed_kmh;
    out.instant_x100 = (uint16_t)(instant > UINT16_MAX ? UINT16_MAX : instant);
    uint64_t fuel_numerator = rate * dt_ms + state->fuel_remainder;
    out.fuel_ul = fuel_numerator / 3600ULL;
    state->fuel_remainder = (uint32_t)(fuel_numerator % 3600ULL);
    uint64_t distance_numerator = (uint64_t)sample->speed_kmh * dt_ms * 5ULL + state->distance_remainder;
    out.distance_mm = distance_numerator / 18ULL;
    state->distance_remainder = (uint32_t)(distance_numerator % 18ULL);
    out.duration_ms = dt_ms;
    return out;
}
