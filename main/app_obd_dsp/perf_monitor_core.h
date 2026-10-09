#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Fixed memory, best-effort telemetry. A producer tries ONCE, never waits. */
#define PERF_QUEUE_CAPACITY 256u
#define PERF_COMMAND_COUNT 18u
enum perf_event_kind {
    PERF_REQUEST, PERF_RX, PERF_PROMPT, PERF_CANCEL, PERF_RPM, PERF_SPEED,
    PERF_PARSE, PERF_UI, PERF_HANDLER, PERF_REFRESH
};
enum perf_cancel_reason { PERF_SEND_FAIL, PERF_TIMEOUT, PERF_DISCONNECT, PERF_DISCARD };
typedef struct {
    uint32_t time_us, value, loss;
    uint16_t kind, arg;
} perf_event_t;
typedef struct {
    uint32_t gate, lost, head, tail;
    perf_event_t events[PERF_QUEUE_CAPACITY];
} perf_queue_t;
typedef struct { uint32_t n, max; uint64_t total; } perf_metric_t;
typedef struct {
    uint32_t requests[PERF_COMMAND_COUNT], cancels[4];
    perf_metric_t rtt[PERF_COMMAND_COUNT], first_rx[PERF_COMMAND_COUNT];
    perf_metric_t interval[3], work[5]; /* RPM/speed/UI; cache RPM/speed, parse, UI, handler */
    perf_metric_t refresh_us, pixels;
    uint32_t samples[3], loss_seen, ui_pages, ui_flashing;
    uint32_t last[3], request_us, first_rx_us;
    uint16_t command;
    bool have_last[3], pending, got_rx, resync;
} perf_stats_t;
bool perf_queue_push(perf_queue_t *q, perf_event_t event);
bool perf_queue_pop(perf_queue_t *q, perf_event_t *event);
uint16_t perf_command_code(const uint8_t *data, size_t len);
uint16_t perf_command_at(unsigned index);
void perf_stats_consume(perf_stats_t *s, const perf_event_t *event);
/* Clear window totals, preserve in-flight request and interval continuity. */
void perf_stats_new_window(perf_stats_t *s);
