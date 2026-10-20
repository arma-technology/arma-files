package pl.arma.viewer.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.arma.viewer.R
import pl.arma.viewer.sheet.HiddenSystemBars
import java.io.File

/**
 * Dekodowanie zdjęć z ograniczeniem rozmiaru: zdjęcie z aparatu (50 Mpx)
 * zdekodowane w całości zajęłoby setki MB, a ekran i tak pokaże mniej.
 */
object ImageDecoding {
    /** Bitmapa z pliku, dłuższy bok najwyżej [maxSide] pikseli. */
    suspend fun decode(file: File, maxSide: Int = 4096): Bitmap = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val longest = maxOf(width, height)
                if (longest > maxSide) {
                    val ratio = maxSide.toFloat() / longest
                    decoder.setTargetSize((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
                }
                // Bitmapa programowa: sprzętowej nie da się rysować na Canvas ani skalować.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalArgumentException("cannot decode ${file.name}")
        }
    }

    /** Czy to SVG — te rysuje [SvgImageViewer], nie [ImageViewer]. */
    fun isSvg(name: String, mimeType: String = ""): Boolean =
        name.endsWith(".svg", ignoreCase = true) || mimeType.contains("svg")
}

/** Obraz z pliku lokalnego. */
@Composable
fun ImageViewer(
    file: File,
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit = {},
    canNavigate: Boolean = false,
    positionLabel: String = ""
) = ImageViewer(
    key = file.path to file.lastModified(),
    name = file.name,
    load = { ImageDecoding.decode(file) },
    modifier = modifier,
    onNavigate = onNavigate,
    canNavigate = canNavigate,
    positionLabel = positionLabel
)

/**
 * Obraz z powiększaniem (szczypanie, przyciski), przesuwaniem i przewijaniem
 * do sąsiednich zdjęć gestem w bok; przycisk pełnego ekranu otwiera
 * [FullscreenImageViewer]. [load] dekoduje obraz (np. [ImageDecoding.decode]),
 * a [key] mówi, kiedy dekodować ponownie.
 */
@Composable
fun ImageViewer(
    key: Any?,
    name: String,
    load: suspend () -> Bitmap,
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit = {},
    canNavigate: Boolean = false,
    positionLabel: String = ""
) {
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offsetX by remember(key) { mutableFloatStateOf(0f) }
    var offsetY by remember(key) { mutableFloatStateOf(0f) }
    var fullscreen by remember(key) { mutableStateOf(false) }
    val openFailed = stringResource(R.string.viewer_image_open_failed)

    LaunchedEffect(key) {
        error = null
        bitmap = runCatching { load() }
            .onFailure { error = it.message ?: openFailed }
            .getOrNull()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            // Obraz pośrodku pola: poziome zdjęcie przyklejone do górnej krawędzi
            // zostawiało pusty pas pod spodem i wyglądało na źle ułożone.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let { image ->
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                            .imageGestures(
                                key = key ?: name,
                                canNavigate = canNavigate,
                                scale = { scale },
                                onTransform = { pan, zoom ->
                                    val next = (scale * zoom).coerceIn(1f, 8f)
                                    scale = next
                                    if (next > 1.01f) {
                                        val shift = 2200f * (next - 1f)
                                        offsetX = (offsetX + pan.x).coerceIn(-shift, shift)
                                        offsetY = (offsetY + pan.y).coerceIn(-shift, shift)
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                },
                                onNavigate = onNavigate
                            )
                    )
                }
                if (bitmap == null && error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error?.let {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.BrokenImage, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.error)
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = {
                    scale = (scale - .25f).coerceAtLeast(1f)
                    if (scale <= 1.01f) { offsetX = 0f; offsetY = 0f }
                }) { Icon(Icons.Default.ZoomOut, stringResource(R.string.viewer_image_zoom_out)) }
                Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { scale = (scale + .25f).coerceAtMost(8f) }) { Icon(Icons.Default.ZoomIn, stringResource(R.string.viewer_image_zoom_in)) }
                IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) { Icon(Icons.Default.FitScreen, stringResource(R.string.viewer_image_zoom_fit)) }
                IconButton(onClick = { fullscreen = true }, enabled = bitmap != null) {
                    Icon(Icons.Default.Fullscreen, stringResource(R.string.viewer_image_fullscreen))
                }
            }
        }
    }

    if (fullscreen) {
        bitmap?.let { image ->
            FullscreenImageViewer(
                image = image,
                name = name,
                onDismiss = { fullscreen = false },
                onNavigate = onNavigate,
                canNavigate = canNavigate,
                position = positionLabel
            )
        }
    }
}

/**
 * Obraz wektorowy SVG (przez Coil). [model] to plik, [Uri] albo adres —
 * cokolwiek przyjmuje Coil; null pokazuje puste pole do czasu przygotowania.
 */
