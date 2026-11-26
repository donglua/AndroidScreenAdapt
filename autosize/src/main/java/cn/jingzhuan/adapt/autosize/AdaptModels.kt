package cn.jingzhuan.adapt.autosize

import android.app.Activity

/**
 * Default scaling axis for global adaptation.
 */
enum class AdaptBasis {
    WIDTH,
    HEIGHT
}

/**
 * Controls when Activity adaptation also updates Application Resources.DisplayMetrics.
 */
enum class ApplicationSyncPolicy {
    /** Writes Activity; writes Application only during isolated creation or when resumed and not in multi-window. */
    FOREGROUND_SHARED,

    /** Writes only the target Activity's DisplayMetrics. */
    ACTIVITY_ONLY,

    /** Writes both Activity and Application unconditionally (legacy behavior). */
    LEGACY_SHARED
}

/**
 * Explicit adaptation directive resolved per Activity, Fragment, or custom strategy.
 */
sealed class AdaptDirective {
    data class Width(val sizeDp: Float) : AdaptDirective()
    data class Height(val sizeDp: Float) : AdaptDirective()
    data class ShortSide @JvmOverloads constructor(
        val effectiveSizeDp: Float,
        val useRealDisplay: Boolean = true
    ) : AdaptDirective()

    data object Native : AdaptDirective()
    data object Skip : AdaptDirective()
}

/**
 * Marker interface for Activities or Fragments that should restore native metrics without adapting.
 */
interface NoAdapt

/**
 * Implemented by Activities or Fragments to declare a custom [AdaptDirective].
 */
interface AdaptTarget {
    fun adaptDirective(): AdaptDirective
}

/**
 * Strategy hook deciding the [AdaptDirective] for an Activity or Fragment target.
 * Returning `null` falls back to default resolution (`NoAdapt` / `AdaptTarget` / global config).
 */
fun interface AdaptStrategy {
    fun resolve(target: Any, activity: Activity): AdaptDirective?
}

/**
 * Target density metrics computed for a Context, useful for Compose LocalDensity integration.
 */
data class TargetMetrics(
    val density: Float,
    val densityDpi: Int,
    val scaledDensity: Float,
    val fontScale: Float,
    val screenWidthDp: Int,
    val screenHeightDp: Int
)

/**
 * Immutable snapshot of the current Activity window bounds and orientation evidence.
 */
data class WindowInfo(
    val widthPx: Int,
    val heightPx: Int,
    val realWidthPx: Int,
    val realHeightPx: Int,
    val statusBarInsetPx: Int,
    val navigationBarInsetPx: Int,
    val configurationOrientation: Int,
    val requestedOrientation: Int,
    val inMultiWindow: Boolean,
    val stable: Boolean
)

/**
 * Event emitted after an adaptation pass on an Activity.
 */
data class AdaptEvent(
    val type: Type,
    val targetDensity: Float,
    val reason: String
) {
    enum class Type {
        APPLIED,
        SKIPPED,
        DEFERRED,
        INVALID,
        NATIVE
    }
}

/**
 * Listener notified when adaptation applies, skips, defers, or resets for an Activity.
 */
fun interface AdaptEventListener {
    fun onAdaptEvent(activity: Activity, event: AdaptEvent)
}
