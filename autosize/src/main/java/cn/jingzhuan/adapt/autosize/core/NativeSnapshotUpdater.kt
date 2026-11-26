package cn.jingzhuan.adapt.autosize.core

/**
 * Pure JVM snapshot updater deriving current native metrics from configuration values
 * while guarding against self-applied screenWidthDp/screenHeightDp overrides.
 */
internal object NativeSnapshotUpdater {

    private const val DENSITY_DEFAULT = 160f

    fun updateFromConfiguration(
        previous: MetricsSnapshot,
        appBaseline: MetricsSnapshot,
        configDensityDpi: Int,
        configFontScale: Float,
        configScreenWidthDp: Int,
        configScreenHeightDp: Int,
        lastAppliedResult: MetricsResult? = null
    ): MetricsSnapshot {
        val baseDensityDpi = when {
            configDensityDpi > 0 -> configDensityDpi
            previous.densityDpi > 0 -> previous.densityDpi
            else -> appBaseline.densityDpi
        }
        val baseDensity = if (configDensityDpi > 0 && configDensityDpi != appBaseline.densityDpi) {
            configDensityDpi / DENSITY_DEFAULT
        } else if (appBaseline.density > 0f) {
            appBaseline.density
        } else {
            baseDensityDpi / DENSITY_DEFAULT
        }

        val fontScale = when {
            configFontScale > 0f -> configFontScale
            appBaseline.density > 0f && appBaseline.scaledDensity > 0f ->
                appBaseline.scaledDensity / appBaseline.density
            else -> 1.0f
        }
        val baseScaledDensity = baseDensity * fontScale

        val appliedTarget = lastAppliedResult?.takeIf { it.applyScreenSizeDp }?.target
        val isPollutedBySelf = appliedTarget != null &&
            configScreenWidthDp == appliedTarget.screenWidthDp &&
            configScreenHeightDp == appliedTarget.screenHeightDp

        val fallbackWidthDp = previous.screenWidthDp.takeIf { it > 0 } ?: appBaseline.screenWidthDp
        val fallbackHeightDp = previous.screenHeightDp.takeIf { it > 0 } ?: appBaseline.screenHeightDp
        val widthDp = if (isPollutedBySelf) {
            fallbackWidthDp
        } else {
            configScreenWidthDp.takeIf { it > 0 } ?: fallbackWidthDp
        }
        val heightDp = if (isPollutedBySelf) {
            fallbackHeightDp
        } else {
            configScreenHeightDp.takeIf { it > 0 } ?: fallbackHeightDp
        }

        return MetricsSnapshot(
            density = baseDensity,
            densityDpi = baseDensityDpi,
            scaledDensity = baseScaledDensity,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp
        )
    }
}
