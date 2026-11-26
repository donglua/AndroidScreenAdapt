# AndroidScreenAdapt

[中文](README.md) | English

AndroidScreenAdapt is a screen adaptation library for modern Android projects (published module `:autosize`, Maven coordinates `io.github.donglua.adapt:autosize`), designed as a modern replacement for the legacy `AndroidAutoSize` architecture.

The library uses a **hybrid architecture built on a single dynamic `DisplayMetrics` engine per current `Window` + localized `Context` / `LocalDensity` isolation**: it provides a clean Kotlin-first API (with full Java interoperability), current-Window bounds resolution, native mode, short-side adaptation for foldables and landscape screens, Android 14+ non-linear font-scaling mitigation, and rotation race-condition guards.

## Why Hybrid Architecture

Instead of modifying global `Configuration.densityDpi` inside `attachBaseContext` as a blanket replacement for `DisplayMetrics`, AndroidScreenAdapt avoids three pitfalls:

1. **Avoids resource qualifier bucket drift caused by integer `densityDpi`**: `DisplayMetrics.density` is a high-precision `Float`, whereas `Configuration.densityDpi` is an `Int`. Mutating `densityDpi` shifts the resource qualifier buckets resolved by the system (e.g., dropping from `xxhdpi` to `xhdpi` or matching the wrong `swXXXdp` layout).
2. **`attachBaseContext` has no real Window dimensions yet**: An Activity is not attached to a Window at `attachBaseContext` time, so real dimensions cannot be obtained under split-screen, freeform windows, or foldable resize events, and `attachBaseContext` does not re-run when window bounds change dynamically.
3. **Avoids `AppCompatActivity` and OEM ROM compatibility risks**: Overriding the full `Configuration` in `attachBaseContext` frequently conflicts with `AppCompatDelegate` `uiMode` (dark mode), locale switching, and `WebView` resource loading on certain devices.

Accordingly, the library adopts only two safe, localized isolation capabilities while keeping the single engine as the coordinator:

- **`ScreenAdapt.wrapContext` / `applyOverrideConfiguration` locks only `fontScale`**: Uses an empty-delta `Configuration` overriding `fontScale` **without modifying `densityDpi`**, preventing Android 14+ `fontScaleConverter` non-linear scaling at the system source.
- **Localized `LocalDensity` override for Compose**: The core library has zero dependency on Compose runtime and provides a read-only `ScreenAdapt.resolveTargetMetrics` API so pages can apply `CompositionLocalProvider(LocalDensity provides ...)` locally.

## Key Features

