package cn.jingzhuan.adapt.autosize

import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import cn.jingzhuan.adapt.autosize.engine.WindowBoundsResolver
import kotlin.math.max

/**
 * Standalone screen and system-bar measurement utilities.
 * Safe to invoke even when [ScreenAdapt.init] has been skipped.
 */
object ScreenMetrics {

    @JvmStatic
    fun getWindowSize(context: Context): IntArray {
        val uiWm = resolveUiWindowManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uiWm != null) {
            try {
                val metrics = uiWm.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
                )
                val w = (bounds.width() - insets.left - insets.right).coerceAtLeast(0)
                val h = (bounds.height() - insets.bottom).coerceAtLeast(0)
                if (w > 0 && h > 0) {
                    return intArrayOf(w, h)
                }
            } catch (_: Exception) {
            }
        }
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (displayManager != null) {
            try {
                val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getMetrics(dm)
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        return intArrayOf(dm.widthPixels, dm.heightPixels)
                    }
                }
            } catch (_: Exception) {
            }
        }
        val dm = context.resources.displayMetrics
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    @JvmStatic
    fun getRealDisplaySize(context: Context): IntArray {
        val uiWm = resolveUiWindowManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uiWm != null) {
            try {
                val bounds = uiWm.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return intArrayOf(bounds.width(), bounds.height())
                }
            } catch (_: Exception) {
            }
        }
        val appContext = context.applicationContext ?: context
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (dm != null) {
            try {
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val point = Point()
                    @Suppress("DEPRECATION")
                    display.getRealSize(point)
                    if (point.x > 0 && point.y > 0) {
                        return intArrayOf(point.x, point.y)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return getWindowSize(appContext)
    }

    @JvmStatic
    @JvmOverloads
    fun getStatusBarHeight(context: Context? = null): Int {
        val uiWm = if (context != null) resolveUiWindowManager(context) else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uiWm != null) {
            try {
                val metrics = uiWm.currentWindowMetrics
                val statusTop = metrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                    .top
                if (statusTop > 0) return statusTop
            } catch (_: Exception) {
            }
        }
        val res = context?.resources ?: Resources.getSystem()
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            try {
                res.getDimensionPixelSize(id)
            } catch (_: Resources.NotFoundException) {
                0
            }
        } else {
            0
        }
    }

    @JvmStatic
    fun getNavigationBarHeight(context: Context): Int {
        val uiWm = resolveUiWindowManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uiWm != null) {
            try {
                return uiWm.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                    .bottom
            } catch (_: Exception) {
            }
        }
        val realH = getRealDisplaySize(context)[1]
        val appH = getWindowSize(context)[1]
        return max(0, realH - appH)
    }

    private fun resolveUiWindowManager(context: Context): WindowManager? {
        WindowBoundsResolver.findActivity(context)?.windowManager?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.isUiContext) {
            return context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        return null
    }
}
