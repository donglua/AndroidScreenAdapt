package cn.jingzhuan.adapt.autosize.core

import kotlin.math.max
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

        val basePx = resolveBasePx(
            spec = spec,
            bounds = bounds,
            availableWidthPx = availableWidthPx,
            availableHeightPx = availableHeightPx,
            normalizeConflictingOrientation = conflictApplied
        )
        if (!isValidPositiveFloat(basePx)) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "invalid_base_px")
        }

        val targetDensity = basePx / designSizeDp
        if (!isValidPositiveFloat(targetDensity)) {
            return MetricsResult(status = AdaptStatus.INVALID, reason = "invalid_target_density")
        }

        val normalizedWidthPx = if (conflictApplied) {
            when (bounds.orientation) {
                CoreOrientation.PORTRAIT -> min(availableWidthPx, availableHeightPx)
                CoreOrientation.LANDSCAPE -> max(availableWidthPx, availableHeightPx)
                CoreOrientation.UNSPECIFIED -> availableWidthPx
            }
        } else {
            availableWidthPx
        }
        val normalizedHeightPx = if (conflictApplied) {
            when (bounds.orientation) {
                CoreOrientation.PORTRAIT -> max(availableWidthPx, availableHeightPx)
                CoreOrientation.LANDSCAPE -> min(availableWidthPx, availableHeightPx)
                CoreOrientation.UNSPECIFIED -> availableHeightPx
            }
        } else {
            availableHeightPx
        }

        val targetDensityDpi = (targetDensity * DENSITY_DEFAULT).toInt()
        val targetScreenWidthDp = (normalizedWidthPx / targetDensity).toInt()
        val targetScreenHeightDp = (normalizedHeightPx / targetDensity).toInt()
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

    /**
     * Returns true when the window is explicitly in multi-window mode or either its short side
     * or long side is significantly smaller than the physical display (e.g. split-screen,
     * freeform, or OEM MagicWindow/letterboxed compatibility windows where `isInMultiWindowMode`
     * may still report false).
     */
    fun isWindowConstrained(
        bounds: WindowBounds,
        availableWidthPx: Int = bounds.widthPx,
        availableHeightPx: Int = bounds.heightPx
    ): Boolean {
        if (bounds.inMultiWindow) return true
        if (availableWidthPx <= 0 || availableHeightPx <= 0) return false
        val windowShortSide = min(availableWidthPx, availableHeightPx)
        val windowLongSide = max(availableWidthPx, availableHeightPx)
        val realShortSide = min(bounds.realWidthPx, bounds.realHeightPx)
        val realLongSide = max(bounds.realWidthPx, bounds.realHeightPx)
        val shortConstrained =
            realShortSide > 0 && windowShortSide < realShortSide * REAL_SHORT_SIDE_MIN_WINDOW_RATIO
        val longConstrained =
            realLongSide > 0 && windowLongSide < realLongSide * REAL_SHORT_SIDE_MIN_WINDOW_RATIO
        return shortConstrained || longConstrained
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
        availableHeightPx: Int,
        normalizeConflictingOrientation: Boolean
    ): Float {
        val effectiveWidthPx = if (normalizeConflictingOrientation) {
            when (bounds.orientation) {
                CoreOrientation.PORTRAIT -> min(availableWidthPx, availableHeightPx)
                CoreOrientation.LANDSCAPE -> max(availableWidthPx, availableHeightPx)
                CoreOrientation.UNSPECIFIED -> availableWidthPx
            }
        } else {
            availableWidthPx
        }
        val effectiveHeightPx = if (normalizeConflictingOrientation) {
            when (bounds.orientation) {
                CoreOrientation.PORTRAIT -> max(availableWidthPx, availableHeightPx)
                CoreOrientation.LANDSCAPE -> min(availableWidthPx, availableHeightPx)
                CoreOrientation.UNSPECIFIED -> availableHeightPx
            }
        } else {
            availableHeightPx
        }
        return when (spec.basis) {
            CoreAdaptBasis.WIDTH -> effectiveWidthPx.toFloat()
            CoreAdaptBasis.HEIGHT -> effectiveHeightPx.toFloat()
            CoreAdaptBasis.SHORT_SIDE -> {
                val windowShortSide = min(effectiveWidthPx, effectiveHeightPx)
                if (!spec.useRealDisplayShortSide ||
                    isWindowConstrained(bounds, effectiveWidthPx, effectiveHeightPx)
                ) {
                    return windowShortSide.toFloat()
                }
                val realShortSide = min(bounds.realWidthPx, bounds.realHeightPx)
                if (realShortSide <= 0 || realShortSide < windowShortSide) {
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
