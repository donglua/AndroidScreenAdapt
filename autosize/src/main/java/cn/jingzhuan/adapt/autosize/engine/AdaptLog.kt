package cn.jingzhuan.adapt.autosize.engine

import android.util.Log

internal object AdaptLog {
    private const val TAG = "ScreenAdapt"

    @Volatile
    var enabled: Boolean = false

    fun d(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    fun w(message: String) {
        if (enabled) Log.w(TAG, message)
    }

    fun e(message: String, tr: Throwable? = null) {
        if (enabled) Log.e(TAG, message, tr)
    }
}
