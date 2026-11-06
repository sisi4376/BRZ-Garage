# 本地 ELM327 蓝牙采集与仪表回放

## Windows 图形版（推荐）

直接双击根目录 `BRZ-OBD-Studio-v1.1.exe`（新增油耗和里程统计），无需安装 Python 或联网下载依赖。旧版 `BRZ-OBD-Studio.exe` 保留供正在使用的窗口继续运行。

- **采集**：点击“扫描蓝牙设备”，选择 ELM327，选择采集范围与保存文件夹，点击“开始录制”。也可手动输入 BLE 地址。
- **实时查看**：转速、车速、水温、油温卡片，最近 60 秒转速曲线，以及所有请求的最新原始响应。无数据或超过 15 秒未更新的卡片显示 `—`。
- **油耗与里程**：新增瞬时油耗、有效采样平均油耗、燃油流量、本次积分里程、本次累计耗油、燃油余量、ECU 总里程和电压。低于 3 km/h 时瞬时油耗自动显示 L/h，行驶时显示 L/100km。
- **统计保存**：停止采集后，在原始 JSONL 旁保存同名 `.summary.json`，包含里程、耗油、平均油耗、有效采样秒数和是否含 MAF 估算。
- **停止保存**：点击“停止任务”；关闭窗口也会先停止后台任务并保存数据。默认保存位置是 EXE 同目录 `OBD-Recordings`，可自行选择其他目录。
- **回放**：切到“回放”，选择 JSONL 文件，设置 0.25～4 倍速和循环选项，点击“启动仪表回放”。显示等待仪表后，在仪表上扫描连接这台电脑。最近完成的录制文件会自动填入回放框。
- **错误**：下方日志显示连接、初始化和异常原因；已经接收的录制数据会保留。窗口中的计时是任务运行时间，回放数据时间轴仍从仪表第一次请求数据开始。

图形版与命令行版使用同一套采集和回放协议。仅支持 Windows x64。EXE 将运行环境打包在内，首次启动需稍候数秒。

油耗计算优先使用 ECU 的 `015E` 燃油流量（包括真实的零值）。没有有效 `015E` 时，发动机转速为零则视为停止；有转速和 MAF 时按汽油空气流量估算。估算沿用固件的 745 g/L 汽油密度及 14.7 默认空燃比；有效 `0144` 指令 λ 可修正空燃比（限制在 8～22）。此处未复现固件按车型推断减速断油的策略，因此明确显示“MAF 汽油估算”，不保证与仪表估算完全相同，不适用于柴油或其他燃料假设。

里程由车速积分，耗油由燃油流量积分，均使用原始响应时间戳；每份样本最多保持 5 秒，过期或收到无效响应后不再延续该样本。缺失区间不补算。平均油耗只使用车速和燃油同时有效的时段，累计有效里程不足 10 米时不显示。累计值是本次有效采样的统计，不能替代整段行程实测。默认全部 PID 扫描较慢时，可选择“仪表常用 PID”提高更新频率。

ECU 总里程来自 `01A6`（0.1 km/count），不支持或返回不可用值时显示 `—`，不会用本次积分里程冒充车辆总里程。回放统计使用录制时间轴，倍速不改变距离和耗油的时间单位；循环重播或切换仪表连接会重新累计。回放只统计仪表实际查询到的数据，因此不一定与采集时覆盖范围相同。

开发构建：先在项目独立环境安装 `tools/obd_studio_requirements.txt`，再用该环境运行 `tools/build_obd_studio.py`。生成根目录 EXE 和 SHA-256 校验文件。图形界面使用 PySide6 / Qt，打包使用 PyInstaller。

## 命令行版

需要 Windows 10/11、Python 3.11+ 和仪表当前使用的 BLE ELM327。第一次启动会在项目 `tools/.cache/obd-recorder-venv` 安装依赖，需要联网。传统蓝牙 SPP/COM 型 ELM 不属于此工具的 BLE 连接方式。

## 在车内录制

1. 给 ELM327 和车辆诊断接口上电，开启电脑蓝牙；先让仪表和手机断开 ELM，避免占用连接。
2. 双击根目录 `Start-OBD-Recorder.cmd`，从扫描列表输入 ELM 的序号。
3. 看到 `Polling` 即开始循环采集。按 Ctrl+C 停止；终端会显示保存路径。
4. 文件默认保存到 `logs/obd-recordings/日期时间.jsonl`。每行立即刷入文件，异常中断也能保留此前数据。文件使用独占创建，不覆盖同名旧录制。

