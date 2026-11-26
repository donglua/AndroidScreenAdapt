# Applied only when running instrumentation tests against R8-minified APK (-PminifiedTest=true)
-dontwarn com.google.errorprone.annotations.*
-keep class androidx.tracing.** { *; }
-keep class androidx.test.** { *; }
-keep class kotlin.** { *; }
