package cn.jingzhuan.adapt.autosize

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

class TestAdaptActivity : Activity() {
    var reentrantGuardOnGetResources: Boolean = false
    var reentrantCalls: Int = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenAdapt.wrapContext(newBase))
    }

    override fun getResources(): Resources {
        val res = super.getResources()
        if (reentrantGuardOnGetResources) {
            reentrantCalls++
            ScreenAdapt.ensureApplied(this)
        }
        return res
    }
}

class TestCustomAdaptActivity : Activity(), AdaptTarget {
    override fun adaptDirective(): AdaptDirective = AdaptDirective.Width(400f)
}

class TestCancelAdaptActivity : Activity(), NoAdapt

class TestNativeConfigChangesActivity : Activity(), NoAdapt {
    override fun getResources(): Resources {
        ScreenAdapt.ensureApplied(this)
        return super.getResources()
    }
}

class TestPortraitConfigActivity : Activity()

class TestLandscapeActivity : Activity()

class TestFragmentHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(android.R.id.content, TestCustomFragment(), "custom_frag")
                .commitNow()
        }
    }
}

class TestCustomFragment : Fragment(), AdaptTarget {
    override fun adaptDirective(): AdaptDirective = AdaptDirective.Width(450f)
}
