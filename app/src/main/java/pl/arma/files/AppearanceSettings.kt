package pl.arma.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.arma.files.ui.theme.Accent
import pl.arma.files.ui.theme.AppearanceMode
import pl.arma.files.ui.theme.ArmaStyle
import pl.arma.files.ui.theme.LocalArmaStyle
import pl.arma.files.ui.theme.PlexMono

/**
 * Ustawienie „Wygląd”: motyw (Automatyczny / Noc / Papier), akcent i trzy
 * przełączniki. Zmiana działa od razu — motyw aplikacji słucha tego samego
 * zapisu.
 */
@Composable
fun AppearanceSettings(modifier: Modifier = Modifier) {
    val store = LocalContext.current.armaApp.appearance
    val appearance by store.state.collectAsState()
    val style = LocalArmaStyle.current
    val caption = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono, fontSize = 11.sp, letterSpacing = .4.sp)

    Surface(
        modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(ArmaStyle.SheetRadius),
        color = style.card.takeIf { style.night } ?: MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, style.hairline)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            Text(stringResource(R.string.appearance_mode).uppercase(), style = caption, color = style.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeTile(AppearanceMode.AUTO, stringResource(R.string.appearance_auto), appearance.mode, Modifier.weight(1f)) { store.update { it.copy(mode = AppearanceMode.AUTO) } }
                ModeTile(AppearanceMode.NIGHT, stringResource(R.string.appearance_night), appearance.mode, Modifier.weight(1f)) { store.update { it.copy(mode = AppearanceMode.NIGHT) } }
                ModeTile(AppearanceMode.PAPER, stringResource(R.string.appearance_paper), appearance.mode, Modifier.weight(1f)) { store.update { it.copy(mode = AppearanceMode.PAPER) } }
            }
            Text(
                stringResource(
                    when (appearance.mode) {
                        AppearanceMode.AUTO -> R.string.appearance_auto_hint
                        AppearanceMode.NIGHT -> R.string.appearance_night_hint
                        AppearanceMode.PAPER -> R.string.appearance_paper_hint
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = style.muted
            )

            Text(stringResource(R.string.appearance_accent).uppercase(), style = caption, color = style.muted, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Accent.entries.forEach { accent ->
                    val color = if (style.night) accent.night else accent.paper
                    val chosen = appearance.accent == accent
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(ArmaStyle.CardRadius))
                            .border(2.dp, if (chosen) color else style.hairline, RoundedCornerShape(ArmaStyle.CardRadius))
                            .selectable(selected = chosen, role = Role.RadioButton) { store.update { it.copy(accent = accent) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(color))
                    }
                }
                Text(stringResource(accentLabel(appearance.accent)), style = MaterialTheme.typography.bodySmall, color = style.muted, modifier = Modifier.padding(start = 4.dp))
            }

            Column(Modifier.padding(top = 6.dp)) {
                ToggleRow(stringResource(R.string.appearance_blur), stringResource(R.string.appearance_blur_hint), appearance.blur) { on -> store.update { it.copy(blur = on) } }
                ToggleRow(stringResource(R.string.appearance_folder_colors), stringResource(R.string.appearance_folder_colors_hint), appearance.folderColors) { on -> store.update { it.copy(folderColors = on) } }
                ToggleRow(stringResource(R.string.appearance_calm), stringResource(R.string.appearance_calm_hint), appearance.calm, last = true) { on -> store.update { it.copy(calm = on) } }
            }
        }
    }
}

private fun accentLabel(accent: Accent) = when (accent) {
    Accent.GREEN -> R.string.accent_green
    Accent.BLUE -> R.string.accent_blue
    Accent.COPPER -> R.string.accent_copper
    Accent.GRAPHITE -> R.string.accent_graphite
}

/** Kafelek motywu z miniaturą: lewa i prawa połowa tła, na nich skrawek arkusza. */
@Composable
private fun ModeTile(mode: AppearanceMode, label: String, current: AppearanceMode, modifier: Modifier, onPick: () -> Unit) {
    val style = LocalArmaStyle.current
    val chosen = mode == current
    val night = Color(0xFF0D0F12)
    val paper = Color(0xFFECE9E2)
    val (left, right) = when (mode) {
        AppearanceMode.AUTO -> night to paper
        AppearanceMode.NIGHT -> night to night
        AppearanceMode.PAPER -> paper to paper
    }
    Column(
        modifier
            .clip(RoundedCornerShape(ArmaStyle.CardRadius))
            .border(2.dp, if (chosen) style.accent else style.hairline, RoundedCornerShape(ArmaStyle.CardRadius))
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onPick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.horizontalGradient(0f to left, .5f to left, .5f to right, 1f to right))
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(
                        when (mode) {
                            AppearanceMode.NIGHT -> Brush.horizontalGradient(listOf(Color(0x24FFFFFF), Color(0x24FFFFFF)))
                            AppearanceMode.PAPER -> Brush.horizontalGradient(listOf(Color.White, Color.White))
                            AppearanceMode.AUTO -> Brush.horizontalGradient(0f to Color(0x24FFFFFF), .45f to Color(0x24FFFFFF), .45f to Color.White, 1f to Color.White)
                        }
                    )
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(label: String, hint: String, on: Boolean, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val style = LocalArmaStyle.current
    HorizontalDivider(color = style.hairline)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = style.muted)
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = style.accent, checkedThumbColor = style.onAccent)
        )
    }
    if (last) HorizontalDivider(color = style.hairline)
}
