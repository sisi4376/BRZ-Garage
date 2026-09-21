#include <assert.h>
#include <stdio.h>

#include "app_obd_dsp/trip_idle_detector.h"

int main(void)
{
    trip_idle_detector_t detector = {0};

    assert(!trip_idle_detector_step(&detector, false, true, 899999, 15));
    assert(trip_idle_detector_step(&detector, false, true, 1, 15));
    assert(!trip_idle_detector_step(&detector, false, true, 1, 15));
    puts("PASS: active trip finalizes once after fifteen stopped minutes");

    trip_idle_detector_reset(&detector);
    assert(!trip_idle_detector_step(&detector, false, true, 600000, 15));
    assert(!trip_idle_detector_step(&detector, true, true, 200, 15));
    assert(!trip_idle_detector_step(&detector, false, true, 600000, 15));
    puts("PASS: engine restart inside the window cancels the pending split");

    trip_idle_detector_reset(&detector);
    assert(!trip_idle_detector_step(&detector, false, false, 900000, 15));
    assert(detector.stopped_ms == 0);
    puts("PASS: an empty accumulator never creates a trip");

    trip_idle_detector_reset(&detector);
    assert(trip_idle_detector_step(&detector, false, true, 900000, 15));
    trip_idle_detector_reset(&detector);
    assert(!trip_idle_detector_step(&detector, true, true, 900000, 15));
    assert(detector.stopped_ms == 0);
    puts("PASS: a real long scheduling interval is counted unless fresh RPM shows the engine running");

    return 0;
}
