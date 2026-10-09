#include "perf_monitor.h"
#if defined(CONFIG_OBD_PERF_MONITOR) && CONFIG_OBD_PERF_MONITOR
#include <inttypes.h>
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

/* CPU CAS must never target PSRAM. Optional storage is allocated only after
 * core startup, rather than taking internal RAM away from the trip task. */
typedef struct {
    perf_queue_t queue;
    perf_stats_t stats;
} perf_context_t;
static perf_context_t *s_context;
#define PERF_INTERNAL_RESERVE (32U * 1024U)
#define PERF_TASK_STACK 4096U
#define PERF_TASK_OVERHEAD_BUDGET 1024U
static uint32_t enabled;
static const char *TAG = "gauge_perf";

void perf_emit(uint16_t kind, uint16_t arg, uint32_t start_us, uint32_t value)
{
    if (!__atomic_load_n(&enabled, __ATOMIC_ACQUIRE)) return;
    perf_event_t event = {.kind = kind, .arg = arg, .time_us = start_us, .value = value};
    (void)perf_queue_push(&s_context->queue, event);
}
void perf_request(const uint8_t *data, size_t len)
{
    perf_emit(PERF_REQUEST, perf_command_code(data, len), perf_now(), 0);
}
static uint32_t avg(const perf_metric_t *m) { return m->n ? (uint32_t)(m->total / m->n) : 0; }
static void report(perf_context_t *context, uint32_t window_us)
{
    perf_queue_t *queue = &context->queue;
    perf_stats_t *stats = &context->stats;
    ESP_LOGI(TAG, "window_ms=%" PRIu32 " loss_marks=%" PRIu32
             " ui_pages=0x%" PRIX32 " flash_ticks=%" PRIu32
             " cancel(send/timeout/disconnect/discard)=%" PRIu32 "/%" PRIu32 "/%" PRIu32 "/%" PRIu32,
             window_us / 1000, __atomic_load_n(&queue->lost, __ATOMIC_RELAXED),
             stats->ui_pages, stats->ui_flashing,
             stats->cancels[0], stats->cancels[1], stats->cancels[2], stats->cancels[3]);
    static const char *names[] = {"rpm", "speed", "ui"};
    for (unsigned i = 0; i < 3; ++i)
        ESP_LOGI(TAG, "%s count=%" PRIu32 " hz_x10=%" PRIu32 " gap_us(avg/max)=%" PRIu32 "/%" PRIu32,
                 names[i], stats->samples[i], window_us ? (uint32_t)((uint64_t)stats->samples[i] * 10000000 / window_us) : 0,
                 avg(&stats->interval[i]), stats->interval[i].max);
    for (unsigned i = 0; i < PERF_COMMAND_COUNT; ++i) {
        if (!stats->requests[i] && !stats->rtt[i].n) continue;
        ESP_LOGI(TAG, "cmd=%04X sent=%" PRIu32 " done=%" PRIu32
                 " first_us(avg/max)=%" PRIu32 "/%" PRIu32 " prompt_us(avg/max)=%" PRIu32 "/%" PRIu32,
                 perf_command_at(i), stats->requests[i], stats->rtt[i].n,
                 avg(&stats->first_rx[i]), stats->first_rx[i].max, avg(&stats->rtt[i]), stats->rtt[i].max);
    }
    static const char *work[] = {"rpm_cache", "speed_cache", "parse", "ui_callback", "lv_handler"};
    for (unsigned i = 0; i < 5; ++i)
        ESP_LOGI(TAG, "%s n=%" PRIu32 " us(avg/max)=%" PRIu32 "/%" PRIu32,
                 work[i], stats->work[i].n, avg(&stats->work[i]), stats->work[i].max);
    ESP_LOGI(TAG, "lv_refresh n=%" PRIu32 " us(avg/max)=%" PRIu32 "/%" PRIu32
             " pixels(avg/max)=%" PRIu32 "/%" PRIu32,
             stats->refresh_us.n, avg(&stats->refresh_us), stats->refresh_us.max,
             avg(&stats->pixels) * 16, stats->pixels.max * 16);
}
static void worker(void *arg)
{
    perf_context_t *context = arg;
    uint32_t window_start = perf_now();
    for (;;) {
        perf_event_t event;
        /* Bound consumer work too; a telemetry storm must not monopolize CPU. */
        for (unsigned n = 0; n < PERF_QUEUE_CAPACITY && perf_queue_pop(&context->queue, &event); ++n)
            perf_stats_consume(&context->stats, &event);
        uint32_t now = perf_now();
        if (now - window_start >= 10000000u) {
            report(context, now - window_start);
            perf_stats_new_window(&context->stats);
            window_start = now;
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}
void perf_monitor_start(void)
{
    if (__atomic_load_n(&enabled, __ATOMIC_ACQUIRE)) return;
    const uint32_t caps = MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT;
    const size_t budget = sizeof(perf_context_t) + PERF_TASK_STACK + PERF_TASK_OVERHEAD_BUDGET;
    if (heap_caps_get_free_size(caps) < PERF_INTERNAL_RESERVE + budget ||
        heap_caps_get_largest_free_block(caps) < budget) {
        ESP_LOGW(TAG, "monitor disabled: preserving internal heap for core/radio/OTA");
        return;
    }
    perf_context_t *context = heap_caps_calloc(1, sizeof(*context), caps);
    if (!context) return;
    // Producers remain disabled until both the context and worker exist.
    s_context = context;
    if (xTaskCreate(worker, "gauge_perf", PERF_TASK_STACK, context,
                    tskIDLE_PRIORITY + 1, NULL) != pdPASS) {
        s_context = NULL;
        heap_caps_free(context);
        ESP_LOGW(TAG, "monitor disabled: no task memory (storage released)");
        return;
    }
    __atomic_store_n(&enabled, 1, __ATOMIC_RELEASE);
}
#endif
