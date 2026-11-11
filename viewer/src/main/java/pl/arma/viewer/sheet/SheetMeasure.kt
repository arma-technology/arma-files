package pl.arma.viewer.sheet

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.material3.Switch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import pl.arma.viewer.R
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pędzle do napisów na płótnie: wymiary i podpis przyciągania. */
internal class MeasurePaints(
    val density: Float,
    /** Napisy rysowane na płótnie — zasobów nie da się czytać w DrawScope. */
    val setScale: String,
    val snapLabels: Map<SnapKind, String>
) {
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6C62828.toInt() }
    val cursorBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61565C0.toInt() }
}

@Composable
internal fun rememberMeasurePaints(): MeasurePaints {
    val density = LocalDensity.current.density
    val setScale = stringResource(R.string.viewer_set_scale_short)
    val snapLabels = SnapKind.entries.filter { it != SnapKind.NONE }.associateWith { stringResource(it.label) }
    return remember(density, setScale) { MeasurePaints(density, setScale, snapLabels) }
}

private val MEASURE = Color(0xFFC62828)
private val CURSOR = Color(0xFF1565C0)
private val SNAP = Color(0xFF00C853)
private val SELECTED = Color(0xFF2962FF)
private val GUIDE = Color(0xFFFF6D00)

/** Napis w zaokrąglonym prostokącie, wyśrodkowany w punkcie. */
private fun DrawScope.pill(text: String, at: Offset, paints: MeasurePaints, background: Paint) {
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val width = paints.label.measureText(text)
        val padX = 6f * paints.density
        val padY = 4f * paints.density
        val height = paints.label.textSize
        native.drawRoundRect(
            at.x - width / 2 - padX, at.y - height / 2 - padY,
            at.x + width / 2 + padX, at.y + height / 2 + padY,
            6f * paints.density, 6f * paints.density, background
        )
        native.drawText(text, at.x - width / 2, at.y + height / 2 - paints.label.descent() / 2, paints.label)
    }
}

/** Odcinek wymiarowy z kreskami na końcach. */
private fun DrawScope.dimension(a: Offset, b: Offset, color: Color, dashed: Boolean = false) {
    val stroke = 2.dp.toPx()
    drawLine(
        color, a, b, strokeWidth = stroke,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
    )
    val angle = atan2(b.y - a.y, b.x - a.x)
    val tick = 7.dp.toPx()
    val nx = -sin(angle) * tick
    val ny = cos(angle) * tick
    for (end in listOf(a, b)) drawLine(color, Offset(end.x - nx, end.y - ny), Offset(end.x + nx, end.y + ny), strokeWidth = stroke)
}

internal fun DrawScope.drawMeasurements(p: Projection, state: SheetViewerState, pages: IntRange, paints: MeasurePaints) {
    for (m in state.measurements) {
        if (m.page !in pages) continue
        val a = p.toScreen(m.page, m.ax, m.ay)
        val b = p.toScreen(m.page, m.bx, m.by)
        dimension(a, b, MEASURE)
        val scale = state.scaleFor(m.page)
        val text = scale?.let { MeasureFormat.length(m.lengthUnits * it.mmPerUnit) } ?: paints.setScale
        pill(text, Offset((a.x + b.x) / 2, (a.y + b.y) / 2), paints, paints.background)
    }
}

internal fun DrawScope.drawCommentMarks(
    p: Projection,
    comments: List<SheetComment>,
    selected: String?,
    pages: IntRange
) {
    val chosen = comments.firstOrNull { it.id == selected } ?: return
    if (chosen.page !in pages) return
    val a = p.toScreen(chosen.page, chosen.rect.left, chosen.rect.top)
    val b = p.toScreen(chosen.page, chosen.rect.right, chosen.rect.bottom)
    val pad = 4.dp.toPx()
    drawRect(
        SELECTED,
        Offset(a.x - pad, a.y - pad),
        Size(b.x - a.x + 2 * pad, b.y - a.y + 2 * pad),
        style = Stroke(2.dp.toPx())
    )
}

/**
 * Celownik w punkcie [center], znacznik punktu, do którego przyciągnął,
 * i gumka od pierwszego punktu z bieżącą długością.
 */
