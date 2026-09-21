# 无烧录预览 / No-flash preview

这个预览直接编译并运行原项目的 LVGL 8.4.0 C 源码。原始页面、Conthrax 字库和 BlackEar 图片由原生 Windows 模拟器渲染成 466×466 RGB565 帧缓冲，浏览器只负责显示这张实时帧，不再用 HTML/CSS 重画仪表。

右侧控制面板不属于开发板画面，只用于无烧录开发。桌面端替换的是 CO5300 显示驱动以及 ESP32 外设层；LVGL 对象树、坐标、字体数据、图片数据和绘制代码与固件共用。

## 1. 只看动态界面

```powershell
.\tools\start_preview.ps1 -Source demo
```

打开 <http://127.0.0.1:8080>。默认显示 TEMP 页面，可以在右侧切换 GEAR、RPM、SPEED、TEMP、INFO、NEEDLE、CHART。

## 2. 使用 Ircama ELM327-emulator

先安装并启动模拟器：

```powershell
py -m pip install ELM327-emulator
py -m elm -s car -n 35000
```

再开一个 PowerShell：

```powershell
.\tools\start_preview.ps1 -Source auto
```

原生 LVGL 模拟器会自动连接 `127.0.0.1:35000`。右侧“数据源”显示 `ELM327 TCP（已连接）` 表示 PID 数据来自模拟器；模拟器没有运行时会使用内置动态数据，并每两秒重试。

也可以使用本仓库已有、响应 PID 更宽松的开发模拟器：

```powershell
py .\tools\fake_elm327.py --port 35000 -v
```

## 3. 连接其他电脑上的模拟器

```powershell
.\tools\start_preview.ps1 -ElmHost 192.168.1.50 -ElmPort 35000
```

预览查询的 ZD8 标准 PID：

| 数据 | PID | 公式 |
|---|---:|---|
| 转速 | `01 0C` | `(A×256+B)/4` rpm |
| 车速 | `01 0D` | `A` km/h |
| 水温 | `01 05` | `A-40` °C |
| 机油温度 | `01 5C` | `A-40` °C |
| 进气温度 | `01 0F` | `A-40` °C |
| 发动机负荷 | `01 04` | `A×100/255` % |
| 节气门 | `01 11` | `A×100/255` % |
| 控制模块电压 | `01 42` | `(A×256+B)/1000` V |

ELM327 TCP 连接和 PID 解析由 `brz_lvgl_sim.exe` 完成；`preview_server.py` 只把原生帧缓冲送到浏览器，并传递页面切换命令。HTTP 服务只监听 `127.0.0.1`，默认不会暴露到局域网。
