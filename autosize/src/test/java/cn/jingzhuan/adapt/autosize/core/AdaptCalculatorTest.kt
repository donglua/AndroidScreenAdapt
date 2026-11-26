package cn.jingzhuan.adapt.autosize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests covering AdaptCalculator formulas, edge cases, deduplication, and deferral.
 */
class AdaptCalculatorTest {

    private val defaultNative = MetricsSnapshot(
        density = 3.0f,
        densityDpi = 480,
        scaledDensity = 3.3f,
        screenWidthDp = 360,
        screenHeightDp = 780
    )

    private fun baseSpec(
        basis: CoreAdaptBasis = CoreAdaptBasis.WIDTH,
        designWidthDp: Float = 360f,
        designHeightDp: Float = 640f,
        effectiveDesignSizeDp: Float = 360f,
        supportDp: Boolean = true,
        supportSp: Boolean = true,
        supportScreenSizeDp: Boolean = false,
        excludeFontScale: Boolean = false,
        privateFontScale: Float = 0f,
        useDeviceSize: Boolean = true,
        manualStatusBarHeightPx: Int = 0,
        useRealDisplayShortSide: Boolean = true
    ) = AdaptSpec(
        basis = basis,
        designWidthDp = designWidthDp,
        designHeightDp = designHeightDp,
        effectiveDesignSizeDp = effectiveDesignSizeDp,
        supportDp = supportDp,
        supportSp = supportSp,
        supportScreenSizeDp = supportScreenSizeDp,
        excludeFontScale = excludeFontScale,
        privateFontScale = privateFontScale,
        useDeviceSize = useDeviceSize,
        manualStatusBarHeightPx = manualStatusBarHeightPx,
        useRealDisplayShortSide = useRealDisplayShortSide
    )

    @Test
    fun `TC-01 computes width height and short side targets accurately`() {
        val bounds = WindowBounds(widthPx = 1080, heightPx = 2400)

        val widthRes = AdaptCalculator.calculate(
            AdaptInput(baseSpec(basis = CoreAdaptBasis.WIDTH, designWidthDp = 360f), bounds, defaultNative)
        )
        assertEquals(AdaptStatus.APPLIED, widthRes.status)
        val widthTarget = widthRes.target!!
        assertEquals(3.0f, widthTarget.density, 1e-6f)
        assertEquals(480, widthTarget.densityDpi)
        assertEquals(defaultNative.screenWidthDp, widthTarget.screenWidthDp)
        assertEquals(defaultNative.screenHeightDp, widthTarget.screenHeightDp)
        assertFalse(widthRes.applyScreenSizeDp)

        val heightRes = AdaptCalculator.calculate(
            AdaptInput(
                baseSpec(basis = CoreAdaptBasis.HEIGHT, designHeightDp = 640f, supportScreenSizeDp = true),
                bounds,
                defaultNative
            )
        )
        assertEquals(AdaptStatus.APPLIED, heightRes.status)
        val expectedHeightDensity = 2400f / 640f
        val heightTarget = heightRes.target!!
        assertEquals(expectedHeightDensity, heightTarget.density, 1e-6f)
        assertEquals((expectedHeightDensity * 160).toInt(), heightTarget.densityDpi)
        assertEquals((1080 / expectedHeightDensity).toInt(), heightTarget.screenWidthDp)
        assertEquals((2400 / expectedHeightDensity).toInt(), heightTarget.screenHeightDp)
        assertTrue(heightRes.applyScreenSizeDp)

        val landscapeBounds = WindowBounds(
            widthPx = 2340,
            heightPx = 1017,
            realWidthPx = 2400,
            realHeightPx = 1080,
            inMultiWindow = false
        )
        val shortSideRes = AdaptCalculator.calculate(
            AdaptInput(
                baseSpec(basis = CoreAdaptBasis.SHORT_SIDE, effectiveDesignSizeDp = 720f, useRealDisplayShortSide = true),
                landscapeBounds,
                defaultNative
            )
        )
        assertEquals(1080f / 720f, shortSideRes.target!!.density, 1e-6f)

        val multiWindowRes = AdaptCalculator.calculate(
            AdaptInput(
                baseSpec(basis = CoreAdaptBasis.SHORT_SIDE, effectiveDesignSizeDp = 360f, useRealDisplayShortSide = true),
                landscapeBounds.copy(inMultiWindow = true),
                defaultNative
            )
        )
        assertEquals(1017f / 360f, multiWindowRes.target!!.density, 1e-6f)

        val freeformBounds = WindowBounds(widthPx = 800, heightPx = 700, realWidthPx = 2400, realHeightPx = 1080)
        val freeformRes = AdaptCalculator.calculate(
            AdaptInput(
                baseSpec(basis = CoreAdaptBasis.SHORT_SIDE, effectiveDesignSizeDp = 360f, useRealDisplayShortSide = true),
                freeformBounds,
                defaultNative
            )
        )
        assertEquals(700f / 360f, freeformRes.target!!.density, 1e-6f)
    }

