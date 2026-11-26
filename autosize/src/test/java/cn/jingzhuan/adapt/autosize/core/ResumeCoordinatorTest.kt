package cn.jingzhuan.adapt.autosize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests verifying onActivityResumed execution order and stale-cache sync guards.
 */
class ResumeCoordinatorTest {

    @Test
    fun `refreshes window and reapplies before any foreground sync`() {
        val trace = mutableListOf<String>()
        var currentDensity = 3.0f
        var currentBounds = WindowBounds(2400, 1080, orientation = CoreOrientation.LANDSCAPE)
        var lastAppliedBounds = currentBounds
        val cachedLandscapeDensity = 2400f / 360f
        var deferred = false

        ResumeCoordinator.executeResumeSequence(
            isNativeOrSkipped = { false },
            refreshWindowAndDidChange = {
                trace += "refresh(densityAtStart=$currentDensity)"
                val prev = currentBounds
                currentBounds = WindowBounds(1080, 2400, orientation = CoreOrientation.PORTRAIT)
                prev != currentBounds
            },
            reapplyForWindowChange = {
                currentDensity = currentBounds.widthPx / 360f
                lastAppliedBounds = currentBounds
                deferred = false
                trace += "reapply(density=$currentDensity)"
            },
            isDeferred = { deferred },
            markResumeFallbackPending = {
                trace += "markFallbackPending"
            },
            syncCachedResultToForeground = {
                val shouldSync = ResumeCoordinator.shouldSyncCachedResult(
                    isNativeMode = false,
                    isSkipped = false,
                    isDeferred = deferred,
                    canSyncApplication = true,
                    lastAppliedStatus = AdaptStatus.APPLIED,
                    lastAppliedBounds = lastAppliedBounds,
                    currentBounds = currentBounds,
                    activityMatchesTarget = true,
                    applicationMatchesTarget = true
                )
                if (shouldSync) {
                    currentDensity = cachedLandscapeDensity
                    trace += "syncCached"
                } else {
                    trace += "skipSync"
                }
            }
        )

        assertEquals(
            listOf("refresh(densityAtStart=3.0)", "reapply(density=3.0)", "skipSync"),
            trace
        )
        assertEquals(3.0f, currentDensity, 0f)
    }

    @Test
    fun `never syncs stale landscape result when resume encounters orientation conflict`() {
        val trace = mutableListOf<String>()
        val landscapeBounds = WindowBounds(2400, 1080, orientation = CoreOrientation.LANDSCAPE)
        var currentBounds = landscapeBounds
        var deferred = false

        ResumeCoordinator.executeResumeSequence(
            isNativeOrSkipped = { false },
            refreshWindowAndDidChange = {
                trace += "refresh"
                val prev = currentBounds
                currentBounds = WindowBounds(2400, 1080, orientation = CoreOrientation.PORTRAIT)
                prev != currentBounds
            },
            reapplyForWindowChange = {
                deferred = true
                trace += "reapply->deferred"
            },
            isDeferred = { deferred },
            markResumeFallbackPending = {
                trace += "markFallbackPending"
            },
            syncCachedResultToForeground = {
                trace += "syncCached"
            }
        )

        assertEquals(listOf("refresh", "reapply->deferred", "markFallbackPending"), trace)
    }

    @Test
    fun `never syncs stale cached result when switching to native on bounds change`() {
        val trace = mutableListOf<String>()
        var nativeMode = false
        var currentBounds = WindowBounds(2400, 1080, orientation = CoreOrientation.LANDSCAPE)

        ResumeCoordinator.executeResumeSequence(
            isNativeOrSkipped = { nativeMode },
            refreshWindowAndDidChange = {
                trace += "refresh"
                val prev = currentBounds
                currentBounds = WindowBounds(1080, 2400, orientation = CoreOrientation.PORTRAIT)
                prev != currentBounds
            },
            reapplyForWindowChange = {
                nativeMode = true
                trace += "reapply->native"
            },
            isDeferred = { false },
            markResumeFallbackPending = {
                trace += "markFallbackPending"
            },
            syncCachedResultToForeground = {
                trace += "syncCached"
            }
        )

        assertEquals(listOf("refresh", "reapply->native"), trace)
    }

    @Test
    fun `shouldSyncCachedResult guards against stale bounds and syncs drifted foreground app`() {
        val portrait = WindowBounds(1080, 2400, orientation = CoreOrientation.PORTRAIT)
        val landscape = WindowBounds(2400, 1080, orientation = CoreOrientation.LANDSCAPE)

        assertFalse(
            ResumeCoordinator.shouldSyncCachedResult(
                isNativeMode = false,
                isSkipped = false,
                isDeferred = false,
                canSyncApplication = true,
                lastAppliedStatus = AdaptStatus.APPLIED,
                lastAppliedBounds = landscape,
                currentBounds = portrait,
                activityMatchesTarget = false,
                applicationMatchesTarget = false
            )
        )

        assertFalse(
            ResumeCoordinator.shouldSyncCachedResult(
                isNativeMode = false,
                isSkipped = false,
                isDeferred = true,
                canSyncApplication = true,
                lastAppliedStatus = AdaptStatus.APPLIED,
                lastAppliedBounds = portrait,
                currentBounds = portrait,
                activityMatchesTarget = false,
                applicationMatchesTarget = false
            )
        )

        assertTrue(
            ResumeCoordinator.shouldSyncCachedResult(
                isNativeMode = false,
                isSkipped = false,
                isDeferred = false,
                canSyncApplication = true,
                lastAppliedStatus = AdaptStatus.APPLIED,
                lastAppliedBounds = portrait,
                currentBounds = portrait,
                activityMatchesTarget = true,
                applicationMatchesTarget = false
            )
        )

        assertFalse(
            ResumeCoordinator.shouldSyncCachedResult(
                isNativeMode = false,
                isSkipped = false,
                isDeferred = false,
                canSyncApplication = true,
                lastAppliedStatus = AdaptStatus.APPLIED,
                lastAppliedBounds = portrait,
                currentBounds = portrait,
                activityMatchesTarget = true,
                applicationMatchesTarget = true
            )
        )
    }
}
