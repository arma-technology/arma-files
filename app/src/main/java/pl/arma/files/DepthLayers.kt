package pl.arma.files

import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.arma.files.ui.theme.ArmaStyle
import pl.arma.files.ui.theme.LocalArmaStyle
import androidx.compose.ui.draw.drawBehind

/** Jeden poziom zagłębienia: folder, jego widoczne pliki i folder pod spodem. */
class DepthLayer(val dir: Uri?, val files: List<FileItem>, val depth: Int, val parent: Uri?)

/**
 * Nawigacja „w głąb”: bieżący folder to arkusz nad rozmytym folderem
 * nadrzędnym. Wejście do podfolderu odsuwa rodzica w tło i wysuwa nowy
 * arkusz; powrót odwraca ruch. Przesunięcie arkusza w dół (gdy lista stoi
 * na górze) albo pociągnięcie za uchwyt zamyka poziom.
 *
 * Rozmycie działa od Androida 12; na starszych tło jest tylko przyciemnione
 * i pomniejszone. Pod spodem zawsze jeden poziom — stos pięciu rozmytych
 * warstw byłby nieczytelny i kosztowny.
 */
@Composable
fun DepthStack(
    layer: DepthLayer,
    behind: (Uri?) -> List<FileItem>?,
    vm: FileManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (DepthLayer) -> Unit
) {
    val style = LocalArmaStyle.current
    // „Spokojny ruch”: krótsze przejścia bez sprężyny.
    val slow = if (style.calm) 120 else 260
    AnimatedContent(
        targetState = layer,
        contentKey = { it.dir },
        transitionSpec = {
            if (style.calm) {
                fadeIn(tween(slow)).togetherWith(fadeOut(tween(slow)))
            } else if (targetState.depth >= initialState.depth) {
                // W głąb: nowy arkusz wjeżdża od dołu, poprzedni cofa się w tło.
                (slideInVertically(spring(dampingRatio = .86f, stiffness = 380f)) { it / 3 } + fadeIn(tween(180)))
                    .togetherWith(scaleOut(tween(slow), targetScale = .92f) + fadeOut(tween(slow)))
            } else {
                // Wyżej: arkusz zjeżdża, rodzic wraca na pierwszy plan.
                (scaleIn(tween(slow), initialScale = .92f) + fadeIn(tween(220)))
                    .togetherWith(slideOutVertically(tween(240)) { it / 2 } + fadeOut(tween(240)))
            }
        },
        modifier = modifier.then(if (style.night) Modifier.drawBehind { accentGlow(style.accent) } else Modifier),
        label = "depth"
    ) { shown ->
        DepthSheet(shown, behind(shown.parent), vm, onBack) { content(shown) }
    }
}

