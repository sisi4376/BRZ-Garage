TOOLBIN := tools/.cache/w64devkit/bin
CC := $(TOOLBIN)/gcc.exe
SHELL := $(TOOLBIN)/sh.exe
TARGET := simulator/build/brz_lvgl_sim.exe
LVGL_DIR := simulator/third_party/lvgl

CFLAGS := -B$(TOOLBIN)/ -std=gnu11 -O2 -Wall -Wextra -Wno-unused-parameter -DLV_CONF_INCLUDE_SIMPLE -Dstrlcat=sim_strlcat -include simulator/stubs/sim_compat.h -Isimulator -Isimulator/stubs -I$(LVGL_DIR) -Imain -Imain/export_path
LDLIBS := -lws2_32 -lgdi32 -luser32 -lm

LVGL_SRCS := $(shell find $(LVGL_DIR)/src -name '*.c')
APP_SRCS := simulator/native_sim.c simulator/sim_ui_port.c \
	main/app_obd_dsp/fuel_estimator.c \
	main/export_path/ui_disp_item.c \
	main/export_path/screens/ui_ScreenPageLogo.c \
	main/export_path/screens/ui_ScreenPageGear.c \
	main/export_path/screens/ui_ScreenPageRpm.c \
	main/export_path/screens/ui_ScreenPageRpmWarn.c \
	main/export_path/screens/ui_ScreenPageSpeed.c \
	main/export_path/screens/ui_ScreenPageTemp.c \
	main/export_path/screens/ui_ScreenPageTempCustom.c \
	main/export_path/screens/ui_ScreenPageInfo.c \
	main/export_path/screens/ui_ScreenPageInfoCustom.c \
	main/export_path/screens/ui_ScreenPageNeedle.c \
	main/export_path/screens/ui_ScreenPageOilPressure.c \
	main/export_path/screens/ui_ScreenPageFuel.c \
	main/export_path/screens/ui_ScreenPageTripOverview.c \
	main/export_path/screens/ui_ScreenPageTripHistory.c \
	main/export_path/screens/ui_ScreenPageChartConfig.c \
	main/export_path/screens/ui_ScreenPageChartAlarm.c \
	main/export_path/screens/ui_ScreenPageEasterEgg.c \
	main/export_path/screens/ui_ScreenPageBLEScan.c \
	main/export_path/screens/ui_ScreenPageSettings.c \
	main/export_path/screens/ui_ScreenPageMultiGauge.c \
	main/export_path/screens/ui_ScreenPageODBProtocal.c \
	main/export_path/fonts/ui_font_FontTypoderSize16.c \
	main/export_path/fonts/ui_font_FontTypoderSize20.c \
	main/export_path/fonts/ui_font_FontTypoderSize24.c \
	main/export_path/fonts/ui_font_FontTypoderSize36.c \
	main/export_path/fonts/ui_font_FontTypoderSize40.c \
	main/export_path/fonts/ui_font_FontTypoderSize44.c \
	main/export_path/fonts/ui_font_FontTypoderSize56.c \
	main/export_path/fonts/ui_font_FontTypoderSize140.c \
	main/export_path/images/ui_img_pngblackear_png.c \
	main/export_path/images/pngLogoSTI.c

SOURCES := $(LVGL_SRCS) $(APP_SRCS)
OBJECTS := $(addprefix simulator/build/obj/,$(SOURCES:.c=.o))

.PHONY: all clean

all: $(TARGET)

$(TARGET): $(OBJECTS)
	@mkdir -p $(dir $@)
	$(CC) -B$(TOOLBIN)/ $(OBJECTS) $(LDLIBS) -o $@

simulator/build/obj/%.o: %.c
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	rm -rf simulator/build
