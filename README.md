# AndroidScreenAdapt

AndroidScreenAdapt 是一个面向现代 Android 工程的屏幕适配库（发布模块 `:autosize`，Maven 坐标 `io.github.donglua.adapt:autosize`），用于替代基于旧版 `AndroidAutoSize` 架构的屏幕适配方案。

项目采用**「以当前 Window 的动态 `DisplayMetrics` 单引擎为基础 + 局部 `Context` / `LocalDensity` 隔离」**的混合架构：提供干净的 Kotlin-first API（同时兼容 Java 调用），内置当前窗口解析、原生模式、折叠屏短边适配、Android 14+ 非线性字体屏蔽与旋转竞态兜底。

## 为什么采用混合架构

不直接用 `attachBaseContext` 修改全局 `Configuration.densityDpi` 替代 `DisplayMetrics`，原因有三点：

1. **避免整型 `densityDpi` 引发资源分桶漂移**：`DisplayMetrics.density` 为高精度 `Float`，而 `Configuration.densityDpi` 为 `Int`；修改 `densityDpi` 会改变系统命中的资源限定符桶（如从 `xxhdpi` 跌入 `xhdpi` 或命中错误的 `swXXXdp` 布局）。
2. **`attachBaseContext` 阶段尚无真实 Window 尺寸**：Activity 此时尚未关联 Window，在分屏、自由窗口、折叠屏拖拽下拿不到真实宽高，且窗口尺寸动态变化时不会重走 `attachBaseContext`。
3. **规避 `AppCompatActivity` 与 ROM 历史兼容风险**：在 `attachBaseContext` 中全量覆盖 `Configuration` 容易与 `AppCompatDelegate` 的 `uiMode`（深色模式）、语言切换以及部分机型 `WebView` 资源加载冲突。

因此，本库仅吸收局部隔离中安全可控的两项能力，其余仍由单引擎统一协调：

- **`ScreenAdapt.wrapContext` / `applyOverrideConfiguration` 仅锁定 `fontScale`**：使用空增量 `Configuration` 覆盖 `fontScale`，**不修改 `densityDpi`**，从系统源头阻断 Android 14+ `fontScaleConverter` 非线性放大。
- **Compose 页面局部接管 `LocalDensity`**：核心库不依赖 Compose 运行时，提供只读接口 `ScreenAdapt.resolveTargetMetrics`，交由页面使用 `CompositionLocalProvider(LocalDensity provides ...)` 接管密度与字体。

## 核心特性