    @Test
    fun `TC-04 computes scaledDensity and fontScale across private exclude and system branches`() {
        val bounds = WindowBounds(widthPx = 1080, heightPx = 2400)

        val privateRes = AdaptCalculator.calculate(
            AdaptInput(baseSpec(excludeFontScale = true, privateFontScale = 1.25f), bounds, defaultNative)
        )
        assertEquals(1.25f, privateRes.fontScale, 1e-6f)
        assertEquals(3.0f * 1.25f, privateRes.target!!.scaledDensity, 1e-6f)

        val excludeRes = AdaptCalculator.calculate(
            AdaptInput(baseSpec(excludeFontScale = true, privateFontScale = 0f), bounds, defaultNative)
        )
        assertEquals(1.0f, excludeRes.fontScale, 1e-6f)
        assertEquals(3.0f, excludeRes.target!!.scaledDensity, 1e-6f)

        val systemRes = AdaptCalculator.calculate(
            AdaptInput(baseSpec(excludeFontScale = false, privateFontScale = 0f), bounds, defaultNative)
        )
        assertEquals(1.1f, systemRes.fontScale, 1e-6f)
        assertEquals(3.3f, systemRes.target!!.scaledDensity, 1e-6f)
    }

    @Test
    fun `TC-05 invalid inputs return INVALID cleanly`() {
        val validBounds = WindowBounds(widthPx = 1080, heightPx = 2400)

        val invalidCases = listOf(
            AdaptInput(baseSpec(designWidthDp = 0f), validBounds, defaultNative),
            AdaptInput(baseSpec(designWidthDp = -360f), validBounds, defaultNative),
            AdaptInput(baseSpec(designWidthDp = Float.NaN), validBounds, defaultNative),
            AdaptInput(baseSpec(designWidthDp = Float.POSITIVE_INFINITY), validBounds, defaultNative),
            AdaptInput(baseSpec(basis = CoreAdaptBasis.HEIGHT, designHeightDp = 0f), validBounds, defaultNative),
            AdaptInput(baseSpec(basis = CoreAdaptBasis.SHORT_SIDE, effectiveDesignSizeDp = -1f), validBounds, defaultNative),
            AdaptInput(baseSpec(privateFontScale = Float.NaN), validBounds, defaultNative),
            AdaptInput(baseSpec(privateFontScale = -0.5f), validBounds, defaultNative),
            AdaptInput(baseSpec(), WindowBounds(widthPx = 0, heightPx = 2400), defaultNative),
            AdaptInput(baseSpec(), WindowBounds(widthPx = 1080, heightPx = -10), defaultNative),
            AdaptInput(baseSpec(), validBounds, defaultNative.copy(density = 0f)),
            AdaptInput(baseSpec(), validBounds, defaultNative.copy(density = Float.NaN)),
            AdaptInput(baseSpec(), validBounds, defaultNative.copy(scaledDensity = 0f))
        )

        invalidCases.forEach { input ->
            val result = AdaptCalculator.calculate(input)
            assertEquals("Expected INVALID for $input", AdaptStatus.INVALID, result.status)
            assertNull(result.target)
        }
    }

