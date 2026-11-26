package cn.jingzhuan.adapt.autosize.engine

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Looper
import cn.jingzhuan.adapt.autosize.AdaptBasis
import cn.jingzhuan.adapt.autosize.AdaptConfig
import cn.jingzhuan.adapt.autosize.AdaptDirective
import cn.jingzhuan.adapt.autosize.AdaptEvent
import cn.jingzhuan.adapt.autosize.AdaptEventListener
import cn.jingzhuan.adapt.autosize.AdaptTarget
import cn.jingzhuan.adapt.autosize.ApplicationSyncPolicy
import cn.jingzhuan.adapt.autosize.NoAdapt
import cn.jingzhuan.adapt.autosize.TargetMetrics
import cn.jingzhuan.adapt.autosize.WindowInfo
import cn.jingzhuan.adapt.autosize.core.AdaptCalculator
import cn.jingzhuan.adapt.autosize.core.AdaptInput
import cn.jingzhuan.adapt.autosize.core.AdaptSpec
import cn.jingzhuan.adapt.autosize.core.AdaptStatus
import cn.jingzhuan.adapt.autosize.core.CoreAdaptBasis
import cn.jingzhuan.adapt.autosize.core.MetricsSnapshot
import cn.jingzhuan.adapt.autosize.core.ResumeCoordinator
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Unified single-engine coordinator for ScreenAdapt.
 */
internal object ScreenAdaptEngine {

    private const val KEY_DESIGN_WIDTH_IN_DP = "design_width_in_dp"
    private const val KEY_DESIGN_HEIGHT_IN_DP = "design_height_in_dp"
    private const val DEFAULT_DESIGN_WIDTH_DP = 360f
    private const val DEFAULT_DESIGN_HEIGHT_DP = 640f

