package pl.arma.files.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF315C49),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8F3E5),
    onPrimaryContainer = Color(0xFF0B2C20),
    secondary = Color(0xFF5B665E),
    secondaryContainer = Color(0xFFE2E9E3),
    tertiary = Color(0xFF6C5B3F),
    tertiaryContainer = Color(0xFFF5E5C7),
    background = Color(0xFFF5F7F5),
    surface = Color(0xFFFCFDFC),
    onSurface = Color(0xFF191D1A),
    surfaceVariant = Color(0xFFE7ECE8),
    onSurfaceVariant = Color(0xFF424A44),
    // Pełna drabinka powierzchni. Niezdefiniowane odcienie Material zastępuje
    // własną, fioletową paletą bazową — a wtedy elementy używające akurat tych
    // odcieni odcinają się kolorem od reszty aplikacji.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FAF8),
    surfaceContainer = Color(0xFFEDF1EE),
    surfaceContainerHigh = Color(0xFFE6EBE7),
    surfaceContainerHighest = Color(0xFFDFE5E1),
    outline = Color(0xFF6F7B73),
    outlineVariant = Color(0xFFD4DDD6)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA7D9BF),
    onPrimary = Color(0xFF113A2A),
    primaryContainer = Color(0xFF214D3A),
    onPrimaryContainer = Color(0xFFC9F2DC),
    secondary = Color(0xFFBEC9C0),
    secondaryContainer = Color(0xFF3B463F),
    tertiary = Color(0xFFD8C29B),
    tertiaryContainer = Color(0xFF51462F),
    background = Color(0xFF101411),
    surface = Color(0xFF151A16),
    onSurface = Color(0xFFE1E4E1),
    surfaceVariant = Color(0xFF273029),
    onSurfaceVariant = Color(0xFFC0C9C2),
    surfaceContainerLowest = Color(0xFF0B0F0C),
    surfaceContainerLow = Color(0xFF161B18),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF242B26),
    surfaceContainerHighest = Color(0xFF2E3630),
    outline = Color(0xFF89968D),
    outlineVariant = Color(0xFF39453D)
)

@Composable
fun ArmaFilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
