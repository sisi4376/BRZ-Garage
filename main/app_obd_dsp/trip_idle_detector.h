#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    uint64_t stopped_ms;
} trip_idle_detector_t;

void trip_idle_detector_reset(trip_idle_detector_t *detector);

/* Returns true exactly once after an active trip has continuously seen no
 * running engine for timeout_min. Any valid running-engine sample cancels the
 * pending split, so short stops remain part of the same trip. */
bool trip_idle_detector_step(trip_idle_detector_t *detector,
                             bool engine_running,
                             bool active_trip_has_data,
                             uint32_t elapsed_ms,
                             uint16_t timeout_min);
