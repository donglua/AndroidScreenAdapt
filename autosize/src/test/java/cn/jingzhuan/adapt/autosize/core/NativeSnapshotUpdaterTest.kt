package cn.jingzhuan.adapt.autosize.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests verifying NativeSnapshotUpdater behavior on fontScale, densityDpi,
 * rotation screenSizeDp changes, and self-applied screenSizeDp pollution guards.
 */
class NativeSnapshotUpdaterTest {

    private val initialBaseline = MetricsSnapshot(
        density = 3.0f,
        densityDpi = 480,
        scaledDensity = 3.0f,
        screenWidthDp = 360,
        screenHeightDp = 780
    )

    @Test
    fun `updates scaledDensity, densityDpi, and rotated screenSizeDp on configuration changes`() {
        val afterFontScale = NativeSnapshotUpdater.updateFromConfiguration(
            previous = initialBaseline,
            appBaseline = initialBaseline,
            configDensityDpi = 480,
            configFontScale = 1.5f,
            configScreenWidthDp = 360,
            configScreenHeightDp = 780
        )
        assertEquals(3.0f, afterFontScale.density, 0f)
        assertEquals(480, afterFontScale.densityDpi)
        assertEquals(4.5f, afterFontScale.scaledDensity, 1e-6f)
        assertEquals(360, afterFontScale.screenWidthDp)
        assertEquals(780, afterFontScale.screenHeightDp)

        val afterDensityChange = NativeSnapshotUpdater.updateFromConfiguration(
            previous = afterFontScale,
            appBaseline = initialBaseline,
            configDensityDpi = 320,
            configFontScale = 1.5f,
            configScreenWidthDp = 540,
            configScreenHeightDp = 1170
        )
        assertEquals(2.0f, afterDensityChange.density, 0f)
        assertEquals(320, afterDensityChange.densityDpi)
        assertEquals(3.0f, afterDensityChange.scaledDensity, 1e-6f)
        assertEquals(540, afterDensityChange.screenWidthDp)
        assertEquals(1170, afterDensityChange.screenHeightDp)

        val afterRotation = NativeSnapshotUpdater.updateFromConfiguration(
            previous = afterDensityChange,
            appBaseline = initialBaseline,
            configDensityDpi = 320,
            configFontScale = 1.5f,
            configScreenWidthDp = 1170,
            configScreenHeightDp = 540
        )
        assertEquals(2.0f, afterRotation.density, 0f)
        assertEquals(320, afterRotation.densityDpi)
        assertEquals(3.0f, afterRotation.scaledDensity, 1e-6f)
        assertEquals(1170, afterRotation.screenWidthDp)
        assertEquals(540, afterRotation.screenHeightDp)
    }

    @Test
    fun `preserves previous native screenSizeDp when configuration holds self-applied screenSizeDp`() {
        val nativeBeforeAdapt = MetricsSnapshot(
            density = 2.625f,
            densityDpi = 420,
            scaledDensity = 2.625f,
            screenWidthDp = 411,
            screenHeightDp = 914
        )
        val adaptedResult = MetricsResult(
            status = AdaptStatus.APPLIED,
            target = MetricsSnapshot(
                density = 3.0f,
                densityDpi = 480,
                scaledDensity = 3.0f,
                screenWidthDp = 360,
                screenHeightDp = 800
            ),
            applyDp = true,
            applySp = true,
            applyScreenSizeDp = true
        )

        val restoredNative = NativeSnapshotUpdater.updateFromConfiguration(
            previous = nativeBeforeAdapt,
            appBaseline = nativeBeforeAdapt,
            configDensityDpi = 420,
            configFontScale = 1.0f,
            configScreenWidthDp = 360,
            configScreenHeightDp = 800,
            lastAppliedResult = adaptedResult
        )
        assertEquals(411, restoredNative.screenWidthDp)
        assertEquals(914, restoredNative.screenHeightDp)

        // If Android later rotates and updates configuration to genuine new values, pick them up.
        val rotatedAfterAdapt = NativeSnapshotUpdater.updateFromConfiguration(
            previous = nativeBeforeAdapt,
            appBaseline = nativeBeforeAdapt,
            configDensityDpi = 420,
            configFontScale = 1.0f,
            configScreenWidthDp = 914,
            configScreenHeightDp = 411,
            lastAppliedResult = adaptedResult
        )
        assertEquals(914, rotatedAfterAdapt.screenWidthDp)
        assertEquals(411, rotatedAfterAdapt.screenHeightDp)
    }
}
