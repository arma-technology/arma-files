package pl.arma.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatcherTest {
    @Test
    fun `bez polskich znakow i wielkosci liter`() {
        assertTrue(NameMatcher("zolw").matches("Żółw.txt"))
        assertTrue(NameMatcher("ŁÓDŹ").matches("plan lodz 2026.pdf"))
    }

    @Test
    fun `kazde slowo frazy musi wystapic`() {
        assertTrue(NameMatcher("plan 2026").matches("Plan_parteru_2026.dwg"))
        assertFalse(NameMatcher("plan 2025").matches("Plan_parteru_2026.dwg"))
        assertFalse(NameMatcher("   ").matches("cokolwiek"))
    }
}