internal fun DrawScope.drawCursor(
    p: Projection,
    state: SheetViewerState,
    cursor: PagePoint?,
    pickLine: Pair<Int, FloatArray>?,
    center: Offset,
    paints: MeasurePaints
) {
    if (state.placement == Placement.MEASURE) {
        // Linia odniesienia prowadnicy: wskazywana właśnie albo już wybrana.
        val reference = pickLine ?: state.guide?.takeIf { state.pickingGuide == null }?.let { it.page to it.line }
        if (reference != null) {
            val (page, line) = reference
            val a = p.toScreen(page, line[0], line[1])
            val b = p.toScreen(page, line[2], line[3])
            drawLine(Color.White, a, b, strokeWidth = 6.dp.toPx())
            drawLine(GUIDE, a, b, strokeWidth = 3.dp.toPx())
        }
    }
    val arm = 16.dp.toPx()
    val gap = 5.dp.toPx()
    val stroke = 1.5.dp.toPx()
    val outline = Color.White
    for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
        val from = Offset(center.x + dx * gap, center.y + dy * gap)
        val to = Offset(center.x + dx * arm, center.y + dy * arm)
        drawLine(outline, from, to, strokeWidth = stroke + 2.dp.toPx())
        drawLine(CURSOR, from, to, strokeWidth = stroke)
    }
    cursor ?: return
    val at = p.toScreen(cursor.page, cursor.snap.x, cursor.snap.y)

    val first = state.firstPoint
    if (first != null && state.placement == Placement.MEASURE) {
        val a = p.toScreen(first.page, first.snap.x, first.snap.y)
        val guide = state.guide?.takeIf { it.page == first.page && state.pickingGuide == null }
        if (guide != null) {
            // Prowadnica przez pierwszy punkt, przez cały ekran.
            val ahead = p.toScreen(first.page, first.snap.x + guide.directionX, first.snap.y + guide.directionY)
            val length = hypot(ahead.x - a.x, ahead.y - a.y)
            if (length > 0f) {
                val reach = (size.width + size.height) / length
                val d = Offset((ahead.x - a.x) * reach, (ahead.y - a.y) * reach)
                drawLine(GUIDE.copy(alpha = .6f), a - d, a + d, strokeWidth = 1.5.dp.toPx())
            }
        } else if (state.axisLock) {
            // Prowadnice osi przez pierwszy punkt.
            val guide = Color(0x661565C0)
            drawLine(guide, Offset(0f, a.y), Offset(size.width, a.y), strokeWidth = 1.dp.toPx())
            drawLine(guide, Offset(a.x, 0f), Offset(a.x, size.height), strokeWidth = 1.dp.toPx())
        }
        if (first.page == cursor.page) {
            dimension(a, at, CURSOR, dashed = true)
            val length = hypot((cursor.snap.x - first.snap.x).toDouble(), (cursor.snap.y - first.snap.y).toDouble())
            val scale = state.scaleFor(cursor.page)
            val text = scale?.let { MeasureFormat.length(length * it.mmPerUnit) } ?: paints.setScale
            pill(text, Offset((a.x + at.x) / 2, (a.y + at.y) / 2 - 16.dp.toPx()), paints, paints.cursorBackground)
        }
        drawCircle(MEASURE, 5.dp.toPx(), a)
        drawCircle(Color.White, 2.dp.toPx(), a)
    }

    if (cursor.snap.kind != SnapKind.NONE) {
        snapMarker(cursor.snap.kind, at)
        pill(paints.snapLabels[cursor.snap.kind].orEmpty(), Offset(at.x, at.y + 26.dp.toPx()), paints, paints.cursorBackground)
    } else if (state.placement == Placement.MEASURE) {
        drawCircle(CURSOR, 3.dp.toPx(), at)
    }
}

