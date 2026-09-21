#pragma once
#include <stdbool.h>
#include <stdint.h>

/* Monotonic evidence used to reconstruct wall-clock trip boundaries when
 * phone time arrives after driving began. This state is intentionally runtime
 * only: ESP timer values cannot survive a power cycle. */
typedef struct {
    bool seen;
    int64_t first_us;
    int64_t last_us;
} trip_time_anchor_t;

void trip_time_anchor_reset(trip_time_anchor_t *anchor);
void trip_time_anchor_observe(trip_time_anchor_t *anchor, int64_t now_us,
                              uint32_t interval_ms);
bool trip_time_anchor_estimate(const trip_time_anchor_t *anchor,
                               uint64_t phone_epoch_s, int64_t now_us,
                               uint64_t accumulated_duration_ms,
                               uint64_t *start_epoch_s,
                               uint64_t *end_epoch_s);

/* Compare adjacent trip boundaries. The caller must pass the reconstructed
 * start of the new powered session, never the later phone-sync timestamp. */
bool trip_time_gap_within(uint64_t previous_end_epoch_s,
                          uint64_t current_start_epoch_s,
                          uint16_t timeout_min);
