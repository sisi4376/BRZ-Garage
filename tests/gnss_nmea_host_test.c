#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "app_obd_dsp/gnss_nmea.h"

static gnss_nmea_result_t sentence(gnss_nmea_t *s, const char *body, int64_t us, uint64_t *epoch)
{
    char text[256]; unsigned sum = 0;
    for (const char *p = body; *p; ++p) sum ^= (unsigned char)*p;
    snprintf(text, sizeof(text), "$%s*%02X\r\n", body, sum);
    gnss_nmea_result_t result = GNSS_NMEA_NONE;
    for (size_t i = 0; i < strlen(text); ++i) result = gnss_nmea_feed(s, text[i], us, epoch);
    return result;
}

int main(void)
{
    gnss_nmea_t s = {0}; uint64_t epoch = 0;
    assert(sentence(&s, "GNRMC,000000.00,V,,,,,,,121026,,,N", 0, &epoch) == GNSS_NMEA_PRESENT);
    assert(!epoch);
    assert(s.rmc_count == 1 && s.status == 'V' && !strcmp(s.reason, "rmc-not-valid"));
    assert(!strcmp(s.utc, "000000.00") && !strcmp(s.date, "121026"));
    assert(sentence(&s, "GNRMC,000000.00,A,1,N,1,E,0,0,121026,,,A", 1000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "GNRMC,000000.10,A,1,N,1,E,0,0,121026,,,A", 1100000, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "GNRMC,000001.00,A,1,N,1,E,0,0,121026,,,A", 2000000, &epoch) == GNSS_NMEA_TIME);
    assert(epoch == 1791763201ULL);
    assert(!strcmp(s.reason, "time-confirmed") && s.rmc_count == 4);
    puts("PASS: no-fix identifies receiver without setting time; valid UTC and duplicates");

    s = (gnss_nmea_t){0};
    assert(sentence(&s, "GPRMC,235959,A,1,N,1,E,0,0,280224,,,A", 1000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "GPRMC,000000,A,1,N,1,E,0,0,290224,,,A", 2000000, &epoch) == GNSS_NMEA_TIME);
    assert(epoch == 1709164800ULL);
    assert(sentence(&s, "GNRMC,000001,A,1,N,1,E,0,0,290225,,,A", 3000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(!s.candidate_epoch);
    assert(sentence(&s, "GNRMC,246001,A,1,N,1,E,0,0,121026,,,A", 4000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(!s.candidate_epoch);
    assert(sentence(&s, "GNRMC,000001,A,1,N,1,E,0,0,121099,,,S", 5000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(!s.candidate_epoch);
    puts("PASS: midnight/leap day and malformed/simulated time rejection");

    s = (gnss_nmea_t){0}; epoch = 0;
    assert(sentence(&s, "GNRMC,000000,A,1,N,1,E,0,0,060180,,,A", 1000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "GNRMC,000001,A,1,N,1,E,0,0,060180,,,A", 2000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(!epoch && !s.candidate_epoch && !strcmp(s.reason, "invalid-date-time"));
    assert(sentence(&s, "GNRMC,000002,A,1,N,1,E,0,0,121026,,,A,V", 3000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "GNRMC,000003,A,1,N,1,E,0,0,121026,,,A,V", 4000000, &epoch) == GNSS_NMEA_TIME);
    assert(epoch == 1791763203ULL);
    puts("PASS: reject 1980 reset date; accept valid RMC with unused NavStatus V");

    s = (gnss_nmea_t){0};
    assert(sentence(&s, "GNRMC,000000,A,1,N,1,E,0,0,121026,,,A", 1000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "GNRMC,000001,A,1,N,1,E,0,0,121026,,,A", 1000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "GNRMC,000005,A,1,N,1,E,0,0,121026,,,A", 2000000, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "GNRMC,000006,A,1,N,1,E,0,0,121026,,,A", 3000000, &epoch) == GNSS_NMEA_TIME);
    puts("PASS: buffered old fixes and jumps do not synchronize immediately");

    s = (gnss_nmea_t){0};
    const char *bad = "$GNRMC,000000,A,1,N,1,E,0,0,121026,,,A*00\r\n";
    for (const char *p = bad; *p; ++p) assert(gnss_nmea_feed(&s, *p, 0, &epoch) == GNSS_NMEA_NONE);
    assert(s.bad_checksum == 1);
    gnss_nmea_feed(&s, '$', 0, &epoch);
    for (int i = 0; i < 300; ++i) gnss_nmea_feed(&s, 'x', 0, &epoch);
    assert(!s.collecting && s.malformed == 1);
    assert(sentence(&s, "GPGGA,123456,,,,,0", 0, &epoch) == GNSS_NMEA_PRESENT);
    assert(sentence(&s, "XXRMC,000000,A,,,,,,,121026", 0, &epoch) == GNSS_NMEA_NONE);
    puts("PASS: corruption, oversized frames and foreign serial traffic rejected");
}
