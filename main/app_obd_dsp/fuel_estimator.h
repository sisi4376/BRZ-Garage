#pragma once
#include <stdbool.h>
#include <stdint.h>

typedef enum {
    FUEL_SOURCE_NONE = 0,
    FUEL_SOURCE_ECU,
    FUEL_SOURCE_MAF,
    FUEL_SOURCE_CUT_EST,
    FUEL_SOURCE_ENGINE_OFF,
} fuel_source_t;

// Caller marks stale inputs invalid. 01 03 is system 1, not a fuel-cut switch.
typedef struct {
    uint16_t rpm;
    uint8_t speed_kmh;
    uint16_t maf_x100;
    int16_t afr_x100;  // -1: absent/stale -> nominal gasoline AFR
    uint32_t fuel_rate_ml_h;
    int16_t load_pct;  // -1: absent/stale
    int16_t tps_pct;   // absolute throttle, NOT accelerator pedal; -1: absent/stale
    uint8_t fuel_status; // 0: absent/stale; 1=cold, 2=closed, 4=load/decel, 8/16=fault
    bool rpm_valid;
    bool speed_valid;
    bool maf_valid;
    bool fuel_rate_valid;
} fuel_sample_t;

typedef struct {
    bool dfco_active;
    uint16_t dfco_candidate_ms;
    uint32_t fuel_remainder;
    uint32_t distance_remainder;
} fuel_estimator_t;

typedef struct {
    bool valid;
    bool integrate;
    bool instant_is_lph;
    fuel_source_t source;
    uint32_t rate_ml_h;
    uint16_t instant_x100;
    uint64_t fuel_ul;
    uint64_t distance_mm;
    uint32_t duration_ms;
} fuel_estimate_t;

// Measured elapsed time, not an assumed timer period. Gaps >2s are not backfilled.
fuel_estimate_t fuel_estimator_step(fuel_estimator_t *state,
                                     const fuel_sample_t *sample, uint32_t dt_ms);
