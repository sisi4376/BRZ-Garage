#pragma once
typedef void *SemaphoreHandle_t;
int xSemaphoreTake(SemaphoreHandle_t semaphore, int ticks);
void xSemaphoreGive(SemaphoreHandle_t semaphore);
