package pl.arma.files.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.arma.files.R

/** Noc i Papier to jeden język projektu w dwóch materiałach; Automatyczny idzie za systemem. */
enum class AppearanceMode { AUTO, NIGHT, PAPER }

/** Akcent w dwóch odcieniach — jasny czytelny na Nocy, ciemny na Papierze. */
enum class Accent(val night: Color, val paper: Color) {
    GREEN(Color(0xFF6FD3A5), Color(0xFF1F6B4E)),
    BLUE(Color(0xFF7FB6FF), Color(0xFF2D4FA8)),
    COPPER(Color(0xFFF2A65A), Color(0xFFB4442E)),
    GRAPHITE(Color(0xFFC9CED2), Color(0xFF17191B))
}

@Immutable
data class Appearance(
    val mode: AppearanceMode = AppearanceMode.AUTO,
    val accent: Accent = Accent.GREEN,
    /** Folder nadrzędny rozmyty pod arkuszem (Noc, Android 12+). */
    val blur: Boolean = true,
    /** Kropki kolorów przy folderach ze zdjęciami. */
    val folderColors: Boolean = true,
    /** Krótsze przejścia, bez paralaksy. */
    val calm: Boolean = false
)

/** Ustawienia wyglądu, zapisane między uruchomieniami. */
class AppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Appearance> = _state.asStateFlow()

    private fun load() = Appearance(
        mode = runCatching { AppearanceMode.valueOf(prefs.getString("mode", null) ?: "AUTO") }.getOrDefault(AppearanceMode.AUTO),
        accent = runCatching { Accent.valueOf(prefs.getString("accent", null) ?: "GREEN") }.getOrDefault(Accent.GREEN),
        blur = prefs.getBoolean("blur", true),
        folderColors = prefs.getBoolean("folder_colors", true),
        calm = prefs.getBoolean("calm", false)
    )

    fun update(change: (Appearance) -> Appearance) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit()
            .putString("mode", next.mode.name)
            .putString("accent", next.accent.name)
            .putBoolean("blur", next.blur)
            .putBoolean("folder_colors", next.folderColors)
            .putBoolean("calm", next.calm)
            .apply()
    }
}

/**
 * Wartości stylu ARMA, z których czytają elementy Głębi (arkusz, karty,
 * szuflada, orbita). Kolory ogólne idą też do MaterialTheme, więc reszta
 * aplikacji przechodzi na Noc albo Papier bez osobnych zmian.
 */
@Immutable
data class ArmaStyle(
    val night: Boolean,
    val ground: Color,
    val sheet: Color,
    val card: Color,
    val hairline: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val shelf: Color,
    val shelfText: Color,
    val shelfButton: Color,
    val shelfButtonText: Color,
    val blur: Boolean,
    val folderColors: Boolean,
    val calm: Boolean
) {
    companion object {
        val CardRadius = 14.dp
        val SheetRadius = 22.dp
        val ControlRadius = 12.dp
        val ShelfRadius = 16.dp
    }
}

val LocalArmaStyle = staticCompositionLocalOf { styleFor(Appearance(), night = true) }

fun styleFor(appearance: Appearance, night: Boolean): ArmaStyle {
    val accent = if (night) appearance.accent.night else appearance.accent.paper
    return if (night) ArmaStyle(
        night = true,
        ground = Color(0xFF0D0F12),
        sheet = Color(0xF0171A1E),
        card = Color(0x0FFFFFFF),
        hairline = Color(0x1AFFFFFF),
        text = Color(0xFFE9EDF0),
        muted = Color(0xFF9AA6AE),
        accent = accent,
        onAccent = Color(0xFF0D0F12),
        shelf = Color(0xE0282E34),
        shelfText = Color(0xFFE9EDF0),
        shelfButton = accent,
        shelfButtonText = Color(0xFF0D0F12),
        blur = appearance.blur,
        folderColors = appearance.folderColors,
        calm = appearance.calm
    ) else ArmaStyle(
        night = false,
        ground = Color(0xFFECE9E2),
        sheet = Color(0xFFFFFFFF),
        card = Color(0xFFFFFFFF),
        hairline = Color(0xFFD6D2C8),
        text = Color(0xFF17191B),
        muted = Color(0xFF5E625F),
        accent = accent,
        onAccent = Color(0xFFFFFFFF),
        shelf = Color(0xFF17191B),
        shelfText = Color(0xFFF4F2EC),
        shelfButton = Color(0xFFF4F2EC),
        shelfButtonText = Color(0xFF17191B),
        blur = false,
        folderColors = appearance.folderColors,
        calm = appearance.calm
    )
}

