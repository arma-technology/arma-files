package pl.arma.viewer.image

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {
    private val view = Size(1000f, 2000f)

    @Test
    fun `poziome zdjęcie dopasowane do szerokości pola`() {
        assertEquals(Size(1000f, 750f), ZoomMath.fitted(Size(4000f, 3000f), view))
    }

    @Test
    fun `przy skali 1 obrazu nie da się przesunąć`() {
        val fitted = ZoomMath.fitted(Size(4000f, 3000f), view)
        assertEquals(Offset.Zero, ZoomMath.clamp(Offset(300f, -200f), 1f, fitted, view))
    }

    @Test
    fun `granica przesunięcia to wystająca połowa obrazu`() {
        val fitted = ZoomMath.fitted(Size(4000f, 3000f), view)
        // 4×: obraz 4000 × 3000 w polu 1000 × 2000 wystaje o 1500 i 500 z każdej strony.
        assertEquals(Offset(1500f, -500f), ZoomMath.clamp(Offset(9000f, -9000f), 4f, fitted, view))
    }

    @Test
    fun `punkt pod palcem zostaje na miejscu przy powiększaniu`() {
        val focus = Offset(800f, 300f)
        val center = Offset(500f, 1000f)
        val offset = Offset(40f, -60f)
        val from = 2f
        val to = 3f
        // Punkt obrazu pod palcem przed zmianą, we współrzędnych względem środka obrazu.
        val local = (focus - center - offset) / from
        val moved = ZoomMath.zoomAround(offset, from, to, focus, view)
        val after = center + moved + local * to
        assertEquals(focus.x, after.x, .01f)
        assertEquals(focus.y, after.y, .01f)
    }
}
