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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    AnimatedContent(
        targetState = layer,
        contentKey = { it.dir },
        transitionSpec = {
            if (targetState.depth >= initialState.depth) {
                // W głąb: nowy arkusz wjeżdża od dołu, poprzedni cofa się w tło.
                (slideInVertically(spring(dampingRatio = .86f, stiffness = 380f)) { it / 3 } + fadeIn(tween(180)))
                    .togetherWith(scaleOut(tween(260), targetScale = .92f) + fadeOut(tween(260)))
            } else {
                // Wyżej: arkusz zjeżdża, rodzic wraca na pierwszy plan.
                (scaleIn(tween(260), initialScale = .92f) + fadeIn(tween(220)))
                    .togetherWith(slideOutVertically(tween(240)) { it / 2 } + fadeOut(tween(240)))
            }
        },
        modifier = modifier,
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
    val threshold = with(LocalDensity.current) { 140.dp.toPx() }
    var drag by remember(layer.dir) { mutableFloatStateOf(0f) }
    val progress = (drag / threshold).coerceIn(0f, 1f)

    fun settle(velocity: Float) {
        if (drag > threshold || (drag > threshold / 4 && velocity > 1800f)) onBack()
        else scope.launch { animate(drag, 0f, animationSpec = spring(stiffness = 500f)) { value, _ -> drag = value } }
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
        // Folder nadrzędny pod spodem: rozmyty, przygaszony, lekko pomniejszony;
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
            }.then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur((10 * (1f - progress)).dp) else Modifier)
        )
        Surface(
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 14.dp,
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
                        .pointerInput(layer.dir) {
                            detectVerticalDragGestures(
                                onDragEnd = { settle(0f) },
                                onDragCancel = { settle(0f) },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    drag = (drag + amount).coerceAtLeast(0f)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = RoundedCornerShape(3.dp), color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.width(40.dp).height(5.dp)) {}
                }
                content()
            }
        }
    }
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