/** Znaczniki jak w programach CAD: kwadrat, krzyżyk, trójkąt, okrąg, ⊥. */
private fun DrawScope.snapMarker(kind: SnapKind, at: Offset) {
    val r = 8.dp.toPx()
    val stroke = Stroke(2.5.dp.toPx())
    when (kind) {
        SnapKind.END -> drawRect(SNAP, Offset(at.x - r, at.y - r), Size(2 * r, 2 * r), style = stroke)
        SnapKind.INTERSECTION, SnapKind.AXIS_INTERSECTION -> {
            drawLine(SNAP, Offset(at.x - r, at.y - r), Offset(at.x + r, at.y + r), strokeWidth = stroke.width)
            drawLine(SNAP, Offset(at.x - r, at.y + r), Offset(at.x + r, at.y - r), strokeWidth = stroke.width)
        }
        SnapKind.MIDPOINT -> {
            val path = Path().apply {
                moveTo(at.x, at.y - r)
                lineTo(at.x + r, at.y + r * .8f)
                lineTo(at.x - r, at.y + r * .8f)
                close()
            }
            drawPath(path, SNAP, style = stroke)
        }
        SnapKind.CENTER -> drawCircle(SNAP, r, at, style = stroke)
        SnapKind.INSERTION -> {
            drawRect(SNAP, Offset(at.x - r, at.y - r), Size(2 * r, 2 * r), style = stroke)
            drawCircle(SNAP, r * .35f, at)
        }
        SnapKind.PERPENDICULAR -> {
            drawLine(SNAP, Offset(at.x - r, at.y + r), Offset(at.x + r, at.y + r), strokeWidth = stroke.width)
            drawLine(SNAP, Offset(at.x - r, at.y + r), Offset(at.x - r, at.y - r), strokeWidth = stroke.width)
            drawLine(SNAP, Offset(at.x - r, at.y), Offset(at.x, at.y), strokeWidth = stroke.width)
            drawLine(SNAP, Offset(at.x, at.y), Offset(at.x, at.y + r), strokeWidth = stroke.width)
        }
        SnapKind.NEAREST -> {
            val path = Path().apply {
                moveTo(at.x - r, at.y - r)
                lineTo(at.x + r, at.y - r)
                lineTo(at.x - r, at.y + r)
                lineTo(at.x + r, at.y + r)
                close()
            }
            drawPath(path, SNAP, style = stroke)
        }
        SnapKind.AXIS -> drawCircle(SNAP, r * .6f, at, style = stroke)
        SnapKind.NONE -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeasurePanel(
    state: SheetViewerState,
    cursor: PagePoint?,
    onPoint: () -> Unit,
    onSaveToFile: (() -> Unit)?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    // Ustawienie przełączników przechodzi między dokumentami i uruchomieniami.
    if (!state.measurePrefsLoaded) {
        state.measurePrefsLoaded = true
        state.snapping = ViewerPreferences.snapping(context)
        state.axisLock = ViewerPreferences.axisLock(context)
        state.edgesOnly = ViewerPreferences.edgesOnly(context)
    }
    val page = cursor?.page ?: 0
    val scale = state.scaleFor(page)
    val first = state.firstPoint
    val readout = when {
        state.loadingGeometry[page] == true -> stringResource(R.string.viewer_loading_lines)
        state.pickingGuide != null -> stringResource(R.string.viewer_aim_reference_line)
        first == null -> stringResource(R.string.viewer_aim_first_point)
        cursor == null || first.page != cursor.page -> stringResource(R.string.viewer_second_point_same_page)
        else -> {
            val dx = (cursor.snap.x - first.snap.x).toDouble()
            val dy = (cursor.snap.y - first.snap.y).toDouble()
            if (scale == null) {
                stringResource(R.string.viewer_set_scale_to_measure)
            } else {
                val k = scale.mmPerUnit
                "${MeasureFormat.length(hypot(dx, dy) * k)}   ·   Δx ${MeasureFormat.length(kotlin.math.abs(dx) * k)}   Δy ${MeasureFormat.length(kotlin.math.abs(dy) * k)}"
            }
        }
    }
    val readoutRow: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            if (state.loadingGeometry[page] == true) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(readout, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
    }
    val controls: @Composable () -> Unit = {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = scale != null,
                onClick = { state.scaleDialog = true },
                label = { Text(if (scale != null) stringResource(R.string.viewer_scale_chip, scale.label) else stringResource(R.string.viewer_set_scale)) }
            )
            IconButton(
                onClick = {
                    if (state.firstPoint != null) state.firstPoint = null
                    else if (state.measurements.isNotEmpty()) state.measurements.removeAt(state.measurements.lastIndex)
                },
                enabled = state.firstPoint != null || state.measurements.isNotEmpty()
            ) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.viewer_undo)) }
            IconButton(onClick = { state.measurements.clear(); state.firstPoint = null }, enabled = state.measurements.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, stringResource(R.string.viewer_clear_measurements))
            }
            if (onSaveToFile != null) {
                IconButton(onClick = onSaveToFile, enabled = state.measurements.isNotEmpty()) {
                    Icon(Icons.Default.Save, stringResource(R.string.viewer_measurements_to_file))
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.viewer_finish_measuring)) }
        }
    }
    // Przełączniki zawsze na widoku — w przewijanym rzędzie nikt ich nie znajdował.
    val switches: @Composable () -> Unit = {
        // Na wąskim ekranie przełączniki łamią się do drugiego rzędu.
        FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.snapping,
                    onCheckedChange = { state.snapping = it; ViewerPreferences.setSnapping(context, it) },
                    modifier = Modifier.scale(.8f)
                )
                Text(stringResource(R.string.viewer_snapping), style = MaterialTheme.typography.labelLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.edgesOnly,
                    onCheckedChange = { state.edgesOnly = it; ViewerPreferences.setEdgesOnly(context, it) },
                    enabled = state.snapping,
                    modifier = Modifier.scale(.8f)
                )
                Text(stringResource(R.string.viewer_edges_only), style = MaterialTheme.typography.labelLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.axisLock,
                    onCheckedChange = {
                        state.axisLock = it
                        ViewerPreferences.setAxisLock(context, it)
                        // Oś i prowadnica wykluczają się: obie wyznaczają kierunek pomiaru.
                        if (it) {
                            state.guide = null
                            state.pickingGuide = null
                        }
                    },
                    modifier = Modifier.scale(.8f)
                )
                Text(stringResource(R.string.viewer_axis_lock), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    // Równolegle albo prostopadle do linii: pierwsze dotknięcie prosi o wskazanie
    // linii, dotknięcie drugiego rodzaju zmienia kierunek przy tej samej linii,
    // a ponowne dotknięcie wybranego wyłącza prowadnicę.
    val guides: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in GuideMode.entries) {
                val active = state.guide
                FilterChip(
                    selected = if (state.pickingGuide != null) state.pickingGuide == mode else active?.mode == mode,
                    onClick = {
                        when {
                            state.pickingGuide == mode -> state.pickingGuide = null
                            state.pickingGuide != null -> state.pickingGuide = mode
                            active == null -> state.pickingGuide = mode
                            active.mode == mode -> state.guide = null
                            else -> state.guide = Guide(active.page, mode, active.line)
                        }
                    },
                    label = {
                        Text(
                            stringResource(if (mode == GuideMode.PARALLEL) R.string.viewer_guide_parallel else R.string.viewer_guide_perpendicular),
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
    val pointButton: @Composable (Modifier) -> Unit = { modifier ->
        Button(onClick = onPoint, modifier = modifier, enabled = cursor != null) {
            Icon(Icons.Default.GpsFixed, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    when {
                        state.pickingGuide != null -> R.string.viewer_pick_line
                        state.firstPoint == null -> R.string.viewer_first_point
                        else -> R.string.viewer_second_point
                    }
                ),
                maxLines = 1
            )
        }
    }
    BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (maxWidth >= 720.dp) {
            // W poziomie panel ma jeden rząd: ekran jest niski, a każdy
            // centymetr wysokości to widoczny rysunek.
            Column(Modifier.widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { readoutRow(Modifier) }
                    switches()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    guides()
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) { controls() }
                    Spacer(Modifier.width(8.dp))
                    pointButton(Modifier)
                }
            }
        } else {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                readoutRow(Modifier)
                switches()
                guides()
                controls()
                pointButton(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun CommentPlacementPanel(
    state: SheetViewerState,
    document: SheetDocument,
    cursor: PagePoint?,
    // Zasięg przeglądarki, nie panelu: panel znika zaraz po „Dodaj”, a wraz
    // z nim jego zasięg przerywałby zapis i odświeżenie rysunku.
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var author by remember { mutableStateOf(ViewerPreferences.author(context)) }
    val add = {
        val point = cursor
        if (point != null) {
            ViewerPreferences.setAuthor(context, author)
            scope.launch {
                runCatching { document.addComment(point.page, point.snap.x, point.snap.y, text.trim(), author.trim()) }
                    .onSuccess { state.message = context.getString(R.string.viewer_comment_added) }
                    .onFailure { state.message = context.getString(R.string.viewer_comment_failed, it.message.orEmpty()) }
            }
            onDone()
        }
    }
    val textField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.viewer_comment_text)) },
            maxLines = 3,
            modifier = modifier
        )
    }
    val authorField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = author,
            onValueChange = { author = it },
            label = { Text(stringResource(R.string.viewer_comment_author)) },
            singleLine = true,
            modifier = modifier
        )
    }
    val addButton: @Composable () -> Unit = {
        Button(onClick = add, enabled = text.isNotBlank() && cursor != null) {
            Icon(Icons.Default.AddLocationAlt, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.viewer_add_here), maxLines = 1)
        }
    }
    BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (maxWidth >= 720.dp) {
            // W poziomie jeden rząd — inaczej panel zasłania celownik pośrodku.
            Column(Modifier.widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.viewer_aim_comment), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    textField(Modifier.weight(1f))
                    authorField(Modifier.width(200.dp))
                    TextButton(onClick = onDone) { Text(stringResource(R.string.viewer_cancel)) }
                    addButton()
                }
            }
        } else {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.viewer_aim_comment), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                textField(Modifier.fillMaxWidth())
                authorField(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.viewer_cancel)) }
                    Spacer(Modifier.weight(1f))
                    addButton()
                }
            }
        }
    }
}

