#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Set display brightness in percent on either supported board. */
void gauge_display_set_brightness(uint8_t percent);

/** Human-readable build-time board identifier. */
const char *gauge_display_board_name(void);

#ifdef __cplusplus
}
#endif
