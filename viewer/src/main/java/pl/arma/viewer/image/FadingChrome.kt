package pl.arma.viewer.image

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * Przyciski nałożone na obraz na całym ekranie: znikają chwilę po otwarciu,
 * żeby nie zasłaniać zdjęcia, a wracają po stuknięciu w ekran.
 */
internal class FadingChrome {
    var visible by mutableStateOf(true)
    var touching by mutableStateOf(false)
    var touchedAt by mutableLongStateOf(0L)
    var alpha = 1f
}

@Composable
internal fun rememberFadingChrome(): FadingChrome {
    val chrome = remember { FadingChrome() }
    LaunchedEffect(chrome.visible, chrome.touching, chrome.touchedAt) {
        if (chrome.visible && !chrome.touching) {
            delay(CHROME_TIMEOUT_MS - CHROME_FADE_MS)
            chrome.visible = false
        }
    }
    val alpha by animateFloatAsState(if (chrome.visible) 1f else 0f, androidx.compose.animation.core.tween(CHROME_FADE_MS.toInt()), label = "chrome")
    chrome.alpha = alpha
    return chrome
}

/**
 * Podgląd dotyku bez zabierania go obrazowi: stuknięcie pokazuje przyciski,
 * a każde dotknięcie widocznych odkłada ich znikanie. Przesuwanie zdjęć
 * i powiększanie dwoma palcami przycisków nie przywołują.
 */
internal fun Modifier.chromeTaps(chrome: FadingChrome): Modifier = pointerInput(chrome) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        chrome.touching = true
        var tap = true
        var last = down.uptimeMillis
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) tap = false
            event.changes.forEach {
                if ((it.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
                last = it.uptimeMillis
            }
        } while (event.changes.any { it.pressed })
        if (tap && last - down.uptimeMillis < TAP_MS) chrome.visible = true
        chrome.touching = false
        chrome.touchedAt = System.nanoTime()
    }
}

/** Od otwarcia pliku (albo ostatniego dotknięcia) do zniknięcia przycisków. */
internal const val CHROME_TIMEOUT_MS = 2_000L
internal const val CHROME_FADE_MS = 300L
private const val TAP_MS = 300L
