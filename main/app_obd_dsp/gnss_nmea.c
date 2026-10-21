#include "gnss_nmea.h"
#include <string.h>

static int hex(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static bool digits(const char *p, size_t n)
{
    for (size_t i = 0; i < n; ++i) if (p[i] < '0' || p[i] > '9') return false;
    return true;
}

static unsigned pair(const char *p) { return (p[0] - '0') * 10u + p[1] - '0'; }
static bool leap(unsigned y) { return y % 4 == 0 && (y % 100 != 0 || y % 400 == 0); }

static bool utc_epoch(const char *utc, const char *date, uint64_t *epoch)
{
    size_t n = strlen(utc);
    if (n < 6 || !digits(utc, 6) || strlen(date) != 6 || !digits(date, 6)) return false;
    if (n > 6 && (utc[6] != '.' || n == 7 || !digits(utc + 7, n - 7))) return false;
    unsigned h = pair(utc), min = pair(utc + 2), sec = pair(utc + 4);
    unsigned day = pair(date), month = pair(date + 2), yy = pair(date + 4);
    /* NMEA has a two-digit year: keep the GPS epoch/reset years in 1900. */
    unsigned year = (yy >= 80 ? 1900 : 2000) + yy;
    static const unsigned mdays[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    if (year < 2024 || h > 23 || min > 59 || sec > 59 || month < 1 || month > 12) return false;
    if (day < 1 || day > mdays[month - 1] + (month == 2 && leap(year))) return false;
    uint64_t days = 0;
    for (unsigned y = 1970; y < year; ++y) days += 365 + leap(y);
    for (unsigned m = 1; m < month; ++m) days += mdays[m - 1] + (m == 2 && leap(year));
    *epoch = ((days + day - 1) * 24 + h) * 3600 + min * 60 + sec;
    return true;
}

static void diagnostic_field(char *dest, size_t size, const char *value)
{
    size_t n = strlen(value);
    if (n >= size) n = size - 1;
    memcpy(dest, value, n);
    dest[n] = '\0';
}

static gnss_nmea_result_t parse(gnss_nmea_t *s, int64_t now, uint64_t *epoch)
{
    char *p = s->line;
    char *star = strchr(p, '*');
    if (!star || strlen(star) != 3 || star - p < 7) { ++s->malformed; return GNSS_NMEA_NONE; }
    int hi = hex(star[1]), lo = hex(star[2]);
    unsigned sum = 0;
    for (char *q = p; q < star; ++q) sum ^= (unsigned char)*q;
    if (hi < 0 || lo < 0 || sum != (unsigned)(hi * 16 + lo)) { ++s->bad_checksum; return GNSS_NMEA_NONE; }
    /* Accept only GNSS talkers, not arbitrary serial text with a checksum. */
    if (strncmp(p, "GP", 2) && strncmp(p, "GN", 2) && strncmp(p, "GL", 2) &&
        strncmp(p, "GA", 2) && strncmp(p, "GB", 2) && strncmp(p, "BD", 2) &&
        strncmp(p, "GQ", 2)) return GNSS_NMEA_NONE;
    if (p[5] != ',') return GNSS_NMEA_NONE;
    ++s->sentences;
    memcpy(s->last_type, p, 5);
    s->last_type[5] = '\0';
    if (strncmp(p + 2, "RMC", 3)) {
        if (!strncmp(p + 2, "GGA", 3) || !strncmp(p + 2, "GSA", 3) ||
            !strncmp(p + 2, "GSV", 3) || !strncmp(p + 2, "GNS", 3) ||
            !strncmp(p + 2, "ZDA", 3)) return GNSS_NMEA_PRESENT;
        return GNSS_NMEA_NONE;
    }
    *star = '\0';
    char *field[14] = { p };
    unsigned count = 1;
    for (char *q = p; *q && count < 14; ++q) {
        if (*q == ',') { *q = '\0'; field[count++] = q + 1; }
    }
    ++s->rmc_count;
    diagnostic_field(s->utc, sizeof(s->utc), count > 1 ? field[1] : "");
    diagnostic_field(s->date, sizeof(s->date), count > 9 ? field[9] : "");
    s->status = count > 2 && field[2][0] ? field[2][0] : '-';
    s->mode = count > 12 && field[12][0] ? field[12][0] : '-';
    uint64_t value;
    if (count < 10 || strcmp(field[2], "A") ||
        (count > 12 && (!strcmp(field[12], "N") || !strcmp(field[12], "E") || !strcmp(field[12], "S"))) ||
        !utc_epoch(field[1], field[9], &value)) {
        s->reason = count < 10 ? "missing-fields" : strcmp(field[2], "A") ? "rmc-not-valid" :
            (count > 12 && (!strcmp(field[12], "N") || !strcmp(field[12], "E") || !strcmp(field[12], "S"))) ?
            "rejected-mode" : "invalid-date-time";
        s->candidate_epoch = 0;
        return GNSS_NMEA_PRESENT;
    }
    int64_t elapsed = now - s->candidate_us;
    if (value == s->candidate_epoch && elapsed < 3000000) {
        s->reason = "same-second";
        return GNSS_NMEA_PRESENT;
    }
    uint64_t delta = value > s->candidate_epoch ? value - s->candidate_epoch : 0;
    bool confirmed = s->candidate_epoch && delta >= 1 && delta <= 3 &&
                     elapsed >= (int64_t)delta * 1000000 - 600000 &&
                     elapsed <= (int64_t)delta * 1000000 + 600000;
    s->reason = confirmed ? "time-confirmed" : !s->candidate_epoch ? "await-second-fix" : "time-discontinuity";
    s->candidate_epoch = value;
    s->candidate_us = now;
    if (!confirmed) return GNSS_NMEA_PRESENT;
    *epoch = value;
    return GNSS_NMEA_TIME;
}

gnss_nmea_result_t gnss_nmea_feed(gnss_nmea_t *s, char c, int64_t now, uint64_t *epoch)
{
    if (c == '$') { s->used = 0; s->collecting = true; return GNSS_NMEA_NONE; }
    if (!s->collecting) return GNSS_NMEA_NONE;
    if (c == '\r') return GNSS_NMEA_NONE;
    if (c == '\n') {
        s->line[s->used] = '\0';
        s->collecting = false;
        return parse(s, now, epoch);
    }
    if (c < 32 || c > 126 || s->used == sizeof(s->line) - 1) {
        ++s->malformed;
        s->collecting = false;
        s->candidate_epoch = 0;
    } else s->line[s->used++] = c;
    return GNSS_NMEA_NONE;
}