    @Test
    fun `TC-06 useDeviceSize and manual statusBarHeight precedence`() {
        val bounds = WindowBounds(widthPx = 1080, heightPx = 2400, statusBarInsetPx = 96)

        val deviceRes = AdaptCalculator.calculate(
            AdaptInput(
                baseSpec(basis = CoreAdaptBasis.HEIGHT, designHeightDp = 640f, useDeviceSize = true, manualStatusBarHeightPx = 120),
                bounds,
                defaultNative
            )
        )
        assertEquals(2400, deviceRes.availableHeightPx)
        assertEquals(2400f / 640f, deviceRes.target!!.density, 1e-6f)

        val insetRes = AdaptCalculator.calculate(
            AdaptInput(
                baseSpec(basis = CoreAdaptBasis.HEIGHT, designHeightDp = 640f, useDeviceSize = false, manualStatusBarHeightPx = 0),
                bounds,
                defaultNative
            )
        )
        assertEquals(2400 - 96, insetRes.availableHeightPx)
        assertEquals((2400 - 96) / 640f, insetRes.target!!.density, 1e-6f)

        val manualRes = AdaptCalculator.calculate(
            AdaptInput(
                baseSpec(basis = CoreAdaptBasis.HEIGHT, designHeightDp = 640f, useDeviceSize = false, manualStatusBarHeightPx = 120),
                bounds,
                defaultNative
            )
        )
        assertEquals(2400 - 120, manualRes.availableHeightPx)
        assertEquals((2400 - 120) / 640f, manualRes.target!!.density, 1e-6f)
    }

    @Test
    fun `TC-07 golden baseline matches expected density outputs`() {
        data class GoldenRow(
            val name: String,
            val input: AdaptInput,
            val expectedDensity: Float,
            val expectedDensityDpi: Int,
            val expectedScaledDensity: Float,
            val expectedScreenWidthDp: Int,
            val expectedScreenHeightDp: Int
        )

        val rows = listOf(
            GoldenRow(
                name = "1080x2400_360w_default",
                input = AdaptInput(
                    baseSpec(basis = CoreAdaptBasis.WIDTH, designWidthDp = 360f, supportScreenSizeDp = true),
                    WindowBounds(1080, 2400),
                    MetricsSnapshot(3.0f, 480, 3.0f)
                ),
                expectedDensity = 3.0f,
                expectedDensityDpi = 480,
                expectedScaledDensity = 3.0f,
                expectedScreenWidthDp = 360,
                expectedScreenHeightDp = 800
            ),
            GoldenRow(
                name = "1080x2340_360w_sysFont1_15",
                input = AdaptInput(
                    baseSpec(basis = CoreAdaptBasis.WIDTH, designWidthDp = 360f, supportScreenSizeDp = true),
                    WindowBounds(1080, 2340),
                    MetricsSnapshot(3.0f, 480, 3.45f)
                ),
                expectedDensity = 3.0f,
                expectedDensityDpi = 480,
                expectedScaledDensity = 3.0f * (3.45f / 3.0f),
                expectedScreenWidthDp = 360,
                expectedScreenHeightDp = 780
            ),
            GoldenRow(
                name = "2200x2480_720w_private1_2",
                input = AdaptInput(
                    baseSpec(basis = CoreAdaptBasis.WIDTH, designWidthDp = 720f, privateFontScale = 1.2f, supportScreenSizeDp = true),
                    WindowBounds(2200, 2480),
                    MetricsSnapshot(3.0f, 480, 3.0f)
                ),
                expectedDensity = 2200f / 720f,
                expectedDensityDpi = ((2200f / 720f) * 160).toInt(),
                expectedScaledDensity = (2200f / 720f) * 1.2f,
                expectedScreenWidthDp = (2200 / (2200f / 720f)).toInt(),
                expectedScreenHeightDp = (2480 / (2200f / 720f)).toInt()
            ),
            GoldenRow(
                name = "1080x2400_640h_noDeviceSize",
                input = AdaptInput(
                    baseSpec(basis = CoreAdaptBasis.HEIGHT, designHeightDp = 640f, useDeviceSize = false, supportScreenSizeDp = true),
                    WindowBounds(1080, 2400, statusBarInsetPx = 80),
                    MetricsSnapshot(3.0f, 480, 3.0f)
                ),
                expectedDensity = 2320f / 640f,
                expectedDensityDpi = ((2320f / 640f) * 160).toInt(),
                expectedScaledDensity = 2320f / 640f,
                expectedScreenWidthDp = (1080 / (2320f / 640f)).toInt(),
                expectedScreenHeightDp = (2320 / (2320f / 640f)).toInt()
            )
        )

        rows.forEach { row ->
            val result = AdaptCalculator.calculate(row.input)
            assertEquals("Failed status for ${row.name}", AdaptStatus.APPLIED, result.status)
            val t = result.target
            assertNotNull(t)
            assertEquals("${row.name} density", row.expectedDensity, t!!.density, 0f)
            assertEquals("${row.name} densityDpi", row.expectedDensityDpi, t.densityDpi)
            assertEquals("${row.name} scaledDensity", row.expectedScaledDensity, t.scaledDensity, 0f)
            assertEquals("${row.name} screenWidthDp", row.expectedScreenWidthDp, t.screenWidthDp)
            assertEquals("${row.name} screenHeightDp", row.expectedScreenHeightDp, t.screenHeightDp)
        }
    }

