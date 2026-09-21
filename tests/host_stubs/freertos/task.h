#pragma once
#include "FreeRTOS.h"
extern TickType_t test_now;
static inline TickType_t xTaskGetTickCount(void) { return test_now; }
static inline UBaseType_t uxTaskGetStackHighWaterMark(TaskHandle_t task) { (void)task; return 1024; }
static inline void vTaskDelay(TickType_t ticks) { (void)ticks; }
static inline int xTaskCreate(void (*fn)(void *), const char *name,
                             unsigned stack, void *arg, unsigned priority, TaskHandle_t *out)
{
    (void)fn; (void)name; (void)stack; (void)arg; (void)priority; (void)out;
    return 1;
}
