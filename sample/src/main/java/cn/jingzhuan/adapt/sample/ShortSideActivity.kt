package cn.jingzhuan.adapt.sample

import android.os.Bundle
import android.widget.TextView
import cn.jingzhuan.adapt.autosize.AdaptEvent
import cn.jingzhuan.adapt.autosize.AdaptEventListener
import cn.jingzhuan.adapt.autosize.ScreenAdapt
import java.util.Locale

class ShortSideActivity : BaseAdaptActivity() {

    val recordedEvents = mutableListOf<AdaptEvent>()

    private val eventListener = AdaptEventListener { _, event ->
        recordedEvents += event
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ScreenAdapt.addListener(this, eventListener)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val dm = resources.displayMetrics
        val info = ScreenAdapt.getWindowInfo(this)
        findViewById<TextView>(R.id.tv_detail_metrics).text = String.format(
            Locale.US,
            "ShortSideActivity (400dp short side)\ndensity=%.3f, window=%dx%d, real=%dx%d, events=%d",
            dm.density,
            info?.widthPx ?: 0,
            info?.heightPx ?: 0,
            info?.realWidthPx ?: 0,
            info?.realHeightPx ?: 0,
            recordedEvents.size
        )
    }

    override fun onDestroy() {
        ScreenAdapt.removeListener(this, eventListener)
        super.onDestroy()
    }
}
