#include "trip_time_anchor.h"
#include <string.h>

#define MIN_VALID_EPOCH 1704067200ULL
#define MAX_VALID_EPOCH 4102444800ULL

void trip_time_anchor_reset(trip_time_anchor_t *anchor)
{
    if (anchor) memset(anchor, 0, sizeof(*anchor));
}

void trip_time_anchor_observe(trip_time_anchor_t *anchor, int64_t now_us,
                              uint32_t interval_ms)
{
    if (!anchor || now_us < 0 || interval_ms == 0 || interval_ms > 2000) return;
    int64_t interval_us = (int64_t)interval_ms * 1000LL;
    int64_t beginning_us = now_us >= interval_us ? now_us - interval_us : 0;
    if (!anchor->seen) {
        anchor->first_us = beginning_us;
        anchor->seen = true;
    }
    if (now_us >= anchor->first_us) anchor->last_us = now_us;
}

bool trip_time_anchor_estimate(const trip_time_anchor_t *anchor,
                               uint64_t phone_epoch_s, int64_t now_us,
                               uint64_t accumulated_duration_ms,
                               uint64_t *start_epoch_s,
                               uint64_t *end_epoch_s)
{
    if (!start_epoch_s || !end_epoch_s || phone_epoch_s < MIN_VALID_EPOCH ||
        phone_epoch_s > MAX_VALID_EPOCH || now_us < 0) return false;
    if ((!anchor || !anchor->seen) && accumulated_duration_ms == 0) return false;

    uint64_t end = phone_epoch_s;
    uint64_t span_s = accumulated_duration_ms / 1000ULL;
    if (anchor && anchor->seen && anchor->first_us >= 0 &&
        anchor->last_us >= anchor->first_us && now_us >= anchor->last_us) {
        uint64_t idle_after_s = (uint64_t)(now_us - anchor->last_us) / 1000000ULL;
        if (idle_after_s < end) end -= idle_after_s;
        uint64_t monotonic_span_s = (uint64_t)(anchor->last_us - anchor->first_us) / 1000000ULL;
        if (monotonic_span_s > span_s) span_s = monotonic_span_s;
    }
    uint64_t start = end > span_s ? end - span_s : end;
    if (start < MIN_VALID_EPOCH) start = MIN_VALID_EPOCH;
    *start_epoch_s = start;
    *end_epoch_s = end;
    return true;
}

bool trip_time_gap_within(uint64_t previous_end_epoch_s,
                          uint64_t current_start_epoch_s,
                          uint16_t timeout_min)
{
    if (previous_end_epoch_s < MIN_VALID_EPOCH ||
        current_start_epoch_s < previous_end_epoch_s || timeout_min == 0) {
        return false;
    }
    return current_start_epoch_s - previous_end_epoch_s <=
           (uint64_t)timeout_min * 60ULL;
}
