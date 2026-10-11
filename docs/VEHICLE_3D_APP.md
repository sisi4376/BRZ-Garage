# Android 3D 车辆集成（当前统一版 4.1.3）

车辆首页的新版与经典布局均使用 `Vehicle3DView`：本地 WebView 绘制真实 ZD8 / ZC6，底部提供前侧、后侧、车牌特写和车色入口。车辆区域可拖动旋转、双指缩放。卡片增加到 228 dp，其中 36 dp 为原生操作栏。

## 数据与资源

- 车型来自已有 `AppState.selectedVehicleModel`。颜色和亮面/哑光按车型保存在 `vehicle_appearance` 本地偏好中。
- 号码与显示开关沿用已有自定义车牌设置。通过 `LicensePlateArtwork` 的专用矢量字形生成内存 PNG，前后车牌共用纹理；无号码时不显示演示车牌。
- ZD8 前牌中心为模型坐标 0.45 m，ZC6 为 0.44 m；保留已确认的下移校准。数值不是实车安装指导。
- Gradle 的 `syncVehicleAssets` 从 `preview/vehicle-3d` 同步同一套渲染器、GLB、来源文件和许可。仅打包运行文件，不包含原始下载 ZIP、研究材料或布局演示页。
- 模型由 Mona x Supercars / GT Cars: Hyperspeed 提供，CC BY 4.0；车色菜单中的“模型来源与许可”可查看署名与链接。Three.js 0.160.1 为 MIT。

## 生命周期与兼容

资源全部通过 `https://appassets.androidplatform.net` 的本地拦截器读取，不依赖网络服务器。外部请求、页面跳转及文件/content 访问被禁用。号码只在内存与本地页面之间传递，不写入模型文件，也不上传。

相同配置不会在仪表数据刷新时重复传入。静止画面跳过重复绘制，页面不可见时停止绘制循环并恢复默认前侧姿态。MainActivity 在新版和经典首页之间复用同一个 Vehicle3DView，切页只移除并重新挂载视图，不销毁 WebView、模型或纹理。Activity 销毁时释放资源；切换车型才替换模型。WebGL 不可用、渲染进程退出或加载超时后显示原有车辆图，可点击重试。

## 验证记录

- 初次集成为 App 4.1.0（133），当前为 App 4.1.3（136），内置监测固件仍为 4.0.3；沿用原有签名。见 `APP_4_1_3_DISPLAY_MATERIAL.md`。
- `python tools/verify_vehicle_3d_apk.py` 核验 APK 内 13 个运行资源与源文件逐个一致，两个 GLB 无外部贴图/缓冲依赖，许可证已打包。
- 浏览器检查实际内嵌页面：两款模型、六色入口、亮面/哑光、前后视角、车牌特写、车牌开关与 390 px 手机宽度。修复初次测试发现的 CSP 未允许 blob 贴图读取问题。
- 现有车牌测试 8 项通过；首页测试 20 项中 19 项通过。剩余 `test_trip_detail_page_exposes_driving_extremes` 仍硬编码数据库版本 5，而当前项目数据库已升级；该失败与本次车辆渲染改动无关，未修改数据库来迁就断言。
- 当前没有连接的 Android 设备，未声称通过真机 WebView、内存峰值、帧率或持续运行验证。ZD8 约 83.6 万三角面、ZC6 约 14.8 万三角面，正式发布前仍需手机性能验证。

`http://127.0.0.1:8088/preview/vehicle-3d/app-preview.html` 是布局预览，使用相同渲染器，但不是 Android 实机截图。演示号码与空白续航不代表用户实际数据。

安装此版 App 即可使用 3D 首页，无需为这项功能升级仪表。当前项目内置的固件资源属于既有固件工作，3D 移植没有改动仪表协议。

4.1.3 新增设置 → 车辆 → 显示 3D 车辆开关，关闭后使用原版 2D 图片。柔光、车漆与软阴影调整见 `APP_4_1_3_DISPLAY_MATERIAL.md`。