    private val sessions = WeakHashMap<Activity, ActivitySession>()
    private val resumedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val creatingSessions = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())

    @Volatile
    private var initialized: Boolean = false
    @Volatile
    private var application: Application? = null
    @Volatile
    private var config: AdaptConfig = AdaptConfig()
    @Volatile
    private var resolvedDesignWidthDp: Float = DEFAULT_DESIGN_WIDTH_DP
    @Volatile
    private var resolvedDesignHeightDp: Float = DEFAULT_DESIGN_HEIGHT_DP
    @Volatile
    private var appNativeSnapshot: MetricsSnapshot? = null

    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var componentCallbacks: ComponentCallbacks? = null
    private var fragmentHook: FragmentHook? = null

    @Synchronized
    fun init(app: Application, adaptConfig: AdaptConfig) {
        if (initialized) return

        this.application = app
        this.config = adaptConfig
        AdaptLog.enabled = adaptConfig.debugLog

        MetricsApplier.inspectMiui(app.resources)
        this.appNativeSnapshot = MetricsSnapshotReader.captureApplicationNative(app)
        resolveDesignSizes(app, adaptConfig)

        val compCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val baseline = appNativeSnapshot ?: return
                appNativeSnapshot = MetricsSnapshotReader.updateForConfiguration(baseline, newConfig, baseline)
            }

            override fun onLowMemory() = Unit
        }
        this.componentCallbacks = compCallbacks
        app.registerComponentCallbacks(compCallbacks)

        if (adaptConfig.customFragment) {
            fragmentHook = createFragmentHook()
        }

        val actCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                onActivityCreatedInternal(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                onActivityStartedInternal(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                onActivityResumedInternal(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities.remove(activity)
            }

            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                onActivityDestroyedInternal(activity)
            }
        }
        this.activityCallbacks = actCallbacks
        app.registerActivityLifecycleCallbacks(actCallbacks)
        this.initialized = true
    }

    fun isInitialized(): Boolean = initialized

    fun wrapContext(base: Context): Context {
        if (!initialized) return base
        val desiredFontScale = resolveDesiredContextFontScale() ?: return base
        val currentConfig = base.resources.configuration
        if (currentConfig.fontScale.toRawBits() == desiredFontScale.toRawBits()) {
            return base
        }
        // Use an empty-delta Configuration overriding ONLY fontScale so densityDpi, uiMode,
        // locale, and window dimensions are never frozen or altered (avoids qualifier bucket drift).
        val deltaConfig = Configuration().apply {
            fontScale = desiredFontScale
        }
        return base.createConfigurationContext(deltaConfig)
    }

    fun applyOverrideConfiguration(baseConfig: Configuration): Configuration {
        if (!initialized) return baseConfig
        val desiredFontScale = resolveDesiredContextFontScale() ?: return baseConfig
        // Only lock fontScale; never mutate densityDpi.
        baseConfig.fontScale = desiredFontScale
        return baseConfig
    }

    fun applyDirective(activity: Activity, directive: AdaptDirective) {
        if (!initialized || Looper.myLooper() != Looper.getMainLooper()) return
        val session = getOrCreateSession(activity)
        if (session.isAdapting) return
        refreshWindow(activity, session)
        applyResolvedDirective(activity, session, directive, isExplicit = true, forceOnConflict = false)
    }

    fun applyNative(activity: Activity) {
        if (!initialized || Looper.myLooper() != Looper.getMainLooper()) return
        val session = getOrCreateSession(activity)
        if (session.isAdapting) return
        applyNativeInternal(activity, session, isExplicit = true)
    }

    fun reapply(activity: Activity) {
        if (!initialized || Looper.myLooper() != Looper.getMainLooper()) return
        if (activity.isFinishing || activity.isDestroyed) return
        val session = getOrCreateSession(activity)
        if (session.isAdapting) return
        refreshWindow(activity, session)
        reapplySession(activity, session, forceOnConflict = false)
    }

    fun ensureApplied(activity: Activity) {
        if (!initialized || Looper.myLooper() != Looper.getMainLooper()) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (creatingSessions.contains(activity)) return
        val session = sessions[activity]
        if (session == null) {
            reapply(activity)
            return
        }
        if (session.isAdapting || session.isResolvingWindow || session.isResolvingDirective ||
            session.isSkipped || session.isDeferred
        ) return

        var needsNative = false
        var needsReapply = false
        session.isAdapting = true
        try {
            val res = activity.resources
            if (session.isNativeMode) {
                val refreshedNative = refreshActivityNativeSnapshot(res, session)
                needsNative = !MetricsApplier.matchesSnapshot(
                    resources = res,
                    snapshot = refreshedNative,
                    checkDp = config.supportDp,
                    checkSp = config.supportSp,
                    checkScreenSizeDp = config.supportDp && config.supportScreenSizeDp
                )
            } else {
                val lastResult = session.lastAppliedResult
                if (lastResult != null && lastResult.status == AdaptStatus.APPLIED) {
                    needsReapply = !MetricsApplier.matchesTarget(res, lastResult)
                } else {
                    needsReapply = true
                }
            }
        } finally {
            session.isAdapting = false
        }

        if (needsNative) {
            applyNativeInternal(activity, session)
        } else if (needsReapply) {
            reapplySession(activity, session, forceOnConflict = false)
        }
    }

    fun resolveTargetMetrics(context: Context, directive: AdaptDirective?): TargetMetrics? {
        val app = application
        val bounds = if (context is Activity) {
            sessions[context]?.cachedBounds ?: WindowBoundsResolver.resolveCurrentWindow(context).first
        } else {
            WindowBoundsResolver.resolveForContext(context)
        }
        val baseline = appNativeSnapshot
            ?: app?.let { MetricsSnapshotReader.captureApplicationNative(it) }
            ?: run {
                val dm = context.resources.displayMetrics
                MetricsSnapshot(dm.density, dm.densityDpi, dm.scaledDensity)
            }
        val nativeSnapshot = if (context is Activity) {
            sessions[context]?.let { computeActivityNativeSnapshot(context.resources, it) }
                ?: MetricsSnapshotReader.captureActivityNative(context, baseline)
        } else {
            MetricsSnapshotReader.updateForConfiguration(baseline, context.resources.configuration, baseline)
        }

        val effectiveDirective = directive ?: defaultGlobalDirective()
        val spec = specFromDirective(effectiveDirective) ?: return TargetMetrics(
            density = nativeSnapshot.density,
            densityDpi = nativeSnapshot.densityDpi,
            scaledDensity = nativeSnapshot.scaledDensity,
            fontScale = nativeSnapshot.scaledDensity / nativeSnapshot.density,
            screenWidthDp = nativeSnapshot.screenWidthDp,
            screenHeightDp = nativeSnapshot.screenHeightDp
        )
        val result = AdaptCalculator.calculate(
            AdaptInput(
                spec = spec,
                bounds = bounds,
                nativeSnapshot = nativeSnapshot,
                forceApplyOnConflict = true
            )
        )
        val target = result.target ?: return null
        return TargetMetrics(
            density = target.density,
            densityDpi = target.densityDpi,
            scaledDensity = target.scaledDensity,
            fontScale = result.fontScale,
            screenWidthDp = target.screenWidthDp,
            screenHeightDp = target.screenHeightDp
        )
    }

    fun getWindowInfo(activity: Activity): WindowInfo? {
        if (!initialized) return null
        val session = getOrCreateSession(activity)
        if (session.cachedWindowInfo == null && Looper.myLooper() == Looper.getMainLooper()) {
            refreshWindow(activity, session)
        }
        return session.cachedWindowInfo
    }

    fun addListener(activity: Activity, listener: AdaptEventListener) {
        getOrCreateSession(activity).listeners.addIfAbsent(listener)
    }

    fun removeListener(activity: Activity, listener: AdaptEventListener) {
        sessions[activity]?.listeners?.remove(listener)
    }

    internal fun resetForTesting() {
        val app = application
        val snap = appNativeSnapshot
        if (app != null) {
            if (snap != null) {
                MetricsApplier.applySnapshot(
                    targetResources = app.resources,
                    application = app,
                    syncApplication = true,
                    snapshot = snap,
                    applyDp = true,
                    applySp = true,
                    applyScreenSizeDp = true
                )
            }
            componentCallbacks?.let { app.unregisterComponentCallbacks(it) }
            activityCallbacks?.let { app.unregisterActivityLifecycleCallbacks(it) }
        }
        sessions.values.forEach { it.dispose(it.activityRef.get()) }
        sessions.clear()
        resumedActivities.clear()
        creatingSessions.clear()
        initialized = false
        application = null
        config = AdaptConfig()
        resolvedDesignWidthDp = DEFAULT_DESIGN_WIDTH_DP
        resolvedDesignHeightDp = DEFAULT_DESIGN_HEIGHT_DP
        appNativeSnapshot = null
        activityCallbacks = null
        componentCallbacks = null
        fragmentHook = null
        MetricsApplier.resetForTesting()
        WindowBoundsResolver.resolveCurrentWindowOverrideForTesting = null
    }

    internal fun getOrCreateSessionForBridge(activity: Activity): ActivitySession = getOrCreateSession(activity)

    internal fun getSessionForBridge(activity: Activity): ActivitySession? = sessions[activity]

    private fun onActivityCreatedInternal(activity: Activity) {
        val session = getOrCreateSession(activity)
        fragmentHook?.registerIfNeeded(activity, session)
        refreshWindow(activity, session)
        attachLayoutWatcher(activity, session)
        resolveAndApply(target = activity, activity = activity, session = session, forceOnConflict = false)
    }

    private fun onActivityStartedInternal(activity: Activity) {
        val session = getOrCreateSession(activity)
        refreshWindow(activity, session)
        attachLayoutWatcher(activity, session)
        reapplySession(activity, session, forceOnConflict = false)
    }

    private fun onActivityResumedInternal(activity: Activity) {
        resumedActivities.add(activity)
        val session = getOrCreateSession(activity)
        session.resumedOnce = true

        ResumeCoordinator.executeResumeSequence(
            isNativeOrSkipped = { session.isNativeMode || session.isSkipped },
            refreshWindowAndDidChange = {
                val prevBounds = session.cachedBounds
                refreshWindow(activity, session)
                prevBounds != session.cachedBounds
            },
            reapplyForWindowChange = {
                reapplySession(activity, session, forceOnConflict = false)
            },
            isDeferred = { session.isDeferred },
            markResumeFallbackPending = {
                session.resumeFallbackPending = true
            },
            syncCachedResultToForeground = {
                val app = application
                val result = session.lastAppliedResult
                if (result != null &&
                    ResumeCoordinator.shouldSyncCachedResult(
                        isNativeMode = session.isNativeMode,
                        isSkipped = session.isSkipped,
                        isDeferred = session.isDeferred,
                        isForegroundSharedPolicy = config.syncPolicy == ApplicationSyncPolicy.FOREGROUND_SHARED,
                        canSyncApplication = canSyncApplication(activity, session),
                        lastAppliedStatus = result.status,
                        lastAppliedBounds = session.lastAppliedInput?.bounds,
                        currentBounds = session.cachedBounds,
                        activityMatchesTarget = MetricsApplier.matchesTarget(activity.resources, result),
                        applicationMatchesTarget = app == null || MetricsApplier.matchesTarget(app.resources, result)
                    )
                ) {
                    MetricsApplier.applyResult(
                        targetResources = activity.resources,
                        application = app,
                        syncApplication = true,
                        result = result
                    )
                }
            }
        )
    }

    private fun onActivityDestroyedInternal(activity: Activity) {
        resumedActivities.remove(activity)
        sessions.remove(activity)?.dispose(activity)
    }

    private fun resolveAndApply(
        target: Any,
        activity: Activity,
        session: ActivitySession,
        forceOnConflict: Boolean
    ) {
        if (session.isAdapting || session.isResolvingDirective) return
        val directive = resolveExplicitDirectiveSafely(target, activity, session) ?: defaultGlobalDirective()
        applyResolvedDirective(activity, session, directive, isExplicit = false, forceOnConflict = forceOnConflict)
    }

    private fun reapplySession(
        activity: Activity,
        session: ActivitySession,
        forceOnConflict: Boolean
    ) {
        if (session.isAdapting || session.isResolvingDirective) return
        val explicitDirective = session.lastExplicitDirective
        if (explicitDirective != null) {
            applyResolvedDirective(
                activity = activity,
                session = session,
                directive = explicitDirective,
                isExplicit = true,
                forceOnConflict = forceOnConflict
            )
            return
        }
        val target = activeFragmentTarget(session) ?: activity
        resolveAndApply(target = target, activity = activity, session = session, forceOnConflict = forceOnConflict)
    }

    private fun resolveExplicitDirectiveSafely(
        target: Any,
        activity: Activity,
        session: ActivitySession
    ): AdaptDirective? {
        if (session.isResolvingDirective) return null
        session.isResolvingDirective = true
        return try {
            resolveExplicitDirective(target, activity)
        } finally {
            session.isResolvingDirective = false
        }
    }

    private fun resolveExplicitDirective(target: Any, activity: Activity): AdaptDirective? {
        val targetClass = target.javaClass
        if (config.cancelTargets.contains(targetClass)) {
            return AdaptDirective.Native
        }
        config.customTargets[targetClass]?.let { return it }

        if (target is NoAdapt) {
            return AdaptDirective.Native
        }
        if (target is AdaptTarget) {
            return target.adaptDirective()
        }
        return config.strategy?.resolve(target, activity)
    }

    private fun activeFragmentTarget(session: ActivitySession): Any? {
        if (!config.customFragment) return null
        return fragmentHook?.resolveActiveTarget(session)
    }

    private fun createFragmentHook(): FragmentHook? {
        return try {
            FragmentHookImpl(
                hasExplicitDirective = { fragment, host ->
                    val session = getOrCreateSession(host)
                    resolveExplicitDirectiveSafely(fragment, host, session) != null
                },
                applyFragmentTarget = { fragment, host, session ->
                    resolveAndApply(target = fragment, activity = host, session = session, forceOnConflict = false)
                }
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun defaultGlobalDirective(): AdaptDirective {
        return when (config.basis) {
            AdaptBasis.WIDTH -> AdaptDirective.Width(resolvedDesignWidthDp)
            AdaptBasis.HEIGHT -> AdaptDirective.Height(resolvedDesignHeightDp)
        }
    }

    private fun applyResolvedDirective(
        activity: Activity,
        session: ActivitySession,
        directive: AdaptDirective,
        isExplicit: Boolean,
        forceOnConflict: Boolean
    ) {
        session.lastExplicitDirective = if (isExplicit) directive else null
        when (directive) {
            is AdaptDirective.Skip -> {
                session.isSkipped = true
                session.isDeferred = false
                session.dispatchEvent(activity, AdaptEvent(AdaptEvent.Type.SKIPPED, 0f, "strategy_skip"))
            }

            is AdaptDirective.Native -> {
                applyNativeInternal(activity, session, isExplicit = isExplicit)
            }

            is AdaptDirective.Width,
            is AdaptDirective.Height,
            is AdaptDirective.ShortSide -> {
                session.isSkipped = false
                session.isNativeMode = false
                val spec = specFromDirective(directive) ?: return
                applySpec(activity, session, spec, forceOnConflict)
            }
        }
    }

    private fun applyNativeInternal(
        activity: Activity,
        session: ActivitySession,
        isExplicit: Boolean = false
    ) {
        if (session.isAdapting) return
        session.isAdapting = true
        try {
            val currentNative = refreshActivityNativeSnapshot(activity.resources, session)
            session.isNativeMode = true
            session.isSkipped = false
            session.isDeferred = false
            session.resumeFallbackPending = false
            if (isExplicit) {
                session.lastExplicitDirective = AdaptDirective.Native
            }
            session.lastAppliedInput = null
            session.lastAppliedResult = null

            // Native mode restores only the target Activity's Context snapshot and NEVER writes Application.
            MetricsApplier.applySnapshot(
                targetResources = activity.resources,
                application = null,
                syncApplication = false,
                snapshot = currentNative,
                applyDp = config.supportDp,
                applySp = config.supportSp,
                applyScreenSizeDp = config.supportDp && config.supportScreenSizeDp
            )
            session.dispatchEvent(
                activity,
                AdaptEvent(AdaptEvent.Type.NATIVE, currentNative.density, "native_mode")
            )
        } finally {
            session.isAdapting = false
        }
    }

    private fun computeActivityNativeSnapshot(
        resources: Resources,
        session: ActivitySession
    ): MetricsSnapshot {
        val baseline = appNativeSnapshot ?: session.nativeSnapshot
        return MetricsSnapshotReader.updateForConfiguration(
            previous = session.nativeSnapshot,
            newConfig = resources.configuration,
            appBaseline = baseline,
            lastAppliedResult = session.lastAppliedResult
        )
    }

    private fun refreshActivityNativeSnapshot(
        resources: Resources,
        session: ActivitySession
    ): MetricsSnapshot {
        return computeActivityNativeSnapshot(resources, session).also {
            session.nativeSnapshot = it
        }
    }

    private fun applySpec(
        activity: Activity,
        session: ActivitySession,
        spec: AdaptSpec,
        forceOnConflict: Boolean
    ) {
        session.isAdapting = true
        try {
            val bounds = session.cachedBounds ?: run {
                refreshWindow(activity, session)
                session.cachedBounds ?: WindowBoundsResolver.resolveForContext(activity)
            }

            val currentNative = refreshActivityNativeSnapshot(activity.resources, session)

            val input = AdaptInput(
                spec = spec,
                bounds = bounds,
                nativeSnapshot = currentNative,
                forceApplyOnConflict = forceOnConflict
            )

            val lastResult = session.lastAppliedResult
            if (session.lastAppliedInput?.bitsEquals(input) == true &&
                lastResult != null &&
                lastResult.status == AdaptStatus.APPLIED
            ) {
                session.isDeferred = false
                session.resumeFallbackPending = false
                val app = application
                if (MetricsApplier.matchesTarget(activity.resources, lastResult)) {
                    val syncApp = canSyncApplication(activity, session)
                    if (syncApp && app != null && !MetricsApplier.matchesTarget(app.resources, lastResult)) {
                        MetricsApplier.applyResult(
                            targetResources = activity.resources,
                            application = app,
                            syncApplication = true,
                            result = lastResult
                        )
                    }
                    session.dispatchEvent(
                        activity,
                        AdaptEvent(AdaptEvent.Type.SKIPPED, lastResult.target?.density ?: 0f, "dedup_matched")
                    )
                    return
                }
                MetricsApplier.applyResult(
                    targetResources = activity.resources,
                    application = app,
                    syncApplication = canSyncApplication(activity, session),
                    result = lastResult
                )
                session.dispatchEvent(
                    activity,
                    AdaptEvent(AdaptEvent.Type.APPLIED, lastResult.target?.density ?: 0f, "drift_restored")
                )
                return
            }

            val result = AdaptCalculator.calculate(input)
            when (result.status) {
                AdaptStatus.APPLIED -> {
                    session.isDeferred = false
                    session.resumeFallbackPending = false
                    session.lastAppliedInput = input
                    session.lastAppliedResult = result
                    MetricsApplier.applyResult(
                        targetResources = activity.resources,
                        application = application,
                        syncApplication = canSyncApplication(activity, session),
                        result = result
                    )
                    val targetDensity = result.target?.density ?: 0f
                    AdaptLog.d(
                        "Adapted ${activity.javaClass.simpleName}: density=$targetDensity, " +
                            "window=${bounds.widthPx}x${bounds.heightPx}, reason=${result.reason}"
                    )
                    session.dispatchEvent(
                        activity,
                        AdaptEvent(AdaptEvent.Type.APPLIED, targetDensity, result.reason)
                    )
                }

                AdaptStatus.DEFERRED -> {
                    session.isDeferred = true
                    AdaptLog.d("Deferred ${activity.javaClass.simpleName}: ${result.reason}")
                    session.dispatchEvent(
                        activity,
                        AdaptEvent(AdaptEvent.Type.DEFERRED, 0f, result.reason)
                    )
                }

                AdaptStatus.INVALID -> {
                    AdaptLog.w("Invalid adapt input for ${activity.javaClass.simpleName}: ${result.reason}")
                    session.dispatchEvent(
                        activity,
                        AdaptEvent(AdaptEvent.Type.INVALID, 0f, result.reason)
                    )
                }

                AdaptStatus.SKIPPED -> {
                    session.dispatchEvent(
                        activity,
                        AdaptEvent(AdaptEvent.Type.SKIPPED, 0f, result.reason)
                    )
                }

                AdaptStatus.NATIVE -> {
                    session.dispatchEvent(
                        activity,
                        AdaptEvent(AdaptEvent.Type.NATIVE, session.nativeSnapshot.density, result.reason)
                    )
                }
            }
        } finally {
            session.isAdapting = false
        }
    }

    private fun attachLayoutWatcher(activity: Activity, session: ActivitySession) {
        session.attachLayoutListener(activity) {
            val current = session.activityRef.get() ?: return@attachLayoutListener
            if (current.isFinishing || current.isDestroyed || session.isAdapting) return@attachLayoutListener

            val prevBounds = session.cachedBounds
            refreshWindow(current, session)
            val newBounds = session.cachedBounds ?: return@attachLayoutListener

            if (session.isDeferred) {
                if (!AdaptCalculator.isOrientationConflicting(newBounds)) {
                    reapplySession(current, session, forceOnConflict = false)
                } else if (session.resumeFallbackPending) {
                    session.resumeFallbackPending = false
                    reapplySession(current, session, forceOnConflict = true)
                }
                return@attachLayoutListener
            }

            if (prevBounds != newBounds) {
                reapplySession(current, session, forceOnConflict = false)
            } else if (!session.isNativeMode && !session.isSkipped) {
                val lastResult = session.lastAppliedResult
                if (lastResult != null && lastResult.status == AdaptStatus.APPLIED &&
                    !MetricsApplier.matchesTarget(current.resources, lastResult)
                ) {
                    reapplySession(current, session, forceOnConflict = false)
                }
            }
        }
    }

    private fun refreshWindow(activity: Activity, session: ActivitySession) {
        if (session.isResolvingWindow) return
        session.isResolvingWindow = true
        try {
            val (bounds, info) = WindowBoundsResolver.resolveCurrentWindow(activity)
            session.cachedBounds = bounds
            session.cachedWindowInfo = info
        } finally {
            session.isResolvingWindow = false
        }
    }

    private fun getOrCreateSession(activity: Activity): ActivitySession {
        sessions[activity]?.let { return it }
        if (!creatingSessions.add(activity)) {
            val fallback = appNativeSnapshot ?: MetricsSnapshot(1f, 160, 1f)
            return ActivitySession(activity, fallback)
        }
        val nativeSnapshot = try {
            val baseline = appNativeSnapshot ?: application?.let {
                MetricsSnapshotReader.captureApplicationNative(it).also { snap -> appNativeSnapshot = snap }
            } ?: run {
                val dm = activity.resources.displayMetrics
                MetricsSnapshot(dm.density, dm.densityDpi, dm.scaledDensity)
            }
            MetricsSnapshotReader.captureActivityNative(activity, baseline)
        } finally {
            creatingSessions.remove(activity)
        }
        return ActivitySession(activity, nativeSnapshot).also {
            sessions[activity] = it
        }
    }

    private fun canSyncApplication(activity: Activity, session: ActivitySession): Boolean {
        return when (config.syncPolicy) {
            ApplicationSyncPolicy.LEGACY_SHARED -> !session.isNativeMode
            ApplicationSyncPolicy.ACTIVITY_ONLY -> false
            ApplicationSyncPolicy.FOREGROUND_SHARED -> {
                if (session.isNativeMode) return false
                val inMultiWindow = session.cachedBounds?.inMultiWindow ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        activity.isInMultiWindowMode
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
                if (inMultiWindow) return false
                if (resumedActivities.contains(activity)) return true
                !session.resumedOnce && resumedActivities.none { it !== activity }
            }
        }
    }

    private fun specFromDirective(directive: AdaptDirective): AdaptSpec? {
        return when (directive) {
            is AdaptDirective.Width -> AdaptSpec(
                basis = CoreAdaptBasis.WIDTH,
                designWidthDp = directive.sizeDp,
                designHeightDp = resolvedDesignHeightDp,
                effectiveDesignSizeDp = directive.sizeDp,
                supportDp = config.supportDp,
                supportSp = config.supportSp,
                supportScreenSizeDp = config.supportScreenSizeDp,
                excludeFontScale = config.excludeFontScale,
                privateFontScale = config.privateFontScale,
                useDeviceSize = config.useDeviceSize,
                manualStatusBarHeightPx = config.statusBarHeightPx,
                useRealDisplayShortSide = true
            )

            is AdaptDirective.Height -> AdaptSpec(
                basis = CoreAdaptBasis.HEIGHT,
                designWidthDp = resolvedDesignWidthDp,
                designHeightDp = directive.sizeDp,
                effectiveDesignSizeDp = directive.sizeDp,
                supportDp = config.supportDp,
                supportSp = config.supportSp,
                supportScreenSizeDp = config.supportScreenSizeDp,
                excludeFontScale = config.excludeFontScale,
                privateFontScale = config.privateFontScale,
                useDeviceSize = config.useDeviceSize,
                manualStatusBarHeightPx = config.statusBarHeightPx,
                useRealDisplayShortSide = true
            )

            is AdaptDirective.ShortSide -> AdaptSpec(
                basis = CoreAdaptBasis.SHORT_SIDE,
                designWidthDp = resolvedDesignWidthDp,
                designHeightDp = resolvedDesignHeightDp,
                effectiveDesignSizeDp = directive.effectiveSizeDp,
                supportDp = config.supportDp,
                supportSp = config.supportSp,
                supportScreenSizeDp = config.supportScreenSizeDp,
                excludeFontScale = config.excludeFontScale,
                privateFontScale = config.privateFontScale,
                useDeviceSize = config.useDeviceSize,
                manualStatusBarHeightPx = config.statusBarHeightPx,
                useRealDisplayShortSide = directive.useRealDisplay
            )

            AdaptDirective.Native,
            AdaptDirective.Skip -> null
        }
    }

    private fun resolveDesiredContextFontScale(): Float? {
        return when {
            config.privateFontScale > 0f -> config.privateFontScale
            config.excludeFontScale -> 1.0f
            else -> null
        }
    }

    private fun resolveDesignSizes(app: Application, adaptConfig: AdaptConfig) {
        var width = adaptConfig.designWidthDp
        var height = adaptConfig.designHeightDp
        if (width <= 0f || height <= 0f) {
            try {
                val appInfo = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
                val meta = appInfo.metaData
                if (meta != null) {
                    if (width <= 0f && meta.containsKey(KEY_DESIGN_WIDTH_IN_DP)) {
                        val wVal = meta.get(KEY_DESIGN_WIDTH_IN_DP)
                        if (wVal is Number && wVal.toFloat() > 0f) {
                            width = wVal.toFloat()
                        }
                    }
                    if (height <= 0f && meta.containsKey(KEY_DESIGN_HEIGHT_IN_DP)) {
                        val hVal = meta.get(KEY_DESIGN_HEIGHT_IN_DP)
                        if (hVal is Number && hVal.toFloat() > 0f) {
                            height = hVal.toFloat()
                        }
                    }
                }
            } catch (e: PackageManager.NameNotFoundException) {
                AdaptLog.w("Failed to read manifest meta-data: ${e.message}")
            }
        }
        resolvedDesignWidthDp = if (width > 0f) width else DEFAULT_DESIGN_WIDTH_DP
        resolvedDesignHeightDp = if (height > 0f) height else DEFAULT_DESIGN_HEIGHT_DP
    }
}
