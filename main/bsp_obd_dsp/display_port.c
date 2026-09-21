#include "sdkconfig.h"
#include "display_port.h"

#if CONFIG_OBD_BOARD_AMOLED_175
#include "bsp/esp32_s3_touch_amoled_1_75.h"
#else
#include "bsp_obd_dsp/lcd_driver/ST77916.h"
#endif

void gauge_display_set_brightness(uint8_t percent)
{
    if (percent > 100) percent = 100;
#if CONFIG_OBD_BOARD_AMOLED_175
    /* CO5300 brightness is command 0x51; this AMOLED has no GPIO backlight. */
    (void)bsp_display_brightness_set((int)percent);
#else
    Set_Backlight(percent);
#endif
}

const char *gauge_display_board_name(void)
{
#if CONFIG_OBD_BOARD_AMOLED_175
    return "waveshare-esp32-s3-touch-amoled-1.75-b";
#else
    return "waveshare-esp32-s3-touch-lcd-1.85";
#endif
}
