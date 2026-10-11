#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef ESP_PLATFORM
#include "sdkconfig.h"
#elif !defined(CONFIG_OBD_POLL_SELF_CHECK)
#define CONFIG_OBD_POLL_SELF_CHECK 1
#endif

/* Append-only bit contract, shared with the phone. Bit 31 distinguishes old
 * trips (no evidence) from a new trip with no successful replies. */
#define OBD_POLL_HEALTH_PRESENT (1UL << 31)
typedef struct {
    uint32_t requested;
    uint32_t received;
} obd_poll_health_t;

#if defined(CONFIG_OBD_POLL_SELF_CHECK) && CONFIG_OBD_POLL_SELF_CHECK
void obd_poll_health_request(const uint8_t *command, size_t length);
void obd_poll_health_response(uint8_t pid, const uint32_t *data, size_t length);
void obd_poll_health_toyota_oil_received(void);
/* Statistics task only; callbacks never allocate, log, persist or wait. */
obd_poll_health_t obd_poll_health_take(void);
#else
static inline void obd_poll_health_request(const uint8_t *command, size_t length)
{ (void)command; (void)length; }
static inline void obd_poll_health_response(uint8_t pid, const uint32_t *data, size_t length)
{ (void)pid; (void)data; (void)length; }
static inline void obd_poll_health_toyota_oil_received(void) {}
static inline obd_poll_health_t obd_poll_health_take(void)
{ return (obd_poll_health_t){0}; }
#endif
static inline void obd_poll_health_merge(obd_poll_health_t *dst, const obd_poll_health_t *src)
{
    dst->requested |= src->requested;
    dst->received |= src->received;
}
