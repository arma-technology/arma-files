package pl.arma.files.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

/**
 * Motyw aplikacji: Noc albo Papier według ustawienia „Wygląd” (Automatyczny
 * idzie za trybem ciemnym systemu). Kolory Material, krój pisma i ikony
 * paska stanu pochodzą z jednego stylu ARMA.
 */
@Composable
fun ArmaFilesTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as? pl.arma.files.ArmaFilesApp)?.appearance }
    val appearance = store?.state?.collectAsState()?.value ?: Appearance()
    val night = when (appearance.mode) {
        AppearanceMode.AUTO -> isSystemInDarkTheme()
        AppearanceMode.NIGHT -> true
        AppearanceMode.PAPER -> false
    }
    val style = remember(appearance, night) { styleFor(appearance, night) }
    val scheme = remember(style) { colorSchemeFor(style) }
    val typography = remember { armaTypography() }
    // Motyw wybrany wbrew systemowi: ikony paska stanu muszą iść za motywem, nie za systemem.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !night
                    isAppearanceLightNavigationBars = !night
                }
            }
        }
    }
    CompositionLocalProvider(
        LocalFileTypeColors provides if (night) DarkFileColors else LightFileColors,
        LocalArmaStyle provides style
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
