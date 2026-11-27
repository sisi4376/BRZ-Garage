#pragma once
#include "bsp_obd_dsp/gauge_layout.h"
#include "bsp_obd_dsp/nvs_storage.h"

int ui_active_page(void);

static inline int ui_copy_slot(int type)
{
    int id = ui_active_page();
    return gauge_page_is_copy(id) && gauge_page_type(id) == type
        ? gauge_copy_slot(id) : -1;
}

static inline uint8_t ui_page_item(const nvs_user_cfg_t *cfg, int type, int index)
{
    int slot = ui_copy_slot(type);
    if (slot >= 0) return cfg->copy_items[slot][index];
    switch (type) {
        case 0: return cfg->temp_display_map[index];
        case 1: return cfg->info_display_map[index];
        case 2: return cfg->chart_source_idx;
        default: return cfg->needle_source_idx;
    }
}

static inline void ui_set_page_item(nvs_user_cfg_t *cfg, int type, int index, uint8_t value)
{
    int slot = ui_copy_slot(type);
    if (slot >= 0) cfg->copy_items[slot][index] = value;
    else if (type == 0) cfg->temp_display_map[index] = value;
    else if (type == 1) cfg->info_display_map[index] = value;
    else if (type == 2) cfg->chart_source_idx = value;
    else cfg->needle_source_idx = value;
}
