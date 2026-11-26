package cn.jingzhuan.adapt.consumer

import android.app.Activity
import cn.jingzhuan.adapt.autosize.AdaptBasis
import cn.jingzhuan.adapt.autosize.AdaptConfig
import cn.jingzhuan.adapt.autosize.AdaptDirective
import cn.jingzhuan.adapt.autosize.adaptConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumerContractTest {
    @Test
    fun `default config values match modernized spec`() {
        val config = AdaptConfig()
        assertEquals(AdaptBasis.WIDTH, config.basis)
        assertTrue(config.useDeviceSize)
        assertTrue(config.customFragment)
        assertFalse(config.excludeFontScale)
        assertFalse(config.debugLog)
    }

    @Test
    fun `dsl builder populates cancel and custom targets cleanly`() {
        val cfg = adaptConfig {
            designSize(360f, 640f)
            excludeFontScale(true)
            cancelTarget(JavaConsumerSmoke::class.java)
            customTarget(Activity::class.java, AdaptDirective.ShortSide(720f, useRealDisplay = true))
        }
        assertEquals(360f, cfg.designWidthDp, 0f)
        assertEquals(640f, cfg.designHeightDp, 0f)
        assertTrue(cfg.excludeFontScale)
        assertTrue(cfg.cancelTargets.contains(JavaConsumerSmoke::class.java))
        assertEquals(AdaptDirective.ShortSide(720f, true), cfg.customTargets[Activity::class.java])
    }
}
