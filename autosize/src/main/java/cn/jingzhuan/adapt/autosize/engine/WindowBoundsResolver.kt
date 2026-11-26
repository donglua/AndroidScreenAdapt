package cn.jingzhuan.adapt.autosize.engine

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import cn.jingzhuan.adapt.autosize.ScreenMetrics
import cn.jingzhuan.adapt.autosize.WindowInfo
import cn.jingzhuan.adapt.autosize.core.AdaptCalculator
import cn.jingzhuan.adapt.autosize.core.CoreOrientation
import cn.jingzhuan.adapt.autosize.core.WindowBounds
import kotlin.math.max

/**
 * Resolves current window bounds and real display dimensions without invoking
 * Activity#getWindowManager() inside getResources() call paths.
 */
internal object WindowBoundsResolver {

    @Volatile
    internal var resolveCurrentWindowOverrideForTesting: ((Activity) -> Pair<WindowBounds, WindowInfo>)? = null

    fun resolveForContext(context: Context): WindowBounds {
        if (context is Activity) {
            return resolveCurrentWindow(context).first
        }
        val windowSize = ScreenMetrics.getWindowSize(context)
        val realSize = ScreenMetrics.getRealDisplaySize(context)
        val statusBar = ScreenMetrics.getStatusBarHeight(context)
        val navBar = ScreenMetrics.getNavigationBarHeight(context)
        return WindowBounds(
            widthPx = windowSize[0],
            heightPx = windowSize[1],
            realWidthPx = realSize[0],
            realHeightPx = realSize[1],
            statusBarInsetPx = statusBar,
            navigationBarInsetPx = navBar,
            inMultiWindow = false,
            orientation = CoreOrientation.UNSPECIFIED
        )
    }

    fun resolveCurrentWindow(activity: Activity): Pair<WindowBounds, WindowInfo> {
        resolveCurrentWindowOverrideForTesting?.let { return it(activity) }
        val realSize = ScreenMetrics.getRealDisplaySize(activity)
        val inMultiWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                activity.isInMultiWindowMode
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        val configOrientation = activity.resources.configuration.orientation
        val requestedOrientation = activity.requestedOrientation
        val expectedOrientation = AdaptCalculator.resolveExpectedOrientation(
            requestedOrientation = requestedOrientation,
            configOrientation = configOrientation
        )

        var widthPx = 0
        var heightPx = 0
        var statusInsetPx = 0
        var navInsetPx = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = activity.windowManager
            if (wm != null) {
                val windowMetrics = wm.currentWindowMetrics
                val bounds: Rect = windowMetrics.bounds
                val insets = windowMetrics.windowInsets
                val navAndCutout = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
                val statusTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top

                widthPx = (bounds.width() - navAndCutout.left - navAndCutout.right).coerceAtLeast(0)
                heightPx = (bounds.height() - navAndCutout.bottom).coerceAtLeast(0)
                statusInsetPx = max(0, statusTop - bounds.top).coerceAtMost(heightPx)
                navInsetPx = navAndCutout.bottom
            }
        }

        if (widthPx <= 0 || heightPx <= 0) {
            val wm = activity.windowManager
            @Suppress("DEPRECATION")
            val display = wm?.defaultDisplay
            if (display != null) {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getMetrics(dm)
                widthPx = dm.widthPixels
                heightPx = dm.heightPixels
            } else {
                val fallback = ScreenMetrics.getWindowSize(activity.applicationContext ?: activity)
                widthPx = fallback[0]
                heightPx = fallback[1]
            }
            statusInsetPx = resolveLegacyWindowStatusBar(activity, inMultiWindow)
            navInsetPx = if (!inMultiWindow) max(0, realSize[1] - heightPx) else 0
        }

        val bounds = WindowBounds(
            widthPx = widthPx,
            heightPx = heightPx,
            realWidthPx = realSize[0],
            realHeightPx = realSize[1],
            statusBarInsetPx = statusInsetPx,
            navigationBarInsetPx = navInsetPx,
            inMultiWindow = inMultiWindow,
            orientation = expectedOrientation
        )
        val stable = inMultiWindow || !AdaptCalculator.isOrientationConflicting(bounds)
        val info = WindowInfo(
            widthPx = widthPx,
            heightPx = heightPx,
            realWidthPx = realSize[0],
            realHeightPx = realSize[1],
            statusBarInsetPx = statusInsetPx,
            navigationBarInsetPx = navInsetPx,
            configurationOrientation = configOrientation,
            requestedOrientation = requestedOrientation,
            inMultiWindow = inMultiWindow,
            stable = stable
        )
        return bounds to info
    }

    private fun resolveLegacyWindowStatusBar(activity: Activity, inMultiWindow: Boolean): Int {
        if (!inMultiWindow) {
            return ScreenMetrics.getStatusBarHeight(activity)
        }
        val decor = activity.window?.decorView ?: return 0
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        return if (location[1] <= 0) ScreenMetrics.getStatusBarHeight(activity) else 0
    }
}
