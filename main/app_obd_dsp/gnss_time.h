#pragma once
#include <stdbool.h>
#include <stdint.h>
bool gnss_position_read(int32_t *latitude_e5, int32_t *longitude_e5);
/* Best-effort background fallback. Once per boot; never waits for a fix here.
 * No-op on unsupported boards or when GNSS support is disabled. */
void gnss_time_start(void);
