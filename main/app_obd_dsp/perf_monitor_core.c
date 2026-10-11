#include "perf_monitor_core.h"
#include <string.h>

#ifdef ESP_PLATFORM
#include "esp_cpu.h"
#endif
static bool compare_once(uint32_t *addr, uint32_t old, uint32_t value)
{
#ifdef ESP_PLATFORM
    /* IDF disables GCC hardware RMW atomics. Its supported CPU CAS is a
     * single hardware attempt for INTERNAL RAM (queue is DRAM_ATTR). */
    bool ok = esp_cpu_compare_and_set(addr, old, value);
    if (ok) __atomic_thread_fence(__ATOMIC_ACQUIRE);
    return ok;
#else
    _Static_assert(__atomic_always_lock_free(sizeof(uint32_t), 0), "32-bit atomics required");
    return __atomic_compare_exchange_n(addr, &old, value, false,
                                       __ATOMIC_ACQUIRE, __ATOMIC_RELAXED);
#endif
}
static void mark_loss(perf_queue_t *q)
{
    uint32_t old = __atomic_load_n(&q->lost, __ATOMIC_RELAXED);
    /* One attempt, no RMW retry loop. Simultaneous losses may coalesce into
     * one marker; either success or a competing marker breaks continuity. */
    (void)compare_once(&q->lost, old, old + 1);
}
static bool try_enter(perf_queue_t *q)
{
    return compare_once(&q->gate, 0, 1);
}
bool perf_queue_push(perf_queue_t *q, perf_event_t event)
{
    if (!try_enter(q)) {
        mark_loss(q);
        return false;
    }
    bool ok = (uint32_t)(q->head - q->tail) < PERF_QUEUE_CAPACITY;
    if (ok) {
        event.loss = __atomic_load_n(&q->lost, __ATOMIC_RELAXED);
        q->events[q->head % PERF_QUEUE_CAPACITY] = event;
        ++q->head;
    } else {
        mark_loss(q);
    }
    __atomic_store_n(&q->gate, 0, __ATOMIC_RELEASE);
    return ok;
}
bool perf_queue_pop(perf_queue_t *q, perf_event_t *event)
{
    if (!try_enter(q)) return false;
    bool ok = q->head != q->tail;
    if (ok) *event = q->events[q->tail++ % PERF_QUEUE_CAPACITY];
    __atomic_store_n(&q->gate, 0, __ATOMIC_RELEASE);
    return ok;
}
static const uint16_t commands[PERF_COMMAND_COUNT] = {
    0x010c, 0x010d, 0x010f, 0x0105, 0x015c, 0x0142, 0x0104, 0x0111,
    0x0103, 0x0144, 0x0110, 0x012f, 0x015e, 0x01a4, 0x0140, 0x01a0, 0x2101, 0xffff
};
uint16_t perf_command_at(unsigned index) { return commands[index]; }
static int hex(uint8_t c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}
uint16_t perf_command_code(const uint8_t *data, size_t len)
{
    if (!data || len != 5 || data[4] != '\r') return 0xffff;
    unsigned code = 0;
    for (unsigned i = 0; i < 4; ++i) {
        int digit = hex(data[i]);
        if (digit < 0) return 0xffff;
        code = (code << 4) | (unsigned)digit;
    }
    return (uint16_t)code;
}
static void add(perf_metric_t *m, uint32_t value)
{
    ++m->n;
    m->total += value;
    if (value > m->max) m->max = value;
}
static void break_continuity(perf_stats_t *s)
{
    s->pending = false;
    s->resync = true; /* Drain one prompt before trusting request pairing again. */
    memset(s->have_last, 0, sizeof(s->have_last));
}
void perf_stats_consume(perf_stats_t *s, const perf_event_t *e)
{
    if (e->loss != s->loss_seen) {
        break_continuity(s); /* Never pair across a dropped request/reply. */
        s->loss_seen = e->loss;
    }
    switch (e->kind) {
    case PERF_REQUEST:
        s->command = PERF_COMMAND_COUNT - 1;
        for (unsigned i = 0; i < PERF_COMMAND_COUNT - 1; ++i)
            if (commands[i] == e->arg) { s->command = i; break; }
        ++s->requests[s->command];
        s->request_us = e->time_us;
        s->pending = !s->resync;
        s->got_rx = false;
        break;
    case PERF_RX:
        if (s->pending && !s->got_rx) {
            s->first_rx_us = e->time_us - s->request_us;
            s->got_rx = true;
        }
        break;
    case PERF_PROMPT:
        if (s->pending) {
            add(&s->rtt[s->command], e->time_us - s->request_us);
            if (s->got_rx) add(&s->first_rx[s->command], s->first_rx_us);
        }
        s->pending = false;
        s->resync = false;
        break;
    case PERF_CANCEL:
        if (e->arg < 4) ++s->cancels[e->arg];
        break_continuity(s);
        break;
    case PERF_RPM:
    case PERF_SPEED:
    case PERF_UI: {
        unsigned i = e->kind == PERF_RPM ? 0 : e->kind == PERF_SPEED ? 1 : 2;
        if (i == 2) {
            s->ui_pages |= 1u << (e->arg & 7u);
            if (e->arg & 0x100u) ++s->ui_flashing;
        }
        ++s->samples[i];
        /* Ignore long-idle and reordered timestamps; subtraction handles wrap. */
        if (s->have_last[i] && e->time_us - s->last[i] < 60000000u)
            add(&s->interval[i], e->time_us - s->last[i]);
        s->last[i] = e->time_us;
        s->have_last[i] = true;
        add(&s->work[i == 2 ? 3 : i], e->value);
        break;
    }
    case PERF_PARSE: add(&s->work[2], e->value); break;
    case PERF_HANDLER: add(&s->work[4], e->value); break;
    case PERF_REFRESH:
        add(&s->refresh_us, e->value);
        add(&s->pixels, e->arg); /* pixels are reported in blocks of 16 */
        break;
    default: break;
    }
}
void perf_stats_new_window(perf_stats_t *s)
{
    memset(s->requests, 0, sizeof(s->requests));
    memset(s->cancels, 0, sizeof(s->cancels));
    memset(s->rtt, 0, sizeof(s->rtt));
    memset(s->first_rx, 0, sizeof(s->first_rx));
    memset(s->interval, 0, sizeof(s->interval));
    memset(s->work, 0, sizeof(s->work));
    memset(s->samples, 0, sizeof(s->samples));
    s->ui_pages = s->ui_flashing = 0;
    memset(&s->refresh_us, 0, sizeof(s->refresh_us));
    memset(&s->pixels, 0, sizeof(s->pixels));
}
