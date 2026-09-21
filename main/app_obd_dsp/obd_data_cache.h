#pragma once

#include <stdint.h>
#include <stdbool.h>
#include "fuel_estimator.h"

#ifdef __cplusplus
extern "C" {
#endif

// Gear enum
typedef enum {
    GEAR_NEUTRAL, // Neutral or unrecognized
    GEAR_1,
    GEAR_2,
    GEAR_3,
    GEAR_4,
    GEAR_5,
    GEAR_6,
    GEAR_7,   // 7-speed such as Porsche 997.2 PDK
    GEAR_8,   // 8-speed such as BMW ZF 8HP
} enGear;

// Direct-gear sources are prioritized so a slower diagnostic reply cannot
// overwrite a fresh CAN broadcast. Ratio inference is used only as fallback.
typedef enum {
    OBD_GEAR_SOURCE_NONE = 0,
    OBD_GEAR_SOURCE_PID_A4,
    OBD_GEAR_SOURCE_OEM_DID,
    OBD_GEAR_SOURCE_CAN,
} obd_gear_source_t;

typedef enum {
    BRAKE_RS485_IDLE = 0,
    BRAKE_RS485_PROBE,
    BRAKE_RS485_OK,
    BRAKE_RS485_TIMEOUT,
    BRAKE_RS485_PARSE_FAIL,
} brake_rs485_status_t;

typedef struct {
    uint16_t rpm;
    uint8_t  speed;
    int16_t  coolant_temp;
    int16_t  oil_temp;
    int16_t  intake_temp;
    int16_t  load_pct;
    int16_t  tps;
    int32_t  bat_mv;
    int16_t  oil_pressure_x10;
    int16_t  boost_x10;
    int16_t  brake_temp_x10;
    int8_t   gear;
    int16_t  afr_x100;
    uint16_t maf_x100;          // mass-air-flow, g/s x100 (0 = unavailable)
    uint32_t fuel_rate_ml_h;     // SAE PID 01 5E, ml/h
    bool     fuel_rate_valid;    // distinguishes a valid fuel-cut zero from unsupported/stale
    brake_rs485_status_t brake_rs485_status;
} obd_data_snapshot_t;

void obd_data_set_rpm(uint16_t rpm);
// RPM override layer: for multi-gauge linkage tests. When enabled, get_rpm returns val; disabling restores the real value.
void obd_data_rpm_override_set(bool en, uint16_t val);
void obd_data_reset_temp_cache(void);
void obd_data_set_oil_temp_invalid(void);
void obd_data_set_speed(uint8_t kmh);
void obd_data_set_coolant_temp(int16_t temp);
void obd_data_set_oil_temp(int16_t temp);   // actual oil temp °C (SSM 22 10 17, A-40)
void obd_data_set_intake_temp(int16_t temp);
void obd_data_set_load_pct(int16_t pct);    // engine load 0~100%
void obd_data_set_tps(int16_t pct);         // throttle opening 0~100%
void obd_data_set_bat_mv(int32_t mv);        // battery voltage mV (e.g. 12000 = 12.0V)
void obd_data_set_oil_pressure_x10(int16_t pressure_x10); // oil pressure, 0.1bar, -1=invalid
void obd_data_set_boost_x10(int16_t boost_x10); // boost gauge pressure, 0.1bar (can be negative), -32768=invalid
void obd_data_set_brake_temp_x10(int16_t temp_x10); // brake temp, 0.1°C
void obd_data_set_gear(int8_t gear);               // direct gear value: -1=R, 0=N, 1+=forward gear, 127=invalid
void obd_data_set_gear_source(int8_t gear, obd_gear_source_t source);
void obd_data_reset_gear(void);
void obd_data_set_brake_rs485_status(brake_rs485_status_t status);
void obd_data_set_afr_x100(int16_t afr_x100);      // air-fuel ratio AFR, ×100 (1470=14.7:1), -1=invalid
void obd_data_set_maf_x100(uint16_t maf_x100);     // mass-air-flow, g/s ×100 (PID 01 10)
void obd_data_set_fuel_rate_ml_h(uint32_t fuel_rate_ml_h); // SAE PID 01 5E; zero is valid
void obd_data_set_fuel_rate_unavailable(void);
void obd_data_set_fuel_status(uint8_t status);
void obd_data_set_fuel_level_x100(uint16_t percent_x100);
int16_t obd_data_get_fuel_level_x100(void); // -1 when missing or older than 90 s
uint32_t obd_data_get_fuel_level_sequence(void); // increments only on a newly decoded PID 01 2F sample
void obd_data_set_factory_odometer_x10_km(uint32_t odometer_x10_km);
void obd_data_reset_factory_odometer(void);
bool obd_data_get_factory_odometer_x10_km(uint32_t *odometer_x10_km); // false until a valid PID 01 A6 response is received
void obd_data_reset_fuel_inputs(void);
void obd_data_get_fuel_sample(fuel_sample_t *out);
uint16_t obd_data_get_rpm(void);
uint8_t  obd_data_get_speed(void);
int16_t  obd_data_get_coolant_temp(void);
int16_t  obd_data_get_oil_temp(void);       // -100 = invalid
int16_t  obd_data_get_intake_temp(void);
int16_t  obd_data_get_load_pct(void);       // -1 = invalid
int16_t  obd_data_get_tps(void);            // -1 = invalid
int32_t  obd_data_get_bat_mv(void);         // -1 = invalid
int16_t  obd_data_get_oil_pressure_x10(void); // -1 = invalid
int16_t  obd_data_get_boost_x10(void); // -32768 = invalid
int16_t  obd_data_get_brake_temp_x10(void); // -1000 = invalid
int8_t   obd_data_get_gear(void);            // validated direct/inferred gear; 127 = unknown
int16_t  obd_data_get_afr_x100(void);        // -1 = invalid
uint16_t obd_data_get_maf_x100(void);        // 0 = unavailable
void     obd_data_get_snapshot(obd_data_snapshot_t *out);
enGear calculate_gear(float rpm, float speed);
void vMileageDataStatisticTask(void);

#ifdef __cplusplus
}
#endif
