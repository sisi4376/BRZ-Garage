# ESP32-S3-Touch-AMOLED-1.75-B 移植说明

## 当前实现

本分支默认硬件目标已切换为微雪 `ESP32-S3-Touch-AMOLED-1.75-B`：

- ESP32-S3R8、16 MB Flash、8 MB Octal PSRAM
- 466×466 QSPI AMOLED，控制器为 CO5300
- CST9217 I2C 双点触摸
- 使用微雪官方 ESP-IDF BSP：`waveshare/esp32_s3_touch_amoled_1_75 ^3.0.1`
- ESP-IDF 最低版本随官方 BSP 提升为 5.5
- `-B` 是带外壳 SKU，与标准 1.75" 型号共用同一 PCB 和 BSP

显示亮度通过 CO5300 的 `0x51` 命令调整。这块 AMOLED 没有传统 GPIO 背光，因此不能沿用旧板 GPIO5 的 LEDC 背光实现。

## 兼容策略

原项目的 SquareLine/LVGL 页面按 360×360 设计。第一阶段在 466×466 根画布上保留这些对象的原始像素尺寸并居中，外围约 53px 作为安全区。这能先保证业务页面、手势和 OBD 数据逻辑稳定，后续再逐页把布局扩展到完整 466px。

免烧录预览现在直接编译 LVGL 8.4.0、原页面 C 文件、原字库和原图片，在 Windows 原生模拟器中生成 466×466 RGB565 帧缓冲；浏览器只显示该帧缓冲并传递切页命令。默认显示 TEMP 页，并提供 GEAR、RPM、SPEED、TEMP、INFO、NEEDLE、CHART 七页。最终 CO5300 色彩和 CST9217 触摸手感仍需真机验证。

## 选择开发板

运行：

```bash
idf.py menuconfig
```

进入 `OBD DSP Configuration → Gauge board`：

- `ESP32-S3-Touch-AMOLED-1.75 / 1.75-B (466x466)`：本分支默认
- `ESP32-S3-Touch-LCD-1.85 (360x360 legacy)`：原上游硬件

也可以在 defconfig 中设置：

```text
CONFIG_OBD_BOARD_AMOLED_175=y
```

## 构建

```bash
idf.py set-target esp32s3
idf.py reconfigure
idf.py build
```

首次构建时组件管理器会下载官方 BSP 及 CO5300、CST9217 驱动。

## 真机验证清单

当前工作区没有安装 ESP-IDF，也没有连接开发板，因此以下项目需要在本地工具链和真机上完成：

1. 冷启动后整屏点亮，无白屏、花屏或残留区域。
2. 亮度滑块能从 10% 到 100% 连续变化。
3. 触摸四角与中心坐标正确，左右/上下滑动方向正确。
4. 七个预览页面对应的 LVGL 页面内容都位于圆形可视安全区。
5. BLE ELM327 连接、ESP-NOW 和 Wi-Fi OTA 在新 BSP 上无内存回归。
6. 连续运行至少 30 分钟，检查 CO5300 DMA 刷新和触摸 I2C 稳定性。

真机出现显示或触摸问题时，先运行微雪官方仓库的 LVGL 和 CST9217 示例，确认硬件/BSP正常，再排查应用层。
