#pragma once
#include "perf_monitor_core.h"
#ifdef ESP_PLATFORM
#include "sdkconfig.h"
#endif
#if defined(CONFIG_OBD_PERF_MONITOR) && CONFIG_OBD_PERF_MONITOR
#include "esp_timer.h"
static inline uint32_t perf_now(void) { return (uint32_t)esp_timer_get_time(); }
void perf_monitor_start(void);
void perf_emit(uint16_t kind, uint16_t arg, uint32_t start_us, uint32_t value);
void perf_request(const uint8_t *data, size_t len);
#else
static inline uint32_t perf_now(void) { return 0; }
static inline void perf_monitor_start(void) {}
static inline void perf_emit(uint16_t k, uint16_t a, uint32_t t, uint32_t v)
{ (void)k; (void)a; (void)t; (void)v; }
static inline void perf_request(const uint8_t *d, size_t n) { (void)d; (void)n; }
#endif
