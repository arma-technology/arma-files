package pl.arma.viewer.image

import android.graphics.Bitmap
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rachunek powiększenia obrazu dopasowanego do pola (ContentScale.Fit),
 * skalowanego wokół środka pola i przesuniętego o [Offset] w pikselach ekranu.
 */
internal object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 8f

    /** Rozmiar obrazu po dopasowaniu do pola, przy skali 1. */
    fun fitted(content: Size, view: Size): Size {
        if (content.width <= 0f || content.height <= 0f || view.width <= 0f || view.height <= 0f) return view
        val ratio = min(view.width / content.width, view.height / content.height)
        return Size(content.width * ratio, content.height * ratio)
    }

    /** Przesunięcie ograniczone tak, żeby krawędź obrazu nie odjeżdżała od krawędzi pola. */
    fun clamp(offset: Offset, scale: Float, fitted: Size, view: Size): Offset {
        val maxX = max(0f, (fitted.width * scale - view.width) / 2f)
        val maxY = max(0f, (fitted.height * scale - view.height) / 2f)
        // Bez -0f: Offset porównuje bity, a -0f != 0f.
        fun fit(value: Float, limit: Float) = if (limit == 0f) 0f else value.coerceIn(-limit, limit)
        return Offset(fit(offset.x, maxX), fit(offset.y, maxY))
    }

    /**
     * Nowe przesunięcie po zmianie skali z [from] na [to] tak, żeby punkt
     * obrazu pod [focus] (współrzędne pola) został pod palcem.
     */
    fun zoomAround(offset: Offset, from: Float, to: Float, focus: Offset, view: Size): Offset {
        val center = Offset(view.width / 2f, view.height / 2f)
        val ratio = to / from
        return (focus - center) - (focus - center - offset) * ratio
    }
}

/** Skala i przesunięcie obrazu; przyciski powiększenia zmieniają je z zewnątrz. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        internal set
    var offset by mutableStateOf(Offset.Zero)
        internal set
    internal var view = Size.Zero
    internal var content = Size.Zero
    internal var animation: (suspend (Float, Offset) -> Unit)? = null

    internal val fitted: Size get() = ZoomMath.fitted(content, view)

    internal fun target(scale: Float, focus: Offset): Pair<Float, Offset> {
        val next = scale.coerceIn(ZoomMath.MIN_SCALE, ZoomMath.MAX_SCALE)
        if (next <= ZoomMath.MIN_SCALE + .01f) return ZoomMath.MIN_SCALE to Offset.Zero
        val moved = ZoomMath.zoomAround(offset, this.scale, next, focus, view)
        return next to ZoomMath.clamp(moved, next, fitted, view)
    }

    internal fun set(scale: Float, offset: Offset) {
        this.scale = scale
        this.offset = if (scale <= ZoomMath.MIN_SCALE + .01f) Offset.Zero else ZoomMath.clamp(offset, scale, fitted, view)
    }

    /** Powiększenie wokół środka pola, z płynnym przejściem. */
    suspend fun zoomTo(scale: Float) {
        val (s, o) = target(scale, Offset(view.width / 2f, view.height / 2f))
        animation?.invoke(s, o) ?: set(s, o)
    }

    suspend fun reset() = zoomTo(1f)
}

/**
 * Obraz z powiększaniem i przewijaniem do sąsiednich zdjęć.
 *
 * Gest jest liczony w polu, a nie na powiększonej warstwie: na warstwie ruch
 * palca przychodził podzielony przez skalę i przy 4× obraz jechał cztery razy
 * wolniej niż palec. Przewijanie do sąsiedniego zdjęcia przesuwa obraz za
 * palcem; po puszczeniu wyjeżdża on w bok, a następny wjeżdża z drugiej strony.
 */
