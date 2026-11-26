package cn.jingzhuan.adapt.autosize.engine

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import cn.jingzhuan.adapt.autosize.core.MetricsResult
import cn.jingzhuan.adapt.autosize.core.MetricsSnapshot
import java.lang.reflect.Field

/**
 * Sole mutator of DisplayMetrics and Configuration.screenWidthDp/screenHeightDp.
 * Performs atomic managed-field validation, restoration, and MIUI mTmpMetrics sync.
 */
internal object MetricsApplier {

    @Volatile
    private var miuiChecked = false
    @Volatile
    private var isMiuiResources = false
    @Volatile
    private var tmpMetricsField: Field? = null

    fun inspectMiui(resources: Resources) {
        if (miuiChecked) return
        miuiChecked = true
        val className = resources.javaClass.simpleName
        if ("MiuiResources" == className || "XResources" == className) {
            isMiuiResources = true
            tmpMetricsField = try {
                Resources::class.java.getDeclaredField("mTmpMetrics").apply {
                    isAccessible = true
                }
            } catch (e: Exception) {
                AdaptLog.w("Failed to reflect MIUI mTmpMetrics: ${e.message}")
                null
            }
        }
    }

    fun matchesTarget(resources: Resources, result: MetricsResult): Boolean {
        val target = result.target ?: return false
        return matchesSnapshot(
            resources = resources,
            snapshot = target,
            checkDp = result.applyDp,
            checkSp = result.applySp,
            checkScreenSizeDp = result.applyScreenSizeDp
        )
    }

    fun matchesSnapshot(
        resources: Resources,
        snapshot: MetricsSnapshot,
        checkDp: Boolean,
        checkSp: Boolean,
        checkScreenSizeDp: Boolean
    ): Boolean {
        if (!matchesDisplayMetrics(resources.displayMetrics, snapshot, checkDp, checkSp)) {
            return false
        }
        if (checkScreenSizeDp && !matchesScreenSizeDp(resources.configuration, snapshot)) {
            return false
        }
        val miuiMetrics = getMiuiTmpMetrics(resources, logFailure = false)
        if (miuiMetrics != null && !matchesDisplayMetrics(miuiMetrics, snapshot, checkDp, checkSp)) {
            return false
        }
        return true
    }

    fun applyResult(
        targetResources: Resources,
        application: Application?,
        syncApplication: Boolean,
        result: MetricsResult
    ) {
        val target = result.target ?: return
        applySnapshot(
            targetResources = targetResources,
            application = application,
            syncApplication = syncApplication,
            snapshot = target,
            applyDp = result.applyDp,
            applySp = result.applySp,
            applyScreenSizeDp = result.applyScreenSizeDp
        )
    }

    fun applySnapshot(
        targetResources: Resources,
        application: Application?,
        syncApplication: Boolean,
        snapshot: MetricsSnapshot,
        applyDp: Boolean,
        applySp: Boolean,
        applyScreenSizeDp: Boolean
    ) {
        writeToResources(targetResources, snapshot, applyDp, applySp, applyScreenSizeDp)
        if (syncApplication && application != null) {
            val appRes = application.resources
            if (appRes !== targetResources) {
                writeToResources(appRes, snapshot, applyDp, applySp, applyScreenSizeDp)
            }
        }
    }

    private fun writeToResources(
        resources: Resources,
        snapshot: MetricsSnapshot,
        applyDp: Boolean,
        applySp: Boolean,
        applyScreenSizeDp: Boolean
    ) {
        writeDisplayMetrics(resources.displayMetrics, snapshot, applyDp, applySp)
        if (applyScreenSizeDp) {
            val config = resources.configuration
            config.screenWidthDp = snapshot.screenWidthDp
            config.screenHeightDp = snapshot.screenHeightDp
        }
        val miuiMetrics = getMiuiTmpMetrics(resources, logFailure = true)
        if (miuiMetrics != null && miuiMetrics !== resources.displayMetrics) {
            writeDisplayMetrics(miuiMetrics, snapshot, applyDp, applySp)
        }
    }

    private fun writeDisplayMetrics(
        dm: DisplayMetrics,
        snapshot: MetricsSnapshot,
        applyDp: Boolean,
        applySp: Boolean
    ) {
        if (applyDp) {
            dm.density = snapshot.density
            dm.densityDpi = snapshot.densityDpi
        }
        if (applySp) {
            dm.scaledDensity = snapshot.scaledDensity
        }
    }

    private fun matchesDisplayMetrics(
        dm: DisplayMetrics,
        target: MetricsSnapshot,
        checkDp: Boolean,
        checkSp: Boolean
    ): Boolean {
        if (checkDp) {
            if (dm.density.toRawBits() != target.density.toRawBits()) return false
            if (dm.densityDpi != target.densityDpi) return false
        }
        if (checkSp) {
            if (dm.scaledDensity.toRawBits() != target.scaledDensity.toRawBits()) return false
        }
        return true
    }

    private fun matchesScreenSizeDp(config: Configuration, target: MetricsSnapshot): Boolean {
        return config.screenWidthDp == target.screenWidthDp &&
            config.screenHeightDp == target.screenHeightDp
    }

    private fun getMiuiTmpMetrics(resources: Resources, logFailure: Boolean): DisplayMetrics? {
        if (!isMiuiResources) return null
        val field = tmpMetricsField ?: return null
        return try {
            field.get(resources) as? DisplayMetrics
        } catch (e: Exception) {
            if (logFailure) {
                AdaptLog.w("Failed to access MIUI mTmpMetrics: ${e.message}")
            }
            null
        }
    }

    internal fun resetForTesting() {
        miuiChecked = false
        isMiuiResources = false
        tmpMetricsField = null
    }
}
