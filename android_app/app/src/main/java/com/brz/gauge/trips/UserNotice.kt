package com.brz.gauge.trips

/** Versioned first-run disclosure shown before permissions, BLE or background work starts. */
object UserNotice {
    const val VERSION = 1
    const val SOURCE_URL = "https://github.com/sisi4376/BRZ-Garage"
    const val LICENSE_URL = "https://github.com/sisi4376/BRZ-Garage/blob/main/LICENSE"
    const val NOTICE_URL = "https://github.com/sisi4376/BRZ-Garage/blob/main/NOTICE.md"

    const val SAFETY = """BRZ Garage 目前仍是早期测试版，仪表读数、油耗、续航、里程、挡位、保养提醒和其他推算结果仅供参考，不能替代原厂仪表、维修手册或专业诊断。

请仅在车辆停稳时操作 App 和仪表。固件更新、回滚及设置写入期间可能暂停 OBD 采集和行程记录，必须保持仪表稳定供电。使用者应自行判断设备、车型、系统和改装的兼容性，并对安全驾驶、数据备份和实际车辆维护负责。

本项目不提供远程车锁、发动机控制或远程访问车辆的能力。"""

    const val DATA_AND_PERMISSIONS = """App 会在手机本地保存仪表蓝牙地址、车辆设置、行程、里程、加油、保养、维修、日常花费和可选自定义车牌；仪表也会在自身存储中保存设置、累计统计和有限数量的行程。这些记录会保留到你主动删除、清除应用数据或卸载 App；清除或卸载会删除手机端记录，但不会自动清除仪表中的数据。

App 不要求账号，不包含广告或云端车辆数据库，也不会在后台自动上传上述记录。App 不读取 GPS 位置。网络仅用于用户主动进行的 App 更新检查、安装包下载、GitHub 问题反馈和查看开源文档；提交反馈前由用户检查内容。

附近设备 / 蓝牙权限用于发现、绑定和同步仪表；通知、后台运行与自启动用于保持连接和授时；部分 Android 版本将蓝牙扫描归入位置权限；附近 Wi-Fi 权限仅用于用户主动执行仪表 OTA。系统权限会单独请求，拒绝非必要权限不会视为同意，但对应功能可能不可用。"""

    const val COPYRIGHT_AND_LICENSE = """BRZ Garage Android App：Copyright (C) 2026 sisi4376。

仪表固件基于 steveEcode/obd_brz_gauge 修改：Copyright (C) 2024-2026 steveEcode and contributors；Modifications Copyright (C) 2026 sisi4376。第三方库、字体、图片及其他素材仍归各自权利人所有，并适用各自许可证。

本仓库的 App 代码、定制仪表源码及相应构建产物按 GNU General Public License version 3（GPLv3）发布。你可以在许可证允许的范围内运行、研究、复制、修改和分发；分发修改版本或二进制时须履行 GPLv3 对许可声明及对应源代码等要求。运行本软件本身不以接受 GPL 为前提。

本软件按现状提供，在适用法律允许的范围内不提供适销性、特定用途适用性等保证。完整条款以项目 LICENSE、NOTICE 及第三方声明为准。"""

    const val UNOFFICIAL = """BRZ Garage 是独立社区项目，与 Subaru Corporation、上游项目作者、硬件厂商及相关商标权利人不存在官方隶属、授权、审核或背书关系。Subaru、BRZ 及其他产品名称和商标仅用于说明兼容对象，其权利归各自权利人所有。"""

    const val ACKNOWLEDGEMENT = """点击“同意并继续”表示你已经阅读并理解上述功能边界、风险、数据处理方式、权限用途、版权归属、开源许可和非官方声明，并选择继续使用 App。系统权限仍由你在后续弹窗中分别决定。

如不同意，可以退出且不会启动蓝牙连接、后台同步或网络请求。以后可在“设置 → 应用 → 使用须知与版权”重新查看，并可撤回确认后停止使用；撤回不会自动删除已有本地记录。"""
}