@Composable
internal fun ZoomableImage(
    image: Bitmap,
    name: String,
    key: Any,
    state: ZoomState,
    canNavigate: Boolean,
    hasNeighbor: (Int) -> Boolean,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // Bez klucza: przeżywa zmianę zdjęcia, żeby nowe wjechało z właściwej strony.
    var slide by remember { mutableFloatStateOf(0f) }
    var enterFrom by remember { mutableFloatStateOf(0f) }
    var motion by remember { mutableStateOf<Job?>(null) }

    state.content = Size(image.width.toFloat(), image.height.toFloat())
    state.animation = { s, o ->
        val fromScale = state.scale
        val fromOffset = state.offset
        animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
            state.set(fromScale + (s - fromScale) * t, fromOffset + (o - fromOffset) * t)
        }
    }

    LaunchedEffect(image) {
        if (enterFrom != 0f) {
            val from = enterFrom
            enterFrom = 0f
            animate(from, 0f, animationSpec = spring(dampingRatio = .9f, stiffness = 420f)) { v, _ -> slide = v }
        } else {
            slide = 0f
        }
    }

    Box(
        modifier
            .onSizeChanged { state.view = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(key, canNavigate) {
                val slop = viewConfiguration.touchSlop
                var lastTap = 0L
                var lastTapAt = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    motion?.cancel()
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var pinched = false
                    var moved = false
                    var swiping = false
                    var travel = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) pinched = true
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        travel += pan
                        if (!moved && travel.getDistance() > slop) moved = true
                        event.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        when {
                            pinched || state.scale > ZoomMath.MIN_SCALE + .01f -> if (moved || pinched) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val focus = if (centroid == Offset.Unspecified) down.position else centroid
                                val next = (state.scale * zoom).coerceIn(ZoomMath.MIN_SCALE, ZoomMath.MAX_SCALE)
                                state.set(next, ZoomMath.zoomAround(state.offset, state.scale, next, focus, state.view) + pan)
                            }
                            moved && canNavigate -> {
                                swiping = true
                                val toward = if (slide + pan.x < 0) 1 else -1
                                // Bez sąsiada obraz stawia opór zamiast odjeżdżać.
                                slide += if (hasNeighbor(toward)) pan.x else pan.x * .3f
                            }
                        }
                        if (moved || pinched) event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })

                    val velocity = tracker.calculateVelocity()
                    val width = state.view.width.coerceAtLeast(1f)
                    when {
                        !moved && !pinched -> {
                            if (down.uptimeMillis - lastTap < 300 && (down.position - lastTapAt).getDistance() < 120f) {
                                lastTap = 0L
                                val goal = if (state.scale > 1.5f) 1f else 2.5f
                                val (s, o) = state.target(goal, down.position)
                                motion = scope.launch { state.animation?.invoke(s, o) }
                            } else {
                                lastTap = down.uptimeMillis
                                lastTapAt = down.position
                            }
                        }
                        swiping -> {
                            val direction = if (slide < 0) 1 else -1
                            val flung = abs(velocity.x) > 900f && (velocity.x < 0) == (direction == 1)
                            val start = slide
                            if (hasNeighbor(direction) && (abs(slide) > width * .22f || flung)) {
                                motion = scope.launch {
                                    animate(start, -direction * width, animationSpec = tween(160)) { v, _ -> slide = v }
                                    enterFrom = direction * width * .35f
                                    onNavigate(direction)
                                }
                            } else {
                                motion = scope.launch {
                                    animate(start, 0f, animationSpec = spring(dampingRatio = .8f, stiffness = 500f)) { v, _ -> slide = v }
                                }
                            }
                        }
                        state.scale > ZoomMath.MIN_SCALE + .01f && !pinched -> {
                            // Rzut powiększonego obrazu: wytraca prędkość, zatrzymuje się na krawędzi.
                            motion = scope.launch {
                                var last = Offset.Zero
                                AnimationState(Offset.VectorConverter, Offset.Zero, Offset(velocity.x, velocity.y))
                                    .animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                                        val step = value - last
                                        last = value
                                        val before = state.offset
                                        state.set(state.scale, state.offset + step)
                                        if (state.offset == before) cancelAnimation()
                                    }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            modifier = imageModifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x + slide
                    translationY = state.offset.y
                    alpha = 1f - (abs(slide) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) * .6f
                }
        )
    }
}

