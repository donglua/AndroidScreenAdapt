# API 文档

中文 | [English](API.en.md)

## 1. 包与制品

```text
Android namespace：cn.jingzhuan.adapt.autosize
Maven group：io.github.donglua.adapt
发布制品：io.github.donglua.adapt:autosize（单制品 Android AAR）
内部核心包：cn.jingzhuan.adapt.autosize.core（纯 JVM，不依赖 android.* / androidx.*）
内部引擎包：cn.jingzhuan.adapt.autosize.engine（内部运行时，非公开 API）
```

---

## 2. 核心入口：`ScreenAdapt`

```kotlin
package cn.jingzhuan.adapt.autosize

object ScreenAdapt {
    @JvmStatic @JvmOverloads
    fun init(application: Application, config: AdaptConfig = AdaptConfig())

    @JvmStatic
    inline fun init(application: Application, block: AdaptConfig.Builder.() -> Unit)

    @JvmStatic
    fun isInitialized(): Boolean

    /** 在 Activity#attachBaseContext 包装 Context，仅锁定 Configuration.fontScale（不修改 densityDpi） */
    @JvmStatic
    fun wrapContext(base: Context): Context

    /** 为 override Configuration 仅写入 fontScale 覆盖 */
    @JvmStatic
    fun applyOverrideConfiguration(config: Configuration): Configuration

    /** 对当前 Activity 立即应用指定指令 */
    @JvmStatic
    fun apply(activity: Activity, directive: AdaptDirective)

    /** 恢复当前 Activity 自身原生快照，绝不写入 Application */
    @JvmStatic
    fun applyNative(activity: Activity)

    /** 重新评估策略并应用目标 */
    @JvmStatic
    fun reapply(activity: Activity)

    /** O(1) 快速校验路径：仅在 DisplayMetrics 偏离会话目标时触发 reapply，可安全用于 getResources() */
    @JvmStatic
    fun ensureApplied(activity: Activity)

    /** 只读计算当前 Context 对应的目标密度（不修改 DisplayMetrics），供 Compose LocalDensity 使用 */
    @JvmStatic @JvmOverloads
    fun resolveTargetMetrics(context: Context, directive: AdaptDirective? = null): TargetMetrics?

    @JvmStatic
    fun getWindowInfo(activity: Activity): WindowInfo?

    /** 读取未被 ScreenAdapt 改写过的原生显示密度（适合在 AdaptStrategy 中区分平板与高密折叠屏） */
    @JvmStatic
    fun getNativeDensity(context: Context): Float

    @JvmStatic
    fun addListener(activity: Activity, listener: AdaptEventListener)

    @JvmStatic
    fun removeListener(activity: Activity, listener: AdaptEventListener)
}
```

---

## 3. 配置与策略模型

### `AdaptConfig` 与 `AdaptConfig.Builder`

```kotlin
class AdaptConfig {
    val designWidthDp: Float        // <= 0 时读 Manifest design_width_in_dp，最终默认 360f
    val designHeightDp: Float       // <= 0 时读 Manifest design_height_in_dp，最终默认 640f
    val basis: AdaptBasis           // 默认 WIDTH
    val excludeFontScale: Boolean   // 默认 false
    val privateFontScale: Float     // 默认 0f
    val useDeviceSize: Boolean      // 默认 true
    val statusBarHeightPx: Int      // 默认 0（自动）
    val supportDp: Boolean          // 默认 true
    val supportSp: Boolean          // 默认 true
    val supportScreenSizeDp: Boolean // 默认 false
    val customFragment: Boolean     // 默认 true
    val debugLog: Boolean           // 默认 false
    val strategy: AdaptStrategy?
    val cancelTargets: Set<Class<*>>
    val customTargets: Map<Class<*>, AdaptDirective>
}
```

Kotlin DSL：

```kotlin
ScreenAdapt.init(application) {
    designSize(360f, 640f)
    excludeFontScale(true)
    customFragment(true)
    cancelTargets(SomeIgnoredActivity::class.java)
    strategy { target, activity -> ... }
}
```

Java Builder：

```java
AdaptConfig config = new AdaptConfig.Builder()
        .designSize(360f, 640f)
        .excludeFontScale(true)
        .build();
ScreenAdapt.init(application, config);
```

### 指令与页面标记

优先级从高到低：`cancelTargets > customTargets > NoAdapt > AdaptTarget > AdaptStrategy > 全局默认`。

```kotlin
enum class AdaptBasis { WIDTH, HEIGHT }

sealed class AdaptDirective {
    data class Width(val sizeDp: Float) : AdaptDirective()
    data class Height(val sizeDp: Float) : AdaptDirective()
    data class ShortSide @JvmOverloads constructor(
        val effectiveSizeDp: Float,
        val useRealDisplay: Boolean = true
    ) : AdaptDirective()
    data object Native : AdaptDirective()
    data object Skip : AdaptDirective()
}

interface NoAdapt
interface AdaptTarget { fun adaptDirective(): AdaptDirective }
fun interface AdaptStrategy { fun resolve(target: Any, activity: Activity): AdaptDirective? }
```

各 `AdaptDirective` 语义与适用场景：

