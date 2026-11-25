---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 82be59fe8c0c454d99e3059d89db290a_4e042038c52d11f19063525400393706
    ReservedCode1: r9Ve0XGckj3lTKTM/NQHNrfF3H8JssnyRROOwqHGLcD/PF0Qh+7lwo/zsGEpORJpbzgkLS6TiGrvXGsGGA/hcb4RlTO8UGR1V+5wb1NMWmhoEAXshhI20DOCZ1pptTU9zQf2DV1U5C+skdUwCXespmU24xrERKOIL4/WHuhEW7nih+vs8PQln8zPH+c=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 82be59fe8c0c454d99e3059d89db290a_4e042038c52d11f19063525400393706
    ReservedCode2: r9Ve0XGckj3lTKTM/NQHNrfF3H8JssnyRROOwqHGLcD/PF0Qh+7lwo/zsGEpORJpbzgkLS6TiGrvXGsGGA/hcb4RlTO8UGR1V+5wb1NMWmhoEAXshhI20DOCZ1pptTU9zQf2DV1U5C+skdUwCXespmU24xrERKOIL4/WHuhEW7nih+vs8PQln8zPH+c=
---

# P7 SHOW

P7 SHOW（适用于小鹏 P7 8295P芯片）：基于 Android WebView + Three.js 的交互式 3D 车模应用，支持车身/内饰/轮毂/镀铬条自定义、车灯与车门联动、车机状态与胎压续航联动、媒体频谱与 3D 歌词浮窗、SR 增强驾驶场景显示。开源免费，禁止倒卖。

> **开源免费声明**：本软件免费开源，仅限个人学习使用，**禁止倒卖**。
> 请勿购买任何倒卖版本；如需分发请保留版权声明与本开源地址。
> 许可证：[LICENSE](./LICENSE)（Copyright © Hades-Heyds）

## 功能

### 3D 展示与视角

- 拖拽旋转，双指捏合 / 滚轮缩放（OrbitControls）
- 车内视角：模型放大到底自动进入，单指拖拽转头
- 自适应分屏布局（全屏 / 七分屏 / 三分屏），按窗口宽高比自动收紧排版
- 「恢复默认归位视角」一键回到当前场景默认机位
- 近处镜头变焦逻辑，抑制视角畸变

### 外观、场景自定义

- 车身颜色 / 内饰颜色 / 轮毂拼色 / 窗框镀铬条，均支持预设色块与自由取色
- 轮毂新旧款切换
- 场景背景切换（daylig/twilight/night）
- 场景可根据车辆所在地的日出日落时间进行切换

### 车机联动（ADB）

- ADB 连接配置（host / port）与连接状态显示
- 车门六路独立开关，同步车辆状态
- 车灯状态同步
- 档位、车速、车灯状态实时同步
- D 档地图：挂 D 档退后台回主页，回 P 档拉回前台；车机时间同步

### 车辆状态与续航

- 车辆状态页：SOC、续航、百公里 / 平均电耗等指标卡片
- 四轮胎压（压力、温度、状态）
- 常驻续航条 + 时钟 / 日期 / 星期

### 充电

- 充电信息模块：电压 / 电流 / 功率
- 充电模式开关，青绿光幕呼吸动画

### 媒体与频谱

- 媒体封面、歌名歌手、播放 / 下一首、进度条
- 媒体控制双通道：MediaCenter 与 ADB 按键
- 频谱条显示：类火焰渐变光晕效果，光亮颜色支持预设与自由取色
- 3D 歌词浮窗，可开启歌词跟随视角（三分屏 170°）
- 退出前台歌词浮窗开关（默认开）；后台悬浮窗显示歌曲名称与歌词

### SR 增强驾驶场景

- 车模周围叠加独立 3D 图层，显示周边车辆、行人、两轮车、锥桶与车道线
- 覆盖行人 / 轿车 / 大巴 / 卡车 / 自行车 / 摩托车 / 三轮车 7 类白模 + 锥桶，按现实尺寸比例归一
- 数据源支持仪表推流（默认）与本地演示
- 帧间限时插值并到点吸附，断流后保持再渐隐，消除抖动与拖尾
- 信息栏显示车速、限速、智驾状态、信号灯、前 / 后超声波、AG 年龄
- 参数页显示画面帧率、SR 数据帧率与累计帧数
- 内建「部署 SR」与「一键还原」，含部署日志浮层与状态记忆

### 设置与调试

- 抽屉式设置，右上角竖列快捷入口（设置 / SR / 车辆状态）
- 自定义页分三组：车辆自定义 / UI 设置 / 车辆预览
- 参数调试页、光照 / 倒影调试、原生车机接口读取表
- 自定义名称、开源信息与关于页

## 技术栈

- 包名：`com.xpeng.p7show`，应用名：`P7 SHOW`
- compileSdk 36 / minSdk 26 / targetSdk 36
- Gradle 8.9 + AGP 8.7.3（兼容 JDK 17-21 运行）
- three.js r128（本地化，无外部 CDN 依赖）
- 车模：`assets/p7biglight.glb`（glTF 2.0）；SR 白模：`assets/sr/*.glb`（7 类目标 + 锥桶）

## 构建

```bat
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

也可直接用 Android Studio 打开本目录构建。

## 目录结构

```
P7Show/
├─ settings.gradle / build.gradle / gradle.properties
├─ gradlew / gradlew.bat / gradle/wrapper/
└─ app/
   ├─ build.gradle / proguard-rules.pro
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/xpeng/p7show/（MainActivity、CarBridge、AdbClient、Sr* 系列）
      ├─ assets/index.html
      ├─ assets/three.min.js / GLTFLoader.js / OrbitControls.js / EffectComposer 等
      ├─ assets/p7biglight.glb（车模）
      ├─ assets/sr/（SR 白模 7 类 + 锥桶）
      ├─ assets/background/（HDR 天空贴图）
      └─ res/ (layout / values / drawable / mipmap)
```

## 模型来源

Sketchfab · supercarmodels · 2022 XPeng P7 · CC-BY-4.0
发布时请保留原作者署名。
*（内容由AI生成，仅供参考）*
