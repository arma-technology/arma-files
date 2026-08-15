package pl.arma.files.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReorderTest {

    private val list = listOf("A", "B", "C", "D")

    @Test
    fun `przeniesienie w prawo wstawia za elementami, ktore sie przesunely`() {
        assertEquals(listOf("B", "C", "A", "D"), Reorder.move(list, 0, 2))
    }

    @Test
    fun `przeniesienie w lewo wstawia przed elementem docelowym`() {
        assertEquals(listOf("A", "D", "B", "C"), Reorder.move(list, 3, 1))
    }

    @Test
    fun `przeniesienie na koniec i na poczatek`() {
        assertEquals(listOf("B", "C", "D", "A"), Reorder.move(list, 0, 3))
        assertEquals(listOf("D", "A", "B", "C"), Reorder.move(list, 3, 0))
    }

    @Test
    fun `ta sama pozycja zwraca te sama liste bez kopiowania`() {
        assertSame(list, Reorder.move(list, 2, 2))
    }

    @Test
    fun `indeksy poza zakresem nic nie zmieniaja`() {
        assertSame(list, Reorder.move(list, -1, 2))
        assertSame(list, Reorder.move(list, 1, 9))
        assertSame(emptyList<String>(), Reorder.move(emptyList<String>(), 0, 0))
    }
}
