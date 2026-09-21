# ELM327-emulator 开发流程

## 推荐拓扑

```text
ELM327-emulator (TCP :35000)
          ↓
tools/preview_server.py
          ↓ JSON / HTTP
浏览器 466×466 仪表预览
```

Windows 下优先使用 TCP，不需要安装 com0com 虚拟串口。浏览器本身不能直接连接任意 TCP socket，所以由 `preview_server.py` 负责发送 ELM/OBD 命令和解析响应。

## 启动

终端 1：

```powershell
py -m pip install ELM327-emulator
py -m elm -s car -n 35000
```

终端 2：

```powershell
.\tools\start_preview.ps1 -Source auto
```

访问 <http://127.0.0.1:8080>。页面显示绿色 `ELM327 TCP` 时，数据来自模拟器；黄色 `DEMO DATA` 表示模拟器不可用，预览正在用内置场景并自动重连。

## 本仓库宽松模拟器

Ircama 的 `car` scenario 不一定覆盖 ZD8 的所有 PID。需要所有仪表都持续变化时，可以使用本仓库的开发模拟器：

```powershell
py .\tools\fake_elm327.py --host 127.0.0.1 --port 35000 -v
```

它会为标准 PID 和未知 PID 生成动态响应，适合检查页面动画和采集请求日志；它不代表 ZD8 ECU 的真实数值范围。

## ZD8 标准轮询集

固件已有的 `ZD8 OBD` / `ZD8` profile 与免烧录预览都以 ISO 15765-4 CAN 11-bit 500 kbit/s（ELM protocol 6）为起点，并使用：

- `01 0C` 发动机转速
- `01 0D` 车速
- `01 05` 冷却液温度
- `01 5C` 机油温度
- `01 0F` 进气温度
- `01 04` 发动机负荷
- `01 11` 节气门位置
- `01 42` 控制模块电压

机油温度 `01 5C` 是否在目标车辆上真实可用仍应以实车返回为准。

## 与真机固件的边界

当前免烧录链路通过 TCP 测试数据模型、PID 解析和界面。车载固件仍沿用上游的 BLE ELM327 客户端；把 ESP32 直接连到电脑上的 TCP 模拟器属于下一阶段的可选调试后端，不影响最终上车使用 BLE ELM327。
