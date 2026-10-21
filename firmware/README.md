# Firmware Package

## 独立固件 4.0.7：GNSS 授时

[固件 4.0.7 Beta 下载](https://github.com/sisi4376/BRZ-Garage/releases/tag/firmware-v4.0.7) 提供 `obd_brz_gauge-v4.0.7-ota.bin`、`firmware-v4.0.7.json` 和 SHA-256 校验文件。本次不发布 APK 或一键烧录包。主页主要下载入口继续指向 [v4.3.10 完整版本](https://github.com/sisi4376/BRZ-Garage/releases/tag/v4.3.10)，其中包含 Android 4.3.10、固件 4.0.6 和 r4 烧录包。

- 1.75 / 1.75-B / 1.75-G 使用同一固件；普通版/B 版外接模块 TX 接仪表 GPIO18，并共地。微雪 LC76G 成品板 VCC 可接仪表 VBUS；串口信号为 3.3V TTL。G 版走板载 I²C，仍需实机验证。
- 时钟无效时后台探测模块并等待有效 RMC，不阻塞正常记录；无模块则退出探测，手机授时保持可用。每次开机成功授时一次即结束 GNSS 接收任务；手机先授时也会结束任务，手机随后断开不会重启 GNSS。模块仍可能耗电，并非物理断电。
- TIME 未授时红色；已授时但手机断开黄色；已授时且 App 保持 BLE 连接绿色。
- 拒绝无效定位和错误时间；要求两个连续时间样本。防止将接收机的 1980 初始日期误认为 2080。低频诊断日志可区分无串口数据、已收到 NMEA 和等待有效定位。

### 升级与回退

此 `.bin` 仅是应用分区镜像，不能当作完整 Flash 镜像写入地址 0。**Android 4.3.10 内置固件仍为 4.0.6，不支持导入本次独立文件，也不会自动升级到 4.0.7。** 初次安装使用上方完整版本；需要 4.0.7 的开发者可检出 `firmware-v4.0.7` 标签，按主页源码烧录说明构建并使用 `tools/flash_gauge.ps1 -Port <串口> -Board AMOLED_175`（默认 Source 模式）。更新前保留原固件与设备备份；需要回退时使用 v4.3.10 的 4.0.6 固件和 r4 烧录包普通重装模式。已保存的旧备份不应删除。

复现构建使用 ESP-IDF 5.5.3，在干净目录中执行 `idf.py -D SDKCONFIG_DEFAULTS=firmware/sdkconfig.release build`。发布配置启用 GNSS，关闭实验性 OBD、自检与性能监控。分区表与 4.0.6 相同。

发布规则：`firmware-` 开头的标签是独立固件发布，自动烧录包工作流跳过；需要补包时可手动触发。主页主要下载入口只有在包含 App 与完整固件包的新版本发布后才更新，不能简单指向最新 Release。

## Windows 离线一键烧录包

2026-10-13：烧录工具修订为 **r4**，固件仍为 **4.0.6**。16 MB 备份改为 1 MB 分段读取；识别到短包等读取传输错误时，仅对失败分段重连重读一次，并使用 115200 波特率（原生 USB 实际速率由 USB 决定）。逐段核对设备身份，完整备份再次与设备校验通过后才写入。重复读取失败、设备变化或备份校验失败均停止；写入失败不自动重试。

r4 已通过模拟短包恢复、重复短包、备份校验失败、设备切换、写入失败等回归测试及打包自检。该结果不代替当前 USB 链路的实机重试；2026-10-13 用户日志中的失败发生在备份 82%，尚未进入写入阶段。

### Release 自动发布

最近一次完整发布的固件为 **4.0.6**（独立更新为上方 **4.0.7**），见 [App 4.3.10 / 固件 4.0.6 发布页](https://github.com/sisi4376/BRZ-Garage/releases/tag/v4.3.10)。`firmware/sdkconfig.release` 保存发布构建配置；复现构建使用 `idf.py -D SDKCONFIG_DEFAULTS=firmware/sdkconfig.release build`。

若草稿中已上传本地验证的烧录 ZIP，发布事件跳过重复构建，保留 APK 与 ZIP 使用同一固件。自动构建优先使用标签内的 `firmware/sdkconfig.release`；上传不覆盖已有同名附件。

`.github/workflows/release-flasher.yml` 在完整版本 Release **published** 时运行（含 Beta 预发布，自动跳过 `firmware-` 开头的独立固件标签）：从该 Release 标签的固定提交构建 ESP-IDF 5.5.3 固件，使用默认分支上最新烧录工具（运行时固定其提交），下载并校验官方 esptool，然后在 Windows 上测试备份/写入故障路径、串口识别、实时进度与打包后的 GUI。全部通过后才将 ZIP 和同名 `.zip.sha256` 上传到原 Release；不会修改 APK、标题或 Beta 状态。

文件名中的 `v` 是固件版本（从对应源码读取并与镜像交叉检查），`r` 是工具修订号。App 单独更新时固件版本可以不变。固件升级需在标签对应源码中更新 `CMakeLists.txt` 的 `PROJECT_VER`；修改烧录器时递增打包脚本中的 `FLASHER_REVISION`。

发布后需等 Actions 中 **Release Windows flasher** 成功才有附件；失败时检查该运行日志，不要向用户宣称已准备完成。可以在 Actions 页面手动运行并填写已有 `release_tag`（例如 `v4.0.0`），为旧 Release 补包或重试；补充缺失的烧录包/校验文件；遇到同名附件会拒绝覆盖。若由其他工作流使用 `GITHUB_TOKEN` 创建 Release，需显式调用此工作流的 `workflow_dispatch`，不能依赖 `release` 事件递归触发。

### 本地打包与验证

`tools/package_windows_flasher.py` 将指定构建目录中的完整固件与 Espressif 官方独立 esptool 打包为 Windows 10/11 x64 ZIP。用户完整解压后双击 `Start-Flasher.cmd`，无需 Python 或 ESP-IDF。详见 [使用说明](../tools/flasher/使用说明.txt)。

维护者先完成固件构建，再下载 [esptool 4.12.0 官方 Windows amd64 ZIP](https://github.com/espressif/esptool/releases/tag/v4.12.0)，运行：

```powershell
python tools/package_windows_flasher.py --build-dir build_3_3_1 --esptool-zip .toolchains/downloads/esptool-v4.12.0-windows-amd64.zip --version 4.0.0
```

输出在 `.publish/flasher/`：离线 ZIP、解压目录及 ZIP 的 SHA-256 文件。指定构建目录必须包含 `flasher_args.json`、`config/sdkconfig.json` 和所有二进制；打包会核验实际镜像版本、项目、芯片、板型配置、分区校验和、Flash 布局及官方工具包 SHA-256，不会静默复用旧 `firmware/release/` 文件。输出已存在时拒绝覆盖，可用 `--output-dir` 指定新目录。

烧录器先完整备份 16 MB Flash，备份不完整则停止。普通重装要求分区布局一致（或为空白新板），不写 NVS，并保留同布局设备的开机动画。勾选“首次安装”才允许替换其他分区布局，同时写入配套开机动画。写入后额外验证所写分区，再重启；不执行整片擦除。备份默认保存到 `%LOCALAPPDATA%\BRZ-Garage\Flasher\backups\`，故障时保留日志和备份。

无设备验证：

```powershell
python -m unittest discover -s tests -p test_windows_flasher.py -v
powershell -NoProfile -ExecutionPolicy Bypass -File .publish/flasher/BRZ-Garage-Flasher-v4.0.0-r3-Windows-x64/flash_worker.ps1 -PackageRoot .publish/flasher/BRZ-Garage-Flasher-v4.0.0-r3-Windows-x64 -ValidateOnly
powershell -NoProfile -STA -ExecutionPolicy Bypass -File .publish/flasher/BRZ-Garage-Flasher-v4.0.0-r3-Windows-x64/Flasher.ps1 -SelfTest
```

自动化测试使用模拟工具，不连接真实串口；成功打包或通过测试不等于已完成实机烧录验证。

2026-10-04，r3 已在连接的 ESP32-S3 / 16 MB 仪表上执行一次 GUI 共用的 `flash_worker.ps1` 正常重装流程：完整备份并校验 SHA-256，写入 bootloader、分区表、OTA 初始化和应用固件，4 个区域全部 `verify OK` 后发出重启指令，退出码为 0。该模式未写 NVS 和开机动画。首次安装路径及其他电脑/硬件仍需分别验证；此记录不代表所有设备均已验证。

## Legacy release directory

This directory contains pre-built firmware binaries ready to flash onto an ESP32-S3 board.

本目录包含可直接烧录到 ESP32-S3 开发板的预编译固件。

## Files

| File | Description |
|------|-------------|
| `release/bootloader/bootloader.bin` | Bootloader |
| `release/partition_table/partition-table.bin` | Partition table |
| `release/ota_data_initial.bin` | OTA data initial partition |
| `release/obd_brz_gauge.bin` | Application firmware |
| `release/bootmedia.bin` | Boot animation media (SPIFFS partition) |
| `release/latest.json` | Release manifest (build tag, file hashes) — the app fetches this to detect a new firmware |
| `release/flash_address_map.txt` | Flash address reference |

## Flash Offsets

| Offset | File |
|--------|------|
| `0x0` | `release/bootloader/bootloader.bin` |
| `0x8000` | `release/partition_table/partition-table.bin` |
| `0xf000` | `release/ota_data_initial.bin` |
| `0x20000` | `release/obd_brz_gauge.bin` |
| `0x620000` | `release/bootmedia.bin` |

## Example Flash Command

Flash all partitions at once:

```bash
esptool.py --chip esp32s3 -p PORT -b 460800 write_flash \
  0x0 release/bootloader/bootloader.bin \
  0x8000 release/partition_table/partition-table.bin \
  0xf000 release/ota_data_initial.bin \
  0x20000 release/obd_brz_gauge.bin \
  0x620000 release/bootmedia.bin
```

## Notes

- All binaries are built from the current source tree.
- The `bootmedia.bin` partition contains boot animation assets and is optional if you do not need the animated startup sequence.
- `release/latest.json` is the release manifest the companion app fetches to detect and verify a new firmware; its `firmware.count`/`build_tag` fields are compared against the device's own manifest (see [docs/APP_INTEGRATION.md](../docs/APP_INTEGRATION.md)). Regenerate it whenever the release binaries change.
- See the flash address map in `release/flash_address_map.txt` for full layout details.

- 所有二进制文件均从当前源码树构建。
- `bootmedia.bin` 分区包含开机动画资源，如不需要可跳过烧录。
- 完整分区布局参见 `release/flash_address_map.txt`。
