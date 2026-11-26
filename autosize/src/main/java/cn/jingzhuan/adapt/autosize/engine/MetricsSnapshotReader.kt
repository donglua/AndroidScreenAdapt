package cn.jingzhuan.adapt.autosize.engine

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import cn.jingzhuan.adapt.autosize.core.MetricsResult
import cn.jingzhuan.adapt.autosize.core.MetricsSnapshot
import cn.jingzhuan.adapt.autosize.core.NativeSnapshotUpdater

/**
 * Captures immutable native DisplayMetrics snapshots per Context without assuming
 * Resources.getSystem() matches attachBaseContext configuration overrides.
 */
internal object MetricsSnapshotReader {

    fun captureApplicationNative(application: Application): MetricsSnapshot {
        val appRes = application.resources
        val appDm = appRes.displayMetrics
        val appConfig = appRes.configuration
        val sysDm = Resources.getSystem().displayMetrics
        val sysConfig = Resources.getSystem().configuration

        val density = appDm.density.takeIf { it > 0f } ?: sysDm.density
        val densityDpi = appDm.densityDpi.takeIf { it > 0 } ?: sysDm.densityDpi
        val scaledDensity = appDm.scaledDensity.takeIf { it > 0f } ?: sysDm.scaledDensity
        val widthDp = appConfig.screenWidthDp.takeIf { it > 0 } ?: sysConfig.screenWidthDp
        val heightDp = appConfig.screenHeightDp.takeIf { it > 0 } ?: sysConfig.screenHeightDp

        return MetricsSnapshot(
            density = density,
            densityDpi = densityDpi,
            scaledDensity = scaledDensity,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp
        )
    }

    fun captureActivityNative(
        activity: Activity,
        appBaseline: MetricsSnapshot
    ): MetricsSnapshot {
        return deriveFromConfig(activity.resources.configuration, appBaseline)
    }

    fun updateForConfiguration(
        previous: MetricsSnapshot,
        newConfig: Configuration,
        appBaseline: MetricsSnapshot,
        lastAppliedResult: MetricsResult? = null
    ): MetricsSnapshot {
        return deriveFromConfig(newConfig, appBaseline, previous, lastAppliedResult)
    }

    private fun deriveFromConfig(
        config: Configuration,
        appBaseline: MetricsSnapshot,
        fallback: MetricsSnapshot = appBaseline,
        lastAppliedResult: MetricsResult? = null
    ): MetricsSnapshot {
        return NativeSnapshotUpdater.updateFromConfiguration(
            previous = fallback,
            appBaseline = appBaseline,
            configDensityDpi = config.densityDpi,
            configFontScale = config.fontScale,
            configScreenWidthDp = config.screenWidthDp,
            configScreenHeightDp = config.screenHeightDp,
            lastAppliedResult = lastAppliedResult
        )
    }
}
