package pl.arma.files

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Kształt karty przeglądarki: zaokrąglony u góry, a u dołu z wklęsłymi
 * narożnikami, które „wtapiają" kartę w pasek.
 *
 * To właśnie te wklęsłe łuki sprawiają, że karta czyta się jako zakładka
 * połączona z treścią, a nie jako pigułka leżąca na pasku — sama zmiana
 * promienia rogów tego efektu nie daje.
 */
internal class BrowserTabShape(
    private val topRadiusDp: Float = 12f,
    private val bottomCurveDp: Float = 10f
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val width = size.width
        val height = size.height

        // Wklęsłość i zaokrąglenie muszą się zmieścić w wysokości karty, inaczej
        // przy niskiej karcie lewa krawędź wychodziłaby ponad jej górny brzeg.
        val curve = with(density) { bottomCurveDp.dp.toPx() }
            .coerceAtMost(width / 4f)
            .coerceAtMost(height / 2f)
            .coerceAtLeast(0f)
        val top = with(density) { topRadiusDp.dp.toPx() }
            .coerceAtMost(height - curve)
            .coerceAtMost(width / 2f - curve)
            .coerceAtLeast(0f)

        val path = Path().apply {
            // Lewy dolny narożnik — łuk wklęsły w stronę karty.
            moveTo(0f, height)
            cubicTo(
                curve * 0.55f, height,
                curve, height - curve * 0.55f,
                curve, height - curve
            )
            // Lewa krawędź w górę.
            lineTo(curve, top)
            cubicTo(curve, top * 0.45f, curve + top * 0.45f, 0f, curve + top, 0f)
            // Górna krawędź.
            lineTo(width - curve - top, 0f)
            cubicTo(
                width - curve - top * 0.45f, 0f,
                width - curve, top * 0.45f,
                width - curve, top
            )
            // Prawa krawędź w dół.
            lineTo(width - curve, height - curve)
            cubicTo(
                width - curve, height - curve * 0.55f,
                width - curve * 0.55f, height,
                width, height
            )
            close()
        }
        return Outline.Generic(path)
    }
}

private fun tabIcon(tab: TabInfo): ImageVector = when {
    tab.atHome -> Icons.Default.Computer
    tab.source == SourceType.SMB -> Icons.Default.Dns
    tab.source == SourceType.FTP -> Icons.Default.Storage
    tab.source in setOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX) ->
        Icons.Default.Cloud
    else -> Icons.Default.Folder
}

/**
 * Pasek kart w stylu przeglądarki. Aktywna karta ma kolor treści pod paskiem,
 * więc wygląda, jakby była z nią połączona; pozostałe są przygaszone.
 */
@Composable
fun BrowserTabStrip(
    tabs: List<TabInfo>,
    dualPane: Boolean,
    canSplit: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
    onToggleDual: () -> Unit
) {
    val barColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = MaterialTheme.colorScheme.surface
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxWidth().background(barColor)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(42.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(scroll)
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                tabs.forEach { tab ->
                    BrowserTab(
                        tab = tab,
                        contentColor = contentColor,
                        barColor = barColor,
                        closable = tabs.size > 1,
                        onSelect = { onSelect(tab.id) },
                        onClose = { onClose(tab.id) }
                    )
                }

                // Plus tuż za ostatnią kartą, tak jak w przeglądarce.
                Box(
                    Modifier
                        .padding(start = 2.dp, bottom = 4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onNew),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        "Nowa karta",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (canSplit) {
                Box(
                    Modifier
                        .padding(end = 8.dp, bottom = 4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleDual),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (dualPane) Icons.Default.Fullscreen else Icons.Default.VerticalSplit,
                        if (dualPane) "Jeden panel" else "Dwa panele",
                        Modifier.size(19.dp),
                        tint = if (dualPane) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserTab(
    tab: TabInfo,
    contentColor: Color,
    barColor: Color,
    closable: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = when {
            tab.active -> contentColor
            // Karta z drugiego panelu jest wyróżniona, ale słabiej niż aktywna —
            // obie są „w użyciu", tylko jedna przyjmuje kliknięcia.
            tab.secondary -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .55f)
            else -> barColor
        },
        label = "tlo karty"
    )
    val labelColor by animateColorAsState(
        targetValue = if (tab.active) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "kolor napisu"
    )
    val height by animateFloatAsState(
        targetValue = if (tab.active) 38f else 34f,
        animationSpec = spring(),
        label = "wysokosc karty"
    )

    Row(
        modifier = Modifier
            .height(height.dp)
            .widthIn(min = 118.dp, max = 190.dp)
            .clip(BrowserTabShape())
            .background(background)
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            tabIcon(tab),
            null,
            Modifier.size(15.dp),
            tint = if (tab.active) MaterialTheme.colorScheme.primary else labelColor
        )
        Text(
            tab.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (tab.active) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (closable) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, "Zamknij kartę", Modifier.size(13.dp), tint = labelColor)
            }
        }
    }
}

/**
 * Gdy karta jest jedna, pasek kart tylko zabierałby miejsce — zostaje sam
 * przycisk otwarcia kolejnej i przełącznik podziału.
 */
@Composable
fun SingleTabBar(dualPane: Boolean, canSplit: Boolean, onNew: () -> Unit, onToggleDual: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canSplit) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onToggleDual),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (dualPane) Icons.Default.Fullscreen else Icons.Default.VerticalSplit,
                        if (dualPane) "Jeden panel" else "Dwa panele",
                        Modifier.size(19.dp),
                        tint = if (dualPane) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onNew),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    "Nowa karta",
                    Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
