#ifndef LV_CONF_H
#define LV_CONF_H

#include <stdint.h>

#define LV_COLOR_DEPTH 16
/* Match the ESP32-S3 firmware so compiled RGB565 assets render identically. */
#define LV_COLOR_16_SWAP 1
#define LV_MEM_CUSTOM 0
#define LV_MEM_SIZE (4U * 1024U * 1024U)
#define LV_TICK_CUSTOM 0
#define LV_DPI_DEF 130

#define LV_USE_LOG 1
#define LV_LOG_LEVEL LV_LOG_LEVEL_WARN
#define LV_LOG_PRINTF 1

#define LV_FONT_MONTSERRAT_12 1
#define LV_FONT_MONTSERRAT_14 1
#define LV_FONT_DEFAULT &lv_font_montserrat_14

#define LV_USE_PERF_MONITOR 0
#define LV_USE_MEM_MONITOR 0
#define LV_USE_REFR_DEBUG 0

#define LV_USE_GPU_SDL 0
#define LV_USE_DRAW_SW 1
#define LV_USE_FS_WIN32 0
#define LV_USE_PNG 0
#define LV_USE_GIF 0
#define LV_USE_BMP 0
#define LV_USE_SJPG 0
#define LV_USE_FFMPEG 0
#define LV_USE_FREETYPE 0

#endif
