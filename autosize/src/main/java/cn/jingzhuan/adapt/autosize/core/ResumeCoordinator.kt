package cn.jingzhuan.adapt.autosize.core

/**
 * Pure JVM coordinator defining the invariant execution order during Activity#onResume:
 * refresh current window bounds and reapply on bounds change BEFORE any foreground sync
 * of a cached [MetricsResult].
 */
internal object ResumeCoordinator {

    inline fun executeResumeSequence(
        isNativeOrSkipped: () -> Boolean,
        refreshWindowAndDidChange: () -> Boolean,
        reapplyForWindowChange: () -> Unit,
        isDeferred: () -> Boolean,
        markResumeFallbackPending: () -> Unit,
        syncCachedResultToForeground: () -> Unit
    ) {
        if (!isNativeOrSkipped()) {
            val boundsChanged = refreshWindowAndDidChange()
            if (boundsChanged) {
                reapplyForWindowChange()
            }
        }

        if (isDeferred()) {
            markResumeFallbackPending()
        }

        if (!isNativeOrSkipped() && !isDeferred()) {
            syncCachedResultToForeground()
        }
    }

    fun shouldSyncCachedResult(
        isNativeMode: Boolean,
        isSkipped: Boolean,
        isDeferred: Boolean,
        canSyncApplication: Boolean,
        lastAppliedStatus: AdaptStatus?,
        lastAppliedBounds: WindowBounds?,
        currentBounds: WindowBounds?,
        activityMatchesTarget: Boolean,
        applicationMatchesTarget: Boolean
    ): Boolean {
        if (isNativeMode || isSkipped || isDeferred) return false
        if (lastAppliedStatus != AdaptStatus.APPLIED) return false
        if (currentBounds == null || lastAppliedBounds != currentBounds) return false
        if (!canSyncApplication) return false
        return !activityMatchesTarget || !applicationMatchesTarget
    }
}
