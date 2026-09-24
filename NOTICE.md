# BRZ Garage 版权与来源声明

## 1. 仪表固件

BRZ Garage 的仪表固件基于
[`steveEcode/obd_brz_gauge`](https://github.com/steveEcode/obd_brz_gauge)
修改开发。

```text
Copyright (C) 2024-2026 steveEcode and contributors
Modifications Copyright (C) 2026 sisi4376
```

上游作者保留其原始代码的版权。sisi4376 对 2026 年在本仓库中自行完成的修改和新增内容主张版权。仪表源码及其构建产物按照 GNU General Public License version 3（GPLv3）发布，完整条款见仓库根目录的 `LICENSE`。

## 2. Android App

BRZ Garage Android App 是本项目独立新增的手机端程序：

```text
Copyright (C) 2026 sisi4376
```

App 的版权声明与上游仪表代码的版权声明相互区分。按本仓库当前发布方案，App 代码同样按照 GPLv3 发布；这不表示上游作者参与了 App 的开发，也不表示 App 是上游项目发布的官方客户端。

## 3. App 内置仪表固件

以下文件是由本仓库中的定制仪表源码编译生成并随 App 打包的 OTA 固件：

```text
android_app/app/src/main/assets/firmware/obd_brz_gauge.bin
```

该文件不是从上游项目或其他第三方发布渠道下载的现成程序。它由 BRZ Garage 项目维护，用于匹配硬件的离线 OTA 更新；它不代表上游作者发布、审核、担保或认可的官方固件。

将该固件作为独立二进制文件随 APK 分发，不改变它自身及其对应源码适用的 GPLv3 条款。对应源代码、构建配置和修改记录均随本仓库提供。

历史回滚目录中的仪表固件亦按相同原则处理。

## 4. 第三方内容

本仓库所含第三方库、组件、字体、图片或其他素材，其版权仍归各自权利人所有，并按各自许可证使用。目录中存在单独 `LICENSE`、`NOTICE` 或 `SOURCE` 文件时，以这些文件中的声明为准。

## 5. 非官方声明

BRZ Garage 是独立的社区项目，与 Subaru Corporation、上游项目作者及相关硬件厂商不存在官方隶属、授权或背书关系。产品名称和商标仅用于说明兼容对象，其权利归相应权利人所有。