@Composable
private fun DepthSheet(layer: DepthLayer, behind: List<FileItem>?, vm: FileManagerViewModel, onBack: () -> Unit, content: @Composable () -> Unit) {
    val nested = layer.depth > 0
    if (!nested) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    val style = LocalArmaStyle.current
    val threshold = with(LocalDensity.current) { 140.dp.toPx() }
    var drag by remember(layer.dir) { mutableFloatStateOf(0f) }
    val progress = (drag / threshold).coerceIn(0f, 1f)

    // Powrót arkusza na miejsce; nowe złapanie go przerywa, zamiast walczyć z palcem.
    var settling by remember(layer.dir) { mutableStateOf<Job?>(null) }
    fun settle(velocity: Float) {
        settling?.cancel()
        if (drag > threshold || (drag > threshold / 4 && velocity > 1800f)) onBack()
        else settling = scope.launch {
            animate(drag, 0f, initialVelocity = velocity, animationSpec = spring(stiffness = 500f)) { value, _ -> drag = value }
        }
    }

    val connection = remember(layer.dir) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Arkusz odciągnięty w dół wraca, zanim lista znów zacznie się przewijać.
                if (drag > 0f && available.y < 0f) {
                    val used = maxOf(available.y, -drag)
                    drag += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Lista na samej górze, palec dalej w dół: ciągnie arkusz.
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    settling?.cancel()
                    drag += available.y * .55f
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (style.night) {
            // Noc: folder nadrzędny pod spodem rozmyty, przygaszony, lekko pomniejszony;
            // im mocniej ciągniesz arkusz w dół, tym wyraźniej wraca.
            BehindLayer(
                behind.orEmpty(),
                vm,
                Modifier.graphicsLayer {
                    val scale = .93f + .07f * progress
                    scaleX = scale
                    scaleY = scale
                    alpha = .45f + .5f * progress
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 0f)
                }.then(
                    if (style.blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur((10 * (1f - progress)).dp) else Modifier
                )
            )
        } else {
            // Papier: głębia jako stos kartek — krawędzie poprzednich poziomów nad arkuszem.
            PaperStack(Modifier.graphicsLayer { translationY = drag * .25f })
        }
        Surface(
            shape = RoundedCornerShape(topStart = ArmaStyle.SheetRadius, topEnd = ArmaStyle.SheetRadius),
            color = style.sheet,
            border = androidx.compose.foundation.BorderStroke(1.dp, style.hairline),
            shadowElevation = if (style.night) 14.dp else 4.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 40.dp)
                .graphicsLayer { translationY = drag }
                .nestedScroll(connection)
        ) {
            Column {
                // Uchwyt: pociągnięcie w dół zamyka poziom także przy pustym folderze.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        // draggable zamiast surowego gestu: puszczenie podaje prędkość,
                        // więc szybkie machnięcie w dół zamyka poziom jak na liście.
                        .draggable(
                            state = rememberDraggableState { amount -> drag = (drag + amount).coerceAtLeast(0f) },
                            orientation = Orientation.Vertical,
                            onDragStarted = { settling?.cancel() },
                            onDragStopped = { velocity -> settle(velocity) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = RoundedCornerShape(2.dp), color = style.hairline, modifier = Modifier.width(36.dp).height(4.dp)) {}
                }
                content()
            }
        }
    }
}

/** Dwie krawędzie kartek nad arkuszem — poziomy wyżej w motywie Papier. */
@Composable
private fun PaperStack(modifier: Modifier) {
    val style = LocalArmaStyle.current
    val shape = RoundedCornerShape(topStart = ArmaStyle.SheetRadius, topEnd = ArmaStyle.SheetRadius)
    Box(modifier.fillMaxWidth()) {
        Surface(
            shape = shape,
            color = style.ground.copy(alpha = 1f).let { androidx.compose.ui.graphics.lerp(it, style.sheet, .45f) },
            border = androidx.compose.foundation.BorderStroke(1.dp, style.hairline),
            modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 14.dp).fillMaxWidth().height(60.dp)
        ) {}
        Surface(
            shape = shape,
            color = androidx.compose.ui.graphics.lerp(style.ground, style.sheet, .75f),
            border = androidx.compose.foundation.BorderStroke(1.dp, style.hairline),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp).fillMaxWidth().height(60.dp)
        ) {}
    }
}

/** Poświata akcentu w lewym górnym rogu — znak Nocy. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.accentGlow(accent: androidx.compose.ui.graphics.Color) {
    val radius = size.minDimension * .75f
    drawCircle(
        brush = androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(accent.copy(alpha = .16f), accent.copy(alpha = 0f)),
            center = Offset(size.width * .1f, 0f),
            radius = radius
        ),
        radius = radius,
        center = Offset(size.width * .1f, 0f)
    )
}

/** Uproszczony podgląd folderu nadrzędnego — tylko do patrzenia, nie do dotykania. */
@Composable
private fun BehindLayer(files: List<FileItem>, vm: FileManagerViewModel, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(top = 4.dp)) {
        files.take(14).forEach { item ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                FileVisual(item, Modifier.size(36.dp), thumbnail = true, vm = vm, crop = true)
                Spacer(Modifier.width(12.dp))
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
