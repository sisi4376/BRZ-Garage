# 车型配置开发指南 / Vehicle Configuration Developer Guide

## 概述 / Overview

本项目采用**数据驱动**的车型配置架构。添加新车型**只需编辑 2 个文件**，不需要修改任何解析逻辑代码。

This project uses a **data-driven** vehicle configuration architecture. Adding a new vehicle **only requires editing 2 files** — no parser logic changes needed.

```
默认行为 = OBD2 标准协议 (SAE J1979)
Default  = Standard OBD2 protocol (SAE J1979)

只有在 vehicle_custom_config.h 里声明了的车型，才会使用自定义 CAN 规则或油温公式。
Only vehicles declared in vehicle_custom_config.h use custom CAN rules or oil temp formulas.
```

---

## 文件结构 / File Structure

| 文件 / File | 作用 / Purpose |
|---|---|
| `vehicle_profiles.c` | 车型基础参数 (传动比/档位/轮胎) / Basic params (gear ratios/tire) |
| `vehicle_custom_config.h` | 自定义覆盖 (CAN规则/油温公式/协议) / Custom overrides |
| `vehicle_profiles.h` | 结构体定义 + API / Struct definitions + API |

## 当前 ZC6 / ZD8 产品配置

2026-11-06 车速和里程修正：按用户要求取消 OBD 车速 1～2 km/h 归零，并将里程与耗油量独立累计。有效车速不再受 MAF、燃油流量或 RPM 缺失影响；低于 3 km/h 显示 L/h 的规则保持不变。发布版保护对车速回调采用删除归零语句后的预期值，对油耗模块继续保护原断油判定函数，新的累计行为由可执行测试验证；历史指纹清单保持不变。详见 `FUEL_CALCULATION.md`。

ZC6 按上游 **ZN/C6 PID** 路径实现，手动挡保留原厂齿比；4.1.0 新增 ZC6/ZD8 6AT 基础数据配置，禁用挡位查询、推算和显示；两款车型均采用串行 OBD 请求，不启用 `ATMA` 或挂载 CAN 广播规则。ZD8 仍为默认车型。

| 项目 | ZC6 | ZD8 |
|---|---|---|
| ELM 协议 / 请求头 | `ATSP6` / `ATSH7E0` | 相同 |
| 转速 / 车速 / 水温 | `010C` / `010D` / `0105` | 相同 |
| 油温 | `2101` → `6101` | `015C` → `415C` |
| 调度 | 共用 14 槽轮询，转速穿插，车速每轮四次；油温/水温/进气温/电压目标间隔 5 秒 | 相同 |
| 挡位 | 共用转速与车速配对推算，采用各自传动比；离合分离或样本过期时显示未知 | 相同；另一个任务增加的可选 A0/A4 能力探测及轮询予以保留 |
| 其余功能 | 共用现有油耗、行程、手机通信及显示路径 | 保持现有实现 |

