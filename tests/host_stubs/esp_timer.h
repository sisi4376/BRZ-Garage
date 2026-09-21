#pragma once
#include "freertos/FreeRTOS.h"
extern TickType_t test_now;
static inline int64_t esp_timer_get_time(void) { return (int64_t)test_now * 1000; }
