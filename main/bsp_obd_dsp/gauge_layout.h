#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <string.h>

#define GAUGE_PAGE_COUNT 12
#define GAUGE_ITEM_SLOTS 19
#define GAUGE_PAGE_SYSTEM 12
#define GAUGE_PAGE_UNUSED 255
#define GAUGE_DEFAULT_ORDER {4, 5, 0, 1, 3, 2, 6, 8, 7, 255, 255, 255}

typedef struct {
    uint8_t pages[GAUGE_PAGE_COUNT];
    uint8_t items[GAUGE_ITEM_SLOTS]; /* TEMP x3, INFO x5, needle, chart */
} gauge_layout_t;

static inline int gauge_page_count(const uint8_t *pages)
{
    unsigned seen = 0;
    int count = 0;
    for (int i = 0; i < GAUGE_PAGE_COUNT; ++i) {
        unsigned id = pages[i];
        if (id == GAUGE_PAGE_UNUSED) continue;
        if (id >= GAUGE_PAGE_COUNT || i != count || (seen & (1U << id))) return 0;
        seen |= 1U << id;
        ++count;
    }
    return count;
}

static inline int gauge_page_index(const uint8_t *pages, int id)
{
    for (int i = 0; i < GAUGE_PAGE_COUNT; ++i)
        if (pages[i] == id) return i;
    return -1;
}

/* The permanent device page provides access to pairing and settings. */
static inline int gauge_page_step(const uint8_t *pages, int id, bool forward)
{
    int count = gauge_page_count(pages);
    int at = gauge_page_index(pages, id);
    if (!count) return GAUGE_PAGE_SYSTEM;
    if (at < 0) return pages[forward ? 0 : count - 1];
    int next = at + (forward ? 1 : -1);
    return next >= 0 && next < count ? pages[next] : GAUGE_PAGE_SYSTEM;
}


/* Twelve page IDs and nineteen signal IDs, each packed into a nibble.
 * The complete versioned frame with CRC fits the default BLE ATT MTU. */
#define GAUGE_LAYOUT_WIRE_SIZE 19
static inline void gauge_layout_pack(uint8_t *b, const gauge_layout_t *layout)
{
    memset(b, 0, GAUGE_LAYOUT_WIRE_SIZE);
    b[0] = 1;
    for (int i = 0; i < GAUGE_PAGE_COUNT; ++i)
        b[1+i/2] |= (layout->pages[i] & 15) << ((i%2)*4);
    for (int i = 0; i < GAUGE_ITEM_SLOTS; ++i)
        b[7+i/2] |= layout->items[i] << ((i%2)*4);
}

static inline bool gauge_layout_unpack(const uint8_t *b, gauge_layout_t *layout)
{
    if (b[0] != 1 || (b[16] & 0xf0)) return false;
    for (int i = 0; i < GAUGE_PAGE_COUNT; ++i) {
        uint8_t id = (b[1+i/2] >> ((i%2)*4)) & 15;
        layout->pages[i] = id == 15 ? GAUGE_PAGE_UNUSED : id;
    }
    for (int i = 0; i < GAUGE_ITEM_SLOTS; ++i) {
        layout->items[i] = (b[7+i/2] >> ((i%2)*4)) & 15;
        if (layout->items[i] >= 13) return false;
    }
    return gauge_page_count(layout->pages) > 0;
}
