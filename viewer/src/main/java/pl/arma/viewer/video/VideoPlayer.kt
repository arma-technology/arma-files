package pl.arma.viewer.video

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import pl.arma.viewer.R
import pl.arma.viewer.sheet.HiddenSystemBars

/**
 * Film do odtworzenia. [key] identyfikuje plik przy zapamiętywaniu miejsca,
 * na którym przerwano — najlepiej adres z rozmiarem, żeby podmieniony plik
 * zaczynał od początku.
 */
class VideoClip(val uri: Uri, val name: String, val key: String = uri.toString())

/**
 * Odtwarzacz na całym ekranie. Kilka filmów to lista: przyciski
 * „poprzedni / następny” przechodzą między nimi, a po końcu jednego rusza
 * następny. Odtwarzanie wraca w miejscu, w którym je przerwano.
 *
 * Adresy muszą dać się otworzyć przez ContentResolver (file://, content://).
 * Formaty zależą od dekoderów telefonu: H.264, HEVC, VP9 i AAC działają
 * zawsze, dźwięk AC3/DTS w MKV — nie na każdym urządzeniu.
 */
@OptIn(UnstableApi::class)
@Composable
fun FullscreenVideoPlayer(
    playlist: List<VideoClip>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onCurrentChanged: (Int) -> Unit = {}
) {
    if (playlist.isEmpty()) return
    // Kolejka z chwili otwarcia. Lista u gospodarza potrafi się zmienić w trakcie
    // (film dopisany do „Ostatnich”, odświeżony folder) — indeksy odtwarzacza
    // wskazywałyby wtedy inne filmy albo wychodziły poza listę.
    val clips = remember { playlist }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var current by remember { mutableIntStateOf(startIndex.coerceIn(clips.indices)) }
    var error by remember { mutableStateOf<String?>(null) }
    // Stan przycisków odtwarzacza podaje sam PlayerView — założone „widoczne”
    // zostawało na ekranie, gdy przyciski schowały się bez zgłoszenia zmiany.
    var controls by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            // Inna aplikacja grająca dźwięk ścisza się albo pauzuje, a wyjęcie
            // słuchawek zatrzymuje film zamiast puścić go na głośnik.
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            setMediaItems(
                clips.map { MediaItem.Builder().setUri(it.uri).setMediaId(it.key).build() },
                current,
                VideoPositions.get(context, clips[current].key)
            )
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
                // Film z listy, do którego przeszliśmy, też wraca w swoim miejscu.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    val resume = VideoPositions.get(context, clips[current].key)
                    if (resume > 0) player.seekTo(resume)
                }
            }

            // Przejście do innego filmu (przycisk, koniec) — miejsce w poprzednim
            // zapisujemy, zanim indeks wskaże już następny.
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (old.mediaItemIndex != new.mediaItemIndex && old.mediaItemIndex in clips.indices) {
                    val duration = player.getCurrentTimeline().takeIf { !it.isEmpty && old.mediaItemIndex < it.windowCount }
                        ?.getWindow(old.mediaItemIndex, androidx.media3.common.Timeline.Window())?.durationMs ?: C.TIME_UNSET
                    VideoPositions.remember(context, clips[old.mediaItemIndex].key, old.positionMs, duration)
                }
            }

            override fun onPlayerError(failure: PlaybackException) {
                error = failure.errorCodeName
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) VideoPositions.clear(context, clips[current].key)
            }
        }
        player.addListener(listener)
        // Wyjście z aplikacji pauzuje i zapisuje miejsce.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                VideoPositions.remember(context, clips[current].key, player.currentPosition, player.duration)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            VideoPositions.remember(context, clips[current].key, player.currentPosition, player.duration)
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        HiddenSystemBars()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                        keepScreenOn = true
                        // Z animacją przyciski nie znikają, tylko zwijają się do cienkiego
                        // paska postępu i widok zostaje VISIBLE — pasek z nazwą nie wiedziałby,
                        // że ma się schować.
                        setControllerAnimationEnabled(false)
                        setShowNextButton(clips.size > 1)
                        setShowPreviousButton(clips.size > 1)
                        // Pasek z nazwą chowa się razem z przyciskami odtwarzacza.
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            controls = visibility == android.view.View.VISIBLE
                        })
                        // Na starcie przyciski pokazane jawnie, żeby zmiana trafiła do słuchacza.
                        post { showController() }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            AnimatedVisibility(visible = controls || error != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_close), tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(clips[current].name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (clips.size > 1) {
                            Text("${current + 1} / ${clips.size}", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            error?.let {
                Text(
                    stringResource(R.string.viewer_video_error, it),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            }
        }
    }
}

/**
 * Miejsce, na którym przerwano film. Krótkie fragmenty na początku i końcu
 * nie są zapamiętywane — film obejrzany do końca zaczyna się od nowa.
 */
internal object VideoPositions {
    private const val EDGE_MS = 5_000L
    private const val LIMIT = 200

    private fun prefs(context: Context) = context.getSharedPreferences("arma_viewer_video", Context.MODE_PRIVATE)

    fun get(context: Context, key: String): Long = prefs(context).getLong(key, 0L)

    fun remember(context: Context, key: String, position: Long, duration: Long) {
        if (duration <= 0 || position < EDGE_MS || position > duration - EDGE_MS) return clear(context, key)
        val prefs = prefs(context)
        // Ograniczona lista: przy setkach obejrzanych filmów usuwamy wszystko naraz.
        if (prefs.all.size > LIMIT) prefs.edit().clear().apply()
        prefs.edit().putLong(key, position).apply()
    }

    fun clear(context: Context, key: String) = prefs(context).edit().remove(key).apply()
}