- **显式幂等初始化**：不在合并后的 `AndroidManifest.xml` 中注册 `ContentProvider`，由宿主在 `Application#onCreate` 或按设备形态条件调用 `ScreenAdapt.init`。
- **当前 Window 唯一事实来源**：移除进程级共享宽高缓存，按目标 Activity 的当前 Window（API 30+ `WindowMetrics`、低版本 `Display#getMetrics`）计算；支持分屏/小窗自动回退窗口短边。
- **前台安全共享 `Application` 密度**：仅在孤立创建或前台单窗口状态下同步 `Application`，避免分屏多窗口或后台页相互覆盖；原生模式（`AdaptDirective.Native` / `NoAdapt` / `applyNative`）仅恢复目标 `Activity`、绝不修改 `Application`（详见 [API 文档](docs/API.md#指令与页面标记)）。
- **事件驱动纠偏 + O(1) 快速校验**：基于生命周期、配置变化与 `OnGlobalLayout` 纠偏，配合零分配 `ScreenAdapt.ensureApplied(activity)` 供基类 `getResources()` 防止系统重置密度（无轮询后台线程）。
- **AndroidX Fragment 与 MIUI 兼容**：支持 Fragment 级 `AdaptTarget` / `NoAdapt` 覆盖，并内置 MIUI `mTmpMetrics` 同步。

## 快速接入

### 1. 添加依赖与初始化

```kotlin
dependencies {
    implementation("io.github.donglua.adapt:autosize:0.1.0")
}
```

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenAdapt.init(this) {
            designSize(360f, 640f)
            excludeFontScale(true)
            customFragment(true)
        }
    }
}
```

### 2. Activity 基类接入字体隔离与防重置

```kotlin
open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenAdapt.wrapContext(newBase))
    }

    override fun getResources(): Resources {
        ScreenAdapt.ensureApplied(this)
        return super.getResources()
    }
}
```

> 若宿主自身在 `attachBaseContext` 中存在前置硬件密度修正，请保持在 `ScreenAdapt.wrapContext` **之前**执行。

### 3. Compose 页面局部 `LocalDensity`

```kotlin
class ComposePageActivity : ComponentActivity(), NoAdapt {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenAdapt.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val target = remember(context, configuration) {
                ScreenAdapt.resolveTargetMetrics(context, AdaptDirective.Width(360f))
            }
            val density = target?.let { Density(it.density, it.fontScale) } ?: LocalDensity.current
            CompositionLocalProvider(LocalDensity provides density) {
                // Composable UI
            }
        }
    }
}
```

### 4. 多形态设备配置（折叠屏 / 阔直板 / 平板 / 横屏）

底层已按当前 `Window` 实时计算并自动监听窗口变化（折叠展开、分屏拖拽、横竖屏切换），但大屏与异形比例的**设计稿宽度或是否退回原生**需要结合业务需求在 `strategy` 中指定。引擎在回调 `strategy` 前已刷新当前窗口信息，可直接安全读取 `ScreenAdapt.getWindowInfo(activity)` 与 `activity.resources.configuration`：

```kotlin
ScreenAdapt.init(this) {
    designSize(360f, 640f)
    excludeFontScale(true)
    strategy { _, activity ->
        val info = ScreenAdapt.getWindowInfo(activity) ?: return@strategy null
        val swDp = activity.resources.configuration.smallestScreenWidthDp
        val shortPx = minOf(info.widthPx, info.heightPx)
        val longPx = maxOf(info.widthPx, info.heightPx)
        val aspectRatio = if (longPx > 0) shortPx.toFloat() / longPx else 0f

        when {
            // 1. 分屏 / 自由窗口：按当前窗口宽度适配（或返回 ShortSide 自动取窗口短边）
            info.inMultiWindow -> AdaptDirective.Width(360f)

            // 2. 折叠屏展开态（接近方形内屏）：扩大设计宽度，避免 360dp 等比过度放大
            aspectRatio >= 0.8f -> AdaptDirective.Width(720f)

            // 3. 平板：按大屏设计稿适配，或返回 AdaptDirective.Native 走原生响应式布局
            swDp >= 600 -> AdaptDirective.Native

            // 4. 阔直板等宽屏手机（如 16:10 / 宽比例直板机）：适当放宽设计宽度以展示更多内容
            swDp >= 450 || aspectRatio >= 0.6f -> AdaptDirective.Width(420f)

            // 5. 普通手机横屏：按真实短边适配，保持与竖屏一致的控件/字号尺度
            info.configurationOrientation == Configuration.ORIENTATION_LANDSCAPE ->
                AdaptDirective.ShortSide(360f, useRealDisplay = true)

            // 6. 其余普通手机竖屏：回退全局 designSize(360f, 640f)
            else -> null
        }
    }
}
```

> **提示**：
> - 以上阈值（`0.8f`、`600dp`、`450dp`、`720f`/`420f`）仅为参考，请结合自身 UI 设计稿调整；若希望平板整机完全不启用适配，也可在 `Application#onCreate` 判断条件后直接跳过 `ScreenAdapt.init(...)`（`ScreenMetrics` 仍可安全调用）。
> - 若希望折叠屏展开/折叠或横竖屏切换时 **不重建 `Activity`**，在 `AndroidManifest.xml` 中为对应页面配置 `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"`，引擎会在配置与布局变更时自动按新窗口重算密度。

完整接口签名、配置项、指令模型与 `ScreenMetrics` 工具说明见 [API 文档](docs/API.md)。

## 工程模块与验证

- `:autosize`：发布模块，内部划分纯 JVM 计算包 `cn.jingzhuan.adapt.autosize.core`（严禁依赖 `android.*` / `androidx.*`）与运行时引擎包 `cn.jingzhuan.adapt.autosize.engine`。
- `:consumer-test`：Kotlin DSL 与 Java Builder 消费者编译及契约测试。
- `:sample`：包含 Fragment 切换、`ShortSide` + `AdaptEventListener`、`NoAdapt`、`wrapContext`、Compose `LocalDensity` 与 R8 混淆验证示例。

```bash
# 本地单元、契约、核心包隔离、API 基线、AAR 校验与 R8 示例构建
./gradlew check

# autosize 与 sample 真机仪器测试（含 R8 混淆包测试）
./gradlew :autosize:connectedDebugAndroidTest :sample:connectedDebugAndroidTest
./gradlew :sample:connectedReleaseAndroidTest -PminifiedTest=true
```

## 许可与致谢

本项目采用 Apache-2.0 许可证（见 [LICENSE](LICENSE)），并在 [NOTICE](NOTICE) 中致谢原开源项目 [JessYan/AndroidAutoSize](https://github.com/JessYanCoding/AndroidAutoSize)（部分设计思路与公式基础衍生自其 Apache-2.0 开源实现）。
