#include <assert.h>
#include <stdio.h>
#include "app_obd_dsp/trip_time_anchor.h"

int main(void)
{
    trip_time_anchor_t a = {0};
    uint64_t start = 0, end = 0;
    trip_time_anchor_observe(&a, 10200000, 200);
    trip_time_anchor_observe(&a, 70100000, 200);
    assert(trip_time_anchor_estimate(&a, 1789000100ULL, 100100000,
                                     60000, &start, &end));
    assert(end == 1789000070ULL);       /* phone arrived 30 s after engine data */
    assert(start == 1789000010ULL);     /* first engine sample was 90 s earlier */
    puts("PASS: delayed sync estimates first and last engine wall time");

    assert(trip_time_anchor_estimate(&a, 1789000200ULL, 200100000,
                                     120000, &start, &end));
    assert(end == 1789000070ULL && start == 1788999950ULL);
    puts("PASS: accumulated driving duration is a lower bound on elapsed span");

    trip_time_anchor_reset(&a);
    assert(!trip_time_anchor_estimate(&a, 1789000100ULL, 1, 0, &start, &end));
    assert(trip_time_anchor_estimate(&a, 1789000100ULL, 1, 3600000,
                                     &start, &end));
    assert(start == 1788996500ULL && end == 1789000100ULL);
    puts("PASS: duration fallback without monotonic samples");

    assert(!trip_time_anchor_estimate(&a, 0, 1, 1, &start, &end));
    assert(!trip_time_anchor_estimate(&a, 1789000100ULL, -1, 1, &start, &end));
    trip_time_anchor_observe(&a, 1000, 2001);
    assert(!a.seen);
    puts("PASS: invalid clock, timer and scheduling gap rejected");

    const uint64_t previous_ble_end = 1789000000ULL;
    const uint64_t next_drive_start = previous_ble_end + 4ULL * 60ULL;
    const uint64_t delayed_phone_sync = previous_ble_end + 25ULL * 60ULL;
    assert(trip_time_gap_within(previous_ble_end, next_drive_start, 15));
    assert(!trip_time_gap_within(previous_ble_end, delayed_phone_sync, 15));
    assert(trip_time_gap_within(previous_ble_end,
                                previous_ble_end + 15ULL * 60ULL, 15));
    assert(!trip_time_gap_within(previous_ble_end - 1, previous_ble_end, 0));
    puts("PASS: merge window uses drive start instead of delayed phone time");
    return 0;
}