/** Kolory Material z wartości stylu — także ekrany spoza Głębi (ustawienia, okna). */
fun colorSchemeFor(style: ArmaStyle): ColorScheme {
    val a = style.accent
    return if (style.night) darkColorScheme(
        primary = a,
        onPrimary = style.onAccent,
        primaryContainer = a.copy(alpha = .22f).compositeOver(style.ground),
        onPrimaryContainer = style.text,
        secondary = Color(0xFFB9C3CA),
        secondaryContainer = Color(0xFF232A30),
        onSecondaryContainer = style.text,
        tertiary = Color(0xFFE6C77A),
        tertiaryContainer = Color(0xFF4F4120),
        background = style.ground,
        surface = style.ground,
        onSurface = style.text,
        onBackground = style.text,
        surfaceVariant = Color(0xFF22272C),
        onSurfaceVariant = style.muted,
        surfaceContainerLowest = Color(0xFF0A0B0D),
        surfaceContainerLow = Color(0xFF14171A),
        surfaceContainer = Color(0xFF171A1E),
        surfaceContainerHigh = Color(0xFF1E2226),
        surfaceContainerHighest = Color(0xFF262B30),
        outline = Color(0xFF6B757C),
        outlineVariant = Color(0xFF2A3036),
        inverseSurface = Color(0xFFE9EDF0),
        inverseOnSurface = Color(0xFF17191B)
    ) else lightColorScheme(
        primary = a,
        onPrimary = style.onAccent,
        primaryContainer = a.copy(alpha = .14f).compositeOver(Color.White),
        onPrimaryContainer = style.text,
        secondary = Color(0xFF5E625F),
        secondaryContainer = Color(0xFFE4E0D7),
        onSecondaryContainer = style.text,
        tertiary = Color(0xFF8A6A1E),
        tertiaryContainer = Color(0xFFF2E3BE),
        background = style.ground,
        surface = style.ground,
        onSurface = style.text,
        onBackground = style.text,
        surfaceVariant = Color(0xFFE2DED5),
        onSurfaceVariant = style.muted,
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F5F0),
        surfaceContainer = Color(0xFFF2EFE9),
        surfaceContainerHigh = Color(0xFFE8E4DC),
        surfaceContainerHighest = Color(0xFFDDD9D0),
        outline = Color(0xFF8A8E8A),
        outlineVariant = style.hairline,
        inverseSurface = Color(0xFF17191B),
        inverseOnSurface = Color(0xFFF4F2EC)
    )
}

/**
 * Instrument Sans do wszystkiego, IBM Plex Mono do ścieżek i szczegółów.
 * Instrument Sans nie ma cyrylicy — w wersjach białoruskiej, bułgarskiej,
 * macedońskiej, rosyjskiej, serbskiej i ukraińskiej litery cyrylickie
 * Android bierze z czcionki systemowej.
 */
@OptIn(ExperimentalTextApi::class)
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.instrument_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.instrument_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.instrument_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium)
)

/** Typografia Material na Instrument Sans — te same rozmiary, nowy krój. */
fun armaTypography(): Typography {
    val base = Typography()
    fun TextStyle.arma() = copy(fontFamily = InstrumentSans)
    return Typography(
        displayLarge = base.displayLarge.arma(), displayMedium = base.displayMedium.arma(), displaySmall = base.displaySmall.arma(),
        headlineLarge = base.headlineLarge.arma(), headlineMedium = base.headlineMedium.arma(), headlineSmall = base.headlineSmall.arma(),
        titleLarge = base.titleLarge.arma(), titleMedium = base.titleMedium.arma(), titleSmall = base.titleSmall.arma(),
        bodyLarge = base.bodyLarge.arma(), bodyMedium = base.bodyMedium.arma(), bodySmall = base.bodySmall.arma(),
        labelLarge = base.labelLarge.arma(), labelMedium = base.labelMedium.arma(), labelSmall = base.labelSmall.arma()
    )
}
