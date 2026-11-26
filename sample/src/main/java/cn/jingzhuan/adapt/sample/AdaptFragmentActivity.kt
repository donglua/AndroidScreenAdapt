package cn.jingzhuan.adapt.sample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import cn.jingzhuan.adapt.autosize.AdaptDirective
import cn.jingzhuan.adapt.autosize.AdaptTarget
import cn.jingzhuan.adapt.autosize.ScreenMetrics
import java.util.Locale

class AdaptFragmentActivity : BaseAdaptActivity() {

    private lateinit var hostInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_host)

        hostInfo = findViewById(R.id.tv_fragment_host_info)

        findViewById<Button>(R.id.btn_switch_standard).setOnClickListener {
            showStandardFragment()
        }
        findViewById<Button>(R.id.btn_switch_custom).setOnClickListener {
            showCustomFragment()
        }

        if (savedInstanceState == null) {
            showCustomFragment()
        } else {
            refreshHostInfo()
        }
    }

    fun showStandardFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, StandardSampleFragment(), TAG_STANDARD)
            .commitNow()
        refreshHostInfo()
    }

    fun showCustomFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, CustomDesignFragment(), TAG_CUSTOM)
            .commitNow()
        refreshHostInfo()
    }

    private fun refreshHostInfo() {
        val dm = resources.displayMetrics
        val winSize = ScreenMetrics.getWindowSize(this)
        hostInfo.text = String.format(
            Locale.US,
            "Host density=%.3f, window=%dx%d",
            dm.density,
            winSize[0],
            winSize[1]
        )
    }

    class StandardSampleFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View = inflater.inflate(R.layout.fragment_sample, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            val ctx = requireContext()
            val dm = ctx.resources.displayMetrics
            val winSize = ScreenMetrics.getWindowSize(ctx)
            view.findViewById<TextView>(R.id.tv_fragment_detail).text = String.format(
                Locale.US,
                "StandardSampleFragment (inherits 360dp)\ndensity=%.3f, window=%dx%d",
                dm.density,
                winSize[0],
                winSize[1]
            )
        }
    }

    class CustomDesignFragment : Fragment(), AdaptTarget {
        override fun adaptDirective(): AdaptDirective = AdaptDirective.Width(450f)

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View = inflater.inflate(R.layout.fragment_sample, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            val ctx = requireContext()
            val dm = ctx.resources.displayMetrics
            val winSize = ScreenMetrics.getWindowSize(ctx)
            view.findViewById<TextView>(R.id.tv_fragment_detail).text = String.format(
                Locale.US,
                "CustomDesignFragment (450dp override)\ndensity=%.3f, window=%dx%d",
                dm.density,
                winSize[0],
                winSize[1]
            )
        }
    }

    companion object {
        const val TAG_STANDARD = "standard"
        const val TAG_CUSTOM = "custom"
    }
}
