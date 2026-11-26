package cn.jingzhuan.adapt.sample

import android.os.Bundle
import android.widget.TextView
import cn.jingzhuan.adapt.autosize.NoAdapt
import java.util.Locale

class NativeNoAdaptActivity : BaseAdaptActivity(), NoAdapt {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val dm = resources.displayMetrics
        val appDm = application.resources.displayMetrics
        findViewById<TextView>(R.id.tv_detail_metrics).text = String.format(
            Locale.US,
            "NativeNoAdaptActivity (NoAdapt)\nactivity.density=%.3f, dpi=%d\napp.density=%.3f",
            dm.density,
            dm.densityDpi,
            appDm.density
        )
    }
}
