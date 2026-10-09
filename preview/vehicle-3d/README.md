# BRZ 3D 车辆功能模拟

在仓库根目录运行 `python -m http.server 8088 --bind 127.0.0.1`，打开 http://127.0.0.1:8088/preview/vehicle-3d/ 。模型和 Three.js 均在本地，无运行时 CDN 或账号要求。使用 ES modules 和模型加载器，必须通过 HTTP 打开，不能直接双击 HTML。

## 已接入的真实 BRZ 模型

| 车型 | 预览资产 | 三角面 | GLB 大小 | 独立车漆材质 |
| --- | --- | ---: | ---: | --- |
| ZD8 第二代 | `models/zd8/vehicle.glb` | 836,008 | 40,086,960 字节 | `2022_Subaru_BRZ_WR_Blue_Pearl` |
| ZC6 第一代 | `models/zc6/vehicle.glb` | 148,158 | 8,929,648 字节 | `body` |

来源、原始下载包和许可文件在各车型目录中。`source.json` 记录来源；`audit.json` 记录打包结果；`original/license.txt` 保留下载包内原始署名。页面同时显示模型、作者与 CC BY 4.0 链接。

- ZD8：https://sketchfab.com/3d-models/subaru-brz-zd8-2e8aca0407ae44d2802bc34761054a69
- ZC6：https://sketchfab.com/3d-models/subaru-brz-zc6-321a9ece66bb4616872892c01d279542
- 作者主页：https://sketchfab.com/Car2022 （当前显示名 Mona x Supercars；ZD8 下载包署名为 GT Cars: Hyperspeed）
- 许可：https://creativecommons.org/licenses/by/4.0/

修改说明：移除不可见辅助线/透明占位面、将 glTF 和贴图合并为 GLB、在运行时按统一比例居中、替换独立车漆材质并添加前后立体车牌。未进行减面，不代表已完成手机性能优化；尤其 ZD8 正式接入前仍需减少面数、压缩资源并实机验证。未对第三方模型的实车尺寸精度作保证。

重新打包：`python preview/vehicle-3d/prepare_models.py zd8 zc6`。原始资源保留在 `models/<车型>/original`，脚本不会修改原始文件。

## 交互

支持 ZD8/ZC6 切换、拖拽旋转、滚轮/双指缩放、前侧/侧面/后侧/车牌特写、自动旋转、车身换色、亮面/哑光、车牌文字及样式、前后车牌显示开关、当前浏览器外观记忆。

车牌为车辆节点下的三维底座、铝制薄板、文字纹理及四颗螺丝，参与深度遮挡。安装高度按车型设置，前后距离通过车身表面射线检测校准。切换车型后卸载上一模型的几何、材质和贴图资源；静止时跳过重复绘制。

2026-10-06 车牌位置校正：参考 [Subaru 2021 年 11 月 TechTIPS 第 7 页](https://static.nhtsa.gov/odi/tsbs/2021/MC-10205917-0001.pdf) 的 ZD8 前后保险杠固定点示意，以及 [2022MY 后车牌安装公告](https://static.nhtsa.gov/odi/tsbs/2022/MC-10210824-0001.pdf)。ZD8 前牌中心从模型坐标 0.49 m 下移到 0.45 m，拉开与车标的间隔；后牌仍位于后保险杠牌照凹槽。两款模型共用的底座厚度从 33 mm 减为 14 mm，并按完整底座范围检测车身表面。高度与厚度为视觉模拟参数，不是原厂测量尺寸或安装指导；美规固定点示意仅用于确认车身区域，不直接套用到中国车牌的孔距。随后按用户反馈将 ZC6 前牌中心从 0.48 m 下移至 0.44 m，增大车标下方间距；ZC6 后牌高度保持 0.66 m。该调整同样为视觉校准，不引用 ZD8 资料作为 ZC6 的尺寸依据。

`real-models.js` 是当前真实模型预览入口；`app.js` 保留上一版程序化概念车实现作为历史参考，不在当前页面加载。Three.js 0.160.1（MIT）及加载器在 `vendor`，许可见 `vendor/THREE-LICENSE.txt`。

`index.html` 保留独立浏览器原型；`app.html` 是 Android 4.1.0 首页复用的嵌入入口，由原生代码传入车型、外观与车牌图案。`app-preview.html` 展示 App 卡片布局和同一渲染器，使用演示号码，不是手机实拍。Gradle 仅同步嵌入入口和必要资产到 APK。集成与验证记录见 `docs/VEHICLE_3D_APP.md`。

2026-10-06 后续复核：ZD8 后牌从 0.61 m 调整至 0.51 m，ZC6 从 0.66 m 调整至 0.75 m，分别居中于后保险杠与尾门凹槽。新增居中旋转相机模块、高分辨率车牌、暗棚车漆反射和切页保留模型。当前 Android 版 4.1.2；资料、解释和验证见 `docs/APP_4_1_2_VISUAL_CACHE.md`。
