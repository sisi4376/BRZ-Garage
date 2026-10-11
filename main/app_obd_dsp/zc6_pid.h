#pragma once
#include <stdbool.h>
#include <stdint.h>

/* Upstream ZN/C6 PID layouts: 38/39 data bytes after 61 01, oil at
 * data[33]/data[34]. Accept a complete ELM CAF1/H0 numbered response with
 * its declared length, or a single fully assembled 61 01 line. No byte
 * guessing, raw CAN headers/PCI, or partial replies. Output unchanged on error.
 * Caller accumulates BLE fragments through the final '>' before calling. */
bool zc6_pid_parse_oil_temp(const char *response, int16_t *oil_c);
