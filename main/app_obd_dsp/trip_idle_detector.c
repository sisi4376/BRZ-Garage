#include "trip_idle_detector.h"

#include <limits.h>
#include <string.h>

void trip_idle_detector_reset(trip_idle_detector_t *detector)
{
    if (detector) memset(detector, 0, sizeof(*detector));
}

bool trip_idle_detector_step(trip_idle_detector_t *detector,
                             bool engine_running,
                             bool active_trip_has_data,
                             uint32_t elapsed_ms,
                             uint16_t timeout_min)
{
    if (!detector) return false;
    if (engine_running || !active_trip_has_data || timeout_min == 0) {
        detector->stopped_ms = 0;
        return false;
    }
    if (elapsed_ms == 0) return false;

    uint64_t timeout_ms = (uint64_t)timeout_min * 60ULL * 1000ULL;
    if (UINT64_MAX - detector->stopped_ms < elapsed_ms) {
        detector->stopped_ms = UINT64_MAX;
    } else {
        detector->stopped_ms += elapsed_ms;
    }
    if (detector->stopped_ms < timeout_ms) return false;

    detector->stopped_ms = 0;
    return true;
}
