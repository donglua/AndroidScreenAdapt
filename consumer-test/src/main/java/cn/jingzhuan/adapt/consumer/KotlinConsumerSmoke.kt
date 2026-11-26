package cn.jingzhuan.adapt.consumer

import android.app.Activity
import android.app.Application
import android.content.Context
import cn.jingzhuan.adapt.autosize.AdaptBasis
import cn.jingzhuan.adapt.autosize.AdaptDirective
import cn.jingzhuan.adapt.autosize.AdaptEvent
import cn.jingzhuan.adapt.autosize.AdaptEventListener
import cn.jingzhuan.adapt.autosize.AdaptStrategy
import cn.jingzhuan.adapt.autosize.AdaptTarget
import cn.jingzhuan.adapt.autosize.ScreenAdapt
import cn.jingzhuan.adapt.autosize.ScreenMetrics

/**
 * Compile-time consumer verification for Kotlin callers using the modernized ScreenAdapt DSL & API.
 */
object KotlinConsumerSmoke {

    class CustomTarget : AdaptTarget {
        override fun adaptDirective(): AdaptDirective = AdaptDirective.ShortSide(effectiveSizeDp = 720f, useRealDisplay = true)
    }

    fun exerciseKotlinApi(application: Application, activity: Activity, baseContext: Context) {
        ScreenAdapt.init(application) {
            designSize(360f, 640f)
            basis(AdaptBasis.WIDTH)
            excludeFontScale(true)
            customFragment(true)
            cancelTargets(JavaConsumerSmoke::class.java)
            strategy(AdaptStrategy { target, _ ->
                when (target) {
                    is CustomTarget -> target.adaptDirective()
                    else -> null
                }
            })
        }

        val wrapped = ScreenAdapt.wrapContext(baseContext)
        val targetMetrics = ScreenAdapt.resolveTargetMetrics(wrapped)
        ScreenAdapt.apply(activity, AdaptDirective.Width(360f))
        ScreenAdapt.applyNative(activity)
        ScreenAdapt.reapply(activity)
        ScreenAdapt.ensureApplied(activity)

        val windowInfo = ScreenAdapt.getWindowInfo(activity)
        val listener = AdaptEventListener { _, event ->
            if (event.type == AdaptEvent.Type.APPLIED && targetMetrics != null && windowInfo != null) {
                event.reason.length
            }
        }
        ScreenAdapt.addListener(activity, listener)
        ScreenAdapt.removeListener(activity, listener)

        ScreenMetrics.getWindowSize(application)
        ScreenMetrics.getRealDisplaySize(application)
        ScreenMetrics.getStatusBarHeight()
        ScreenMetrics.getNavigationBarHeight(application)
    }
}
