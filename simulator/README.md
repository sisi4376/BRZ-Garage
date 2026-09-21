# 原生 LVGL 无烧录模拟器

这个目录运行的是项目自己的 LVGL 8.4.0 C 代码，不是 HTML/CSS 仿制界面。

直接参与桌面构建的原项目文件包括：

- `main/export_path/screens/ui_ScreenPage*.c`
- `main/export_path/fonts/ui_font_FontTypoderSize*.c`
- `main/export_path/images/ui_img_pngblackear_png.c`

Windows 代码只提供 466×466 显示缓冲、鼠标/键盘输入和 ELM327 TCP 数据桥接。默认打开 TEMP 页面；点击窗口左/右半边或按方向键切页，Esc 退出。

## 运行

```powershell
.\tools\start_native_sim.ps1
```

强制重新编译：

```powershell
.\tools\start_native_sim.ps1 -Rebuild
```

模拟器会尝试连接 `127.0.0.1:35000` 的 ELM327-emulator。没有模拟器时自动显示动态演示数据，每两秒重连。

## 边界

桌面端使用 Win32 显示驱动，硬件端使用 CO5300/CST9217 BSP；显示驱动不同，但 LVGL 对象树、尺寸、字库、图片和绘制代码来自固件源文件。BLE、NVS、OTA、ESP-NOW 等 ESP32 外设逻辑不会在桌面模拟器中运行。