@Composable
fun SvgImageViewer(model: Any?, name: String, modifier: Modifier = Modifier) {
    val uri = model
    var fullscreen by remember(model) { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = uri,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = { fullscreen = true }, enabled = uri != null) {
                    Icon(Icons.Default.Fullscreen, stringResource(R.string.viewer_image_fullscreen))
                }
            }
        }
    }

    if (fullscreen) {
        uri?.let { imageUri ->
            FullscreenSvgViewer(model = imageUri, name = name, onDismiss = { fullscreen = false })
        }
    }
}

/**
 * Powiększanie, przesuwanie i przewijanie zdjęć w jednym geście. Dwa osobne
 * detektory się wykluczały: szczypanie zjadało ruch palca, zanim przewijanie
 * go zobaczyło, i zdjęcia nie dało się zmienić.
 */
internal fun Modifier.imageGestures(
    key: Any,
    canNavigate: Boolean,
    scale: () -> Float,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    onNavigate: (Int) -> Unit
): Modifier = pointerInput(key, canNavigate) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var dragged = 0f
        var pinched = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) pinched = true
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (pinched || scale() > 1.01f) onTransform(pan, zoom) else dragged += pan.x
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
        if (canNavigate && !pinched && scale() <= 1.01f && kotlin.math.abs(dragged) > 120f) {
            onNavigate(if (dragged < 0) 1 else -1)
        }
    }
}

/** Obraz na całym ekranie, z narzędziami na nim i przewijaniem do sąsiednich zdjęć. */
@Composable
fun FullscreenImageViewer(
    image: Bitmap?,
    name: String,
    onDismiss: () -> Unit,
    onNavigate: (Int) -> Unit = {},
    canNavigate: Boolean = false,
    position: String = "",
    error: String? = null
) {
    var scale by remember(name) { mutableFloatStateOf(1f) }
    var offsetX by remember(name) { mutableFloatStateOf(0f) }
    var offsetY by remember(name) { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        HiddenSystemBars()
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (image == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (error == null) CircularProgressIndicator() else Text(error, color = Color.White)
                    }
                } else Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                        .imageGestures(
                            key = name,
                            canNavigate = canNavigate,
                            scale = { scale },
                            onTransform = { pan, zoom ->
                                val next = (scale * zoom).coerceIn(1f, 8f)
                                scale = next
                                if (next > 1.01f) {
                                    val shift = 3200f * (next - 1f)
                                    offsetX = (offsetX + pan.x).coerceIn(-shift, shift)
                                    offsetY = (offsetY + pan.y).coerceIn(-shift, shift)
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            },
                            onNavigate = onNavigate
                        )
                )

                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = .58f)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.viewer_image_fullscreen_close), tint = Color.White)
                    }
                }

                if (position.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(12.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Black.copy(alpha = .58f)
                    ) {
                        Text(
                            position,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = .62f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                            scale = (scale - .25f).coerceAtLeast(1f)
                            if (scale <= 1.01f) { offsetX = 0f; offsetY = 0f }
                        }) { Icon(Icons.Default.ZoomOut, stringResource(R.string.viewer_image_zoom_out), tint = Color.White) }
                        Text("${(scale * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { scale = (scale + .25f).coerceAtMost(8f) }) {
                            Icon(Icons.Default.ZoomIn, stringResource(R.string.viewer_image_zoom_in), tint = Color.White)
                        }
                        IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                            Icon(Icons.Default.FitScreen, stringResource(R.string.viewer_image_zoom_fit), tint = Color.White)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.FullscreenExit, stringResource(R.string.viewer_image_fullscreen_exit), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FullscreenSvgViewer(model: Any, name: String, onDismiss: () -> Unit) {
    var scale by remember(name) { mutableFloatStateOf(1f) }
    var offsetX by remember(name) { mutableFloatStateOf(0f) }
    var offsetY by remember(name) { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        HiddenSystemBars()
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(
                    model = model,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                        .pointerInput(name) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val next = (scale * zoom).coerceIn(1f, 8f)
                                scale = next
                                if (next > 1.01f) {
                                    val shift = 3200f * (next - 1f)
                                    offsetX = (offsetX + pan.x).coerceIn(-shift, shift)
                                    offsetY = (offsetY + pan.y).coerceIn(-shift, shift)
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        }
                )

                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = .58f)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.viewer_image_fullscreen_close), tint = Color.White)
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = .62f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                            scale = (scale - .25f).coerceAtLeast(1f)
                            if (scale <= 1.01f) { offsetX = 0f; offsetY = 0f }
                        }) { Icon(Icons.Default.ZoomOut, stringResource(R.string.viewer_image_zoom_out), tint = Color.White) }
                        Text("${(scale * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { scale = (scale + .25f).coerceAtMost(8f) }) {
                            Icon(Icons.Default.ZoomIn, stringResource(R.string.viewer_image_zoom_in), tint = Color.White)
                        }
                        IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                            Icon(Icons.Default.FitScreen, stringResource(R.string.viewer_image_zoom_fit), tint = Color.White)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.FullscreenExit, stringResource(R.string.viewer_image_fullscreen_exit), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
