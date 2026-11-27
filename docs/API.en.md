# API Documentation

[中文](API.md) | English

## 1. Package & Artifact

```text
Android namespace: cn.jingzhuan.adapt.autosize
Maven group: io.github.donglua.adapt
Published artifact: io.github.donglua.adapt:autosize (single Android AAR)
Internal core package: cn.jingzhuan.adapt.autosize.core (pure JVM, no android.* / androidx.* dependencies)
Internal engine package: cn.jingzhuan.adapt.autosize.engine (internal runtime, non-public API)
```

---

## 2. Core Entry Point: `ScreenAdapt`

```kotlin
package cn.jingzhuan.adapt.autosize

object ScreenAdapt {
    @JvmStatic @JvmOverloads
    fun init(application: Application, config: AdaptConfig = AdaptConfig())

    @JvmStatic
    inline fun init(application: Application, block: AdaptConfig.Builder.() -> Unit)

    @JvmStatic
    fun isInitialized(): Boolean

    /** Wraps Context in Activity#attachBaseContext, locking only Configuration.fontScale (never mutates densityDpi). */
    @JvmStatic
    fun wrapContext(base: Context): Context

    /** Writes only the fontScale override into an override Configuration. */
    @JvmStatic
    fun applyOverrideConfiguration(config: Configuration): Configuration

    /** Immediately applies the given directive to the Activity. */
    @JvmStatic
    fun apply(activity: Activity, directive: AdaptDirective)

    /** Restores the Activity's own native snapshot; never writes to Application. */
    @JvmStatic
    fun applyNative(activity: Activity)

    /** Re-evaluates strategy and reapplies target metrics. */
    @JvmStatic
    fun reapply(activity: Activity)

    /** O(1) fast verification path: triggers reapply only when DisplayMetrics drift from the session target; safe in getResources(). */
    @JvmStatic
    fun ensureApplied(activity: Activity)

    /** Read-only calculation of target metrics for a Context without mutating DisplayMetrics (for Compose LocalDensity). */
    @JvmStatic @JvmOverloads
    fun resolveTargetMetrics(context: Context, directive: AdaptDirective? = null): TargetMetrics?

    @JvmStatic
    fun getWindowInfo(activity: Activity): WindowInfo?

    /** Returns the unadapted native display density for a Context (useful in AdaptStrategy to distinguish tablets from high-PPI foldables). */
    @JvmStatic
    fun getNativeDensity(context: Context): Float

    @JvmStatic
    fun addListener(activity: Activity, listener: AdaptEventListener)

    @JvmStatic
    fun removeListener(activity: Activity, listener: AdaptEventListener)
}
```

---

## 3. Configuration & Strategy Models

### `AdaptConfig` & `AdaptConfig.Builder`

```kotlin
class AdaptConfig {
    val designWidthDp: Float         // Reads Manifest design_width_in_dp when <= 0; final fallback 360f
    val designHeightDp: Float        // Reads Manifest design_height_in_dp when <= 0; final fallback 640f
    val basis: AdaptBasis            // Default WIDTH
    val excludeFontScale: Boolean    // Default false
    val privateFontScale: Float      // Default 0f
    val useDeviceSize: Boolean       // Default true
    val statusBarHeightPx: Int       // Default 0 (auto)
    val supportDp: Boolean           // Default true
    val supportSp: Boolean           // Default true
    val supportScreenSizeDp: Boolean // Default false
    val customFragment: Boolean      // Default true
    val debugLog: Boolean            // Default false
    val strategy: AdaptStrategy?
    val cancelTargets: Set<Class<*>>
    val customTargets: Map<Class<*>, AdaptDirective>
}
```

Kotlin DSL:

```kotlin
ScreenAdapt.init(application) {
    designSize(360f, 640f)
    excludeFontScale(true)
    customFragment(true)
    cancelTargets(SomeIgnoredActivity::class.java)
    strategy { target, activity -> ... }
}
```

Java Builder:

```java
AdaptConfig config = new AdaptConfig.Builder()
        .designSize(360f, 640f)
        .excludeFontScale(true)
        .build();
ScreenAdapt.init(application, config);
```

### Directives and Page Markers

Precedence from highest to lowest: `cancelTargets > customTargets > NoAdapt > AdaptTarget > AdaptStrategy > global default`.

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

