#pragma once

#include <stdbool.h>
#include <stdint.h>

#define VEHICLE_MAX_GEARS 9
#define VEHICLE_PROFILE_ZC6_INDEX 1
#define VEHICLE_PROFILE_ZD8_INDEX 4
#define VEHICLE_PROFILE_DEFAULT_INDEX VEHICLE_PROFILE_ZD8_INDEX
#define VEHICLE_PROFILE_SELECTABLE_COUNT 2

typedef struct {
    const char *name;
    float final_drive_ratio;
    float tire_rolling_radius_m;
    uint8_t gear_count;
    float gear_ratios[VEHICLE_MAX_GEARS];
    bool has_boost;
} vehicle_profile_t;

const vehicle_profile_t *vehicle_profile_get_all(uint8_t *count);
const vehicle_profile_t *vehicle_profile_get_active(void);
void vehicle_profile_set_active(uint8_t index);
