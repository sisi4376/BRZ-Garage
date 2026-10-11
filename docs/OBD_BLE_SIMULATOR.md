# Windows 蓝牙静态 OBD 模拟器

电脑充当 BLE ELM327 外设，实体 ESP32 仪表作为客户端连接。不需要另一块开发板，也不需要重刷仪表。
仅支持标准 OBD 轮询，测试时选择 **ZD8** 或 **ZD8 OBD** 车型；不模拟 ZC6 的 `21 01` 私有数据或 `ATMA` 被动 CAN 广播。

## 启动与连接

可直接双击项目根目录的 `Start-OBD-Simulator.cmd`，保留打开的窗口即可。它会显示完整请求日志。

在项目根目录打开 PowerShell：

```powershell
.\tools\start_obd_ble_simulator.ps1
```

首次运行会将 Python BLE 依赖安装到 `tools/.cache/obd-ble-python`，需要联网。需要 Windows 10/11、Python 3.10+，且蓝牙适配器及驱动支持 BLE Peripheral Role。不会自动修改蓝牙开关或电脑名称。

1. 开启 Windows 蓝牙，运行脚本，等待输出 `READY`。
2. 仪表选择 ZD8 / ZD8 OBD，进入 **BLE SCAN** 页面。
3. 选择电脑名称（脚本打印 `PC name`，本机为 `SISI-Light`）并绑定；若系统单独设置了蓝牙名称，以实际广播名称为准。仪表按 MAC 绑定，之前绑定真实 OBD 的话必须重新选择；Windows 广播地址可能与脚本打印的适配器地址不同，以扫描结果为准。
4. 出现 `Subscribed gauges: 1` 表示通知已订阅，随后可看到初始化日志；加 `-VerboseLog` 可查看全部 PID 请求。
5. 按 `Ctrl+C` 停止模拟器。测试结束后，在仪表扫描页重新绑定原来的 OBD 适配器。

Windows 使用系统蓝牙名称广播，不提供自定义 `OBDII` 名称。不要在 Windows 设置里搜索仪表来建立此连接：这里由仪表主动连接电脑。

## 数据

| 项目 | 固定值 | PID |
|---|---:|---|
| 转速 | 0 rpm | 01 0C |
| 车速 | 0 km/h | 01 0D |
| 燃油流量 | 0 L/h | 01 5E |
| 进气流量 | 0 g/s | 01 10 |
| 负荷、节气门 | 0% | 01 04 / 01 11 |
| 发动机运行时间 | 0 s | 01 1F |
| 水温、油温、进气温度 | 25°C | 01 05 / 01 5C / 01 0F |
| 电压 | 12.6 V | 01 42 / ATRV |
| 油量 | 约 50.2% | 01 2F |
| 歧管压力、大气压力 | 100 kPa | 01 0B / 01 33 |
| Lambda | 1.0 | 01 44 |

油耗不是独立的 L/100km 标准 PID。模拟器将燃油流量和进气流量都设为 0，避免仪表计算出非零燃油消耗。零车速时的单位显示取决于仪表逻辑；历史行程、平均油耗及累计值不会被此工具清空。零转速表示熄火，仪表可能按现有逻辑进入空闲/行程结束状态。

只在收到请求后返回数据，不主动灌入随机数据。支持位图仅声明已实现的 PID；未知 PID 返回 `NO DATA`。兼容初始化 AT 命令、回显、空格、报头、换行，以及 BLE 分包；每段通知最多 20 字节。不同连接的 ELM 会话独立。

服务为 `FFF0`，写入为 `FFF1`（Write / Write Without Response），通知为 `FFF2`，Windows 自动创建 CCCD。

## 检查与日志

```powershell
# 只检查电脑蓝牙能力，不广播
.\tools\start_obd_ble_simulator.ps1 -Check

# 打印每条命令和响应
.\tools\start_obd_ble_simulator.ps1 -VerboseLog

# 广播 10 秒后自动停止，用于本机冒烟检查
.\tools\start_obd_ble_simulator.ps1 -Duration 10

# 不需要蓝牙的协议测试
python -m unittest discover -s tests -p test_obd_ble_simulator.py
```

如果提示不支持 Peripheral Role，该蓝牙适配器/驱动无法用作模拟器。启动时短暂出现 `Advertising state: ABORTED` 不一定表示失败：Windows 可能随后转为 `STARTED`，脚本会等待最多 10 秒，以 `READY` 为启动成功标志。超过等待时间仍失败才退出；此时可关闭其他 BLE 外设服务再重试。如果 Windows 只能发布部分广播数据，脚本会提示；若扫描不到电脑名称，可关闭其他 BLE 广播程序后重试。若仪表找不到电脑，确认脚本仍显示 READY、电脑未休眠、蓝牙开启，然后重新扫描。广播成功不等于已完成实物收发验证，需看到真实仪表请求日志。

Windows API 依据：[Microsoft GATT Server 文档](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-server)。
