#pragma once
// ================================================================
//  app_event.h — cross-task event queue
//
//  All cross-task communication goes through this queue, eliminating race conditions from direct function calls.
//  Producers (ESP-NOW callback / BLE callback): app_event_send()
//  Consumers (LVGL task / poll task):           app_event_recv()
//
//  Migration plan:
//    1. ESP-NOW recv → ui_showroom_set_page_from_sync(): change to app_event_send()
//    2. BLE disconnect → directly modifying s_connected: change to app_event_send()
//    3. Handle all events via app_event_recv() inside the LVGL task my_timerMain
// ================================================================

#include <stdint.h>
#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    // ESP-NOW → UI
    APP_EVT_ESPNOW_SYNC_SLOT = 1,   // data.u8 = slot (0-7)
    APP_EVT_ESPNOW_INTRO_STEP,      // data.u8 = intro_step (boot animation progress)
    APP_EVT_ESPNOW_ENTER_SHOWROOM,  // no data
    APP_EVT_ESPNOW_OBD_DATA,        // master gauge OBD data received by the sub-gauge (already handled in espnow_link)
    APP_EVT_ESPNOW_THRESH_SYNC,     // data.u16 = RPM alarm threshold synced from another gauge (linkage mode)

    // BLE → poll task
    APP_EVT_BLE_CONNECTED,          // no data
    APP_EVT_BLE_DISCONNECTED,       // no data

    // Phone companion → UI/config task
    APP_EVT_PHONE_BRIGHTNESS,       // data.u8 = validated display brightness (10-100%)
    APP_EVT_PHONE_VEHICLE_PROFILE,  // data.u8 = validated supported BRZ profile index
    APP_EVT_PHONE_ODOMETER_DISPLAY, // data.u8 = show shared odometer on gauge/app home
    APP_EVT_PHONE_ODOMETER_CALIBRATE, // data.u32 = calibrated odometer in 0.1 km
    APP_EVT_PHONE_REFUEL_RESET,     // manually close the current since-refuel interval
    APP_EVT_PHONE_REFUEL_DELETE,    // data.u32 = saved refuel reset-node id
    APP_EVT_PHONE_REFUEL_THRESHOLD, // data.u32 = automatic refuel threshold in ml
    APP_EVT_PHONE_REFUEL_DISCARD_OLDEST, // data.u32 = expected oldest reset-node id
    APP_EVT_PHONE_CUSTOM_TRIP_BASELINE, // data.trip_baseline = App's persisted lifetime baseline

    // Internal
    APP_EVT_BOOT_VIDEO_DONE,        // video playback finished
    APP_EVT_SHOWROOM_EXIT,          // exit showroom
} app_event_type_t;

typedef struct {
    app_event_type_t type;
    union {
        uint8_t  u8;
        uint16_t u16;
        int16_t  s16;
        uint32_t u32;
        struct {
            uint32_t distance_m;
            uint32_t duration_s;
            uint32_t fuel_ml;
        } trip_baseline;
    } data;
} app_event_t;

#define APP_EVENT_QUEUE_DEPTH 16

// Init (called once from app_main)
void app_event_init(void);

// Send an event (any task, non-blocking)
bool app_event_send(app_event_type_t type, uint32_t data);

// Copy the complete custom-trip baseline into the queue so a later BLE write
// cannot overwrite it before the UI/config task persists it.
bool app_event_send_custom_trip_baseline(uint32_t distance_m,
                                         uint32_t duration_s,
                                         uint32_t fuel_ml);

// Receive an event (non-blocking, returns false when there is no event)
bool app_event_recv(app_event_t *out);

// Get the queue handle (for xQueueReceive with timeout)
QueueHandle_t app_event_queue(void);

#ifdef __cplusplus
}
#endif
