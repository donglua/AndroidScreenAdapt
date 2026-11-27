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
    implementation("io.github.donglua.adapt:autosize:0.2.0")
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

### 4. Multi-Form-Factor Configuration (Foldables / Wide Foldables / Tablets / MagicWindow / Landscape)

The engine computes metrics from the current `Window` and automatically tracks window changes (fold/unfold, OEM MagicWindow/letterboxing, split-screen resizing, orientation changes), while tier thresholds or native fallbacks for large/unusual screens should be defined in `strategy` to match your product design. `ScreenAdapt.getWindowInfo(activity)`, `ScreenAdapt.getNativeDensity(activity)`, and `activity.resources.configuration` are refreshed before `strategy` runs and can be read safely inside it:

```kotlin
ScreenAdapt.init(this) {
    designSize(375f, 667f)
    excludeFontScale(true)
    strategy { _, activity ->
        val info = ScreenAdapt.getWindowInfo(activity) ?: return@strategy null
        val w = info.widthPx
        val h = info.heightPx
        if (w <= 0 || h <= 0) return@strategy null

        val shortPx = minOf(w, h)
        val longPx = maxOf(w, h)
        val realShort = minOf(info.realWidthPx, info.realHeightPx)
        val realLong = maxOf(info.realWidthPx, info.realHeightPx)
        val ratio = w.toFloat() / h.toFloat()

        // Detect split-screen, freeform, and OEM MagicWindow/letterboxed windows
        // (where activity.isInMultiWindowMode may still report false).
        val isSubWindow = info.inMultiWindow ||
            (realShort > 0 && shortPx < realShort * 0.85f) ||
            (realLong > 0 && longPx < realLong * 0.85f)

        val swDp = activity.resources.configuration.smallestScreenWidthDp
        val nativeDensity = ScreenAdapt.getNativeDensity(activity)
        val isTabletDevice = swDp >= 600 && (realLong.toFloat() / realShort.coerceAtLeast(1)) > 1.2f

        when {
            // 1. Near-square unfolded foldable (e.g., Mate X5/X6 inner display)
            !isSubWindow && w > 1500 && ratio in 0.85f..1.15f ->
                AdaptDirective.Width(750f)

            // 2. Fullscreen tablets / tri-foldables (excluding constrained MagicWindow/freeform sub-windows):
            //    scale to 750dp or return AdaptDirective.Native for responsive layouts.
            !isSubWindow && (
                (isTabletDevice && shortPx >= 1500) ||
                    (shortPx >= 1500 && nativeDensity in 0.1f..2.5f) ||
                    shortPx >= 2000
                ) -> AdaptDirective.ShortSide(750f, useRealDisplay = false)

            // 3. High-PPI wide-foldable inner display (e.g., 7-8" wide foldables): 600dp short side
            //    to avoid sudden font shrinkage from falling into the tablet 750dp bucket.
            !isSubWindow && shortPx >= 1500 ->
                AdaptDirective.ShortSide(600f, useRealDisplay = false)

            // 4. Phones (portrait/landscape), short/square cover screens, MagicWindow, and split/freeform sub-windows:
            //    375dp base with short-screen protection relaxing short side up to 420dp.
            else -> {
                val effectiveShortDp = ((shortPx.toFloat() / longPx.toFloat()) * 667f)
                    .coerceIn(375f, 420f)
                AdaptDirective.ShortSide(effectiveShortDp, useRealDisplay = !isSubWindow)
            }
        }
    }
}
```

> **Notes**:
> - Thresholds above (`375f / 420f / 600f / 750f`, `667f`, `1500px`, `2.5f`) are reference examples—adjust them to match your design specs. If tablets (including MagicWindow) should use native responsive layouts, return `AdaptDirective.Native` for tablets or skip calling `ScreenAdapt.init(...)` in `Application#onCreate` (`ScreenMetrics` remains safe to call).
> - **Always return `AdaptDirective.Native` (never `AdaptDirective.Skip`) to opt out of scaling**: `Skip` leaves the previous `DisplayMetrics` untouched, so rotating without Activity recreation (for instance, entering a centered MagicWindow on a tablet) would retain the portrait density and clip the UI.
> - To handle fold/unfold, MagicWindow transitions, or rotation **without recreating the `Activity`**, declare `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"` in `AndroidManifest.xml`; the engine will automatically recalculate density on configuration and layout changes.

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
