package pl.arma.files

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import pl.arma.files.ui.theme.LocalArmaStyle

/**
 * Podkład miniatury, która jeszcze się wczytuje: kolor typu pliku z łagodnym
 * pulsowaniem zamiast kółka postępu w każdej ikonie — przy kilkudziesięciu
 * naraz kółka migały. Jasność czytana dopiero przy rysowaniu, więc puls nie
 * przelicza kompozycji co klatkę. „Spokojny ruch” — bez pulsu.
 */
@Composable
internal fun ThumbPlaceholder(tint: Color, icon: ImageVector?, modifier: Modifier = Modifier.fillMaxSize()) {
    val calm = LocalArmaStyle.current.calm
    val pulse = if (calm) null else rememberInfiniteTransition(label = "thumbPulse").animateFloat(
        initialValue = .07f,
        targetValue = .18f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "thumbPulseAlpha"
    )
    Box(
        modifier.drawBehind { drawRect(tint.copy(alpha = pulse?.value ?: .12f)) },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Icon(icon, null, Modifier.fillMaxSize(.4f), tint = tint.copy(alpha = .45f))
    }
}

/**
 * Przezroczystość obrazu, który właśnie doszedł: krótkie wyłonienie się
 * zamiast skoku. Obraz gotowy od pierwszej klatki (z pamięci podręcznej)
 * pokazuje się od razu — przewijanie listy nie może migać.
 */
@Composable
internal fun rememberAppear(ready: Boolean, key: Any?): State<Float> {
    val instant = remember(key) { ready }
    val calm = LocalArmaStyle.current.calm
    return animateFloatAsState(
        if (ready) 1f else 0f,
        if (instant) snap() else tween(if (calm) 90 else 200),
        label = "thumbAppear"
    )
}
