#pragma once
/* Best-effort background fallback. Once per boot; never waits for a fix here.
 * No-op on unsupported boards or when GNSS support is disabled. */
void gnss_time_start(void);
