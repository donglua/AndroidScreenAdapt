package cn.jingzhuan.adapt.sample

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity
import cn.jingzhuan.adapt.autosize.ScreenAdapt

open class BaseAdaptActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenAdapt.wrapContext(newBase))
    }

    override fun getResources(): Resources {
        ScreenAdapt.ensureApplied(this)
        return super.getResources()
    }
}