ZC6 油温布局参考 [上游固定版本](https://github.com/steveEcode/obd_brz_gauge/blob/826a5071c842558771498507aa3335e906405d71/main/bsp_obd_dsp/elm327_ble_client.c)：`61 01` 后 38 个数据字节时取 `data[33]`，39 个时取 `data[34]`，温度为原始值减 40。索引从 0 开始，服务和 PID 字节不计入 data。

本地 `zc6_pid.c` 对这一布局增加以下约束：

- 等待完整 ELM 提示符 `>` 后再解析，并在解析结束后唤醒下一次请求，避免跨请求覆盖上下文。
- 接受 ELM 默认 CAN 自动格式化、关闭报文头后的编号分行格式：总长度 `028` / `029`，首行 6 字节，后续行最多 7 字节；也接受完整的单行 `6101` 数据。兼容空格、紧凑十六进制及一次请求回显。
- 验证总长度、每行长度、行号顺序和 `6101`。分行中的 `20`–`2F` 保留为数据，不能再次当成 ISO-TP 序号删除。未知布局、截断、错误、超时或溢出响应均不更新油温。
- ZC6 使用完整的原始值减 40 范围（−40～215°C），沿用用户油温偏移；不再经过可能卡住读数的旧重复值等待滤波。超过 15 秒未收到有效样本后，缓存返回无效值，显示为 `--`。这里的范围是编码范围，并非正常工作温度范围。
- 在线切换车型时，轮询任务重新初始化 ELM 与油温策略；旧车型响应被丢弃。ZC6 初始化阶段也不写入实时读数，ZD8 单独启动时保留发布版处理方式。
- ESP-NOW v6 使用空闲标志位 `0x04` 标记 ZC6 数据来源；只对带标志的报文同步扩展油温范围及无效值。无标志的 ZD8 报文保持发布版油温 setter，且 ZD8 的发送标志、布局和版本号均不变。主表来源在 ZC6/ZD8 间切换时清空旧油温；ZC6 从表需更新固件才能使用这些规则。

这些油温范围、滤波和过期规则仅用于 ZC6；ZD8 的 `015C` 公式、滤波、传动比、轮胎半径和油耗算法保持原样。传输层按请求来源分流：ZC6 在解析结束后唤醒并丢弃超时/溢出报文；ZD8 保持发布版收到提示符即唤醒，以及原有接收超时和溢出处理方式。两款车型仍共用已经修正尾牙系数的车速/挡位方法，不复制算法，也不把上游旧挡位公式带回。

发布版保护：`test_zd8_release_contract.py` 以 `v4.0.0`（`e11162c87593d18e615dcd23c1be1ca2c6a29c2a`）为基准，固定 15 组车速、挡位、油温滤波、ZD8 参数及油耗方法的代码指纹。只忽略注释、格式和性能打点；不会以当前工作区重新生成期望值。行为测试另外验证 ZD8 提前唤醒、强制发送超时、油温缓存及主从表旧规则。用户另外确认保留的 A0/A4、健康检测改动不属于本次 ZC6 修改。

验证：`python -m unittest discover -s tests -p test_zc6_pid.py -v` 编译并执行实际 C 解析器、油温回调和缓存，并测试生产代码中的接收累积及发送顺序。覆盖两种布局全部 256 个油温原始值、`20`–`2F` 行首、截断/乱序/错误报文、所有 BLE 分包切点、缓存过期、计时回绕、车型切换，以及 ZC6 六个前进挡的推算。测试报文是合成夹具，不代表已完成实车验证。

适配边界：保留上游 ZC6 的 4.100 尾牙与 0.314 m 滚动半径，不据此宣称全部年款/市场的传动参数一致。原车若与这些参数不同，挡位推算仍需对应修正；ECU 报文长度与所用 ELM 的输出格式也需实车确认。油压、刹车温度等外部传感器通道不会因选择 ZC6 而自动获得数据。

---

## 添加新车型 / Adding a New Vehicle

### 步骤 1: 基础参数 / Step 1: Basic Parameters

在 `vehicle_profiles.c` 的 `s_profiles[]` 数组末尾添加：

```c
{
    .name = "My Car",                    // 显示名称 / Display name
    .final_drive_ratio = 3.73f,          // 主减速比 / Final drive ratio
    .tire_rolling_radius_m = 0.310f,     // 轮胎滚动半径(m) / Tire rolling radius
    .gear_count = 6,                     // 前进挡数 / Forward gears
    .gear_ratios = {0, 3.63f, 2.38f, 1.56f, 1.18f, 1.00f, 0.81f},
    .gear_tolerance = 0.15f,             // 档位识别容差 / Gear detection tolerance
    // 以下字段可省略，默认值 = 纯 OBD2 标准
    // Fields below are optional, defaults = pure OBD2 standard
},
```

**如果新车只需要标准 OBD2**，到这里就结束了！不需要步骤 2。

**If the new car only needs standard OBD2**, you're done! Skip to step 2 is not needed.

---

### 步骤 2: 自定义覆盖 (可选) / Step 2: Custom Overrides (Optional)

在 `vehicle_custom_config.h` 中添加 CAN 规则和/或油温公式，然后在 `s_vehicle_overrides[]` 表中注册。

#### 2a. CAN 广播解码规则 / CAN Broadcast Decode Rules

如果车型支持通过 CAN 总线广播帧读取数据 (比 OBD2 PID 更快)：

```c
// 定义规则表 / Define rule table
static const can_rule_t can_rules_mycar[] = {
    // CAN_ID  位偏移  位长  乘数        偏移    通道
    { 0x140,   16,    14,   1.0f,       0.0f,  CH_RPM },
    { 0x140,   48,     8,   100.0f/255, 0.0f,  CH_TPS },
    { 0x360,   16,     8,   1.0f,     -40.0f,  CH_OIL_TEMP },
    { 0x360,   24,     8,   1.0f,     -40.0f,  CH_COOLANT },
};
```

**位域说明 / Bit field explanation:**
- `bit_off`: 从 LSB 开始的位偏移。Byte0 bit0 = 0, Byte0 bit7 = 7, Byte1 bit0 = 8, ...
- `bit_len`: 位长度。14 位 = 最大 16383
- `scale/offset`: 最终值 = raw × scale + offset

**可用通道 / Available channels:**
`CH_RPM`, `CH_SPEED`, `CH_OIL_TEMP`, `CH_COOLANT`, `CH_TPS`, `CH_LOAD`, `CH_INTAKE`, `CH_BOOST`

#### 2b. 油温公式 / Oil Temperature Formula

```c
// 标准 PID 01 5C / Standard PID
static const oil_formula_t oil_std = {
    OIL_STD_PID, {0x5C}, 1, 0, 1, 1.0f, -40.0f, 0
};

// UDS Mode 22 (单字节) / UDS single byte
static const oil_formula_t oil_uds_1byte = {
    OIL_UDS_22, {0x11,0x1F}, 2, 0, 1, 1.0f, -50.0f, 0
};

// UDS Mode 22 (双字节大端) / UDS double byte big-endian
static const oil_formula_t oil_uds_2byte = {
    OIL_UDS_22, {0x13,0x10}, 2, 0, 2, 0.01f, -40.0f, 0
};
```

**公式类型 / Formula types:**

| 类型 / Type | 说明 / Description | 命令格式 / Command |
|---|---|---|
| `OIL_STD_PID` | 标准 Mode 01 | `01 XX\r` |
| `OIL_UDS_22` | UDS Mode 22 | `22 XX XX\r` |
| `OIL_SPECIAL` | 特殊解析 (需额外代码) | 自定义 / Custom |

#### 2c. 注册覆盖 / Register Override

在 `s_vehicle_overrides[]` 数组中添加：

```c
{
    .match_name      = "My Car",              // 必须与 vehicle_profiles.c 中的 name 完全匹配
    .can_rules       = can_rules_mycar,       // CAN 规则 (NULL = 不用 CAN)
    .can_rule_count  = 4,
    .oil_primary     = &oil_uds_1byte,        // 油温主公式 (NULL = 标准 01 5C)
    .oil_secondary   = &oil_std,              // 备用公式 (主公式连续失败后回退)
    .forced_protocol = 6,                     // ELM327 协议 (0 = 自动探测)
    .functional_addr = false,                 // true = ATSH7DF (BMW 等)
    .obd_timeout     = 0x0F,                  // ATST 超时 (0 = 默认 0x19)
    .has_boost       = false,                 // 涡轮车 = true
    .poll_gap_ms     = 1,                     // 轮询间隔 ms (0 = 默认 30ms)
},
```

---

## 完整示例 / Complete Example

添加一辆 Mazda MX-5 ND (已有配置，作为参考)：

```c
// vehicle_profiles.c — 基础参数
{
    .name = "MX-5 ND",
    .final_drive_ratio = 2.866f,
    .tire_rolling_radius_m = 0.300f,
    .gear_count = 6,
    .gear_ratios = {0, 5.087f, 2.991f, 2.035f, 1.594f, 1.286f, 1.000f},
    .gear_tolerance = 0.15f,
},

// vehicle_custom_config.h — 自定义覆盖
static const oil_formula_t oil_mazda_1310 = {
    OIL_UDS_22, {0x13,0x10}, 2, 0, 2, 0.01f, -40.0f, 0
};
static const oil_formula_t oil_mazda_111f = {
    OIL_UDS_22, {0x11,0x1F}, 2, 0, 1, 1.0f, -50.0f, 0
};

// 在 s_vehicle_overrides[] 中:
{
    .match_name  = "MX-5 ND",
    .oil_primary = &oil_mazda_1310,
    .oil_secondary = &oil_mazda_111f,
    .obd_timeout = 0x0A,
    .poll_gap_ms = 1,
},
```

---

## 运行时查找逻辑 / Runtime Lookup

```
vehicle_profile_get_override()
    ├── 找到 override → 使用自定义 CAN 规则 / 油温公式
    └── 返回 NULL    → 纯 OBD2 标准协议 (01 0C/0D/05/5C/0F/04/11/42)
```

---

## CAN 规则解析器 API / CAN Rule Parser API

```c
// 通用位域提取 / Generic bit extraction
bool can_extract_bits_le(const uint8_t data[8],
                         uint8_t bit_off, uint8_t bit_len,
                         uint32_t *out);

// 遍历规则表, 匹配 CAN ID, 写入 channels[] / Apply rules to channels
void can_apply_rules(const can_rule_t *rules, uint8_t count,
                     uint16_t can_id, const uint8_t data[8],
                     float channels[CH_COUNT]);

// 构建油温查询命令 / Build oil temp query command
const char *oil_formula_build_cmd(const oil_formula_t *f,
                                  char *buf, size_t buflen);

// 解析油温响应 / Parse oil temp response
int16_t oil_formula_parse_resp(const oil_formula_t *f,
                               const uint32_t *resp_data,
                               uint8_t resp_len);
```

---

## 注意事项 / Notes

1. **name 必须完全匹配** — `vehicle_profiles.c` 和 `vehicle_custom_config.h` 中的 `match_name` 必须字符串完全一致。
2. **OIL_SPECIAL 类型** — 目前用于 Toyota Mode 21 及其他少量遗留特殊解析，这类逻辑较复杂，仍需 `elm327_ble_client.c` 中的专用代码处理。未来可逐步迁移。
3. **CAN 规则中的位偏移** — 采用 SAE J1939 风格的位编号：Byte0 的 LSB = bit 0，Byte1 的 LSB = bit 8。
4. **双字节公式** — `resp_bytes=2` 时按大端序组合：`value = data[byte] * 256 + data[byte+1]`。
5. **回退机制** — `oil_primary` 连续失败 5 次后自动切换到 `oil_secondary`。

---

## 迁移状态 / Migration Status

当前 `vehicle_custom_config.h` 已包含所有车型的覆盖配置数据表。`elm327_ble_client.c` 中的旧 switch/case 代码仍在运行，后续将逐步迁移到通用解析器。新旧代码通过 `vehicle_profile_get_override()` 桥接。

The `vehicle_custom_config.h` already contains override data for all vehicles. The legacy switch/case in `elm327_ble_client.c` is still active and will be migrated to the generic parsers incrementally. The bridge is `vehicle_profile_get_override()`.

## 4.1.0 自动挡配置

追加编号 16=ZC6 6AT、17=ZD8 6AT；1/4 仍为原手动挡，不重用冻结车型编号。NVS、BLE 写入校验和设置回读均支持四项。ZC6 AT 复用 Mode 21 油温与过期策略；ZD8 AT 复用标准 015C。AT 的 gear_count=0、A4 关闭，无变速箱 DID，数字挡位与 GEAR 标签隐藏，转速表仍保留。ESP-NOW v6 新增 0x10 自动挡标志；主从表均需升级 4.1.0。保留 MT 参数不表示 AT 的齿比标定；这些参数不用于 AT 挡位或车速计算，车速直接来自 PID 0D。

油耗仍优先 015E，回退 MAF/AFR；自动挡关闭按 ZD8 MT 日志校准的滑行断油估算。ECU 提供的有效零流量仍被接受，MAF 模式滑行阶段可能偏高，不能视为实车油耗标定完成。基础 PID、FA20 油温、多种适配器、P/R/N/D、起步/巡航/滑行、怠速累计仍需实车验收；本版不提供 P/R/N/D、实际挡位、ATF 温度。

手机端版本 Android 4.6.0(165)、Harmony 0.6.0(600)，均内置 4.1.0。先升级主从仪表，再选择 6AT；旧固件会拒绝新编号，不能仅升级手机。车型外观按 ZC6/ZD8 复用，保养选择与提醒按变速箱过滤，新增自动变速箱油记录且不设未经确认的周期；历史记录不重写。

### 2026-11-06 构建与验证

- ESP-IDF AMOLED 固件 4.1.0 构建通过，应用镜像 2,115,408 字节，3 MB OTA 槽剩余 33%。
- Android 4.6.0 / Harmony 0.6.0 构建通过；两端安装包内固件版本和 SHA-256 与交付 OTA 镜像一致。Android 原调试证书验证通过，Harmony 沿用已授权设备的原私有调试证书并验证通过。
- ZC6 专项 5 项、油耗 5 项、挡位 6 项、手动挡旧版本方法保护 1 项通过。新增执行用例覆盖 AT 停车/行驶/直接挡位污染屏蔽、切回 MT、MAF 累计、ECU 零/正流量和怠速油耗。
- Harmony 128 项宿主测试及 Android→Harmony→Android 数据往返通过。六处 Android 源码差异已对照 Harmony 的车型协议、展示、3D 外观键、保养过滤、版本和嵌入固件检查，更新 57 文件审阅基线；不将该记录视为手机画面验收。
- 未执行实车采集、手机真机界面验收、BLE/ESP-NOW 无线实测或刷写；没有发布到远端。
- 交付目录 `.publish/automatic-4.6.0`：APK、私有调试签名 HAP、仅用于 App OTA 的固件及各自 SHA-256。使用 App 内固件升级入口升级主从表，再在车辆设置选择对应 6AT。旧版 2.6.0 回滚包仍保留，但回滚后不支持 6AT。
