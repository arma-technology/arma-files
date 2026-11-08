package pl.arma.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import pl.arma.files.archive.ArchiveFormats
import pl.arma.files.archive.ArchivePasswordException
import pl.arma.files.ui.theme.LocalFileTypeColors
import java.util.Locale

/** Akcje pod szybkim podglądem. */
class PeekActions(
    val open: (FileItem) -> Unit,
    val share: (FileItem) -> Unit,
    val shelf: (FileItem) -> Unit,
    val rename: (FileItem) -> Unit,
    val delete: (FileItem) -> Unit
)

/**
 * Szybki podgląd: unoszący się panel z zawartością pliku albo folderu bez
 * otwierania go — zdjęcie z danymi EXIF, film, muzyka z wykresem fali,
 * wnętrze archiwum z wyciąganiem pojedynczych plików, zawartość folderu,
 * pierwsza strona PDF, początek tekstu. Pod spodem pasek akcji.
 */
@Composable
fun QuickPeek(item: FileItem, vm: FileManagerViewModel, actions: PeekActions, onDismiss: () -> Unit, origin: Offset? = null) {
    val appear = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.uri) { appear.animateTo(1f, spring(dampingRatio = .72f, stiffness = 420f)) }
    // Zamknięcie dotknięciem obok albo Wstecz: panel wraca pod palec, zamiast zniknąć w jednej klatce.
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                appear.animateTo(0f, tween(140))
                onDismiss()
            }
        }
    }
    // Środek skalowania w miejscu przytrzymania, przeliczony na układ panelu.
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(pl.arma.files.ui.theme.ArmaStyle.SheetRadius),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .padding(16.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    // Panel „wyrasta” spod palca: skala i przezroczystość ze sprężyną.
                    .onGloballyPositioned { bounds = androidx.compose.ui.geometry.Rect(it.positionOnScreen(), it.size.toSize()) }
                    .graphicsLayer {
                        val t = appear.value
                        // Z miejsca palca panel rośnie wyraźniej niż od środka.
                        val start = if (origin != null) .3f else .86f
                        scaleX = start + (1f - start) * t
                        scaleY = start + (1f - start) * t
                        alpha = t.coerceIn(0f, 1f)
                        val frame = bounds
                        if (origin != null && frame != null && frame.width > 0f && frame.height > 0f) {
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                ((origin.x - frame.left) / frame.width).coerceIn(-.5f, 1.5f),
                                ((origin.y - frame.top) / frame.height).coerceIn(-.5f, 1.5f)
                            )
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FileVisual(item, Modifier.size(40.dp), thumbnail = true, vm = vm, crop = true)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(meta(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        PeekBody(item, vm, onOpenChild = { actions.open(it) }, onPlay = { actions.open(item) })
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PeekAction(Icons.Default.OpenInFull, stringResource(R.string.common_open)) { onDismiss(); actions.open(item) }
                        if (!item.isDirectory) PeekAction(Icons.Default.Share, stringResource(R.string.common_share)) { actions.share(item) }
                        PeekAction(Icons.Default.Inbox, stringResource(R.string.shelf_add)) { onDismiss(); actions.shelf(item) }
                        PeekAction(Icons.Default.DriveFileRenameOutline, stringResource(R.string.common_rename)) { onDismiss(); actions.rename(item) }
                        PeekAction(Icons.Default.Delete, stringResource(R.string.common_delete)) { onDismiss(); actions.delete(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeekAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun PeekBody(item: FileItem, vm: FileManagerViewModel, onOpenChild: (FileItem) -> Unit, onPlay: () -> Unit) {
    val local = LocalFiles.isLocal(item.uri)
    val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    when {
        item.isDirectory -> FolderPeekGrid(item, vm, onOpenChild)
        item.mimeType.startsWith("image/") && !item.mimeType.contains("svg") -> ImagePeek(item, vm, local)
        item.mimeType.startsWith("video/") -> VideoPeek(item, local, onPlay)
        item.mimeType.startsWith("audio/") -> AudioPeek(item, local)
        ArchiveFormats.canBrowse(item.name) -> ArchivePeek(item, vm)
        local && (item.mimeType == "application/pdf" || ext == "pdf") -> PdfPageThumb(item, Modifier.fillMaxWidth().aspectRatio(.75f)) { BigIcon(item) }
        local && ext in setOf("md", "markdown", "mdown", "mkd") -> MarkdownPeek(item)
        local && ext in Syntax.CODE_EXTENSIONS -> TextPeek(item)
        else -> BigIcon(item)
    }
}

@Composable
private fun BigIcon(item: FileItem) {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        FileVisual(item, Modifier.size(110.dp), thumbnail = false)
    }
}

// ---- zdjęcie z EXIF --------------------------------------------------------

@Composable
private fun ImagePeek(item: FileItem, vm: FileManagerViewModel, local: Boolean) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var exif by remember(item.uri) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var location by remember(item.uri) { mutableStateOf<Pair<Double, Double>?>(null) }
    val labels = ExifLabels(
        stringResource(R.string.exif_camera), stringResource(R.string.exif_taken), stringResource(R.string.exif_exposure),
        stringResource(R.string.exif_focal)
    )
    LaunchedEffect(item.uri) {
        bitmap = runCatching { vm.decodeImage(item, targetMaxSide = 1600) }.getOrNull()
        if (local) withContext(Dispatchers.IO) {
            runCatching {
                LocalFiles.open(context, item.uri)?.use { input ->
                    val data = ExifInterface(input)
                    exif = readExif(data, labels)
                    val latLong = FloatArray(2)
                    if (data.getLatLong(latLong)) location = latLong[0].toDouble() to latLong[1].toDouble()
                }
            }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp).clip(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                ?: CircularProgressIndicator()
        }
        if (exif.isNotEmpty() || location != null) Spacer(Modifier.height(10.dp))
        exif.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
        location?.let { (lat, lon) ->
            // Mapa w aplikacji map — bez ściągania kafelków przez nas.
            TextButton(onClick = {
                val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) {
                Icon(Icons.Default.Map, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.exif_show_map) + "  " + "%.5f, %.5f".format(Locale.ROOT, lat, lon))
            }
        }
    }
}

private class ExifLabels(val camera: String, val taken: String, val exposure: String, val focal: String)

private fun readExif(data: ExifInterface, labels: ExifLabels): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    val camera = listOfNotNull(data.getAttribute(ExifInterface.TAG_MAKE), data.getAttribute(ExifInterface.TAG_MODEL))
        .map { it.trim() }.distinct().joinToString(" ")
    if (camera.isNotBlank()) list += labels.camera to camera
    data.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { taken ->
        // „2026:10:20 14:35:12” → „20.10.2026 14:35”.
        val match = Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2})""").find(taken)
        list += labels.taken to (match?.let { "${it.groupValues[3]}.${it.groupValues[2]}.${it.groupValues[1]} ${it.groupValues[4]}:${it.groupValues[5]}" } ?: taken)
    }
    val exposure = buildList {
        data.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
            add(if (it >= 1) "%.1f s".format(it) else "1/${(1 / it).toInt()} s")
        }
        data.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { add("f/%.1f".format(it)) }
        @Suppress("DEPRECATION")
        data.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { add("ISO $it") }
    }
    if (exposure.isNotEmpty()) list += labels.exposure to exposure.joinToString(" · ")
    data.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { list += labels.focal to "%.1f mm".format(it) }
    return list
}

// ---- film -------------------------------------------------------------------

@Composable
private fun VideoPeek(item: FileItem, local: Boolean, onPlay: () -> Unit) {
    val context = LocalContext.current
    var frame by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) { if (local) frame = context.armaApp.videoThumbnails.load(item) }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        frame?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: FileVisual(item, Modifier.size(90.dp), thumbnail = false)
        FilledIconButton(onClick = onPlay, modifier = Modifier.size(64.dp), shape = CircleShape) {
            Icon(Icons.Default.PlayArrow, stringResource(R.string.peek_play), Modifier.size(36.dp))
        }
    }
}

// ---- muzyka: okładka, wykres fali, odtwarzanie ------------------------------

@Composable
private fun AudioPeek(item: FileItem, local: Boolean) {
    val context = LocalContext.current
    var peaks by remember(item.uri) { mutableStateOf<FloatArray?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(playing) {
        while (playing && isActive) {
            val duration = player.duration
            if (duration > 0) progress = player.currentPosition.toFloat() / duration
            delay(100)
        }
    }
    LaunchedEffect(item.uri) {
        if (local) peaks = withContext(Dispatchers.IO) { runCatching { Waveform.peaks(context, item.uri, 96) }.getOrNull() }
    }
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        AudioArtThumb(item, Modifier.size(96.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    // Dotknięcie wykresu przewija w to miejsce.
                    .pointerInput(player) {
                        detectTapGestures { at ->
                            val duration = player.duration
                            if (duration > 0) {
                                player.seekTo((duration * (at.x / size.width)).toLong())
                                progress = at.x / size.width
                            }
                        }
                    }
            ) {
                val bars = peaks ?: FloatArray(96) { .08f }
                val step = size.width / bars.size
                bars.forEachIndexed { i, peak ->
                    val h = (peak.coerceIn(.04f, 1f) * size.height)
                    drawRoundRect(
                        if (i.toFloat() / bars.size <= progress) played else rest,
                        topLeft = Offset(i * step + step * .15f, (size.height - h) / 2),
                        size = Size(step * .7f, h),
                        cornerRadius = CornerRadius(step * .35f)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { if (playing) player.pause() else player.play() }) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (playing) R.string.peek_pause else R.string.peek_play)
                    )
                }
                if (peaks == null && local) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/**
 * Wykres fali: dekodowanie dźwięku do PCM i najgłośniejsza próbka w każdym
 * z [bars] odcinków czasu. Długie nagrania kończymy po kilku sekundach
 * pracy — wykres pokazuje wtedy początek, a reszta zostaje płaska.
 */
internal object Waveform {
    fun peaks(context: Context, uri: Uri, bars: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else return null
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val peaks = FloatArray(bars)
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 6_000
            var inputDone = false
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 5_000)
                if (out >= 0) {
                    val buffer = codec.getOutputBuffer(out)
                    if (buffer != null && info.size > 0) {
                        val bar = ((info.presentationTimeUs.toDouble() / duration) * bars).toInt().coerceIn(0, bars - 1)
                        val shorts = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var max = 0
                        // Co ósma próbka wystarcza do szczytu odcinka i oszczędza czas.
                        var i = 0
                        while (i < shorts.remaining()) {
                            val v = kotlin.math.abs(shorts.get(i).toInt())
                            if (v > max) max = v
                            i += 8
                        }
                        peaks[bar] = maxOf(peaks[bar], max / 32768f)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val loudest = peaks.maxOrNull()?.takeIf { it > 0f } ?: return peaks
            return FloatArray(bars) { peaks[it] / loudest }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}

// ---- archiwum: drzewo z wyciąganiem pojedynczych plików ---------------------

@Composable
private fun ArchivePeek(item: FileItem, vm: FileManagerViewModel) {
    var roots by remember(item.uri) { mutableStateOf<List<FileItem>?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    val expanded = remember(item.uri) { mutableStateMapOf<String, List<FileItem>?>() }
    val passwordText = stringResource(R.string.peek_archive_password)
    LaunchedEffect(item.uri) {
        roots = runCatching { vm.peekArchive(item) }
            .onFailure { error = if (it is ArchivePasswordException) passwordText else it.message }
            .getOrNull()
    }
    val canExtract = vm.canDropHere()
    when {
        error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        roots == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> {
            // Spłaszczone drzewo: wpis i głębokość; rozwinięte foldery wstawiają dzieci pod sobą.
            val rows = ArrayList<Pair<FileItem, Int>>()
            fun addRows(list: List<FileItem>, depth: Int) {
                for (entry in list) {
                    rows += entry to depth
                    if (entry.isDirectory) expanded[entry.uri.toString()]?.let { addRows(it, depth + 1) }
                }
            }
            addRows(roots.orEmpty(), 0)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(rows, key = { it.first.uri.toString() }) { (entry, depth) ->
                    val key = entry.uri.toString()
                    var loading by remember { mutableStateOf(false) }
                    LaunchedEffect(loading) {
                        if (loading) {
                            expanded[key] = runCatching { vm.listArchiveFolder(entry.uri) }.getOrDefault(emptyList())
                            loading = false
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.isDirectory) {
                                if (expanded.containsKey(key)) expanded.remove(key) else loading = true
                            }
                            .padding(start = (depth * 18).dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (entry.isDirectory) {
                            Icon(if (expanded.containsKey(key)) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                        } else Spacer(Modifier.width(18.dp))
                        FileVisual(entry, Modifier.size(26.dp), thumbnail = false)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (!entry.isDirectory) Text(formatSize(entry.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        if (canExtract) {
                            IconButton(onClick = { vm.extractFromArchive(listOf(entry)) }) {
                                Icon(Icons.Default.FileDownload, stringResource(R.string.peek_extract), tint = LocalFileTypeColors.current.archive)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- folder i tekst -----------------------------------------------------------

@Composable
private fun FolderPeekGrid(item: FileItem, vm: FileManagerViewModel, onOpenChild: (FileItem) -> Unit) {
    var children by remember(item.uri) { mutableStateOf<List<FileItem>?>(null) }
    LaunchedEffect(item.uri) { children = runCatching { vm.peekFolder(item) }.getOrDefault(emptyList()) }
    val list = children
    when {
        list == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> Text(stringResource(R.string.empty_folder), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 380.dp)
        ) {
            items(list, key = { it.uri.toString() }) { child ->
                Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onOpenChild(child) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    FileVisual(child, Modifier.fillMaxWidth().aspectRatio(1f), thumbnail = true, vm = vm, crop = true)
                    Text(child.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Początek pliku Markdown, sformatowany jak w podglądzie. */
@Composable
private fun MarkdownPeek(item: FileItem) {
    val context = LocalContext.current
    var blocks by remember(item.uri) { mutableStateOf<List<pl.arma.viewer.text.Markdown.Block>?>(null) }
    LaunchedEffect(item.uri) {
        blocks = withContext(Dispatchers.IO) {
            runCatching {
                LocalFiles.open(context, item.uri)?.use { input ->
                    val bytes = ByteArray(16_000)
                    var read = 0
                    while (read < bytes.size) {
                        val n = input.read(bytes, read, bytes.size - read)
                        if (n < 0) break
                        read += n
                    }
                    // Ucięty w połowie wiersz zostawiamy — reszta i tak jest w pełnym podglądzie.
                    pl.arma.viewer.text.Markdown.parse(String(bytes, 0, read, Charsets.UTF_8).lineSequence().take(120).joinToString("\n"))
                }
            }.getOrNull()
        }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        pl.arma.viewer.text.MarkdownColumn(
            blocks.orEmpty(),
            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp).verticalScroll(rememberScrollState()).padding(12.dp)
        )
    }
}

@Composable
private fun TextPeek(item: FileItem) {
    val context = LocalContext.current
    var text by remember(item.uri) { mutableStateOf<androidx.compose.ui.text.AnnotatedString?>(null) }
    val keyword = MaterialTheme.colorScheme.primary
    val string = LocalFileTypeColors.current.image
    val comment = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)
    val number = LocalFileTypeColors.current.archive
    LaunchedEffect(item.uri) {
        val raw = withContext(Dispatchers.IO) {
            runCatching {
                LocalFiles.open(context, item.uri)?.use { input ->
                    val bytes = ByteArray(8_000)
                    String(bytes, 0, input.read(bytes).coerceAtLeast(0), Charsets.UTF_8)
                }
            }.getOrNull()
        } ?: return@LaunchedEffect
        text = Syntax.highlight(raw.lineSequence().take(60).joinToString("\n"), keyword, string, comment, number)
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text ?: androidx.compose.ui.text.AnnotatedString(""),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp).verticalScroll(rememberScrollState()).padding(10.dp)
        )
    }
}
