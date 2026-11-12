#pragma once
#include <stddef.h>
#include <stdint.h>

/* Clockwise quarter turns, relative to the board's existing panel mapping.
 * Rotate a complete flush rectangle so even-aligned panel boundaries remain
 * even. LVGL's generic chunked rotation can produce odd-width CO5300 writes. */
static inline void gauge_rotate_rgb565(const uint16_t *src, uint16_t *dst,
                                      unsigned width, unsigned height, uint8_t turns)
{
    for (unsigned y = 0; y < height; ++y) {
        for (unsigned x = 0; x < width; ++x) {
            size_t target;
            switch (turns) {
            case 1: target = (size_t)x * height + height - 1U - y; break;
            case 2: target = (size_t)(height - 1U - y) * width + width - 1U - x; break;
            case 3: target = (size_t)(width - 1U - x) * height + y; break;
            default: target = (size_t)y * width + x; break;
            }
            dst[target] = src[(size_t)y * width + x];
        }
    }
}

static inline void gauge_rotate_area(int *x, int *y, int *width, int *height,
                                     int screen_width, int screen_height, uint8_t turns)
{
    int ox = *x, oy = *y, w = *width, h = *height;
    switch (turns) {
    case 1: *x = screen_width - oy - h; *y = ox; *width = h; *height = w; break;
    case 2: *x = screen_width - ox - w; *y = screen_height - oy - h; break;
    case 3: *x = oy; *y = screen_height - ox - w; *width = h; *height = w; break;
    default: break;
    }
}
