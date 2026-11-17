# App 内置仪表固件说明

本目录保存 BRZ Garage Android App 用于离线 OTA 更新和历史回滚的仪表固件。

`obd_brz_gauge.bin` 由本仓库中的定制仪表源码编译生成，不是从上游项目或其他作者的发布渠道下载的现成程序，也不代表上游作者发布、审核或认可的官方固件。`latest.json` 记录与该文件配套的版本、目标硬件、文件大小和 SHA-256，App 会在上传前进行完整性和硬件兼容性校验。

仪表固件基于 `steveEcode/obd_brz_gauge` 修改开发：

```text
Copyright (C) 2024-2026 steveEcode and contributors
Modifications Copyright (C) 2026 sisi4376
```

该固件二进制及其对应源码按照 GNU General Public License version 3（GPLv3）发布。对应源码、构建配置、完整许可证和详细归属说明位于本仓库中；参见根目录的 `LICENSE` 与 `NOTICE.md`。
