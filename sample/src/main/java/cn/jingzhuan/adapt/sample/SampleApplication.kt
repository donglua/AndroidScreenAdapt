package cn.jingzhuan.adapt.sample

import android.app.Application
import android.content.res.Configuration
import cn.jingzhuan.adapt.autosize.AdaptDirective
import cn.jingzhuan.adapt.autosize.ScreenAdapt

class SampleApplication : Application() {

    var initialAppNativeDensity: Float = 0f
        private set

    override fun onCreate() {
        super.onCreate()
        initialAppNativeDensity = resources.displayMetrics.density
        ScreenAdapt.init(this) {
            designSize(360f, 640f)
            excludeFontScale(true)
            customFragment(true)
            debugLog(true)
            strategy { target, activity ->
                if (target === activity && activity is ShortSideActivity) {
                    AdaptDirective.ShortSide(400f, useRealDisplay = true)
                } else if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    target is MainActivity
                ) {
                    AdaptDirective.ShortSide(360f, useRealDisplay = true)
                } else {
                    null
                }
            }
        }
    }
}