| 指令 | 含义 | 常见用途 |
| :--- | :--- | :--- |
| `AdaptDirective.Width(sizeDp)` | 以当前窗口可用宽度为基准计算 `density = widthPx / sizeDp` | 常规竖屏页、折叠屏展开态扩宽设计稿（如 `720f`）、阔直板调宽设计稿 |
| `AdaptDirective.Height(sizeDp)` | 以当前窗口可用高度为基准计算 `density = heightPx / sizeDp` | 强依赖纵向满屏比例的页面 |
| `AdaptDirective.ShortSide(effectiveSizeDp, useRealDisplay = true)` | 以短边为基准计算密度；当 `useRealDisplay = true` 且处于全屏时取物理短边，**若处于分屏/多窗口，或窗口短边/长边任一小于物理对应边的 `85%`（如平行视界、自由小窗）时自动回退为当前窗口短边** | 横屏页（保持与竖屏一致的视觉尺度）、大屏、平行视界与分屏兼容 |
| `AdaptDirective.Native` | 恢复当前 `Activity` 自身原生快照，绝不写入 `Application` | 平板/大屏原生响应式页面、不需要缩放的登录/媒体/Compose 页面 |
| `AdaptDirective.Skip` | 跳过本次适配，保持当前 `DisplayMetrics` 状态不变（**不会还原原生密度**，取消适配请使用 `Native`） | 特殊过渡页或由业务自行接管密度的场景 |

> **`AdaptStrategy` 与多形态设备动态切换说明**
>
> - 引擎在调用 `AdaptStrategy.resolve(target, activity)` 之前已完成当前 `WindowInfo` 刷新，策略内可直接安全调用 `ScreenAdapt.getWindowInfo(activity)`、`ScreenAdapt.getNativeDensity(activity)` 与读取 `activity.resources.configuration`（`smallestScreenWidthDp` 不会被本库修改，始终反映系统原生短边 dp）。
> - 折叠屏展开/折叠、阔直板旋转、分屏/自由窗口拖拽时，引擎会通过 `ComponentCallbacks.onConfigurationChanged`（API 29+）与 `OnGlobalLayout` 自动刷新窗口并重新执行 `AdaptStrategy`；不重建 `Activity` 的页面需在 `AndroidManifest.xml` 中声明 `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"`。
> - `AdaptStrategy` 返回 `null` 时回退全局 `designWidthDp` / `designHeightDp` 与 `basis`。

> **原生模式边界约束（`Native` / `NoAdapt` / `cancelTargets` / `applyNative`）**
>
> 1. **只恢复目标 `Activity`，绝不回写 `Application`**：进入原生模式页面后，`Application.resources.displayMetrics` 会**保留上一次已适配页面写入的共享密度**。
> 2. **`Application Context` 不能默认处于原生密度**：原生页面中通过 `applicationContext` 读取 `DisplayMetrics`、解析资源或 inflate 视图，仍会命中上一次共享密度。
> 3. **`Dialog` 取决于载体与 `Context` 来源**：`ScreenAdapt` 仅挂接 `Activity` 与 AndroidX `Fragment` 生命周期，**不会**识别普通 `android.app.Dialog` 上的 `NoAdapt`。使用 `Application` Context 构造的 `Dialog`（或宿主 `Activity` 处于适配态时的普通 `Dialog`）不会处于原生密度；只有在 `Native` 态 `Activity` 中使用该 `Activity` Context 构造的 `Dialog`，或命中 `FragmentHook` 的 `NoAdapt` `DialogFragment`（注意这会将宿主 `Activity` 一并切为原生密度），才会读到原生密度。
> 4. **`Flutter` 视图不能默认全链路处于原生密度**：挂载在 `Native` `Activity` 上且直接读取 `Activity` Context 的 `FlutterView` 会使用原生密度；但使用 `applicationContext` 预热的 `FlutterEngine`，或通过 `Application` Context 读取 `DisplayMetrics` 的插件与测量逻辑，仍会拿到上一次共享密度。

### 只读快照与事件

```kotlin
data class TargetMetrics(
    val density: Float,
    val densityDpi: Int,
    val scaledDensity: Float,
    val fontScale: Float,
    val screenWidthDp: Int,
    val screenHeightDp: Int
)

data class WindowInfo(
    val widthPx: Int,
    val heightPx: Int,
    val realWidthPx: Int,
    val realHeightPx: Int,
    val statusBarInsetPx: Int,
    val navigationBarInsetPx: Int,
    val configurationOrientation: Int,
    val requestedOrientation: Int,
    val inMultiWindow: Boolean,
    val stable: Boolean
)

data class AdaptEvent(val type: Type, val targetDensity: Float, val reason: String) {
    enum class Type { APPLIED, SKIPPED, DEFERRED, INVALID, NATIVE }
}

fun interface AdaptEventListener {
    fun onAdaptEvent(activity: Activity, event: AdaptEvent)
}
```

---

## 4. 独立屏幕指标工具：`ScreenMetrics`

不依赖 `ScreenAdapt.init` 是否已执行（条件跳过初始化时仍可安全调用）：

```kotlin
object ScreenMetrics {
    @JvmStatic fun getWindowSize(context: Context): IntArray
    @JvmStatic fun getRealDisplaySize(context: Context): IntArray
    @JvmStatic @JvmOverloads fun getStatusBarHeight(context: Context? = null): Int
    @JvmStatic fun getNavigationBarHeight(context: Context): Int
}
```