- **Explicit, idempotent initialization**: Does not register any `ContentProvider` in the merged `AndroidManifest.xml`. Call `ScreenAdapt.init` in `Application#onCreate` or conditionally by device form factor.
- **Current Window as the single source of truth**: Removes process-wide cached screen dimensions and computes metrics from each target Activity's current Window (`WindowMetrics` on API 30+, `Display#getMetrics` on older APIs), with automatic fallback to window short side in split-screen/freeform windows.
- **Foreground-safe `Application` metrics sync**: Synchronizes `Application` metrics only during isolated creation or when an Activity is resumed in single-window mode, preventing multi-window or background pages from overwriting each other. Native mode (`AdaptDirective.Native` / `NoAdapt` / `applyNative`) restores only the target `Activity` and never writes to `Application` (see [API Documentation](docs/API.en.md#directives-and-page-markers)).
- **Event-driven correction + O(1) fast verification**: Corrects drift via lifecycle callbacks, configuration changes, and `OnGlobalLayout`, paired with zero-allocation `ScreenAdapt.ensureApplied(activity)` for base `getResources()` protection (no background polling threads).
- **AndroidX Fragment & MIUI support**: Supports Fragment-level `AdaptTarget` / `NoAdapt` overrides and built-in MIUI `mTmpMetrics` synchronization.

## Quick Start

### 1. Add Dependency and Initialize

```kotlin
dependencies {
    implementation("io.github.donglua.adapt:autosize:0.1.0")
}
```

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenAdapt.init(this) {
            designSize(360f, 640f)
            excludeFontScale(true)
            customFragment(true)
        }
    }
}
```

### 2. Base Activity Font Isolation & Reset Protection

```kotlin
open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenAdapt.wrapContext(newBase))
    }

    override fun getResources(): Resources {
        ScreenAdapt.ensureApplied(this)
        return super.getResources()
    }
}
```

> If your host app performs pre-adaptation hardware density correction in `attachBaseContext`, keep it **before** `ScreenAdapt.wrapContext`.

### 3. Local `LocalDensity` in Compose

```kotlin
class ComposePageActivity : ComponentActivity(), NoAdapt {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenAdapt.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val target = remember(context, configuration) {
                ScreenAdapt.resolveTargetMetrics(context, AdaptDirective.Width(360f))
            }
            val density = target?.let { Density(it.density, it.fontScale) } ?: LocalDensity.current
            CompositionLocalProvider(LocalDensity provides density) {
                // Composable UI
            }
        }
    }
}
```

### 4. Multi-Form-Factor Configuration (Foldables / Wide-Bar Phones / Tablets / Landscape)

The engine computes metrics from the current `Window` and automatically tracks window changes (fold/unfold, split-screen resizing, orientation changes), while design widths or native fallbacks for large/unusual screens should be defined in `strategy` to match your product design. `ScreenAdapt.getWindowInfo(activity)` and `activity.resources.configuration` are already refreshed before `strategy` is invoked and can be read safely inside it:

```kotlin
ScreenAdapt.init(this) {
    designSize(360f, 640f)
    excludeFontScale(true)
    strategy { _, activity ->
        val info = ScreenAdapt.getWindowInfo(activity) ?: return@strategy null
        val swDp = activity.resources.configuration.smallestScreenWidthDp
        val shortPx = minOf(info.widthPx, info.heightPx)
        val longPx = maxOf(info.widthPx, info.heightPx)
        val aspectRatio = if (longPx > 0) shortPx.toFloat() / longPx else 0f

        when {
            // 1. Split-screen / freeform window: adapt to current window width (or ShortSide for window short side)
            info.inMultiWindow -> AdaptDirective.Width(360f)

            // 2. Unfolded foldable (near-square inner display): widen design width to avoid over-scaling 360dp
            aspectRatio >= 0.8f -> AdaptDirective.Width(720f)

            // 3. Tablets: use a tablet design width or return AdaptDirective.Native for responsive layouts
            swDp >= 600 -> AdaptDirective.Native

            // 4. Wide-aspect phones (e.g. 16:10 / wide-bar devices): widen design width to show more content
            swDp >= 450 || aspectRatio >= 0.6f -> AdaptDirective.Width(420f)

            // 5. Standard phone landscape: scale by real display short side to match portrait proportions
            info.configurationOrientation == Configuration.ORIENTATION_LANDSCAPE ->
                AdaptDirective.ShortSide(360f, useRealDisplay = true)

            // 6. Standard phone portrait: fall back to global designSize(360f, 640f)
            else -> null
        }
    }
}
```

> **Notes**:
> - Thresholds above (`0.8f`, `600dp`, `450dp`, `720f`/`420f`) are reference examples—adjust them to match your design specs. If you want tablets to disable adaptation app-wide, you can skip calling `ScreenAdapt.init(...)` in `Application#onCreate` (`ScreenMetrics` remains safe to call).
> - To handle fold/unfold or rotation **without recreating the `Activity`**, declare `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"` in `AndroidManifest.xml`; the engine will automatically recalculate density on configuration and layout changes.

See [API Documentation](docs/API.en.md) for full signatures, configuration options, directives, and `ScreenMetrics` utilities.

## Modules & Verification

- `:autosize`: Published AAR module containing the pure-JVM calculation package `cn.jingzhuan.adapt.autosize.core` (zero `android.*` / `androidx.*` dependencies) and runtime engine `cn.jingzhuan.adapt.autosize.engine`.
- `:consumer-test`: Kotlin DSL and Java Builder consumer compilation and contract tests.
- `:sample`: Sample app covering Fragment switching, `ShortSide` + `AdaptEventListener`, `NoAdapt`, `wrapContext`, Compose `LocalDensity`, and R8 minification checks.

```bash
# Run unit tests, contract tests, core isolation, API baseline, AAR checks, and R8 sample build
./gradlew check

# Run instrumentation tests on a connected device/emulator (including R8 minified release test)
./gradlew :autosize:connectedDebugAndroidTest :sample:connectedDebugAndroidTest
./gradlew :sample:connectedReleaseAndroidTest -PminifiedTest=true
```

## License & Acknowledgments

Licensed under the Apache-2.0 License (see [LICENSE](LICENSE)). See [NOTICE](NOTICE) for acknowledgments to [JessYan/AndroidAutoSize](https://github.com/JessYanCoding/AndroidAutoSize) (portions of the original design ideas and scaling formulas are derived from its Apache-2.0 implementation).
