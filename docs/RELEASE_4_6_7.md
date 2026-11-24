## 下载与部署

- 安卓手机安装 [BRZ Garage 4.6.7 APK](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/BRZ-Garage-v4.6.7-debug.apk)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/BRZ-Garage-v4.6.7-debug.apk.sha256)）。已有用户直接覆盖安装，停车并保持供电，再从“仪表固件更新”执行 OTA 至 4.1.4-beta.6。
- 首次安装可用 [Windows x64 一键烧录包 r4](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/BRZ-Garage-Flasher-v4.1.4-beta.6-r4-Windows-x64.zip)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/BRZ-Garage-Flasher-v4.1.4-beta.6-r4-Windows-x64.zip.sha256)）。完整解压后连接 USB，运行 Start-Flasher.cmd，按提示确认板型和备份。
- 可选采集工具　[Android OBD Studio beta.12](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/BRZ-OBD-Studio-Android-v1.0.0-beta.12.apk)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/BRZ-OBD-Studio-Android-v1.0.0-beta.12.apk.sha256)）、[Windows OBD Studio 1.6](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/BRZ-OBD-Studio-v1.6.exe)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/BRZ-OBD-Studio-v1.6.exe.sha256)）。
- 手动部署可用 [独立 OTA 镜像](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/obd_brz_gauge.bin)（[SHA-256](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/obd_brz_gauge.bin.sha256)） 和 [固件清单](https://github.com/sisi4376/BRZ-Garage/releases/download/v4.6.7/latest.json)。

## 自 v4.6.6 以来的更新

上一公开版为 Android 4.6.6（177）、固件 4.1.4-beta.3、Android Studio beta.4 和 Windows Studio 1.5。本版为 Android 4.6.7（179）、固件 4.1.4-beta.6、Android Studio beta.12 和 Windows Studio 1.6。

- **仪表轮询**　ZC6、ZD8 的 6MT 与 6AT 交替发送 A/B 组，每组都有转速、车速和 MAF。A 组另查节气门、发动机负荷和燃油系统状态，已知不支持状态时换成指令 λ。B 组另查指令 λ、点火提前角和一项慢数据。进气温度、水温、油温、电压、油箱液位、直接燃油流量和支持位依次轮转。ZC6 油温和支持位在五项组合请求之后单发。
- **回包处理**　支持平铺及带序号的多帧响应，按 PID 和长度解析，允许 ECU 省略不支持的项目，拒绝重复、截断及错序分帧。组合回包连续两次未满足要求时，对应组拆成单项查询。轮询自检支持组合请求。
- **燃油估算**　瞬时和累计油耗按 MAF 与有效指令空燃比估算，缺少有效混合比时使用默认汽油空燃比。直接燃油流量 01 5E 继续采集供对照。6AT 与 6MT 共用减速断油进入、保持和退出条件，允许燃油状态以外的负荷和空气流量证据持续 400 ms 后触发。既有行程数据保留。
- **仪表显示与主 App**　温度、信息、指针及曲线页可选择 IGN 点火提前角，支持负角度、半度、超时占位和副表同步。选项可保存。Android 仪表设置识别 IGN，内置新版镜像，并将 beta.3 加入手动回滚列表。
- **两端采集与回放**　四车型模式跟随新版轮询和拆单规则，实时表解码点火提前角、进气温度及同组各项，统计以同一回包时间更新。保留原始命令、帧和时间戳，组合录制可按原组或实际返回的单 PID 回放。兼容旧 JSONL，保留全量模式的最近样本。
- **Android 一键采集**　从扫描结果主动选择 OBD 并保存地址和车型。先采集 ECU 支持的全部标准 PID 五分钟，再采集车型轮询五分钟，每段从初始化完成后计时。两段分别保存原始记录和摘要，通过任务编号关联。提前停止、阶段失败或导出失败时保留已采数据。支持同名设备区分、高级手动模式和后台阶段通知。
- **Android 蓝牙与诊断**　连接前重新扫描确认已选地址，GATT 失败后释放并重试。按服务和特征属性选择写入与通知，兼容同特征、Indicate 及唯一自定义串口服务。订阅后等待并以 ATZ、ATE0 完整应答验证双向通信。初始化无回复或写入拒绝时重新连接尝试其他通道或写入方式，最多八种组合。记录服务清单、连接状态、原始收发、版本及异常堆栈，支持导出失败片段及最近一次 Java/Kotlin 崩溃日志。修复实时页显示负荷、节气门和油箱液位百分比时的闪退。
- **其他平台**　Windows r4 烧录工具更新内置镜像。鸿蒙沿用 0.6.5（605）源码和 beta.3 资源，本次无新版 HAP。

## 验证与兼容性

用户已测试通过本版分组轮询。未记录该次测试的具体车型、适配器和逐 PID 刷新率；其余车型组合、油耗精度、真机 OTA、USB 烧录和手机后台采集仍待验证。两个 APK 均核对原签名。主 App 内置、独立 OTA 以及 Windows 烧录包中的应用镜像已逐字节核对，SHA-256 为 `fb82e8daf551281a229c5e98d25d24dd633a4051b6e6f4cb6ffabc9ceb276e12`。升级 App 后需另行更新仪表，已有记录和配置可继续使用。
