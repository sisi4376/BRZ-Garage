## 下载与部署

- [Android BRZ Garage 4.6.8 APK](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/BRZ-Garage-v4.6.8-debug.apk)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/BRZ-Garage-v4.6.8-debug.apk.sha256)）。已有用户可覆盖安装，停车并保持仪表供电，再进入“仪表固件更新”升级至 4.1.6-beta.1。
- [Windows x64 一键烧录包 r4（固件 4.1.6-beta.1）](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/BRZ-Garage-Flasher-v4.1.6-beta.1-r4-Windows-x64.zip)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/BRZ-Garage-Flasher-v4.1.6-beta.1-r4-Windows-x64.zip.sha256)）。首次安装时完整解压，连接 USB，再运行 `Start-Flasher.cmd` 并按提示核对板型和备份。
- 可选数据采集工具：[Android OBD Studio beta.13](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/BRZ-OBD-Studio-Android-v1.0.0-beta.13.apk)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/BRZ-OBD-Studio-Android-v1.0.0-beta.13.apk.sha256)）、[Windows OBD Studio 1.6](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/BRZ-OBD-Studio-v1.6.exe)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/BRZ-OBD-Studio-v1.6.exe.sha256)）。
- 手动 OTA 可用 [独立固件镜像](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/obd_brz_gauge.bin)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/obd_brz_gauge.bin.sha256)） 和 [固件清单](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.8/latest.json)。鸿蒙提供 0.6.5 源码。

## 从 v4.6.7 到 v4.6.8

| 组件 | 上一公开版 | 本版 |
| --- | --- | --- |
| Android BRZ Garage | 4.6.7（179） | 4.6.8（201） |
| 仪表固件 | 4.1.4-beta.6 | 4.1.6-beta.1 |
| Windows 一键烧录 | r4 / 4.1.4-beta.6 | r4 / 4.1.6-beta.1 |
| Android OBD Studio | 1.0.0-beta.12（12） | 1.0.0-beta.13（13） |
| Windows OBD Studio | 1.6 | 1.6 |
| 鸿蒙原生 App | 0.6.5（605）源码 | 0.6.5（605）源码、内置 4.1.4-beta.3 |

### 仪表编排与 Android App

- “版本与设置”固定在第一位，最多另加 20 个可编辑表盘。其余表盘可全部移除、恢复并重排。专用表盘每种限一份，三槽位、四槽位、五槽位、指针、图表可重复添加，每份都有独立数据设置。新增表盘插入当前编号，从第 1 页添加时插入第 2 页。
- 新增四槽位信息页，十字分隔四个数据区，调整每格代号、数据与单位的行距。三槽位与五槽位各格可在支持的数据类型里选择。仪表上左右手势按编排切换，行程历史的上下翻页与行程总览、行程区间之间的上下切换保留。
- App 的“仪表设置 → 基本信息”下新增统一样式的“仪表页面设置”。旧“页面显示”内容迁入编辑器。可左右滑动查看表盘、上下翻阅行程预览，并在列表中调换顺序或修改项目。
- 预览使用原生 LVGL 画面，含温度、信息、四槽位、指针、图表、挡位转速、车速、油量、行程及版本页。挡位转速示例用 BRZ ZD8 6MT 同一车速和齿比计算降挡转速标记。
- 用星标设置当前表盘为默认启动页，支持固定版本页和重复表盘中的某一份。已删除的启动页回退到首个可编辑页，只有版本页时回退到版本页。朝向按充电口相对表盘的位置选择，预览边缘标出对应位置，固件原始方向为右侧。实机启动页与朝向在下一次上电生效。
- 亮度、页面编排及启动设置可在断开时保存到手机，按仪表分别排队。重连后在当前同步操作结束时发送，经固件保存和回读后确认。分块编排在仪表 UI 事件队列应用，兼容旧编排与设置读取格式。旧固件不支持的配置会保留待升级，避免反复写入。原有 OBD 轮询继续按 A/B 组运行。

### 仪表显示与 GPS

- 三槽位的大字数据位于中间，标签沿左侧圆周布置，单位在右侧纵向对齐。上方为 OBD 和 TIME 留出空间。五槽位的信息代号、数据和单位增大，较长读数按可用空间收缩。保留原有 Conthrax 字体。
- 指针刻度扩大至 432 像素，趋势图扩大到 384 × 198 像素并增大读数。转速和车速读数字号由 56 增至 64。油量页放大瞬时、本次行程及累计油耗，分开显示单位。
- “版本与设置”页放大总里程，增加油箱图标和车辆通过 OBD 回报的剩余油量。显示值为 25% 或以下时呈黄色，缺少读数显示 `--%`。
- GNSS 在完成授时后继续接收定位数据。三槽位任意一格或五槽位底格可选择 GPS，经纬度一行显示、保留两位小数。无模块、无效定位或超过 5 秒未刷新时显示 `--`。App 预览提供示例坐标。发布构建启用 GNSS，实验性轮询、自检和性能监控使用独立诊断配置。

### 无线升级与数据兼容

- Android OTA 续传按 64 KiB 边界分块，兼容旧固件靠该边界刷新活动计时，针对 4.1.4-beta.6 续传过程中提前重启的问题。
- 仪表端收到有效字节即更新活动时间，进度按跨越分块边界报告。修复未完成包的判断以及接收、超时退出与安装争用，先停止并等待 HTTP 服务，再释放接收缓存。安装与退出由单个流程接管，减少重复重启。
- Android 包内新增 4.1.4-beta.6 手动回滚，保留之前的回滚版本。较旧固件可继续读取原有行程，使用新版页面编排与 GPS 需要配套固件。使用回滚版本前请备份数据和记下新版表盘编排。

### 数据采集与其他平台

- Android OBD Studio beta.13 的一键流程先逐条探测 `0100` 至 `01FF`，依据有效实时响应确定后续清单，并保存支持位图、每次探测的原始回包和有效 PID 列表。先采集有效 PID 五分钟，再用所选车型轮询五分钟。探测和初始化时间另计。
- 识别标准响应及带序号分帧，探测遇到 `NO DATA` 时继续，超时、断线或提前停止时保留已采片段。两段沿用分开的 JSONL 和摘要，可覆盖安装并保留旧录制。
- Windows OBD Studio 1.6 使用上一版已发布的相同程序。Windows 一键烧录工具 r4 更新内置固件与校验清单。鸿蒙 0.6.5 源码和固件资源沿用上一版，当前无公开 HAP。
