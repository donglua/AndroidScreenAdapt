package cn.jingzhuan.adapt.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import cn.jingzhuan.adapt.autosize.ScreenAdapt
import cn.jingzhuan.adapt.autosize.ScreenMetrics
import java.util.Locale

class MainActivity : BaseAdaptActivity() {

    private lateinit var metricsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        metricsView = findViewById(R.id.tv_metrics_summary)

        findViewById<Button>(R.id.btn_open_fragment).setOnClickListener {
            startActivity(Intent(this, AdaptFragmentActivity::class.java))
        }
        findViewById<Button>(R.id.btn_open_short_side).setOnClickListener {
            startActivity(Intent(this, ShortSideActivity::class.java))
        }
        findViewById<Button>(R.id.btn_open_native).setOnClickListener {
            startActivity(Intent(this, NativeNoAdaptActivity::class.java))
        }
        findViewById<Button>(R.id.btn_open_compose).setOnClickListener {
            startActivity(Intent(this, ComposeLocalDensityActivity::class.java))
        }

        renderSummary()
    }

    override fun onResume() {
        super.onResume()
        renderSummary()
    }

    private fun renderSummary() {
        val dm = resources.displayMetrics
        val appDm = application.resources.displayMetrics
        val windowSize = ScreenMetrics.getWindowSize(this)
        val realSize = ScreenMetrics.getRealDisplaySize(this)
        val windowInfo = ScreenAdapt.getWindowInfo(this)
        metricsView.text = String.format(
            Locale.US,
            "MainActivity (360dp width)\n" +
                "activity.density=%.3f, dpi=%d, scaled=%.3f, fontScale=%.2f\n" +
                "app.density=%.3f\n" +
                "window=%dx%d, real=%dx%d, inMultiWindow=%s",
            dm.density,
            dm.densityDpi,
            dm.scaledDensity,
            resources.configuration.fontScale,
            appDm.density,
            windowSize[0],
            windowSize[1],
            realSize[0],
            realSize[1],
            windowInfo?.inMultiWindow ?: false
        )
    }
}
