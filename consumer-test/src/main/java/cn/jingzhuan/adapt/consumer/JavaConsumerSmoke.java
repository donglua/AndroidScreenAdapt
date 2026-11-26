package cn.jingzhuan.adapt.consumer;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import cn.jingzhuan.adapt.autosize.AdaptBasis;
import cn.jingzhuan.adapt.autosize.AdaptConfig;
import cn.jingzhuan.adapt.autosize.AdaptDirective;
import cn.jingzhuan.adapt.autosize.AdaptEvent;
import cn.jingzhuan.adapt.autosize.AdaptEventListener;
import cn.jingzhuan.adapt.autosize.NoAdapt;
import cn.jingzhuan.adapt.autosize.ScreenAdapt;
import cn.jingzhuan.adapt.autosize.ScreenMetrics;
import cn.jingzhuan.adapt.autosize.TargetMetrics;
import cn.jingzhuan.adapt.autosize.WindowInfo;

/**
 * Compile-time consumer verification for Java callers using the modernized ScreenAdapt API.
 */
public final class JavaConsumerSmoke implements NoAdapt {

    public static void exerciseApi(Application application, Activity activity, Context baseContext) {
        AdaptConfig config = new AdaptConfig.Builder()
                .designSize(360f, 640f)
                .basis(AdaptBasis.WIDTH)
                .excludeFontScale(true)
                .customFragment(true)
                .cancelTarget(JavaConsumerSmoke.class)
                .customTarget(Activity.class, new AdaptDirective.ShortSide(360f, true))
                .strategy((target, act) -> AdaptDirective.Native.INSTANCE)
                .build();

        if (!ScreenAdapt.isInitialized()) {
            ScreenAdapt.init(application, config);
        }

        Context wrapped = ScreenAdapt.wrapContext(baseContext);
        ScreenAdapt.apply(activity, new AdaptDirective.Width(360f));
        ScreenAdapt.apply(activity, new AdaptDirective.Height(640f));
        ScreenAdapt.apply(activity, new AdaptDirective.ShortSide(360f));
        ScreenAdapt.applyNative(activity);
        ScreenAdapt.reapply(activity);
        ScreenAdapt.ensureApplied(activity);

        TargetMetrics metrics = ScreenAdapt.resolveTargetMetrics(wrapped);
        if (metrics != null) {
            float ignored = metrics.getDensity() + metrics.getFontScale();
        }
        WindowInfo info = ScreenAdapt.getWindowInfo(activity);
        if (info != null) {
            int ignored = info.getWidthPx() + info.getHeightPx();
        }

        AdaptEventListener listener = (act, event) -> {
            if (event.getType() == AdaptEvent.Type.APPLIED) {
                float d = event.getTargetDensity();
            }
        };
        ScreenAdapt.addListener(activity, listener);
        ScreenAdapt.removeListener(activity, listener);

        ScreenMetrics.getWindowSize(application);
        ScreenMetrics.getRealDisplaySize(application);
        ScreenMetrics.getStatusBarHeight(application);
        ScreenMetrics.getNavigationBarHeight(application);
    }
}
