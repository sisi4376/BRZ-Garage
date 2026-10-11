#include "zc6_pid.h"
#include <stddef.h>

enum { MAX_PAYLOAD = 41 }; /* 61 01 + at most 39 data bytes */

static int hex_digit(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static bool blank(char c) { return c == ' ' || c == '\t'; }

static int decode_bytes(const char *begin, const char *end, uint8_t *out, size_t capacity)
{
    size_t n = 0;
    while (begin < end) {
        if (blank(*begin)) { ++begin; continue; }
        if (end - begin < 2 || n == capacity) return -1;
        int hi = hex_digit(begin[0]), lo = hex_digit(begin[1]);
        if (hi < 0 || lo < 0) return -1;
        out[n++] = (uint8_t)((hi << 4) | lo);
        begin += 2;
    }
    return (int)n;
}

bool zc6_pid_parse_oil_temp(const char *response, int16_t *oil_c)
{
    if (!response || !oil_c) return false;
    uint8_t payload[MAX_PAYLOAD];
    size_t count = 0;
    int declared = 0, next_row = 0;
    bool flat = false, echo_seen = false;
    const char *p = response;
    while (*p && *p != '>') {
        const char *begin = p;
        while (*p && *p != '\r' && *p != '\n' && *p != '>') ++p;
        const char *end = p;
        while (begin < end && blank(*begin)) ++begin;
        while (end > begin && blank(end[-1])) --end;
        if (begin < end) {
            /* AT E0 is normal, but tolerate one exact request echo. */
            if (!count && !declared && !echo_seen &&
                ((end - begin == 4 && begin[0]=='2' && begin[1]=='1' && begin[2]=='0' && begin[3]=='1') ||
                 (end - begin == 5 && begin[0]=='2' && begin[1]=='1' && begin[2]==' ' && begin[3]=='0' && begin[4]=='1'))) {
                echo_seen = true;
            } else if (!count && !declared && end - begin == 3) {
                int a = hex_digit(begin[0]), b = hex_digit(begin[1]), c = hex_digit(begin[2]);
                if (a < 0 || b < 0 || c < 0) return false;
                declared = (a << 8) | (b << 4) | c;
                if (declared != 40 && declared != 41) return false;
            } else if (end - begin >= 2 && begin[1] == ':') {
                if (!declared || flat || hex_digit(begin[0]) != next_row) return false;
                int expected = next_row == 0 ? 6 : 7;
                if (expected > declared - (int)count) expected = declared - (int)count;
                if (expected <= 0) return false;
                int n = decode_bytes(begin + 2, end, payload + count, MAX_PAYLOAD - count);
                if (n != expected) return false;
                count += (size_t)n;
                ++next_row;
            } else {
                if (count || declared || flat) return false;
                int n = decode_bytes(begin, end, payload, sizeof(payload));
                if (n != 40 && n != 41) return false;
                count = (size_t)n;
                flat = true;
            }
        }
        if (*p == '\r' || *p == '\n') ++p;
    }
    if (*p++ != '>') return false;
    while (*p == '\r' || *p == '\n' || blank(*p)) ++p;
    if (*p || (count != 40 && count != 41) ||
        (!flat && count != (size_t)declared) || payload[0] != 0x61 || payload[1] != 0x01) return false;
    *oil_c = (int16_t)payload[count - 5] - 40;
    return true;
}
