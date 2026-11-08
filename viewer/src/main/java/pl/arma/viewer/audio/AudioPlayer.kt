package pl.arma.viewer.audio

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import pl.arma.viewer.R
import java.util.Locale

/** Nagranie do odtworzenia; adres musi dać się otworzyć przez ContentResolver (file://, content://). */
class AudioTrack(val uri: Uri, val name: String)

/**
 * Odtwarzacz dźwięku w podglądzie pliku: okładka i opis z metadanych (ID3,
 * Vorbis), pasek z przewijaniem, poprzedni / odtwórz / następny. Kilka nagrań
 * to lista — po końcu jednego rusza następne. Gra, dopóki podgląd jest
 * otwarty; zamknięcie go zatrzymuje dźwięk.
 */
@Composable
fun AudioPlayer(
    playlist: List<AudioTrack>,
    startIndex: Int,
    modifier: Modifier = Modifier,
    onCurrentChanged: (Int) -> Unit = {}
) {
    if (playlist.isEmpty()) return
    // Kolejka z chwili otwarcia — lista u gospodarza może się w tym czasie zmienić.
    val tracks = remember { playlist }
    val context = LocalContext.current
    var current by remember { mutableIntStateOf(startIndex.coerceIn(tracks.indices)) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(C.TIME_UNSET) }
    var metadata by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var error by remember { mutableStateOf<String?>(null) }
    // Pozycja przeciąganego suwaka; null, gdy palec go nie trzyma.
    var dragging by remember { mutableStateOf<Float?>(null) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            // Inne aplikacje ściszają się albo pauzują, a wyjęcie słuchawek zatrzymuje dźwięk.
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setMediaItems(tracks.map { MediaItem.Builder().setUri(it.uri).setMediaId(it.uri.toString()).build() }, current, 0L)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current = player.currentMediaItemIndex
                error = null
                onCurrentChanged(current)
            }
            override fun onMediaMetadataChanged(value: MediaMetadata) { metadata = value }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { duration = player.duration }
            override fun onPlayerError(failure: PlaybackException) { error = failure.errorCodeName }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Pozycja odczytywana kilka razy na sekundę tylko wtedy, gdy coś gra.
    LaunchedEffect(playing) {
        while (true) {
            position = player.currentPosition
            duration = player.duration
            if (!playing) break
            delay(250)
        }
    }

    val artwork: ImageBitmap? = remember(metadata) {
        metadata.artworkData?.let { bytes -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
    }
    val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: tracks[current].name
    val subtitle = listOfNotNull(metadata.artist?.toString(), metadata.albumTitle?.toString()).filter { it.isNotBlank() }.joinToString(" · ")

    Column(
        modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(.72f).aspectRatio(1f)
        ) {
            if (artwork != null) {
                Image(artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.clip(RoundedCornerShape(20.dp)))
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(72.dp))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tracks.size > 1) {
                Text("${current + 1} / ${tracks.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let {
            Text(stringResource(R.string.viewer_audio_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        val known = duration != C.TIME_UNSET && duration > 0
        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = dragging ?: if (known) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { if (known) player.seekTo((it * duration).toLong()) }
                    position = player.currentPosition
                    dragging = null
                },
                enabled = known
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                val shown = dragging?.let { (it * duration).toLong() } ?: position
                Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f))
                Text(if (known) formatTime(duration) else "--:--", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            IconButton(onClick = { player.seekToPreviousMediaItem() }, enabled = tracks.size > 1 && current > 0) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.viewer_audio_previous), Modifier.size(32.dp))
            }
            FilledIconButton(
                onClick = {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    if (player.isPlaying) player.pause() else player.play()
                },
                modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors()
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (playing) R.string.viewer_audio_pause else R.string.viewer_audio_play),
                    Modifier.size(38.dp)
                )
            }
            IconButton(onClick = { player.seekToNextMediaItem() }, enabled = tracks.size > 1 && current < tracks.lastIndex) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.viewer_audio_next), Modifier.size(32.dp))
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
