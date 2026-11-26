# API 文档

## 1. 包与制品

```text
Android namespace：cn.jingzhuan.adapt.autosize
Maven group：io.github.donglua.autosize
发布制品：io.github.donglua.autosize:autosize（单制品 Android AAR）
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
    val syncPolicy: ApplicationSyncPolicy // 默认 FOREGROUND_SHARED
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
    syncPolicy(ApplicationSyncPolicy.FOREGROUND_SHARED)
    cancelTargets(SomeIgnoredActivity::class.java)
    strategy { target, activity -> ... }
}
```

Java Builder：

```java
AdaptConfig config = new AdaptConfig.Builder()
        .designSize(360f, 640f)
        .excludeFontScale(true)
        .syncPolicy(ApplicationSyncPolicy.FOREGROUND_SHARED)
        .build();
ScreenAdapt.init(application, config);
```

### 指令与页面标记

优先级从高到低：`cancelTargets > customTargets > NoAdapt > AdaptTarget > AdaptStrategy > 全局默认`。

```kotlin
enum class AdaptBasis { WIDTH, HEIGHT }

enum class ApplicationSyncPolicy {
    /** 写入目标 Activity；仅在孤立创建或当前 Activity 前台可见且非多窗口时同步 Application */
    FOREGROUND_SHARED,

    /** 仅写入目标 Activity，绝不写入 Application */
    ACTIVITY_ONLY,

    /** 同时写入 Activity 与 Application */
    LEGACY_SHARED
}

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

> **原生模式边界约束（`Native` / `NoAdapt` / `cancelTargets` / `applyNative`）**
>
> 1. **只恢复目标 `Activity`，绝不回写 `Application`**：在默认的 `FOREGROUND_SHARED` 或 `LEGACY_SHARED` 策略下，进入原生模式页面后，`Application.resources.displayMetrics` 会**保留上一次已适配页面写入的共享密度**（若需要 `Application` 始终保持原生密度，需配置 `ApplicationSyncPolicy.ACTIVITY_ONLY`）。
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
