package pl.arma.files.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF315C49),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F1D2),
    secondary = Color(0xFF4D6358),
    surface = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFFE0E7E2)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF99D5B7),
    primaryContainer = Color(0xFF163F2F),
    secondary = Color(0xFFB5CCBE),
    surface = Color(0xFF0F1512),
    surfaceVariant = Color(0xFF27312C)
)

@Composable
fun ArmaFilesTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light

    MaterialTheme(colorScheme = colors, content = content)
}
