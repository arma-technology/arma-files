package pl.arma.files

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Akcje na orbicie; kolejność = kolejność wokół palca, zaczynając od góry. */
enum class OrbitAction(val icon: ImageVector) {
    SHARE(Icons.Default.Share),
    SHELF(Icons.Default.Inbox),
    RENAME(Icons.Default.DriveFileRenameOutline),
    DELETE(Icons.Default.Delete),
    SELECT(Icons.Default.CheckCircle),
    DETAILS(Icons.Default.Info);

    @Composable
    fun label(): String = stringResource(
        when (this) {
            SHARE -> R.string.common_share
            SHELF -> R.string.shelf_add
            RENAME -> R.string.common_rename
            DELETE -> R.string.common_delete
            SELECT -> R.string.orbit_select
            DETAILS -> R.string.common_properties
        }
    )
}

/**
 * Stan jednego gestu orbity. Przytrzymanie pliku zaczyna gest, ruch palca
 * wybiera akcję na kole, puszczenie ją wykonuje. Puszczenie bez ruchu to
 * szybki podgląd, a puszczenie nad strefą na dole — dodanie do szuflady.
 */
@Stable
class OrbitController {
    var item by mutableStateOf<FileItem?>(null)
        private set
    var center by mutableStateOf(Offset.Zero)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var selected by mutableStateOf<OrbitAction?>(null)
        private set
    var overShelf by mutableStateOf(false)
        private set

    /** Akcje dostępne dla bieżącego elementu (folderu nie udostępniamy). */
    var actions by mutableStateOf(OrbitAction.entries.toList())
        private set

    // Ustawiane przez nakładkę: promień koła i strefa szuflady w pikselach okna.
    internal var radius = 0f
    internal var deadZone = 0f
    internal var shelfTop = Float.MAX_VALUE
    internal var screen = Offset.Zero
    internal var margin = 0f

    fun begin(item: FileItem, at: Offset) {
        this.item = item
        actions = if (item.isDirectory) OrbitAction.entries.filterNot { it == OrbitAction.SHARE } else OrbitAction.entries.toList()
        // Koło całe na ekranie, także przy krawędzi; palec może być obok środka.
        center = Offset(
            at.x.coerceIn(margin, (screen.x - margin).coerceAtLeast(margin)),
            at.y.coerceIn(margin, (screen.y - margin).coerceAtLeast(margin))
        )
        pointer = at
        selected = null
        overShelf = false
    }

    /** Zwraca true, gdy zmienił się wybór — wtedy krótka wibracja. */
    fun move(to: Offset): Boolean {
        pointer = to
        val before = selected to overShelf
        overShelf = to.y >= shelfTop
        val delta = to - center
        selected = if (overShelf || delta.getDistance() < deadZone) null else {
            val angle = (atan2(delta.y, delta.x) + PI / 2 + 2 * PI) % (2 * PI)
            val sector = (2 * PI / actions.size)
            actions[((angle + sector / 2) / sector).toInt() % actions.size]
        }
        return (selected to overShelf) != before
    }

    /** Kończy gest; zwraca, co zrobić. */
    fun end(): OrbitResult? {
        val target = item ?: return null
        val result = when {
            overShelf -> OrbitResult.Action(target, OrbitAction.SHELF)
            selected != null -> OrbitResult.Action(target, selected!!)
            (pointer - center).getDistance() < deadZone -> OrbitResult.Peek(target)
            else -> null
        }
        item = null
        selected = null
        overShelf = false
        return result
    }

    fun cancel() {
        item = null
        selected = null
        overShelf = false
    }
}

/** Jeden kontroler orbity na okno — gest w liście, nakładka nad całością. */
val LocalOrbit = androidx.compose.runtime.staticCompositionLocalOf { OrbitController() }

sealed interface OrbitResult {
    data class Peek(val item: FileItem) : OrbitResult
    data class Action(val item: FileItem, val action: OrbitAction) : OrbitResult
}

/**
 * Gesty elementu listy: dotknięcie otwiera, przytrzymanie zaczyna orbitę.
 * W trybie zaznaczania przytrzymanie zaznacza, jak dotąd. Dopóki palec nie
 * przytrzyma elementu, lista przewija się normalnie.
 */
