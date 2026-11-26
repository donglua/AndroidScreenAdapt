package cn.jingzhuan.adapt.autosize.engine

import android.app.Activity
import android.os.Bundle
import androidx.fragment.app.DialogFragment
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
    private val applyFragmentTarget: (Fragment, Activity, ActivitySession) -> Unit,
    private val reapplyHost: (Activity, ActivitySession) -> Unit
) : FragmentHook {

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
            val host = f.activity ?: return
            val session = ScreenAdaptEngine.getOrCreateSessionForBridge(host)
            val effective = findEffectiveFragmentTarget(f, host)
            if (effective != null) {
                session.activeFragmentOverrideRef = WeakReference(effective)
                applyFragmentTarget(effective, host, session)
            } else if (!isHeadlessWithoutDirective(f) && isTopLevelFragment(fm, host)) {
                session.activeFragmentOverrideRef = null
                reapplyHost(host, session)
            }
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            val host = f.activity ?: return
            val session = ScreenAdaptEngine.getSessionForBridge(host) ?: return
            val effective = findEffectiveFragmentTarget(f, host)
            if (effective != null) {
                if (session.activeFragmentOverrideRef?.get() !== effective) {
                    session.activeFragmentOverrideRef = WeakReference(effective)
                    applyFragmentTarget(effective, host, session)
                }
            } else if (!isHeadlessWithoutDirective(f) && isTopLevelFragment(fm, host) &&
                session.activeFragmentOverrideRef?.get() != null
            ) {
                session.activeFragmentOverrideRef = null
                reapplyHost(host, session)
            }
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            val host = f.activity ?: return
            val session = ScreenAdaptEngine.getSessionForBridge(host) ?: return
            if (session.activeFragmentOverrideRef?.get() === f) {
                session.activeFragmentOverrideRef = null
                if (!host.isFinishing && !host.isDestroyed) {
                    reapplyHost(host, session)
                }
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

    private fun findEffectiveFragmentTarget(fragment: Fragment, host: Activity): Fragment? {
        var current: Fragment? = fragment
        while (current != null) {
            if (hasExplicitDirective(current, host)) {
                return current
            }
            current = current.parentFragment
        }
        return null
    }

    private fun isHeadlessWithoutDirective(fragment: Fragment): Boolean {
        return fragment.id == 0 && fragment !is DialogFragment
    }

    private fun isTopLevelFragment(fm: FragmentManager, host: Activity): Boolean {
        val fragmentActivity = host as? FragmentActivity ?: return false
        return fm === fragmentActivity.supportFragmentManager
    }
}