Semantics and common use cases for each `AdaptDirective`:

| Directive | Meaning | Common Use Cases |
| :--- | :--- | :--- |
| `AdaptDirective.Width(sizeDp)` | Computes `density = widthPx / sizeDp` using current available window width | Standard portrait pages, widened design specs on unfolded foldables (e.g., `720f`) or wide-bar phones |
| `AdaptDirective.Height(sizeDp)` | Computes `density = heightPx / sizeDp` using current available window height | Full-height layout pages sensitive to vertical proportions |
| `AdaptDirective.ShortSide(effectiveSizeDp, useRealDisplay = true)` | Scales by short side; uses physical display short side when `useRealDisplay = true` and fullscreen, **automatically falling back to current window short side in multi-window/split-screen or when either window short side or long side is `< 85%` of the physical display (e.g., OEM MagicWindow / freeform)** | Landscape pages (keeps visual scale consistent with portrait), large screens, MagicWindow, and split-screen compatibility |
| `AdaptDirective.Native` | Restores the target `Activity`'s own native snapshot without writing to `Application` | Tablet/large-screen responsive pages, login/media/Compose pages that should not be scaled |
| `AdaptDirective.Skip` | Skips this adaptation pass and keeps current `DisplayMetrics` untouched (**does not restore native density**; use `Native` to opt out) | Transitional pages or screens managed manually |

> **`AdaptStrategy` & Multi-Form-Factor Dynamic Switching**
>
> - The engine refreshes the current `WindowInfo` before invoking `AdaptStrategy.resolve(target, activity)`, so strategies can safely call `ScreenAdapt.getWindowInfo(activity)`, `ScreenAdapt.getNativeDensity(activity)`, and read `activity.resources.configuration` (`smallestScreenWidthDp` is never mutated by this library and always reflects the system native value).
> - On foldable fold/unfold, wide-bar rotation, or split-screen/freeform resizing, the engine automatically refreshes window bounds and re-runs `AdaptStrategy` via `ComponentCallbacks.onConfigurationChanged` (API 29+) and `OnGlobalLayout`. Pages that should not recreate on resize must declare `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"` in `AndroidManifest.xml`.
> - Returning `null` from `AdaptStrategy` falls back to the global `designWidthDp` / `designHeightDp` and `basis`.

> **Native Mode Boundaries (`Native` / `NoAdapt` / `cancelTargets` / `applyNative`)**
>
> 1. **Restores only the target `Activity`, never writes `Application`**: After entering a native-mode page, `Application.resources.displayMetrics` **retains the shared density written by the previously adapted page**.
> 2. **`Application Context` is not guaranteed to be at native density**: Reading `DisplayMetrics`, resolving resources, or inflating views via `applicationContext` inside a native page still observes the previously shared density.
> 3. **`Dialog` depends on its host and `Context` source**: `ScreenAdapt` hooks only `Activity` and AndroidX `Fragment` lifecycles and **does not** detect `NoAdapt` on plain `android.app.Dialog` instances. A `Dialog` constructed with an `Application` Context (or inside an adapted host `Activity`) will not use native density; only a `Dialog` constructed with a `Native`-state `Activity` Context, or a `NoAdapt` `DialogFragment` handled by `FragmentHook` (note: this switches the host `Activity` to native density as well), will observe native density.
> 4. **`Flutter` views are not guaranteed to run end-to-end at native density**: A `FlutterView` attached to a `Native` `Activity` that reads the `Activity` Context directly will use native density, whereas a `FlutterEngine` pre-warmed with `applicationContext`, or plugins/measurements reading `DisplayMetrics` from `Application` Context, will still receive the previously shared density.

### Read-Only Snapshots & Events

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

## 4. Standalone Screen Metrics Utilities: `ScreenMetrics`

Does not depend on `ScreenAdapt.init` (safe to call even when initialization is conditionally skipped):

```kotlin
object ScreenMetrics {
    @JvmStatic fun getWindowSize(context: Context): IntArray
    @JvmStatic fun getRealDisplaySize(context: Context): IntArray
    @JvmStatic @JvmOverloads fun getStatusBarHeight(context: Context? = null): Int
    @JvmStatic fun getNavigationBarHeight(context: Context): Int
}
```
