#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>

typedef struct { uint8_t pid, size, data[2]; } obd_group_value_t;

static inline int group_digit(char ch)
{
    if (ch >= '0' && ch <= '9') return ch - '0';
    if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
    if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
    return -1;
}

static inline unsigned group_width(unsigned pid)
{
    switch (pid) {
    case 0x03: case 0x0C: case 0x10: case 0x42: case 0x44: case 0x5E: return 2;
    case 0x04: case 0x05: case 0x0D: case 0x0E: case 0x0F: case 0x11: case 0x2F: case 0x5C: return 1;
    default: return 0;
    }
}

static inline unsigned group_decode(const char *text, const uint8_t *expected, unsigned count,
                                    obd_group_value_t values[6])
{
    uint8_t bytes[24];
    unsigned used = 0, declared = 0, row = 0;
    if (!text || !expected || !values || count == 0 || count > 6) return 0;

    // CAF1/H0 returns a plain payload or a size followed by numbered ISO-TP rows.
    while (*text) {
        text += strspn(text, " \t\r\n");
        if (*text == '>') break;
        const char *end = text + strcspn(text, "\r\n>");
        const char *stop = end;
        while (stop > text && (stop[-1] == ' ' || stop[-1] == '\t')) --stop;
        if (!declared && !used && stop - text == 3) {
            for (; text < stop; ++text) {
                int digit = group_digit(*text);
                if (digit < 0) return 0;
                declared = declared * 16 + digit;
            }
            if (declared < 3 || declared > sizeof(bytes)) return 0;
        } else {
            if (declared) {
                if (stop - text < 2 || group_digit(*text) != (int)row ||
                    text[1] != ':' || used >= declared) return 0;
                text += 2;
            } else if (used) {
                return 0;
            }

            unsigned length = 0;
            unsigned capacity = row ? 7 : 6;
            unsigned remaining = declared ? declared - used : 0;
            while (text < stop) {
                if (*text == ' ' || *text == '\t') {
                    ++text;
                    continue;
                }
                if (stop - text < 2) return 0;
                int hi = group_digit(text[0]), lo = group_digit(text[1]);
                if (hi < 0 || lo < 0) return 0;
                if (!declared || used < declared) {
                    if (used == sizeof(bytes)) return 0;
                    bytes[used++] = (uint8_t)(hi * 16 + lo);
                }
                text += 2;
                ++length;
            }
            unsigned required = remaining < capacity ? remaining : capacity;
            if (declared && (length > capacity || length < required)) return 0;
            ++row;
        }
        text = end;
    }
    if (*text != '>' || text[1 + strspn(text + 1, " \t\r\n")] ||
        used < 3 || (declared && used != declared) || bytes[0] != 0x41) return 0;

    // Some ECUs omit unsupported PIDs. Validate every returned PID and its width
    // before the caller publishes any values from this response.
    unsigned parsed = 0, seen = 0;
    for (unsigned pos = 1; pos < used;) {
        unsigned pid = bytes[pos++], width = group_width(pid);
        unsigned index = 0;
        while (index < count && expected[index] != pid) ++index;
        if (index == count || (seen & (1u << index)) || !width || pos + width > used) return 0;
        seen |= 1u << index;
        values[parsed].pid = pid;
        values[parsed].size = width;
        memcpy(values[parsed].data, bytes + pos, width);
        ++parsed;
        pos += width;
    }
    return parsed;
}
