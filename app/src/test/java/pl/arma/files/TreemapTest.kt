package pl.arma.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreemapTest {
    private val area = Cell(0f, 0f, 390f, 600f)

    @Test
    fun `pola proporcjonalne do wag i wypelniaja calosc`() {
        val weights = listOf(62.0, 38.0, 21.0, 14.0, 8.0, 3.0, 2.0)
        val cells = Treemap.layout(weights, area)
        val total = weights.sum()
        cells.zip(weights).forEach { (cell, w) ->
            assertEquals(w / total * area.width * area.height, (cell.width * cell.height).toDouble(), 1.0)
        }
        assertEquals((area.width * area.height).toDouble(), cells.sumOf { (it.width * it.height).toDouble() }, 2.0)
    }

    @Test
    fun `kafelki nie wychodza poza obszar i sie nie nakladaja`() {
        val weights = (1..40).map { 1000.0 / it }
        val cells = Treemap.layout(weights, area)
        cells.forEach {
            assertTrue(it.left >= -0.5f && it.top >= -0.5f && it.right <= area.width + 0.5f && it.bottom <= area.height + 0.5f)
        }
        for (a in cells.indices) for (b in a + 1 until cells.size) {
            val x = minOf(cells[a].right, cells[b].right) - maxOf(cells[a].left, cells[b].left)
            val y = minOf(cells[a].bottom, cells[b].bottom) - maxOf(cells[a].top, cells[b].top)
            assertTrue("$a i $b nachodzą", x <= 0.5f || y <= 0.5f)
        }
    }

    @Test
    fun `rowne wagi daja kafelki bliskie kwadratom`() {
        val cells = Treemap.layout(List(16) { 1.0 }, Cell(0f, 0f, 400f, 400f))
        cells.forEach { assertTrue(maxOf(it.width / it.height, it.height / it.width) < 2f) }
    }

    @Test
    fun `puste wagi i zerowy obszar nie wywracaja`() {
        assertTrue(Treemap.layout(emptyList(), area).isEmpty())
        assertEquals(2, Treemap.layout(listOf(1.0, 1.0), Cell(0f, 0f, 0f, 10f)).size)
    }
}
