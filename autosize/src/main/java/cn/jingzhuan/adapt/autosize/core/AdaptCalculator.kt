package cn.jingzhuan.adapt.autosize.core

import kotlin.math.min

/**
 * Pure JVM calculator implementing DisplayMetrics adaptation formulas without Android dependencies.
 */
object AdaptCalculator {

    private const val DENSITY_DEFAULT = 160
    private const val REAL_SHORT_SIDE_MIN_WINDOW_RATIO = 0.85f

    private const val SCREEN_ORIENTATION_LANDSCAPE = 0
    private const val SCREEN_ORIENTATION_PORTRAIT = 1
    private const val SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6
    private const val SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7
    private const val SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8
    private const val SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9
    private const val SCREEN_ORIENTATION_USER_LANDSCAPE = 11
    private const val SCREEN_ORIENTATION_USER_PORTRAIT = 12

    private const val CONFIG_ORIENTATION_PORTRAIT = 1
    private const val CONFIG_ORIENTATION_LANDSCAPE = 2

    fun calculate(input: AdaptInput): MetricsResult {
        val spec = input.spec
        val bounds = input.bounds
        val native = input.nativeSnapshot

        val invalidReason = validateInputs(spec, bounds, native)
        if (invalidReason != null) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = invalidReason)
        }

        val statusBarPx = if (spec.manualStatusBarHeightPx > 0) {
            spec.manualStatusBarHeightPx
        } else {
            bounds.statusBarInsetPx.coerceAtLeast(0)
        }
        val availableWidthPx = bounds.widthPx
        val availableHeightPx = if (spec.useDeviceSize) bounds.heightPx else bounds.heightPx - statusBarPx
        if (availableWidthPx <= 0 || availableHeightPx <= 0) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "non_positive_available_bounds")
        }

        var conflictApplied = false
        if (!bounds.inMultiWindow && isOrientationConflicting(bounds)) {
            if (!input.forceApplyOnConflict) {
                return MetricsResult(
                    status = AdaptStatus.DEFERRED,
                    availableWidthPx = availableWidthPx,
                    availableHeightPx = availableHeightPx,
                    reason = "orientation_conflict"
                )
            }
            conflictApplied = true
        }

        val designSizeDp = resolveDesignSizeDp(spec)
        if (!isValidPositiveFloat(designSizeDp)) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "invalid_design_size")
        }

        val basePx = resolveBasePx(spec, bounds, availableWidthPx, availableHeightPx)
        if (!isValidPositiveFloat(basePx)) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "invalid_base_px")
        }

        val targetDensity = basePx / designSizeDp
        if (!isValidPositiveFloat(targetDensity)) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "invalid_target_density")
        }

        val targetDensityDpi = (targetDensity * DENSITY_DEFAULT).toInt()
        val targetScreenWidthDp = (availableWidthPx / targetDensity).toInt()
        val targetScreenHeightDp = (availableHeightPx / targetDensity).toInt()
        if (targetDensityDpi <= 0) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "invalid_target_density_dpi")
        }

        val effectiveFontScale = resolveEffectiveFontScale(spec, native)
        if (!isValidPositiveFloat(effectiveFontScale)) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "invalid_effective_font_scale")
        }
        val targetScaledDensity = targetDensity * effectiveFontScale
        if (!isValidPositiveFloat(targetScaledDensity)) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "invalid_target_scaled_density")
        }

        val applyDp = spec.supportDp
        val applySp = spec.supportSp
        val applyScreenSizeDp = spec.supportDp && spec.supportScreenSizeDp

        val targetSnapshot = MetricsSnapshot(
            density = if (applyDp) targetDensity else native.density,
            densityDpi = if (applyDp) targetDensityDpi else native.densityDpi,
            scaledDensity = if (applySp) targetScaledDensity else native.scaledDensity,
            screenWidthDp = if (applyScreenSizeDp) targetScreenWidthDp else native.screenWidthDp,
            screenHeightDp = if (applyScreenSizeDp) targetScreenHeightDp else native.screenHeightDp
        )

        return MetricsResult(
            status = AdaptStatus.APPLIED,
            target = targetSnapshot,
            availableWidthPx = availableWidthPx,
            availableHeightPx = availableHeightPx,
            applyDp = applyDp,
            applySp = applySp,
            applyScreenSizeDp = applyScreenSizeDp,
            fontScale = effectiveFontScale,
            reason = if (conflictApplied) "deferred_fallback" else "applied"
        )
    }

    fun resolveEffectiveFontScale(spec: AdaptSpec, native: MetricsSnapshot): Float {
        return when {
            spec.privateFontScale > 0f -> spec.privateFontScale
            spec.excludeFontScale -> 1.0f
            else -> native.scaledDensity / native.density
        }
    }

    fun resolveExpectedOrientation(requestedOrientation: Int, configOrientation: Int): CoreOrientation {
        return when (requestedOrientation) {
            SCREEN_ORIENTATION_PORTRAIT,
            SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            SCREEN_ORIENTATION_USER_PORTRAIT -> CoreOrientation.PORTRAIT

            SCREEN_ORIENTATION_LANDSCAPE,
            SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            SCREEN_ORIENTATION_USER_LANDSCAPE -> CoreOrientation.LANDSCAPE

            else -> when (configOrientation) {
                CONFIG_ORIENTATION_PORTRAIT -> CoreOrientation.PORTRAIT
                CONFIG_ORIENTATION_LANDSCAPE -> CoreOrientation.LANDSCAPE
                else -> CoreOrientation.UNSPECIFIED
            }
        }
    }

    fun isOrientationConflicting(bounds: WindowBounds): Boolean {
        return when (bounds.orientation) {
            CoreOrientation.PORTRAIT -> bounds.widthPx > bounds.heightPx
            CoreOrientation.LANDSCAPE -> bounds.heightPx > bounds.widthPx
            CoreOrientation.UNSPECIFIED -> false
        }
    }

    private fun validateInputs(
        spec: AdaptSpec,
        bounds: WindowBounds,
        native: MetricsSnapshot
    ): String? {
        if (bounds.widthPx <= 0 || bounds.heightPx <= 0) {
            return "non_positive_window_bounds"
        }
        if (!isValidPositiveFloat(native.density)) {
            return "invalid_native_density"
        }
        if (spec.privateFontScale.isNaN() || spec.privateFontScale.isInfinite() || spec.privateFontScale < 0f) {
            return "invalid_private_font_scale"
        }
        if (spec.privateFontScale <= 0f && !spec.excludeFontScale && !isValidPositiveFloat(native.scaledDensity)) {
            return "invalid_native_scaled_density"
        }
        return null
    }

    private fun resolveDesignSizeDp(spec: AdaptSpec): Float {
        return when (spec.basis) {
            CoreAdaptBasis.WIDTH -> spec.designWidthDp
            CoreAdaptBasis.HEIGHT -> spec.designHeightDp
            CoreAdaptBasis.SHORT_SIDE -> spec.effectiveDesignSizeDp
        }
    }

    private fun resolveBasePx(
        spec: AdaptSpec,
        bounds: WindowBounds,
        availableWidthPx: Int,
        availableHeightPx: Int
    ): Float {
        return when (spec.basis) {
            CoreAdaptBasis.WIDTH -> availableWidthPx.toFloat()
            CoreAdaptBasis.HEIGHT -> availableHeightPx.toFloat()
            CoreAdaptBasis.SHORT_SIDE -> {
                val windowShortSide = min(availableWidthPx, availableHeightPx)
                if (!spec.useRealDisplayShortSide || bounds.inMultiWindow) {
                    return windowShortSide.toFloat()
                }
                val realShortSide = min(bounds.realWidthPx, bounds.realHeightPx)
                if (realShortSide <= 0 ||
                    realShortSide < windowShortSide ||
                    windowShortSide < realShortSide * REAL_SHORT_SIDE_MIN_WINDOW_RATIO
                ) {
                    windowShortSide.toFloat()
                } else {
                    realShortSide.toFloat()
                }
            }
        }
    }

    private fun isValidPositiveFloat(value: Float): Boolean {
        return !value.isNaN() && !value.isInfinite() && value > 0f
    }
}
