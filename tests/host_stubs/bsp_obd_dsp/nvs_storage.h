#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "app_obd_dsp/fuel_estimator.h"
void nvs_stat_update_speed(uint8_t speed, uint32_t dt_ms);
void nvs_fuel_update(const fuel_sample_t *sample, uint32_t dt);
int nvs_fuel_save(void);
static inline void nvs_refuel_observe_fuel_level(uint16_t percent_x100) { (void)percent_x100; }
