#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "lvgl.h"

typedef enum {
    SIM_PAGE_GEAR = 0,
    SIM_PAGE_RPM,
    SIM_PAGE_SPEED,
    SIM_PAGE_TEMP,
    SIM_PAGE_INFO,
    SIM_PAGE_NEEDLE,
    SIM_PAGE_CHART,
    SIM_PAGE_DEVICE,
    SIM_PAGE_BLE_SCAN,
    SIM_PAGE_SETTINGS,
    SIM_PAGE_MULTI_GAUGE,
    SIM_PAGE_OBD_PROTOCOL,
    SIM_PAGE_LOGO,
    SIM_PAGE_RPM_WARN,
    SIM_PAGE_TEMP_SOURCE,
    SIM_PAGE_INFO_SOURCE,
    SIM_PAGE_NEEDLE_SOURCE,
    SIM_PAGE_CHART_SOURCE,
    SIM_PAGE_CHART_ALARM,
    SIM_PAGE_FUEL,
    SIM_PAGE_TRIP_OVERVIEW,
    SIM_PAGE_TRIP_HISTORY,
    SIM_PAGE_COUNT
} sim_page_id_t;

void sim_ui_create_pages(void);
void sim_ui_show_page(int page);
void sim_ui_handle_gesture(const char *direction);
int sim_ui_current_page(void);
const char *sim_ui_page_name(int page);
void sim_ui_set_elm_connected(bool connected);
void sim_ui_set_rpm_warn_threshold(uint16_t threshold);