/**
 * Skala pomiaru: wykryta w pliku, wybrana z listy, wpisana albo policzona
 * z wymiaru, którego długość znamy (kalibracja).
 */
@Composable
internal fun ScaleDialog(state: SheetViewerState, document: SheetDocument, page: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val detected = state.detectedScales[page]
    val current = state.scaleFor(page)
    val lastMeasurement = state.measurements.lastOrNull { it.page == page }
    var custom by remember { mutableStateOf("") }
    var known by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf(current) }

    fun apply(scale: SheetScale) {
        state.scales[page] = scale
        state.lastScale = scale
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_measure_scale)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (detected != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.viewer_detected_scale, detected.label, stringResource(detected.source.label)),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { apply(detected) }) { Text(stringResource(R.string.viewer_use)) }
                    }
                    HorizontalDivider()
                }
                if (document.modelUnits) {
                    Text(stringResource(R.string.viewer_drawing_unit), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, mm) in listOf("mm" to 1.0, "cm" to 10.0, "m" to 1000.0, stringResource(R.string.viewer_unit_inch) to 25.4, stringResource(R.string.viewer_unit_foot) to 304.8)) {
                            FilterChip(
                                selected = choice?.mmPerUnit == mm,
                                onClick = { choice = SheetScale(mm, label, ScaleSource.USER) },
                                label = { Text(label) }
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.viewer_drawing_scale), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (ratio in MeasureFormat.COMMON) {
                            val option = SheetScale.pdfRatio(ratio.toDouble(), ScaleSource.USER)
                            FilterChip(
                                selected = choice?.label == option.label,
                                onClick = { choice = option },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { value ->
                            custom = value.filter { it.isDigit() || it == ',' || it == '.' }
                            custom.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let {
                                choice = SheetScale.pdfRatio(it, ScaleSource.USER)
                            }
                        },
                        prefix = { Text("1 : ") },
                        label = { Text(stringResource(R.string.viewer_other_scale)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                HorizontalDivider()
                Text(stringResource(R.string.viewer_calibration), style = MaterialTheme.typography.labelLarge)
                if (lastMeasurement == null) {
                    Text(
                        stringResource(R.string.viewer_calibration_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = known,
                        onValueChange = { known = it },
                        label = { Text(stringResource(R.string.viewer_calibration_known)) },
                        placeholder = { Text(stringResource(R.string.viewer_calibration_example)) },
                        supportingText = { Text(stringResource(R.string.viewer_calibration_unit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val calibrated = lastMeasurement?.let { m ->
                    MeasureFormat.parseLength(known)?.takeIf { m.lengthUnits > 0 }?.let { realMm ->
                        val mmPerUnit = realMm / m.lengthUnits
                        val label = if (document.modelUnits) {
                            context.getString(R.string.viewer_mm_per_unit, String.format(Locale.getDefault(), "%.4g", mmPerUnit))
                        } else {
                            "1:" + SheetScale.formatRatio(mmPerUnit / SheetScale.MM_PER_POINT)
                        }
                        SheetScale(mmPerUnit, label, ScaleSource.CALIBRATION)
                    }
                }
                (calibrated ?: choice)?.let(::apply) ?: onDismiss()
            }) { Text(stringResource(R.string.viewer_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.viewer_cancel)) } }
    )
}

/** Ustawienia przeglądarki zapamiętane na urządzeniu. */
object ViewerPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("arma_viewer", Context.MODE_PRIVATE)

    /** Podpis komentarzy dodawanych w PDF. */
    fun author(context: Context): String = prefs(context).getString("author", null) ?: android.os.Build.MODEL ?: "ARMA"

    fun setAuthor(context: Context, author: String) {
        if (author.isNotBlank()) prefs(context).edit().putString("author", author.trim()).apply()
    }

    /** Przyciąganie celownika do linii, końców i przecięć rysunku. */
    fun snapping(context: Context): Boolean = prefs(context).getBoolean("snapping", true)

    fun setSnapping(context: Context, on: Boolean) = prefs(context).edit().putBoolean("snapping", on).apply()

    /** Prowadzenie po osi: drugi punkt tylko poziomo albo pionowo od pierwszego. */
    fun axisLock(context: Context): Boolean = prefs(context).getBoolean("axis_lock", false)

    fun setAxisLock(context: Context, on: Boolean) = prefs(context).edit().putBoolean("axis_lock", on).apply()

    /** Przyciąganie tylko do krawędzi kształtów, z pominięciem kreskowań. */
    fun edgesOnly(context: Context): Boolean = prefs(context).getBoolean("edges_only", false)

    fun setEdgesOnly(context: Context, on: Boolean) = prefs(context).edit().putBoolean("edges_only", on).apply()
}