    @Test
    fun `TC-08 adaptInput detects any single field or IEEE bit difference`() {
        val base = AdaptInput(
            spec = baseSpec(designWidthDp = 360f, privateFontScale = 0.0f),
            bounds = WindowBounds(widthPx = 1080, heightPx = 2400),
            nativeSnapshot = defaultNative
        )

        assertTrue(base.bitsEquals(base.copy()))
        assertFalse(base.bitsEquals(null))
        assertFalse(base.bitsEquals(base.copy(spec = base.spec.copy(privateFontScale = -0.0f))))
        assertFalse(base.bitsEquals(base.copy(spec = base.spec.copy(designWidthDp = 360.0001f))))
        assertFalse(base.bitsEquals(base.copy(bounds = base.bounds.copy(heightPx = 2401))))
        assertFalse(base.bitsEquals(base.copy(nativeSnapshot = defaultNative.copy(scaledDensity = 3.3001f))))
        assertFalse(base.bitsEquals(base.copy(forceApplyOnConflict = true)))
    }

    @Test
    fun `W3 and W5 orientation conflict defers in single window and falls back when forced`() {
        assertEquals(CoreOrientation.PORTRAIT, AdaptCalculator.resolveExpectedOrientation(1, 2))
        assertEquals(CoreOrientation.LANDSCAPE, AdaptCalculator.resolveExpectedOrientation(6, 1))
        assertEquals(CoreOrientation.PORTRAIT, AdaptCalculator.resolveExpectedOrientation(-1, 1))

        val portraitWithLandscapeBounds = WindowBounds(
            widthPx = 2400,
            heightPx = 1080,
            inMultiWindow = false,
            orientation = CoreOrientation.PORTRAIT
        )
        val deferred = AdaptCalculator.calculate(
            AdaptInput(baseSpec(), portraitWithLandscapeBounds, defaultNative)
        )
        assertEquals(AdaptStatus.DEFERRED, deferred.status)
        assertEquals("orientation_conflict", deferred.reason)
        assertNull(deferred.target)

        val multiWindow = AdaptCalculator.calculate(
            AdaptInput(baseSpec(), portraitWithLandscapeBounds.copy(inMultiWindow = true), defaultNative)
        )
        assertEquals(AdaptStatus.APPLIED, multiWindow.status)
        assertEquals(2400f / 360f, multiWindow.target!!.density, 1e-6f)

        val fallback = AdaptCalculator.calculate(
            AdaptInput(baseSpec(), portraitWithLandscapeBounds, defaultNative, forceApplyOnConflict = true)
        )
        assertEquals(AdaptStatus.APPLIED, fallback.status)
        assertEquals("deferred_fallback", fallback.reason)
        assertEquals(1080f / 360f, fallback.target!!.density, 1e-6f)
    }
}
