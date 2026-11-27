package cn.jingzhuan.adapt.autosize

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.jingzhuan.adapt.autosize.core.CoreOrientation
import cn.jingzhuan.adapt.autosize.core.WindowBounds
import cn.jingzhuan.adapt.autosize.engine.ScreenAdaptEngine
import cn.jingzhuan.adapt.autosize.engine.WindowBoundsResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RuntimeInstrumentationTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdaptEngine.resetForTesting()
        }
    }

    @After
    fun tearDown() {
        currentActivityDensitySnapshotProvider = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdaptEngine.resetForTesting()
        }
    }

    @Test
    fun TR01_initIsIdempotentAndDoesNotDuplicateCallbacks() {
        val strategyCalls = AtomicInteger(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val config = adaptConfig {
                designSize(360f, 640f)
                strategy { _, _ ->
                    strategyCalls.incrementAndGet()
                    null
                }
            }
            ScreenAdapt.init(app, config)
            ScreenAdapt.init(app, config)
            assertTrue(ScreenAdapt.isInitialized())
        }

        ActivityScenario.launch(TestAdaptActivity::class.java).use {
            assertEquals(2, strategyCalls.get())
        }
    }

    @Test
    fun TR03_strategyPrecedenceAndDedup() {
        val events = mutableListOf<AdaptEvent>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
                customTarget(TestCustomAdaptActivity::class.java, AdaptDirective.Width(500f))
            }
        }

        ActivityScenario.launch(TestCustomAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val listener = AdaptEventListener { _, event -> events += event }
                ScreenAdapt.addListener(activity, listener)

                val info = ScreenAdapt.getWindowInfo(activity)!!
                assertEquals(info.widthPx / 500f, activity.resources.displayMetrics.density, 1e-4f)

                ScreenAdapt.reapply(activity)
                assertTrue(events.any { it.type == AdaptEvent.Type.SKIPPED && it.reason == "dedup_matched" })
                ScreenAdapt.removeListener(activity, listener)
            }
        }
    }

    @Test
    fun TR04_TR05_singleFieldDriftRestoresEntireManagedGroup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
                excludeFontScale(true)
            }
        }

        ActivityScenario.launch(TestAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dm = activity.resources.displayMetrics
                val targetDensity = dm.density
                val targetDpi = dm.densityDpi
                val targetScaled = dm.scaledDensity

                ScreenAdapt.ensureApplied(activity)
                assertEquals(targetDensity, dm.density, 0f)

                dm.scaledDensity = 9.99f
                dm.densityDpi = 120

                ScreenAdapt.ensureApplied(activity)
                assertEquals(targetDensity, dm.density, 0f)
                assertEquals(targetDpi, dm.densityDpi)
                assertEquals(targetScaled, dm.scaledDensity, 0f)
            }
        }
    }

    @Test
    fun TR06_foregroundActivitySyncsApplicationMetricsAndSkipsMultiWindow() {
        val initialAppDensity = app.resources.displayMetrics.density
        WindowBoundsResolver.resolveCurrentWindowOverrideForTesting = { _ ->
            WindowBounds(
                widthPx = 1080,
                heightPx = 1200,
                realWidthPx = 1080,
                realHeightPx = 2400,
                orientation = CoreOrientation.PORTRAIT,
                inMultiWindow = true
            ) to WindowInfo(
                widthPx = 1080,
                heightPx = 1200,
                realWidthPx = 1080,
                realHeightPx = 2400,
                statusBarInsetPx = 0,
                navigationBarInsetPx = 0,
                configurationOrientation = Configuration.ORIENTATION_PORTRAIT,
                requestedOrientation = -1,
                inMultiWindow = true,
                stable = true
            )
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
            }
        }

        ActivityScenario.launch(TestCustomAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                assertEquals(info.widthPx / 400f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(initialAppDensity, app.resources.displayMetrics.density, 1e-4f)
            }
        }

        WindowBoundsResolver.resolveCurrentWindowOverrideForTesting = { _ ->
            WindowBounds(
                widthPx = 1400,
                heightPx = 1600,
                realWidthPx = 2560,
                realHeightPx = 1600,
                orientation = CoreOrientation.PORTRAIT,
                inMultiWindow = false
            ) to WindowInfo(
                widthPx = 1400,
                heightPx = 1600,
                realWidthPx = 2560,
                realHeightPx = 1600,
                statusBarInsetPx = 0,
                navigationBarInsetPx = 0,
                configurationOrientation = Configuration.ORIENTATION_PORTRAIT,
                requestedOrientation = -1,
                inMultiWindow = false,
                stable = true
            )
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdaptEngine.resetForTesting()
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
            }
        }
        ActivityScenario.launch(TestCustomAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                assertEquals(info.widthPx / 400f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(initialAppDensity, app.resources.displayMetrics.density, 1e-4f)
            }
        }

        WindowBoundsResolver.resolveCurrentWindowOverrideForTesting = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdaptEngine.resetForTesting()
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
            }
        }
        ActivityScenario.launch(TestCustomAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                val expected = info.widthPx / 400f
                assertEquals(expected, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(expected, app.resources.displayMetrics.density, 1e-4f)
            }
        }
    }

    @Test
    fun TR07_ensureAppliedInGetResourcesIsReentrancySafe() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
            }
        }

        ActivityScenario.launch(TestAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.reentrantGuardOnGetResources = true
                val d = activity.resources.displayMetrics.density
                assertTrue(d > 0f)
                assertTrue(activity.reentrantCalls >= 1)
                activity.reentrantGuardOnGetResources = false
            }
        }
    }

    @Test
    fun TR08_applyNativeAndNoAdaptLeaveApplicationUntouched() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
            }
        }
        val nativeDensity = app.resources.displayMetrics.density

        ActivityScenario.launch(TestAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                val adapted = info.widthPx / 360f
                assertEquals(adapted, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(adapted, app.resources.displayMetrics.density, 1e-4f)
                assertEquals(nativeDensity, ScreenAdapt.getNativeDensity(activity), 1e-4f)
                assertEquals(nativeDensity, ScreenAdapt.getNativeDensity(app), 1e-4f)

                ScreenAdapt.applyNative(activity)
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(adapted, app.resources.displayMetrics.density, 1e-4f)
                assertEquals(nativeDensity, ScreenAdapt.getNativeDensity(activity), 1e-4f)
            }
        }
    }

    @Test
    fun TR13_noPollingThreadsAndResolveTargetMetricsDoesNotMutate() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
                excludeFontScale(true)
            }
        }

        val beforeThreads = Thread.getAllStackTraces().keys.map { it.name }.toSet()
        val standaloneCtx = app.createConfigurationContext(Configuration(app.resources.configuration))
        val beforeDensity = standaloneCtx.resources.displayMetrics.density
        val targetMetrics = ScreenAdapt.resolveTargetMetrics(standaloneCtx, AdaptDirective.Width(400f))
        assertNotNull(targetMetrics)
        assertEquals(beforeDensity, standaloneCtx.resources.displayMetrics.density, 0f)
        assertEquals(1.0f, targetMetrics!!.fontScale, 1e-5f)

        lateinit var activityRef: WeakReference<Activity>
        ActivityScenario.launch(TestAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activityRef = WeakReference(activity)
                val info = ScreenAdapt.getWindowInfo(activity)!!
                ScreenAdapt.apply(activity, AdaptDirective.ShortSide(720f, useRealDisplay = true))
                val shortSide = minOf(info.realWidthPx, info.realHeightPx)
                assertEquals(shortSide / 720f, activity.resources.displayMetrics.density, 1e-4f)
                assertNotEquals(0f, activity.resources.displayMetrics.scaledDensity)
            }
        }

        val newThreads = Thread.getAllStackTraces().keys.map { it.name }.toSet() - beforeThreads
        assertFalse(
            "Unexpected polling thread: $newThreads",
            newThreads.any { it.contains("Adapt", ignoreCase = true) || it.contains("Density", ignoreCase = true) }
        )
        assertNotNull(activityRef)
    }

    @Test
    fun TR_fragmentCustomDirectiveAppliesWhenEnabled() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
                customFragment(true)
            }
        }

        ActivityScenario.launch(TestFragmentHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                assertEquals(info.widthPx / 450f, activity.resources.displayMetrics.density, 1e-4f)
            }
        }
    }

    @Test
    fun TR14_onActivityResumedRefreshesWindowBeforeSyncingAndAvoidsTransientHighDensity() {
        val landscapeBounds = WindowBounds(
            widthPx = 2400,
            heightPx = 1080,
            realWidthPx = 2400,
            realHeightPx = 1080,
            orientation = CoreOrientation.LANDSCAPE
        )
        val portraitBounds = WindowBounds(
            widthPx = 1080,
            heightPx = 2400,
            realWidthPx = 1080,
            realHeightPx = 2400,
            orientation = CoreOrientation.PORTRAIT
        )
        var currentBounds = landscapeBounds
        val densitiesObservedAtRefresh = mutableListOf<Pair<Float, Float>>()

        WindowBoundsResolver.resolveCurrentWindowOverrideForTesting = { _ ->
            densitiesObservedAtRefresh += app.resources.displayMetrics.density to
                (currentActivityDensitySnapshot ?: -1f)
            currentBounds to WindowInfo(
                widthPx = currentBounds.widthPx,
                heightPx = currentBounds.heightPx,
                realWidthPx = currentBounds.realWidthPx,
                realHeightPx = currentBounds.realHeightPx,
                statusBarInsetPx = 0,
                navigationBarInsetPx = 0,
                configurationOrientation = if (currentBounds.orientation == CoreOrientation.LANDSCAPE) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                },
                requestedOrientation = -1,
                inMultiWindow = false,
                stable = true
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(400f, 640f)
            }
        }

        ActivityScenario.launch(TestPortraitConfigActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                currentActivityDensitySnapshotProvider = { activity.resources.displayMetrics.density }
                ScreenAdapt.reapply(activity)
                assertEquals(2400f / 400f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(2400f / 400f, app.resources.displayMetrics.density, 1e-4f)
            }

            // Pause activity, simulate configChanges landscape -> portrait reset before onResume
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity { activity ->
                densitiesObservedAtRefresh.clear()
                currentBounds = portraitBounds
                activity.resources.displayMetrics.density = 3.0f
                app.resources.displayMetrics.density = 3.0f
            }

            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(densitiesObservedAtRefresh.isNotEmpty())
                // During refreshWindow inside onActivityResumed, stale 6.0f must NOT have been written
                assertTrue(
                    "Transient high density leaked before window refresh: $densitiesObservedAtRefresh",
                    densitiesObservedAtRefresh.none { (appD, actD) -> appD > 4.0f || actD > 4.0f }
                )
                assertEquals(1080f / 400f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(1080f / 400f, app.resources.displayMetrics.density, 1e-4f)
            }
        }
    }

    @Test
    fun TR15_onActivityResumedDoesNotWriteStaleResultWhenDeferredAndRestoresForegroundSync() {
        val landscapeBounds = WindowBounds(
            widthPx = 2400,
            heightPx = 1080,
            realWidthPx = 2400,
            realHeightPx = 1080,
            orientation = CoreOrientation.LANDSCAPE
        )
        val conflictingPortraitBounds = WindowBounds(
            widthPx = 2400,
            heightPx = 1080,
            realWidthPx = 2400,
            realHeightPx = 1080,
            orientation = CoreOrientation.PORTRAIT
        )
        val portraitBounds = WindowBounds(
            widthPx = 1080,
            heightPx = 2400,
            realWidthPx = 1080,
            realHeightPx = 2400,
            orientation = CoreOrientation.PORTRAIT
        )
        var currentBounds = landscapeBounds

        WindowBoundsResolver.resolveCurrentWindowOverrideForTesting = { _ ->
            currentBounds to WindowInfo(
                widthPx = currentBounds.widthPx,
                heightPx = currentBounds.heightPx,
                realWidthPx = currentBounds.realWidthPx,
                realHeightPx = currentBounds.realHeightPx,
                statusBarInsetPx = 0,
                navigationBarInsetPx = 0,
                configurationOrientation = if (currentBounds.orientation == CoreOrientation.LANDSCAPE) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                },
                requestedOrientation = -1,
                inMultiWindow = false,
                stable = currentBounds != conflictingPortraitBounds
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(400f, 640f)
            }
        }

        val events = mutableListOf<AdaptEvent>()
        ActivityScenario.launch(TestPortraitConfigActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ScreenAdapt.addListener(activity) { _, event -> events += event }
                ScreenAdapt.reapply(activity)
                assertEquals(2400f / 400f, activity.resources.displayMetrics.density, 1e-4f)
            }

            // Transition to portrait with transiently conflicting landscape bounds -> DEFERRED
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity { activity ->
                events.clear()
                currentBounds = conflictingPortraitBounds
                activity.resources.displayMetrics.density = 3.0f
                app.resources.displayMetrics.density = 3.0f
            }

            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(events.any { it.type == AdaptEvent.Type.DEFERRED })
                assertEquals(3.0f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(3.0f, app.resources.displayMetrics.density, 1e-4f)

                // Resolve bounds and dispatch layout -> applies portrait 2.7f
                currentBounds = portraitBounds
                activity.window.decorView.viewTreeObserver.dispatchOnGlobalLayout()
                assertEquals(1080f / 400f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(1080f / 400f, app.resources.displayMetrics.density, 1e-4f)
            }

            // Simulate returning from another Activity that mutated Application density while paused
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity {
                app.resources.displayMetrics.density = 2.0f
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(1080f / 400f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(1080f / 400f, app.resources.displayMetrics.density, 1e-4f)
            }
        }
    }

    @Test
    fun TR16_nativeConfigChangesUpdatesFontScaleDensityAndRotationWithoutRecreating() {
        val portraitBounds = WindowBounds(
            widthPx = 1080,
            heightPx = 2400,
            realWidthPx = 1080,
            realHeightPx = 2400,
            orientation = CoreOrientation.PORTRAIT
        )
        val landscapeBounds = WindowBounds(
            widthPx = 2400,
            heightPx = 1080,
            realWidthPx = 2400,
            realHeightPx = 1080,
            orientation = CoreOrientation.LANDSCAPE
        )
        var currentBounds = portraitBounds
        WindowBoundsResolver.resolveCurrentWindowOverrideForTesting = { _ ->
            currentBounds to WindowInfo(
                widthPx = currentBounds.widthPx,
                heightPx = currentBounds.heightPx,
                realWidthPx = currentBounds.realWidthPx,
                realHeightPx = currentBounds.realHeightPx,
                statusBarInsetPx = 0,
                navigationBarInsetPx = 0,
                configurationOrientation = if (currentBounds.orientation == CoreOrientation.LANDSCAPE) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                },
                requestedOrientation = -1,
                inMultiWindow = false,
                stable = true
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
                excludeFontScale(false)
                supportScreenSizeDp(true)
            }
        }

        ActivityScenario.launch(TestNativeConfigChangesActivity::class.java).use { scenario ->
            lateinit var initialActivity: Activity
            var initialDensity = 0f
            scenario.onActivity { activity ->
                initialActivity = activity
                initialDensity = activity.resources.displayMetrics.density
            }

            // 1. fontScale change without recreating Activity: reapply / ensureApplied must not write back stale scaledDensity
            scenario.onActivity { activity ->
                assertTrue(activity === initialActivity)
                activity.resources.configuration.fontScale = 1.5f
                activity.resources.displayMetrics.scaledDensity = initialDensity * 1.5f

                // Drift scaledDensity and verify ensureApplied restores new 1.5x scaledDensity, not old 1.0x
                activity.resources.displayMetrics.scaledDensity = 9.9f
                assertEquals(initialDensity * 1.5f, activity.resources.displayMetrics.scaledDensity, 1e-4f)

                ScreenAdapt.reapply(activity)
                assertEquals(initialDensity * 1.5f, activity.resources.displayMetrics.scaledDensity, 1e-4f)
            }

            // 2. densityDpi/density change without recreating Activity
            scenario.onActivity { activity ->
                assertTrue(activity === initialActivity)
                activity.resources.configuration.densityDpi = 320
                activity.resources.displayMetrics.density = 2.0f
                activity.resources.displayMetrics.densityDpi = 320
                activity.resources.displayMetrics.scaledDensity = 2.0f * 1.5f

                // Drift density and verify ensureApplied + reapply keep new 2.0f / 320dpi
                activity.resources.displayMetrics.density = 6.0f
                activity.resources.displayMetrics.densityDpi = 960
                assertEquals(2.0f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(320, activity.resources.displayMetrics.densityDpi)
                assertEquals(3.0f, activity.resources.displayMetrics.scaledDensity, 1e-4f)

                ScreenAdapt.reapply(activity)
                assertEquals(2.0f, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(320, activity.resources.displayMetrics.densityDpi)
                assertEquals(3.0f, activity.resources.displayMetrics.scaledDensity, 1e-4f)
            }

            // 3. Rotation without recreating Activity while supportScreenSizeDp=true: must not restore old portrait screenWidthDp/screenHeightDp
            scenario.onActivity { activity ->
                assertTrue(activity === initialActivity)
                currentBounds = landscapeBounds
                activity.resources.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
                activity.resources.configuration.screenWidthDp = 780
                activity.resources.configuration.screenHeightDp = 360

                activity.window.decorView.viewTreeObserver.dispatchOnGlobalLayout()
                ScreenAdapt.reapply(activity)

                // Drift screenSizeDp and verify ensureApplied restores rotated 780x360
                activity.resources.configuration.screenWidthDp = 111
                activity.resources.configuration.screenHeightDp = 222
                ScreenAdapt.ensureApplied(activity)

                assertTrue(activity === initialActivity)
                assertEquals(780, activity.resources.configuration.screenWidthDp)
                assertEquals(360, activity.resources.configuration.screenHeightDp)

                val resolved = ScreenAdapt.resolveTargetMetrics(activity, AdaptDirective.Native)!!
                assertEquals(2.0f, resolved.density, 1e-4f)
                assertEquals(2.0f, ScreenAdapt.getNativeDensity(activity), 1e-4f)
                assertEquals(3.0f, resolved.scaledDensity, 1e-4f)
                assertEquals(780, resolved.screenWidthDp)
                assertEquals(360, resolved.screenHeightDp)
            }
        }
    }

    @Test
    fun TR17_explicitApplyAndApplyNativeSurviveStopAndStartLifecycle() {
        val nativeDensity = app.resources.displayMetrics.density
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
            }
        }

        ActivityScenario.launch(TestAdaptActivity::class.java).use { scenario ->
            var expectedExplicit = 0f
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                expectedExplicit = info.widthPx / 500f
                ScreenAdapt.apply(activity, AdaptDirective.Width(500f))
                assertEquals(expectedExplicit, activity.resources.displayMetrics.density, 1e-4f)
            }

            // Stopping and restarting must preserve explicit ScreenAdapt.apply directive
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(expectedExplicit, activity.resources.displayMetrics.density, 1e-4f)
                ScreenAdapt.applyNative(activity)
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-3f)
            }

            // Stopping and restarting must also preserve explicit ScreenAdapt.applyNative
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-3f)
            }
        }
    }

    @Test
    fun TR18_ensureAppliedRefreshesWindowOnConfigChangesWithoutStaleHighDensity() {
        val landscapeBounds = WindowBounds(
            widthPx = 2400,
            heightPx = 1080,
            realWidthPx = 2400,
            realHeightPx = 1080,
            orientation = CoreOrientation.LANDSCAPE
        )
        val portraitBounds = WindowBounds(
            widthPx = 1080,
            heightPx = 2400,
            realWidthPx = 1080,
            realHeightPx = 2400,
            orientation = CoreOrientation.PORTRAIT
        )
        var currentBounds = landscapeBounds
        WindowBoundsResolver.resolveCurrentWindowOverrideForTesting = { _ ->
            currentBounds to WindowInfo(
                widthPx = currentBounds.widthPx,
                heightPx = currentBounds.heightPx,
                realWidthPx = currentBounds.realWidthPx,
                realHeightPx = currentBounds.realHeightPx,
                statusBarInsetPx = 0,
                navigationBarInsetPx = 0,
                configurationOrientation = if (currentBounds.orientation == CoreOrientation.LANDSCAPE) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                },
                requestedOrientation = -1,
                inMultiWindow = false,
                stable = true
            )
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(400f, 640f)
            }
        }

        ActivityScenario.launch(TestAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(2400f / 400f, activity.resources.displayMetrics.density, 1e-4f)

                // Simulate configChanges rotation while staying RESUMED (no onResume, no OnGlobalLayout yet):
                // Android resets DisplayMetrics to native 3.0f and updates configuration before onMeasure.
                currentBounds = portraitBounds
                activity.resources.configuration.orientation = Configuration.ORIENTATION_PORTRAIT
                activity.resources.displayMetrics.density = 3.0f

                // Calling ensureApplied inside getResources() must refresh window and apply 2.7f, NOT stale 6.0f.
                ScreenAdapt.ensureApplied(activity)
                assertEquals(1080f / 400f, activity.resources.displayMetrics.density, 1e-4f)
            }
        }
    }

    @Test
    fun TR19_fragmentFallbackPreservesNoAdaptHostAndDialogDismissRestoresHost() {
        val nativeDensity = app.resources.displayMetrics.density
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
                customFragment(true)
            }
        }

        // 1. Plain fragment & headless fragment inside NoAdapt host must NOT overwrite host to 360dp
        ActivityScenario.launch(TestNoAdaptFragmentHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-3f)

                activity.supportFragmentManager.beginTransaction()
                    .add(TestPlainFragment(), "headless")
                    .commitNow()
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-3f)

                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, TestPlainFragment(), "content")
                    .commitNow()
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-3f)
            }
        }

        // 2. NoAdapt DialogFragment switches host to native, and destroying/dismissing it restores host density
        ActivityScenario.launch(TestFragmentHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                val hostDefaultDensity = info.widthPx / 360f

                // Replace custom fragment with plain fragment so host is at 360dp
                activity.supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, TestPlainFragment(), "plain")
                    .commitNow()
                assertEquals(hostDefaultDensity, activity.resources.displayMetrics.density, 1e-4f)

                val dialog = TestNoAdaptDialogFragment()
                dialog.showNow(activity.supportFragmentManager, "no_adapt_dialog")
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-3f)

                dialog.dismiss()
                activity.supportFragmentManager.executePendingTransactions()
                assertEquals(hostDefaultDensity, activity.resources.displayMetrics.density, 1e-4f)
            }
        }
    }

    private var currentActivityDensitySnapshotProvider: (() -> Float)? = null
    private val currentActivityDensitySnapshot: Float?
        get() = currentActivityDensitySnapshotProvider?.invoke()
}
