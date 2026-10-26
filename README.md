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

基于 Android WebView 的 3D 汽车展示应用，展示 2022 小鹏 P7 模型。

## 功能

- 拖拽手动旋转（OrbitControls）
- 双指捏合 / 滚轮缩放
- 按钮切换车身颜色（Bodyshell / Bonnet / Bumper 车漆材质）
- 按钮切换场景背景（纯色 / 渐变 / 环境夜色）
- 任意操作后 5 秒无交互，相机自动平滑归位至车左前 45° 视角

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
