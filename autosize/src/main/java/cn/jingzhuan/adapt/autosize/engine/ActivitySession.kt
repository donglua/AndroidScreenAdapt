package cn.jingzhuan.adapt.autosize.engine

import android.app.Activity
import android.view.ViewTreeObserver
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
    var nativeSnapshot: MetricsSnapshot
) {
    val activityRef = WeakReference(activity)
    val listeners = CopyOnWriteArrayList<AdaptEventListener>()

    var cachedBounds: WindowBounds? = null
    var cachedWindowInfo: WindowInfo? = null
    var lastExplicitSpec: AdaptSpec? = null
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

    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var attachedViewTreeObserver: WeakReference<ViewTreeObserver>? = null

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
        attachedViewTreeObserver = null
        activeFragmentOverrideRef = null
        listeners.clear()
        lastAppliedInput = null
        lastAppliedResult = null
        lastExplicitSpec = null
    }
}
