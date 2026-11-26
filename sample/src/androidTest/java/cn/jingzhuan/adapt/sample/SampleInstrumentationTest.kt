package cn.jingzhuan.adapt.sample

import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.jingzhuan.adapt.autosize.AdaptDirective
import cn.jingzhuan.adapt.autosize.ScreenAdapt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class SampleInstrumentationTest {

    private val app: SampleApplication
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun TS01_mainActivityAppliesDefaultWidthAndLocksFontScale() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)
                assertNotNull(info)
                val expected = info!!.widthPx / 360f
                assertEquals(expected, activity.resources.displayMetrics.density, 1e-4f)
                assertEquals(1.0f, activity.resources.configuration.fontScale, 1e-4f)
            }
        }
    }

    @Test
    fun TS01_fragmentCustomDirectiveAndSwitchWorks() {
        ActivityScenario.launch(AdaptFragmentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                assertEquals(info.widthPx / 450f, activity.resources.displayMetrics.density, 1e-4f)

                activity.showStandardFragment()
                assertEquals(info.widthPx / 360f, activity.resources.displayMetrics.density, 1e-4f)
            }
        }
    }

    @Test
    fun TS01_shortSideStrategyAndEventListenerWork() {
        ActivityScenario.launch(ShortSideActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = ScreenAdapt.getWindowInfo(activity)!!
                val baseShortPx = min(info.realWidthPx, info.realHeightPx)
                val expected = baseShortPx / 400f
                assertEquals(expected, activity.resources.displayMetrics.density, 1e-4f)
                assertTrue(activity.recordedEvents.isNotEmpty())
            }
        }
    }

    @Test
    fun TS01_nativeNoAdaptKeepsNativeDensity() {
        val nativeDensity = app.initialAppNativeDensity
        ActivityScenario.launch(NativeNoAdaptActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-3f)
            }
        }
    }

    @Test
    fun TS01_composeLocalDensityIsolatesFromDisplayMetrics() {
        val nativeDensity = app.initialAppNativeDensity
        val largeFontConfig = Configuration(app.resources.configuration).apply {
            fontScale = 2.0f
        }
        val largeFontCtx = app.createConfigurationContext(largeFontConfig)
        val wrapped = ScreenAdapt.wrapContext(largeFontCtx)
        assertEquals(1.0f, wrapped.resources.configuration.fontScale, 1e-4f)

        ActivityScenario.launch(ComposeLocalDensityActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val target = ScreenAdapt.resolveTargetMetrics(activity, AdaptDirective.Width(360f))
                assertNotNull(target)
                assertEquals(nativeDensity, activity.resources.displayMetrics.density, 1e-3f)
                assertEquals(target!!.density, activity.providedLocalDensity, 1e-4f)
                assertEquals(1.0f, activity.providedLocalFontScale, 1e-4f)
            }
        }
    }
}
