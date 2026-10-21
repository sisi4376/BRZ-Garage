#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    /* Diagnostics only: never used to accept or reject a timestamp. */
    unsigned sentences, bad_checksum, malformed, rmc_count;
    char last_type[6], utc[20], date[12], status, mode;
    const char *reason;
    char line[128];
    size_t used;
    bool collecting;
    uint64_t candidate_epoch;
    int64_t candidate_us;
} gnss_nmea_t;

typedef enum { GNSS_NMEA_NONE, GNSS_NMEA_PRESENT, GNSS_NMEA_TIME } gnss_nmea_result_t;
/* Streaming, allocation-free parser. TIME requires two progressing valid RMC
 * sentences with plausible dates and matching monotonic elapsed time. */
gnss_nmea_result_t gnss_nmea_feed(gnss_nmea_t *state, char byte,
                                  int64_t now_us, uint64_t *epoch);
