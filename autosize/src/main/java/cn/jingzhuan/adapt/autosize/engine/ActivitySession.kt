package cn.jingzhuan.adapt.autosize.engine

import android.app.Activity
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Build
import android.view.ViewTreeObserver
import cn.jingzhuan.adapt.autosize.AdaptDirective
import cn.jingzhuan.adapt.autosize.AdaptEvent
import cn.jingzhuan.adapt.autosize.AdaptEventListener
import cn.jingzhuan.adapt.autosize.WindowInfo
import cn.jingzhuan.adapt.autosize.core.AdaptInput
import cn.jingzhuan.adapt.autosize.core.AdaptSpec
import cn.jingzhuan.adapt.autosize.core.MetricsResult
import cn.jingzhuan.adapt.autosize.core.MetricsSnapshot
import cn.jingzhuan.adapt.autosize.core.WindowBounds
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-Activity session storing native baseline, cached bounds, deduplication input,
 * applied result, and layout listener cleanup hooks.
 */
internal class ActivitySession(
    activity: Activity,
    @Volatile
    var nativeSnapshot: MetricsSnapshot
) {
    val activityRef = WeakReference(activity)
    val listeners = CopyOnWriteArrayList<AdaptEventListener>()

    @Volatile
    var cachedBounds: WindowBounds? = null
    @Volatile
    var cachedWindowInfo: WindowInfo? = null
    var lastExplicitDirective: AdaptDirective? = null
    var lastAppliedInput: AdaptInput? = null
    var lastAppliedResult: MetricsResult? = null

    var isNativeMode: Boolean = false
    var isSkipped: Boolean = false
    var isDeferred: Boolean = false
    var resumedOnce: Boolean = false
    var resumeFallbackPending: Boolean = false
    var fragmentCallbacksRegistered: Boolean = false
    var activeFragmentOverrideRef: WeakReference<Any>? = null

    var isAdapting: Boolean = false
    var isResolvingWindow: Boolean = false
    var isResolvingDirective: Boolean = false

    private var hasConfigSnapshot: Boolean = false
    private var lastConfigOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var lastConfigScreenWidthDp: Int = Configuration.SCREEN_WIDTH_DP_UNDEFINED
    private var lastConfigScreenHeightDp: Int = Configuration.SCREEN_HEIGHT_DP_UNDEFINED
    private var lastConfigSmallestScreenWidthDp: Int = Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED
    private var lastConfigDensityDpi: Int = Configuration.DENSITY_DPI_UNDEFINED
    private var lastConfigFontScaleBits: Int = 0

    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var attachedViewTreeObserver: WeakReference<ViewTreeObserver>? = null
    private var activityComponentCallbacks: ComponentCallbacks? = null

    fun recordConfigSnapshot(config: Configuration) {
        hasConfigSnapshot = true
        lastConfigOrientation = config.orientation
        lastConfigScreenWidthDp = config.screenWidthDp
        lastConfigScreenHeightDp = config.screenHeightDp
        lastConfigSmallestScreenWidthDp = config.smallestScreenWidthDp
        lastConfigDensityDpi = config.densityDpi
        lastConfigFontScaleBits = config.fontScale.toRawBits()
    }

    fun hasConfigChanged(config: Configuration): Boolean {
        if (!hasConfigSnapshot) return false
        return lastConfigOrientation != config.orientation ||
            lastConfigScreenWidthDp != config.screenWidthDp ||
            lastConfigScreenHeightDp != config.screenHeightDp ||
            lastConfigSmallestScreenWidthDp != config.smallestScreenWidthDp ||
            lastConfigDensityDpi != config.densityDpi ||
            lastConfigFontScaleBits != config.fontScale.toRawBits()
    }

    fun attachComponentCallbacks(activity: Activity, onConfigChanged: (Configuration) -> Unit) {
        if (activityComponentCallbacks != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                onConfigChanged(newConfig)
            }

            override fun onLowMemory() = Unit
        }
        try {
            activity.registerComponentCallbacks(callbacks)
            activityComponentCallbacks = callbacks
        } catch (_: Exception) {
        }
    }

    fun attachLayoutListener(activity: Activity, onLayout: () -> Unit) {
        if (globalLayoutListener != null) return
        val decorView = activity.window?.decorView ?: return
        val vto = decorView.viewTreeObserver
        if (!vto.isAlive) return

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            onLayout()
        }
        vto.addOnGlobalLayoutListener(listener)
        globalLayoutListener = listener
        attachedViewTreeObserver = WeakReference(vto)
    }

    fun dispatchEvent(activity: Activity, event: AdaptEvent) {
        if (listeners.isEmpty()) return
        for (listener in listeners) {
            listener.onAdaptEvent(activity, event)
        }
    }

    fun dispose(activity: Activity?) {
        val listener = globalLayoutListener
        globalLayoutListener = null
        if (listener != null) {
            val decorVto = activity?.window?.decorView?.viewTreeObserver
            if (decorVto != null && decorVto.isAlive) {
                decorVto.removeOnGlobalLayoutListener(listener)
            } else {
                val oldVto = attachedViewTreeObserver?.get()
                if (oldVto != null && oldVto.isAlive) {
                    oldVto.removeOnGlobalLayoutListener(listener)
                }
            }
        }
        val compCallbacks = activityComponentCallbacks
        activityComponentCallbacks = null
        if (compCallbacks != null && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                activity.unregisterComponentCallbacks(compCallbacks)
            } catch (_: Exception) {
            }
        }
        attachedViewTreeObserver = null
        activeFragmentOverrideRef = null
        hasConfigSnapshot = false
        listeners.clear()
        lastAppliedInput = null
        lastAppliedResult = null
        lastExplicitDirective = null
    }
}
