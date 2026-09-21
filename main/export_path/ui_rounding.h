#pragma once

#include <stdint.h>

/* Display-only integer rounding helpers. They avoid floating point and leave
 * the stored/statistical values untouched. Half values round away from zero. */
static inline uint64_t ui_round_div_u64(uint64_t value, uint64_t divisor)
{
    if (divisor == 0) return 0;
    return value / divisor + ((value % divisor) >= (divisor + 1U) / 2U);
}

static inline int32_t ui_round_div_i32(int32_t value, int32_t divisor)
{
    if (divisor <= 0) return 0;
    int64_t magnitude = value < 0 ? -(int64_t)value : (int64_t)value;
    int64_t rounded = magnitude / divisor +
                      ((magnitude % divisor) >= ((int64_t)divisor + 1) / 2);
    return (int32_t)(value < 0 ? -rounded : rounded);
}
