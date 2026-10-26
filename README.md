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

P7 Show｜基于 Android WebView+Three.js 的初代小鹏 P7 交互式 3D 车模展示，供已获取 ADB 权限的 P7 车机使用。
本应用推荐 8295P 芯片的小鹏 P7 上运行；820A 芯片机型支持运行，但受限于性能存在明显的卡顿。

## 功能
- 3D 车模展示与交互（拖拽手动旋转/双指捏合/默认视角自定义）
- HDRI 场景切换（daylight / twilight / night 三套 + 可随时间自动变换）
- 自定义车漆配色（5 套原厂预设 + 自定义调色）
- P7天玑系统分屏模式适配（三分屏/七分屏/全屏适配）
- 3D 悬浮桌面歌词，可随视野跟随转动（同步车机自带音乐软件）
- 音频频谱可视化（读取音乐播放状态与麦克风触发）
- 红绿灯状态联动（liblight.so + 扫码激活）
- UI信息展示：续航、时间等

- 车门状态联动：四车门以及前备箱、后尾箱开关状态与车辆同步
- 充电状态联动：充电布景，显示充电数据
- 档位状态联动：P/非P档切换（可选择视角切换，或七分屏/全屏状态下D档切换至系统地图，P档返回3D桌面）

## 技术栈

- 包名：`com.show.p7`，应用名：`P7 SHOW`
- compileSdk 36 / minSdk 24 / targetSdk 36
- Gradle 8.9 + AGP 8.7.3（兼容 JDK 17-21 运行）
- three.js r128（本地化，无外部 CDN 依赖）
- 模型：`assets/model/model.glb`（glTF 2.0，74 网格 / 58 材质）

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
      ├─ java/com/show/p7/MainActivity.java
      ├─ assets/index.html
      ├─ assets/three.min.js / GLTFLoader.js / OrbitControls.js
      ├─ assets/model/model.glb
      └─ res/ (layout / values / drawable / mipmap)
```

## 模型来源

Sketchfab · supercarmodels · 2022 XPeng P7 · CC-BY-4.0
发布时请保留原作者署名。
*（内容由AI生成，仅供参考）*
