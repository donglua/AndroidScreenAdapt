package cn.jingzhuan.adapt.autosize

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.jingzhuan.adapt.autosize.engine.ScreenAdaptEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowAndFontScaleInstrumentationTest {

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
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdaptEngine.resetForTesting()
        }
    }

    @Test
    fun TF01_wrapContextNeutralizesApi34PlusFontScaleConverter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdapt.init(app) {
                designSize(360f, 640f)
                excludeFontScale(true)
            }
        }

        val largeFontBase = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { fontScale = 2.0f }
        )
        val wrapped = ScreenAdapt.wrapContext(largeFontBase)
        assertEquals(1.0f, wrapped.resources.configuration.fontScale, 0f)
        // Must NOT alter densityDpi or uiMode (avoids resource qualifier bucket drift)
        assertEquals(largeFontBase.resources.configuration.densityDpi, wrapped.resources.configuration.densityDpi)
        assertEquals(largeFontBase.resources.configuration.uiMode, wrapped.resources.configuration.uiMode)

        val dm = wrapped.resources.displayMetrics
        dm.density = 3.0f
        dm.scaledDensity = 3.0f
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, dm)
        Log.i("ScreenAdaptTF", "API=${Build.VERSION.SDK_INT} wrapped 16sp px=$px (expected 48.0)")
        assertEquals(48.0f, px, 0.5f)
    }

    @Test
    fun TW01_screenMetricsConsistentWithoutInit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ScreenAdaptEngine.resetForTesting()
        }
        val windowSize = ScreenMetrics.getWindowSize(app)
        val realSize = ScreenMetrics.getRealDisplaySize(app)
        assertTrue(windowSize[0] > 0 && windowSize[1] > 0)
        assertTrue(realSize[0] >= windowSize[0] && realSize[1] >= windowSize[1])
    }
}