fun Modifier.orbitGestures(
    item: FileItem,
    controller: OrbitController,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onResult: (OrbitResult) -> Unit
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Podświetlenie pod palcem jak przy zwykłym kliknięciu.
    val interactions = remember { MutableInteractionSource() }
    this
        .onGloballyPositioned { coordinates = it }
        .indication(interactions, ripple())
        // Czytnik ekranu nie wykona gestu — dostaje te same akcje wprost.
        .semantics {
            onClick { onClick(); true }
            onLongClick { if (selectionMode) onLongClick() else onResult(OrbitResult.Peek(item)); true }
        }
        .pointerInput(item.uri, selectionMode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                interactions.tryEmit(press)
                // null = palec wciąż trzyma po czasie przytrzymania.
                val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var result = 0
                    while (result == 0) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        result = when {
                            change == null -> TAP_CANCEL
                            change.changedToUp() -> if (change.isConsumed) TAP_CANCEL else TAP
                            // Przewijanie listy albo ruch większy niż drgnięcie palca.
                            change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> TAP_CANCEL
                            else -> 0
                        }
                    }
                    result
                }
                interactions.tryEmit(if (outcome == TAP_CANCEL) PressInteraction.Cancel(press) else PressInteraction.Release(press))
                when (outcome) {
                    TAP -> onClick()
                    TAP_CANCEL -> Unit
                    else -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (selectionMode) {
                            onLongClick()
                            return@awaitEachGesture
                        }
                        val origin = coordinates
                        val toWindow = { local: Offset -> origin?.localToWindow(local) ?: local }
                        controller.begin(item, toWindow(down.position))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) { controller.cancel(); break }
                            // Ruch należy do orbity, nie do przewijania listy.
                            change.consume()
                            if (controller.move(toWindow(change.position))) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (change.changedToUp()) {
                                controller.end()?.let(onResult)
                                break
                            }
                        }
                    }
                }
            }
        }
}

private const val TAP = 1
private const val TAP_CANCEL = 2


/**
 * Nakładka orbity na całe okno: przyciemnienie, koło akcji wokół miejsca
 * przytrzymania, podpis wybranej akcji i strefa szuflady przy dolnej krawędzi.
 */
@Composable
fun OrbitOverlay(controller: OrbitController) {
    val item = controller.item
    val appear by animateFloatAsState(if (item != null) 1f else 0f, spring(dampingRatio = .7f, stiffness = 500f), label = "orbit")
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        with(density) {
            controller.radius = 96.dp.toPx()
            controller.deadZone = 36.dp.toPx()
            controller.margin = 128.dp.toPx()
            controller.screen = Offset(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            controller.shelfTop = constraints.maxHeight - 88.dp.toPx()
        }
        if (appear <= 0.01f) return@BoxWithConstraints
        val center = controller.center
        val radius = controller.radius * appear
        val primary = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = appear }) {
            drawRect(Color.Black.copy(alpha = .42f))
            drawCircle(Color.White.copy(alpha = .10f), controller.radius + 40.dp.toPx(), center)
            drawCircle(Color.White.copy(alpha = .35f), controller.deadZone, center, style = Stroke(1.5.dp.toPx()))
            // Nić od środka do palca — widać, w którą akcję się celuje.
            if (!controller.overShelf) drawLine(primary.copy(alpha = .8f), center, controller.pointer, strokeWidth = 3.dp.toPx())
        }
        controller.actions.forEachIndexed { index, action ->
            val angle = -PI / 2 + index * 2 * PI / controller.actions.size
            val position = center + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
            val chosen = controller.selected == action
            val scale by animateFloatAsState(if (chosen) 1.25f else 1f, label = "orbitItem")
            val size = 52.dp
            Surface(
                shape = CircleShape,
                color = if (chosen) primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .offset { IntOffset((position.x - size.toPx() / 2).roundToInt(), (position.y - size.toPx() / 2).roundToInt()) }
                    .size(size)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = appear }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        action.icon,
                        action.label(),
                        tint = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        // Podpis: nazwa pliku albo wybranej akcji, nad kołem.
        val label = when {
            controller.overShelf -> stringResource(R.string.shelf_add)
            controller.selected != null -> controller.selected!!.label()
            else -> item?.name.orEmpty()
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier
                .offset { IntOffset(0, (center.y - controller.radius - 92.dp.toPx()).roundToInt().coerceAtLeast(8.dp.roundToPx())) }
                .align(Alignment.TopCenter)
                .graphicsLayer { alpha = appear }
        ) {
            Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        // Strefa szuflady.
        Surface(
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = if (controller.overShelf) primary else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .92f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(88.dp)
                .graphicsLayer { translationY = (1f - appear) * 88.dp.toPx() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.shelf_drop_zone),
                    color = if (controller.overShelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
