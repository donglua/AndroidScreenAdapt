package cn.jingzhuan.adapt.autosize.engine

import android.app.Activity
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import java.lang.ref.WeakReference

internal interface FragmentHook {
    fun registerIfNeeded(activity: Activity, session: ActivitySession)
    fun resolveActiveTarget(session: ActivitySession): Any?
}

internal class FragmentHookImpl(
    private val hasExplicitDirective: (Fragment, Activity) -> Boolean,
    private val applyFragmentTarget: (Fragment, Activity, ActivitySession) -> Unit
) : FragmentHook {

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
            val host = f.activity ?: return
            val session = ScreenAdaptEngine.getOrCreateSessionForBridge(host)
            if (hasExplicitDirective(f, host)) {
                session.activeFragmentOverrideRef = WeakReference(f)
                applyFragmentTarget(f, host, session)
            } else if (session.activeFragmentOverrideRef?.get() == null || f.id != 0) {
                session.activeFragmentOverrideRef = null
                applyFragmentTarget(f, host, session)
            }
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            val host = f.activity ?: return
            val session = ScreenAdaptEngine.getSessionForBridge(host) ?: return
            if (session.activeFragmentOverrideRef?.get() === f) {
                session.activeFragmentOverrideRef = null
            }
        }
    }

    override fun registerIfNeeded(activity: Activity, session: ActivitySession) {
        if (!session.fragmentCallbacksRegistered && activity is FragmentActivity) {
            session.fragmentCallbacksRegistered = true
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        }
    }

    override fun resolveActiveTarget(session: ActivitySession): Any? {
        val frag = session.activeFragmentOverrideRef?.get() as? Fragment ?: return null
        if (!frag.isAdded || frag.isRemoving) return null
        return frag
    }
}
