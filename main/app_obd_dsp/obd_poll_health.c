#include "obd_poll_health.h"

static uint32_t s_requested;
static uint32_t s_received;
/* 0..13 are measurements; 14 is ZC6 oil, 15..17 are capability replies. */
static const uint8_t s_pids[] = {
    0x0C, 0x0D, 0x0F, 0x05, 0x5C, 0x42, 0x04, 0x11,
    0x03, 0x44, 0x10, 0x2F, 0x5E, 0xA4, 0xFF, 0x00, 0x40, 0xA0
};
static const uint8_t s_lengths[] = {2,1,1,1,1,2,1,1,2,2,2,1,2,4,0,4,4,4};

static int channel(uint8_t pid)
{
    for (unsigned i = 0; i < sizeof(s_pids); ++i)
        if (s_pids[i] == pid && pid != 0xFF) return (int)i;
    return -1;
}
static int hex(uint8_t c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}
void obd_poll_health_request(const uint8_t *command, size_t length)
{
    if (!command || length != 5 || command[4] != '\r') return;
    int bit = -1;
    if (command[0] == '2' && command[1] == '1' && command[2] == '0' && command[3] == '1') bit = 14;
    else if (command[0] == '0' && command[1] == '1') {
        int hi = hex(command[2]), lo = hex(command[3]);
        if (hi >= 0 && lo >= 0) bit = channel((uint8_t)(hi * 16 + lo));
    }
    if (bit >= 0) __atomic_fetch_or(&s_requested, 1UL << bit, __ATOMIC_RELAXED);
}
void obd_poll_health_response(uint8_t pid, const uint32_t *data, size_t length)
{
    int bit = channel(pid);
    if (bit < 0 || !data || length < s_lengths[bit]) return;
    for (unsigned i = 0; i < s_lengths[bit]; ++i) if (data[i] > 255) return;
    __atomic_fetch_or(&s_received, 1UL << bit, __ATOMIC_RELAXED);
}
void obd_poll_health_toyota_oil_received(void)
{
    __atomic_fetch_or(&s_received, 1UL << 14, __ATOMIC_RELAXED);
}
obd_poll_health_t obd_poll_health_take(void)
{
    obd_poll_health_t result;
    result.requested = __atomic_exchange_n(&s_requested, 0, __ATOMIC_RELAXED);
    result.received = __atomic_exchange_n(&s_received, 0, __ATOMIC_RELAXED);
    /* A response can arrive between exchanges; it is also proof of a request. */
    result.requested |= result.received | OBD_POLL_HEALTH_PRESENT;
    return result;
}
