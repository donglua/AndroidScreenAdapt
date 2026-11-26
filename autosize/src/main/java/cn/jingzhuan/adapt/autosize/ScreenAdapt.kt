package cn.jingzhuan.adapt.autosize

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import cn.jingzhuan.adapt.autosize.engine.ScreenAdaptEngine

/**
 * Primary public entry point for ScreenAdapt.
 */
object ScreenAdapt {

    @JvmStatic
    @JvmOverloads
    fun init(application: Application, config: AdaptConfig = AdaptConfig()) {
        ScreenAdaptEngine.init(application, config)
    }

    @JvmStatic
    inline fun init(application: Application, block: AdaptConfig.Builder.() -> Unit) {
        init(application, adaptConfig(block))
    }

    @JvmStatic
    fun isInitialized(): Boolean = ScreenAdaptEngine.isInitialized()

    /**
     * Wraps a base Context in `Activity#attachBaseContext` to enforce `excludeFontScale` or
     * `privateFontScale` at the `Configuration` level, preventing Android 14+ non-linear
     * `fontScaleConverter` from bypassing `scaledDensity`.
     */
    @JvmStatic
    fun wrapContext(base: Context): Context = ScreenAdaptEngine.wrapContext(base)

    /**
     * Applies fontScale override to an override Configuration if configured.
     */
    @JvmStatic
    fun applyOverrideConfiguration(config: Configuration): Configuration {
        return ScreenAdaptEngine.applyOverrideConfiguration(config)
    }

    /**
     * Applies an explicit [AdaptDirective] to the given Activity.
     */
    @JvmStatic
    fun apply(activity: Activity, directive: AdaptDirective) {
        ScreenAdaptEngine.applyDirective(activity, directive)
    }

    /**
     * Restores the Activity's own native snapshot without mutating Application metrics.
     */
    @JvmStatic
    fun applyNative(activity: Activity) {
        ScreenAdaptEngine.applyNative(activity)
    }

    /**
     * Re-evaluates the Activity's strategy and reapplies target metrics.
     */
    @JvmStatic
    fun reapply(activity: Activity) {
        ScreenAdaptEngine.reapply(activity)
    }

    /**
     * O(1) fast-path check verifying that managed DisplayMetrics match the session target;
     * delegates to [reapply] only when drift is detected. Safe inside `Activity#getResources()`.
     */
    @JvmStatic
    fun ensureApplied(activity: Activity) {
        ScreenAdaptEngine.ensureApplied(activity)
    }

    /**
     * Computes target density metrics for [context] without mutating DisplayMetrics.
     * Useful for Compose `LocalDensity` integration.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveTargetMetrics(
        context: Context,
        directive: AdaptDirective? = null
    ): TargetMetrics? {
        return ScreenAdaptEngine.resolveTargetMetrics(context, directive)
    }

    @JvmStatic
    fun getWindowInfo(activity: Activity): WindowInfo? {
        return ScreenAdaptEngine.getWindowInfo(activity)
    }

    @JvmStatic
    fun addListener(activity: Activity, listener: AdaptEventListener) {
        ScreenAdaptEngine.addListener(activity, listener)
    }

    @JvmStatic
    fun removeListener(activity: Activity, listener: AdaptEventListener) {
        ScreenAdaptEngine.removeListener(activity, listener)
    }
}
