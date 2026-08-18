package pl.arma.files

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Zaokrąglenie tylko u góry — dolna krawędź styka się z treścią. */
private val TabShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val TabHeight = 40.dp

private fun tabIcon(tab: TabInfo): ImageVector = when {
    tab.atHome -> Icons.Default.Computer
    tab.source == SourceType.SMB -> Icons.Default.Dns
    tab.source == SourceType.FTP -> Icons.Default.Storage
    tab.source in setOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX) ->
        Icons.Default.Cloud
    else -> Icons.Default.Folder
}

/**
 * Pasek zakładek katalogów.
 *
 * Zakładki są zaokrąglone u góry i obrysowane cienką linią, a dolną krawędzią
 * dotykają treści — stąd wrażenie, że zakładka jest z nią połączona. Aktywna
 * jest jaśniejsza i ma wyraźniejszy obrys.
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
    val listState = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.active }

    // Po otwarciu albo przełączeniu zakładki trzeba ją pokazać — inaczej
    // aktywna potrafi zostać poza ekranem albo pod przyciskiem „+".
    LaunchedEffect(activeIndex, tabs.size) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(TabHeight),
            verticalAlignment = Alignment.Bottom
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
                contentPadding = PaddingValues(end = 6.dp)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    BrowserTab(
                        tab = tab,
                        closable = tabs.size > 1,
                        onSelect = { onSelect(tab.id) },
                        onClose = { onClose(tab.id) }
                    )
                }
            }

            // „+" stoi poza obszarem przewijania, więc nie znika przy krawędzi,
            // gdy zakładek jest więcej niż mieści ekran.
            NewTabButton(onNew)

            if (canSplit) {
                Box(
                    Modifier
                        .padding(end = 8.dp, bottom = 4.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleDual),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (dualPane) Icons.Default.Fullscreen else Icons.Default.VerticalSplit,
                        if (dualPane) "Jeden panel" else "Dwa panele",
                        Modifier.size(20.dp),
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
    closable: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = when {
            tab.active -> MaterialTheme.colorScheme.surface
            tab.secondary -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .4f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "tlo zakladki"
    )
    val outline by animateColorAsState(
        targetValue = if (tab.active) {
            MaterialTheme.colorScheme.outline.copy(alpha = .45f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "obrys zakladki"
    )
    val label = if (tab.active) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .height(TabHeight)
            .widthIn(min = 110.dp, max = 156.dp)
            .clip(TabShape)
            .background(background)
            .border(1.dp, outline, TabShape)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            tabIcon(tab),
            null,
            Modifier.size(17.dp),
            tint = if (tab.active) MaterialTheme.colorScheme.primary else label
        )
        Text(
            tab.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (tab.active) FontWeight.SemiBold else FontWeight.Normal,
            color = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (closable) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, "Zamknij kartę", Modifier.size(16.dp), tint = label)
            }
        }
    }
}

@Composable
private fun NewTabButton(onNew: () -> Unit) {
    Box(
        Modifier
            .height(TabHeight)
            .width(50.dp)
            .clip(TabShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, TabShape)
            .clickable(onClick = onNew),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Add,
            "Nowa karta",
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Gdy zakładka jest jedna, pasek tylko zabierałby miejsce — zostaje sam
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
