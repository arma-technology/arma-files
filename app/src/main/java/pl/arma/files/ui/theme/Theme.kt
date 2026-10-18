package pl.arma.files.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2E6B52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEEDC),
    onPrimaryContainer = Color(0xFF0B2C20),
    secondary = Color(0xFF5A5F66),
    secondaryContainer = Color(0xFFDCEBE2),
    onSecondaryContainer = Color(0xFF16201A),
    tertiary = Color(0xFF8A6A1E),
    tertiaryContainer = Color(0xFFF7E3B5),
    // Tło i powierzchnie są neutralnie szare. Zieleń zostaje tylko jako akcent;
    // wcześniej przenikała wszystko i ekran zlewał się w jedną plamę.
    background = Color(0xFFF6F7F8),
    surface = Color(0xFFF6F7F8),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE4E6E9),
    onSurfaceVariant = Color(0xFF5B6067),
    // Pełna drabinka powierzchni. Niezdefiniowane odcienie Material zastępuje
    // własną, fioletową paletą bazową — a wtedy elementy używające akurat tych
    // odcieni odcinają się kolorem od reszty aplikacji.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F1F3),
    surfaceContainer = Color(0xFFEDEFF1),
    surfaceContainerHigh = Color(0xFFE6E8EB),
    surfaceContainerHighest = Color(0xFFDFE2E5),
    outline = Color(0xFF7A8088),
    outlineVariant = Color(0xFFD9DCE0)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FDCB6),
    onPrimary = Color(0xFF0B3A26),
    primaryContainer = Color(0xFF1E4D39),
    onPrimaryContainer = Color(0xFFC6F2DA),
    secondary = Color(0xFFB9BEC6),
    secondaryContainer = Color(0xFF26372E),
    onSecondaryContainer = Color(0xFFCFEBDB),
    tertiary = Color(0xFFE6C77A),
    tertiaryContainer = Color(0xFF4F4120),
    background = Color(0xFF111213),
    surface = Color(0xFF111213),
    onSurface = Color(0xFFE4E5E7),
    surfaceVariant = Color(0xFF2A2C2F),
    onSurfaceVariant = Color(0xFFA3A8AF),
    surfaceContainerLowest = Color(0xFF0C0D0E),
    surfaceContainerLow = Color(0xFF18191B),
    surfaceContainer = Color(0xFF1D1F21),
    surfaceContainerHigh = Color(0xFF26282B),
    surfaceContainerHighest = Color(0xFF303236),
    outline = Color(0xFF80868E),
    outlineVariant = Color(0xFF34373B)
)

/**
 * Kolory typów plików. Na liście to one niosą informację — folder, zdjęcie
 * i PDF rozpoznaje się kątem oka, zanim przeczyta się nazwę.
 */
@Immutable
data class FileTypeColors(
    val folder: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val pdf: Color,
    val document: Color,
    val sheet: Color,
    val archive: Color,
    val code: Color,
    val cad: Color,
    val apk: Color,
    val other: Color
)

private val LightFileColors = FileTypeColors(
    folder = Color(0xFFE09B16),
    image = Color(0xFF8E5BD8),
    video = Color(0xFFE5484D),
    audio = Color(0xFFE8673A),
    pdf = Color(0xFFD93A3A),
    document = Color(0xFF2F72E0),
    sheet = Color(0xFF239A57),
    archive = Color(0xFF8A6A4A),
    code = Color(0xFF0E98A8),
    cad = Color(0xFFC2408F),
    apk = Color(0xFF5E9E2E),
    other = Color(0xFF7A8088)
)

private val DarkFileColors = FileTypeColors(
    folder = Color(0xFFF5BE4F),
    image = Color(0xFFB794F6),
    video = Color(0xFFFF7A7E),
    audio = Color(0xFFFF9C6E),
    pdf = Color(0xFFFF6B6B),
    document = Color(0xFF6EA8FF),
    sheet = Color(0xFF5BCB8A),
    archive = Color(0xFFC9A27E),
    code = Color(0xFF4FD0DE),
    cad = Color(0xFFF07AC8),
    apk = Color(0xFF9BD46A),
    other = Color(0xFF9BA1A9)
)

val LocalFileTypeColors = staticCompositionLocalOf { DarkFileColors }

@Composable
fun ArmaFilesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalFileTypeColors provides if (dark) DarkFileColors else LightFileColors) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
