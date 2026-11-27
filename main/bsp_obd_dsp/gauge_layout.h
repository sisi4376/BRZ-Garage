#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <string.h>

#define GAUGE_PAGE_COUNT 20
#define GAUGE_COPY_SLOTS 19
#define GAUGE_COPY_FIELDS 5
#define GAUGE_ITEM_SLOTS (10 + GAUGE_COPY_SLOTS * GAUGE_COPY_FIELDS)
#define GAUGE_PAGE_SYSTEM (9 + 4 * GAUGE_COPY_SLOTS)
#define GAUGE_TYPE_QUAD 9
#define GAUGE_QUAD_FIRST (GAUGE_PAGE_SYSTEM + 1)
#define GAUGE_PAGE_ID_LIMIT (GAUGE_QUAD_FIRST + GAUGE_COPY_SLOTS)
#define GAUGE_ITEM_GPS 13
#define GAUGE_PAGE_UNUSED 255
#define GAUGE_DEFAULT_ORDER {4, 5, 0, 1, 3, 2, 6, 8, 7, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255}

typedef struct {
    uint8_t pages[GAUGE_PAGE_COUNT];
    uint8_t items[GAUGE_ITEM_SLOTS]; /* TEMP x3, INFO x5, needle, chart */
} gauge_layout_t;

/* Built-ins have fixed IDs. Extra instances encode template and storage slot. */
static inline int gauge_page_type(int id)
{
    if (id < 9 || id == GAUGE_PAGE_SYSTEM) return id;
    return id >= GAUGE_QUAD_FIRST ? GAUGE_TYPE_QUAD : (id - 9) / GAUGE_COPY_SLOTS;
}

static inline int gauge_copy_slot(int id)
{
    return id >= GAUGE_QUAD_FIRST ? id - GAUGE_QUAD_FIRST : (id - 9) % GAUGE_COPY_SLOTS;
}

static inline bool gauge_page_is_copy(int id)
{
    return id >= 9 && id < GAUGE_PAGE_ID_LIMIT && id != GAUGE_PAGE_SYSTEM;
}

static inline int gauge_page_count(const uint8_t *pages)
{
    unsigned seen = 0;
    int count = 0;
    for (int i = 0; i < GAUGE_PAGE_COUNT; ++i) {
        unsigned id = pages[i];
        if (id == GAUGE_PAGE_UNUSED) continue;
        if (id == GAUGE_PAGE_SYSTEM || id >= GAUGE_PAGE_ID_LIMIT || i != count) return -1;
        unsigned slot = id < 9 ? id : 9 + gauge_copy_slot(id);
        if (seen & (1U << slot)) return -1;
        seen |= 1U << slot;
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

static inline uint8_t gauge_startup_page(const uint8_t *pages, uint8_t configured)
{
    if (configured == GAUGE_PAGE_SYSTEM ||
        (configured < GAUGE_PAGE_ID_LIMIT && gauge_page_index(pages, configured) >= 0))
        return configured;
    return pages[0] == GAUGE_PAGE_UNUSED ? GAUGE_PAGE_SYSTEM : pages[0];
}

/* The permanent device page provides access to pairing and settings. */
static inline int gauge_page_step(const uint8_t *pages, int id, bool forward)
{
    int count = gauge_page_count(pages);
    int at = gauge_page_index(pages, id);
    if (count <= 0) return GAUGE_PAGE_SYSTEM;
    if (at < 0) return pages[forward ? 0 : count - 1];
    int next = at + (forward ? 1 : -1);
    return next >= 0 && next < count ? pages[next] : GAUGE_PAGE_SYSTEM;
}


/* Version, ordered IDs, independent signal maps, little-endian CRC16.
 * Transfer fixed-size chunks so layout writes work at the default ATT MTU. */
#define GAUGE_LAYOUT_WIRE_SIZE (1 + GAUGE_PAGE_COUNT + GAUGE_ITEM_SLOTS + 2)
#define GAUGE_CHUNK_SIZE 16
#define GAUGE_CHUNKS (GAUGE_LAYOUT_WIRE_SIZE / GAUGE_CHUNK_SIZE)

static inline void gauge_layout_pack(uint8_t *b, const gauge_layout_t *layout)
{
    b[0] = 2;
    memcpy(b + 1, layout->pages, GAUGE_PAGE_COUNT);
    memcpy(b + 1 + GAUGE_PAGE_COUNT, layout->items, GAUGE_ITEM_SLOTS);
}

static inline bool gauge_layout_items_valid(const gauge_layout_t *layout)
{
    for (int i = 0; i < GAUGE_ITEM_SLOTS; ++i) {
        if (layout->items[i] < GAUGE_ITEM_GPS) continue;
        if (layout->items[i] != GAUGE_ITEM_GPS) return false;
        if (i < 3 || i == 7) continue; /* Built-in TEMP rows and INFO slot 5. */
        if (i < 10) return false;
        int field = (i - 10) % GAUGE_COPY_FIELDS;
        if (field == 3) return false;
        /* Copy storage is shared by all templates. Ignore dormant fields. */
        int slot = (i - 10) / GAUGE_COPY_FIELDS;
        for (int j = 0; j < GAUGE_PAGE_COUNT; ++j) {
            int id = layout->pages[j];
            if (gauge_page_is_copy(id) && gauge_copy_slot(id) == slot &&
                gauge_page_type(id) != (field == 4 ? 1 : 0)) return false;
        }
    }
    return true;
}

static inline bool gauge_layout_unpack(const uint8_t *b, gauge_layout_t *layout)
{
    if (b[0] != 2) return false;
    memcpy(layout->pages, b + 1, GAUGE_PAGE_COUNT);
    memcpy(layout->items, b + 1 + GAUGE_PAGE_COUNT, GAUGE_ITEM_SLOTS);
    return gauge_page_count(layout->pages) >= 0 && gauge_layout_items_valid(layout);
}