连接顺序沿用固件：FFF0、18F0、FF12；按特征属性选择写入和通知通道，FFF0 优先 FFF1 写入、FFF2 通知，并兼容 FFF1 通知。初始化沿用 BRZ 标准 OBD：ATZ、ATE0、ATL0、ATS1、ATH0、ATAT1、ATST19、ATSP6、ATSH7E0。

默认读取标准 Mode 01 支持位图，轮询发动机 ECU 声明支持的全部实时 PID；读不到支持位图时尝试仪表现有的标准 PID 集合。不执行清故障码、编码或执行器控制。支持列表不包含所有厂商私有数据；一次只发送一个请求并等待 ELM 的 `>`。任何查询超时都会保存部分数据并退出，避免迟到数据与下一请求错配。

更多标准 PID 意味着每个 PID 的更新频率下降。仪表动态测试可以用 `gauge` 限定到仪表相关标准 PID；`zc6` 额外请求项目已使用的 `2101`：

```powershell
.\tools\start_obd_recorder.ps1 -Profile gauge -Duration 300
.\tools\start_obd_recorder.ps1 -Profile zc6
.\tools\start_obd_recorder.ps1 -Mode scan
.\tools\start_obd_recorder.ps1 -Address AA:BB:CC:DD:EE:FF
```

首启安装后，可直接使用项目 Python 添加已知只读查询（使用同一 7E0 地址，不自动遍历 ECU 或私有 PID）：

```powershell
.\tools\.cache\obd-recorder-venv\Scripts\python.exe tools\obd_recorder.py record --profile gauge --extra 221017 --extra 2101 --verbose
```

## 回放到真实仪表

1. 断开或拔掉实车 ELM；电脑不需要继续连接车辆。
2. 将 JSONL 文件拖到 `Replay-OBD-Recording.cmd`；也可以双击后输入文件路径。
3. 看到 `READY` 后，在仪表的 BLE SCAN 页面选择这台电脑，使用与录制匹配的 OBD 车型模式。

```powershell
.\tools\start_obd_recorder.ps1 -Mode replay -Recording .\logs\obd-recordings\文件名.jsonl -Speed 1 -LoopReplay
```

回放复用现有 Windows 蓝牙模拟器，要求电脑蓝牙驱动支持 BLE peripheral role。所有 PID 共用一条时间轴，从仪表第一次实时数据请求开始计时，按原始时间选择最近已到达的响应。支持倍速和循环；默认播完返回 NO DATA。每个仪表连接各有独立时间轴；重新连接从头开始。ATZ 只复位 ELM 设置，不重启回放时间轴。

未录到的请求、尚未到达首个采样时间的 PID、不同请求地址均返回 NO DATA，不使用模拟器的静态假值填充。查询间沿用上次采样，保留采样中的 NO DATA 等错误。回放按仪表查询节奏发送，并不逐字节重现无线延时或断线事件。

录制与仪表默认一样使用 ATH0，不含 ECU 响应地址；因此暂不支持 ATH1 回放、不同 header 的替代匹配、多 PID 合并请求的拆分匹配、原始 CAN 监听模式和被动 CAN 仪表模式。私有多行响应在 ATH0 下保留原文回放。标准 PID 是轮询采样，不是同时采样。

## 数据格式与范围

JSONL schema 1：`session` 包含 UTC 起始时间、设备地址和协议；`tx` / `rx` 保留每次发送及每个通知分包的十六进制原始字节；`reply` 保存请求、header、阶段、开始时间、完整响应及 complete 标志；`poll_plan` 保存轮询列表；`stop` / `end` 记录结束。所有 `t` 都是相对会话开始的单调时钟秒数，不受系统校时影响。崩溃留下的末尾不完整行在回放时忽略。

“全量”指电脑收到的全部 ELM 通知，以及本次主动查询的响应，并不代表整车总线上所有数据。ELM 的 ATMA 是另一种监听模式，可能受到网关、带宽和适配器缓冲限制，本工具不把它当作完整 CAN 抓包。参考 [ELM327 官方手册](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf)；BLE 客户端使用 [Bleak 官方接口](https://bleak.readthedocs.io/en/stable/api/client.html)。

软件测试覆盖分包、原始字节保留、超时保存、服务选择、支持位图和回放时序；实际适配器连接和仪表显示仍需实物联调。
