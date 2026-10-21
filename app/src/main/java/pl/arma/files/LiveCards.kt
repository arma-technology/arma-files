package pl.arma.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import pl.arma.files.ui.theme.LocalFileTypeColors
import java.io.File
import java.io.InputStream

/**
 * Pliki, które naprawdę leżą w telefonie. content:// bywa folderem z chmury
 * (Dropbox, Dysk Google przez SAF) — tam każdy podgląd ściągałby cały plik,
 * więc karty i miniatury robimy tylko z pamięci telefonu i karty.
 */
object LocalFiles {
    private val LOCAL_PROVIDERS = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
        MediaStore.AUTHORITY
    )

    fun isLocal(uri: Uri): Boolean = uri.scheme == "file" || (uri.scheme == "content" && uri.authority in LOCAL_PROVIDERS)

    fun open(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") uri.path?.let { File(it).inputStream() } else context.contentResolver.openInputStream(uri)

    fun descriptor(context: Context, uri: Uri): ParcelFileDescriptor? =
        if (uri.scheme == "file") uri.path?.let { ParcelFileDescriptor.open(File(it), ParcelFileDescriptor.MODE_READ_ONLY) }
        else context.contentResolver.openFileDescriptor(uri, "r")
}

/** Co pokazać na karcie folderu: kilka najnowszych zdjęć/filmów, okładki albo typy dokumentów. */
internal class FolderPeek(val count: Int, val media: List<FileItem>, val audio: List<FileItem>, val kinds: List<FileItem>)

internal object FolderPeeks {
    private val cache = LruCache<String, FolderPeek>(400)
    // Dwa foldery naraz — przewijanie długiej listy folderów nie zasypuje dysku.
    private val gate = Semaphore(2)

    fun key(item: FileItem) = "${item.uri}|${item.lastModified}"

    fun cached(item: FileItem): FolderPeek? = cache.get(key(item))

    suspend fun load(item: FileItem, vm: FileManagerViewModel): FolderPeek? {
        cached(item)?.let { return it }
        return gate.withPermit {
            runCatching {
                val children = vm.peekFolder(item)
                val files = children.filterNot { it.isDirectory }
                var media = files.filter { isVisualMedia(it) }.take(4)
                // Folder z samymi podfolderami („Zdjęcia” → „2026”): kolaż z ich wnętrza,
                // najwyżej dwa poziomy niżej i trzy podfoldery — karta ma powiedzieć, co tam jest.
                if (media.isEmpty() && files.isEmpty()) {
                    media = children.filter { it.isDirectory }.take(3).flatMap { sub ->
                        val inner = runCatching { vm.peekFolder(sub) }.getOrDefault(emptyList())
                        inner.filter { isVisualMedia(it) }.ifEmpty {
                            inner.filter { it.isDirectory }.take(2).flatMap { deeper ->
                                runCatching { vm.peekFolder(deeper) }.getOrDefault(emptyList()).filter { isVisualMedia(it) }
                            }
                        }
                    }.sortedByDescending { it.lastModified }.take(4)
                }
                FolderPeek(
                    count = children.size,
                    media = media,
                    audio = files.filter { it.mimeType.startsWith("audio/") }.take(4),
                    kinds = files.distinctBy { it.name.substringAfterLast('.', "").lowercase() }.take(3)
                )
            }.getOrNull()?.also { cache.put(key(item), it) }
        }
    }

    private fun isVisualMedia(item: FileItem) =
        (item.mimeType.startsWith("image/") && !item.mimeType.contains("svg")) || item.mimeType.startsWith("video/")
}

/**
 * Karta folderu z podglądem zawartości. Kolaż najnowszych zdjęć i filmów
 * delikatnie przesuwa się przy przewijaniu listy (paralaksa); folder z muzyką
 * pokazuje okładki, z dokumentami — ikony typów. Zdalne foldery zostają przy
 * zwykłej ikonie: zajrzenie do każdego to ruch sieciowy.
 */
