# Firmware Package

## Windows 离线一键烧录包

### Release 自动发布

`.github/workflows/release-flasher.yml` 在每次 Release **published** 时运行（含 Beta 预发布）：从该 Release 标签的固定提交构建 ESP-IDF 5.5.3 固件，使用默认分支上最新烧录工具（运行时固定其提交），下载并校验官方 esptool，然后在 Windows 上测试备份/写入故障路径、串口识别、实时进度与打包后的 GUI。全部通过后才将 ZIP 和同名 `.zip.sha256` 上传到原 Release；不会修改 APK、标题或 Beta 状态。

文件名中的 `v` 是固件版本（从对应源码读取并与镜像交叉检查），`r` 是工具修订号。App 单独更新时固件版本可以不变。固件升级需在标签对应源码中更新 `CMakeLists.txt` 的 `PROJECT_VER`；修改烧录器时递增打包脚本中的 `FLASHER_REVISION`。

发布后需等 Actions 中 **Release Windows flasher** 成功才有附件；失败时检查该运行日志，不要向用户宣称已准备完成。可以在 Actions 页面手动运行并填写已有 `release_tag`（例如 `v4.0.0`），为旧 Release 补包或重试；仅替换同名烧录包/校验文件，保留其他附件。若由其他工作流使用 `GITHUB_TOKEN` 创建 Release，需显式调用此工作流的 `workflow_dispatch`，不能依赖 `release` 事件递归触发。

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
