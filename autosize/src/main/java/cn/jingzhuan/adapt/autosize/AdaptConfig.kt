package cn.jingzhuan.adapt.autosize

/**
 * Immutable configuration passed to [ScreenAdapt.init].
 * Values <= 0 for [designWidthDp] or [designHeightDp] fall back to Manifest meta-data
 * (`design_width_in_dp` / `design_height_in_dp`) and then library defaults (360 x 640dp).
 */
class AdaptConfig private constructor(
    val designWidthDp: Float,
    val designHeightDp: Float,
    val basis: AdaptBasis,
    val excludeFontScale: Boolean,
    val privateFontScale: Float,
    val useDeviceSize: Boolean,
    val statusBarHeightPx: Int,
    val supportDp: Boolean,
    val supportSp: Boolean,
    val supportScreenSizeDp: Boolean,
    val customFragment: Boolean,
    val syncPolicy: ApplicationSyncPolicy,
    val debugLog: Boolean,
    val strategy: AdaptStrategy?,
    val cancelTargets: Set<Class<*>>,
    val customTargets: Map<Class<*>, AdaptDirective>
) {
    constructor() : this(
        designWidthDp = 0f,
        designHeightDp = 0f,
        basis = AdaptBasis.WIDTH,
        excludeFontScale = false,
        privateFontScale = 0f,
        useDeviceSize = true,
        statusBarHeightPx = 0,
        supportDp = true,
        supportSp = true,
        supportScreenSizeDp = false,
        customFragment = true,
        syncPolicy = ApplicationSyncPolicy.FOREGROUND_SHARED,
        debugLog = false,
        strategy = null,
        cancelTargets = emptySet(),
        customTargets = emptyMap()
    )

    class Builder {
        var designWidthDp: Float = 0f
        var designHeightDp: Float = 0f
        var basis: AdaptBasis = AdaptBasis.WIDTH
        var excludeFontScale: Boolean = false
        var privateFontScale: Float = 0f
        var useDeviceSize: Boolean = true
        var statusBarHeightPx: Int = 0
        var supportDp: Boolean = true
        var supportSp: Boolean = true
        var supportScreenSizeDp: Boolean = false
        var customFragment: Boolean = true
        var syncPolicy: ApplicationSyncPolicy = ApplicationSyncPolicy.FOREGROUND_SHARED
        var debugLog: Boolean = false
        var strategy: AdaptStrategy? = null
        private val cancelTargets = LinkedHashSet<Class<*>>()
        private val customTargets = LinkedHashMap<Class<*>, AdaptDirective>()

        fun designSize(widthDp: Float, heightDp: Float) = apply {
            require(widthDp > 0f && heightDp > 0f) { "designSize widthDp and heightDp must be > 0" }
            this.designWidthDp = widthDp
            this.designHeightDp = heightDp
        }

        fun basis(basis: AdaptBasis) = apply { this.basis = basis }
        fun excludeFontScale(exclude: Boolean) = apply { this.excludeFontScale = exclude }
        fun privateFontScale(scale: Float) = apply {
            require(scale >= 0f && !scale.isNaN() && !scale.isInfinite()) { "privateFontScale must be >= 0" }
            this.privateFontScale = scale
        }

        fun useDeviceSize(useDeviceSize: Boolean) = apply { this.useDeviceSize = useDeviceSize }
        fun statusBarHeightPx(heightPx: Int) = apply {
            require(heightPx >= 0) { "statusBarHeightPx must be >= 0" }
            this.statusBarHeightPx = heightPx
        }

        fun supportDp(support: Boolean) = apply { this.supportDp = support }
        fun supportSp(support: Boolean) = apply { this.supportSp = support }
        fun supportScreenSizeDp(support: Boolean) = apply { this.supportScreenSizeDp = support }
        fun customFragment(enable: Boolean) = apply { this.customFragment = enable }
        fun syncPolicy(policy: ApplicationSyncPolicy) = apply { this.syncPolicy = policy }
        fun debugLog(enable: Boolean) = apply { this.debugLog = enable }
        fun strategy(strategy: AdaptStrategy?) = apply { this.strategy = strategy }

        fun cancelTarget(targetClass: Class<*>) = apply { cancelTargets.add(targetClass) }
        fun cancelTargets(vararg classes: Class<*>) = apply { cancelTargets.addAll(classes) }
        fun customTarget(targetClass: Class<*>, directive: AdaptDirective) = apply {
            customTargets[targetClass] = directive
        }

        fun build(): AdaptConfig = AdaptConfig(
            designWidthDp = designWidthDp,
            designHeightDp = designHeightDp,
            basis = basis,
            excludeFontScale = excludeFontScale,
            privateFontScale = privateFontScale,
            useDeviceSize = useDeviceSize,
            statusBarHeightPx = statusBarHeightPx,
            supportDp = supportDp,
            supportSp = supportSp,
            supportScreenSizeDp = supportScreenSizeDp,
            customFragment = customFragment,
            syncPolicy = syncPolicy,
            debugLog = debugLog,
            strategy = strategy,
            cancelTargets = cancelTargets.toSet(),
            customTargets = customTargets.toMap()
        )
    }
}

inline fun adaptConfig(block: AdaptConfig.Builder.() -> Unit): AdaptConfig {
    return AdaptConfig.Builder().apply(block).build()
}
