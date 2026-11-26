package cn.jingzhuan.adapt.autosize.core

/**
 * Pure JVM internal calculation models (zero android.* dependencies).
 */

enum class CoreAdaptBasis {
    WIDTH,
    HEIGHT,
    SHORT_SIDE
}

enum class AdaptStatus {
    APPLIED,
    SKIPPED,
    DEFERRED,
    INVALID,
    NATIVE
}

enum class CoreOrientation {
    UNSPECIFIED,
    PORTRAIT,
    LANDSCAPE
}

data class WindowBounds(
    val widthPx: Int,
    val heightPx: Int,
    val realWidthPx: Int = widthPx,
    val realHeightPx: Int = heightPx,
    val statusBarInsetPx: Int = 0,
    val navigationBarInsetPx: Int = 0,
    val inMultiWindow: Boolean = false,
    val orientation: CoreOrientation = CoreOrientation.UNSPECIFIED
)

data class MetricsSnapshot(
    val density: Float,
    val densityDpi: Int,
    val scaledDensity: Float,
    val screenWidthDp: Int = 0,
    val screenHeightDp: Int = 0
)

data class AdaptSpec(
    val basis: CoreAdaptBasis,
    val designWidthDp: Float,
    val designHeightDp: Float,
    val effectiveDesignSizeDp: Float,
    val supportDp: Boolean = true,
    val supportSp: Boolean = true,
    val supportScreenSizeDp: Boolean = false,
    val excludeFontScale: Boolean = false,
    val privateFontScale: Float = 0f,
    val useDeviceSize: Boolean = true,
    val manualStatusBarHeightPx: Int = 0,
    val useRealDisplayShortSide: Boolean = true
)

/**
 * Immutable deduplication key holding all calculation inputs.
 * Float fields are compared by IEEE 754 raw bits.
 */
data class AdaptInput(
    val spec: AdaptSpec,
    val bounds: WindowBounds,
    val nativeSnapshot: MetricsSnapshot,
    val forceApplyOnConflict: Boolean = false
) {
    fun bitsEquals(other: AdaptInput?): Boolean {
        if (other == null) return false
        return this == other &&
            spec.designWidthDp.toRawBits() == other.spec.designWidthDp.toRawBits() &&
            spec.designHeightDp.toRawBits() == other.spec.designHeightDp.toRawBits() &&
            spec.effectiveDesignSizeDp.toRawBits() == other.spec.effectiveDesignSizeDp.toRawBits() &&
            spec.privateFontScale.toRawBits() == other.spec.privateFontScale.toRawBits() &&
            nativeSnapshot.density.toRawBits() == other.nativeSnapshot.density.toRawBits() &&
            nativeSnapshot.scaledDensity.toRawBits() == other.nativeSnapshot.scaledDensity.toRawBits()
    }
}

data class MetricsResult(
    val status: AdaptStatus,
    val target: MetricsSnapshot? = null,
    val availableWidthPx: Int = 0,
    val availableHeightPx: Int = 0,
    val applyDp: Boolean = false,
    val applySp: Boolean = false,
    val applyScreenSizeDp: Boolean = false,
    val fontScale: Float = 1.0f,
    val reason: String = ""
)
