# Minimal keep rules for ScreenAdapt autosize public API
-keep class cn.jingzhuan.adapt.autosize.ScreenAdapt { *; }
-keep class cn.jingzhuan.adapt.autosize.ScreenMetrics { *; }
-keep class cn.jingzhuan.adapt.autosize.AdaptConfig { *; }
-keep class cn.jingzhuan.adapt.autosize.AdaptConfig$Builder { *; }
-keep enum cn.jingzhuan.adapt.autosize.AdaptBasis { *; }
-keep enum cn.jingzhuan.adapt.autosize.ApplicationSyncPolicy { *; }
-keep class cn.jingzhuan.adapt.autosize.AdaptDirective { *; }
-keep class cn.jingzhuan.adapt.autosize.AdaptDirective$* { *; }
-keep interface cn.jingzhuan.adapt.autosize.NoAdapt { *; }
-keep interface cn.jingzhuan.adapt.autosize.AdaptTarget { *; }
-keep interface cn.jingzhuan.adapt.autosize.AdaptStrategy { *; }
-keep interface cn.jingzhuan.adapt.autosize.AdaptEventListener { *; }
-keep class cn.jingzhuan.adapt.autosize.TargetMetrics { *; }
-keep class cn.jingzhuan.adapt.autosize.WindowInfo { *; }
-keep class cn.jingzhuan.adapt.autosize.AdaptEvent { *; }
-keep enum cn.jingzhuan.adapt.autosize.AdaptEvent$Type { *; }

# Fragment support is compileOnly; avoid R8 missing-class failures in non-Fragment consumers
-dontwarn androidx.fragment.app.**