@Composable
internal fun FolderLiveCard(item: FileItem, vm: FileManagerViewModel, modifier: Modifier, compact: Boolean) {
    var peek by remember(item.uri, item.lastModified) { mutableStateOf(FolderPeeks.cached(item)) }
    LaunchedEffect(item.uri, item.lastModified) {
        if (peek == null) peek = FolderPeeks.load(item, vm)
    }
    val folderColor = LocalFileTypeColors.current.folder
    val shown = peek
    Surface(modifier = modifier, shape = RoundedCornerShape(if (compact) 10.dp else 14.dp), color = folderColor.copy(alpha = .16f)) {
        Box(Modifier.fillMaxSize()) {
            when {
                // „Spokojny ruch”: kolaż bez paralaksy.
                shown != null && shown.media.isNotEmpty() && pl.arma.files.ui.theme.LocalArmaStyle.current.calm ->
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) {
                        Collage(shown.media) { child, m -> FileVisual(child, m, thumbnail = true, vm = vm, crop = true) }
                    }
                shown != null && shown.media.isNotEmpty() -> ParallaxCollage(shown.media, vm)
                shown != null && shown.audio.isNotEmpty() -> Collage(shown.audio) { child, m -> AudioArtThumb(child, m) }
                shown != null && shown.kinds.isNotEmpty() && !compact -> Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    shown.kinds.forEach { kind -> FileVisual(kind, Modifier.size(30.dp), thumbnail = false) }
                }
                else -> Icon(Icons.Default.Folder, null, Modifier.align(Alignment.Center).fillMaxSize(if (compact) .7f else .5f), tint = folderColor)
            }
            // Znak folderu i liczba elementów — karta nie może udawać zdjęcia.
            if (shown != null && (shown.media.isNotEmpty() || shown.audio.isNotEmpty() || !compact)) {
                Surface(
                    shape = RoundedCornerShape(topEnd = 8.dp, bottomStart = if (compact) 10.dp else 14.dp),
                    color = folderColor,
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    Row(Modifier.padding(horizontal = if (compact) 3.dp else 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, Modifier.size(if (compact) 9.dp else 12.dp), tint = Color.White)
                        if (!compact) Text(" ${shown.count}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParallaxCollage(media: List<FileItem>, vm: FileManagerViewModel) {
    var shift by remember { mutableFloatStateOf(0f) }
    val screen = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val range = with(LocalDensity.current) { 6.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .onGloballyPositioned { coordinates ->
                // Od -1 (góra ekranu) do 1 (dół): zawartość przesuwa się wolniej niż lista.
                shift = ((coordinates.positionInWindow().y / screen.coerceAtLeast(1f)) * 2f - 1f).coerceIn(-1f, 1f)
            }
            .graphicsLayer {
                scaleX = 1.12f
                scaleY = 1.12f
                translationY = -shift * range
            }
    ) {
        Collage(media) { child, m -> FileVisual(child, m, thumbnail = true, vm = vm, crop = true) }
    }
}

/** 1 = całość, 2 = obok siebie, 3 = jeden duży i dwa małe, 4 = 2×2. */
@Composable
private fun Collage(items: List<FileItem>, cell: @Composable (FileItem, Modifier) -> Unit) {
    val gap = 1.dp
    when (items.size) {
        1 -> cell(items[0], Modifier.fillMaxSize())
        2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            items.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
        }
        3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            cell(items[0], Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                cell(items[1], Modifier.weight(1f).fillMaxWidth())
                cell(items[2], Modifier.weight(1f).fillMaxWidth())
            }
        }
        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (rowItems in items.take(4).chunked(2)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

// ---- miniatury plików, których zwykła ikona mówi za mało -------------------

private val thumbGate = Semaphore(2)

/** Bitmapa z pamięci miniatur albo policzona w tle i zapamiętana. */
@Composable
private fun rememberThumbnail(key: String, compute: suspend () -> Bitmap?): Bitmap? {
    var bitmap by remember(key) { mutableStateOf(ThumbnailCache.get(key)) }
    LaunchedEffect(key) {
        if (bitmap != null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            thumbGate.withPermit { runCatching { compute() }.getOrNull() }
        }?.also { ThumbnailCache.put(key, it) }
    }
    return bitmap
}

/** Pierwsza strona PDF zamiast ikony aplikacji. */
@Composable
internal fun PdfPageThumb(item: FileItem, modifier: Modifier, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val bitmap = rememberThumbnail("pdf|${item.uri}|${item.lastModified}|${item.size}") {
        LocalFiles.descriptor(context, item.uri)?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val scale = 360f / maxOf(page.width, page.height)
                    Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
    ThumbImage(bitmap, modifier, fallback)
}

/** Okładka z tagów pliku audio. */
@Composable
internal fun AudioArtThumb(item: FileItem, modifier: Modifier, fallback: @Composable () -> Unit = { NoteIcon() }) {
    val context = LocalContext.current
    val bitmap = rememberThumbnail("art|${item.uri}|${item.lastModified}") { AudioArt.load(context, item.uri, 320) }
    ThumbImage(bitmap, modifier, fallback)
}

@Composable
private fun NoteIcon() {
    Box(Modifier.fillMaxSize().background(LocalFileTypeColors.current.audio.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(.5f), tint = LocalFileTypeColors.current.audio)
    }
}

@Composable
private fun ThumbImage(bitmap: Bitmap?, modifier: Modifier, fallback: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(10.dp))) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else fallback()
    }
}

internal object AudioArt {
    fun load(context: Context, uri: Uri, side: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * Pierwsze linie kodu z kolorowaniem składni — tylko na kafelkach siatki;
 * w wierszu listy 40 dp tekstu i tak nie da się przeczytać.
 */
@Composable
internal fun CodeThumb(item: FileItem, modifier: Modifier) {
    val context = LocalContext.current
    var lines by remember(item.uri, item.lastModified) { mutableStateOf<AnnotatedString?>(null) }
    val keyword = MaterialTheme.colorScheme.primary
    val string = LocalFileTypeColors.current.image
    val comment = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)
    val number = LocalFileTypeColors.current.archive
    LaunchedEffect(item.uri, item.lastModified) {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                LocalFiles.open(context, item.uri)?.use { input ->
                    val bytes = ByteArray(1600)
                    val read = input.read(bytes).coerceAtLeast(0)
                    String(bytes, 0, read, Charsets.UTF_8)
                }
            }.getOrNull()
        } ?: return@LaunchedEffect
        lines = Syntax.highlight(text.lineSequence().take(14).joinToString("\n") { it.take(60) }, keyword, string, comment, number)
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        lines?.let {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 6.5.sp, lineHeight = 8.sp, modifier = Modifier.padding(5.dp), softWrap = false)
        }
    }
}

internal object Syntax {
    val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml", "html", "css", "sh", "c", "cpp", "h",
        "hpp", "go", "rs", "yaml", "yml", "sql", "gradle", "md", "txt", "ini", "toml", "properties", "php", "rb", "swift", "dart"
    )

    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "if", "else", "when", "for", "while", "return", "import", "package",
        "def", "from", "public", "private", "static", "void", "int", "const", "let", "function", "true", "false", "null",
        "None", "True", "False", "new", "this", "self", "interface", "struct", "enum", "async", "await", "override"
    )
    private val TOKEN = Regex("""(//[^\n]*|#[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_]\w*\b)""", RegexOption.DOT_MATCHES_ALL)

    fun highlight(text: String, keyword: Color, string: Color, comment: Color, number: Color): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (match in TOKEN.findAll(text)) {
            append(text, last, match.range.first)
            val token = match.value
            val style = when {
                token.startsWith("//") || token.startsWith("#") || token.startsWith("/*") -> SpanStyle(color = comment)
                token.startsWith("\"") || token.startsWith("'") -> SpanStyle(color = string)
                token[0].isDigit() -> SpanStyle(color = number)
                token in KEYWORDS -> SpanStyle(color = keyword, fontWeight = FontWeight.Bold)
                else -> null
            }
            if (style != null) withStyle(style) { append(token) } else append(token)
            last = match.range.last + 1
        }
        append(text, last, text.length)
    }
}
