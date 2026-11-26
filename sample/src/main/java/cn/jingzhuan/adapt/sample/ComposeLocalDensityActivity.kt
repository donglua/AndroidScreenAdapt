package cn.jingzhuan.adapt.sample

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jingzhuan.adapt.autosize.AdaptDirective
import cn.jingzhuan.adapt.autosize.NoAdapt
import cn.jingzhuan.adapt.autosize.ScreenAdapt
import cn.jingzhuan.adapt.autosize.TargetMetrics
import java.util.Locale

class ComposeLocalDensityActivity : ComponentActivity(), NoAdapt {

    @Volatile
    var providedLocalDensity: Float = 0f
        private set

    @Volatile
    var providedLocalFontScale: Float = 0f
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenAdapt.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val targetMetrics = remember(context) {
                ScreenAdapt.resolveTargetMetrics(context, AdaptDirective.Width(360f))
            }
            val nativeDm = context.resources.displayMetrics
            val customDensity = targetMetrics?.let { Density(it.density, it.fontScale) }
                ?: LocalDensity.current

            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides customDensity) {
                    providedLocalDensity = LocalDensity.current.density
                    providedLocalFontScale = LocalDensity.current.fontScale
                    ComposeLocalDensityScreen(
                        targetMetrics = targetMetrics,
                        nativeDensity = nativeDm.density
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposeLocalDensityScreen(
    targetMetrics: TargetMetrics?,
    nativeDensity: Float
) {
    val activeDensity = LocalDensity.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = String.format(
                    Locale.US,
                    "Compose LocalDensity (NoAdapt)\n" +
                        "LocalDensity=%.3f, fontScale=%.2f\n" +
                        "Context DisplayMetrics.density=%.3f\n" +
                        "TargetMetrics=%.3f",
                    activeDensity.density,
                    activeDensity.fontScale,
                    nativeDensity,
                    targetMetrics?.density ?: 0f
                ),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(24.dp)
                    .background(Color(0xFF6A1B9A))
            )
        }
    }
}
