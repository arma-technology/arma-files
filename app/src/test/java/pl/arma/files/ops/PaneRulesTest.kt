package pl.arma.files.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneRulesTest {

    private val ids = listOf("a", "b", "c")

    @Test
    fun `zamkniecie nieaktywnej karty nie zmienia aktywnej`() {
        assertEquals("b", PaneRules.activeAfterClose(ids, activeId = "b", closing = "a"))
    }

    @Test
    fun `zamkniecie aktywnej karty przechodzi na kolejna`() {
        assertEquals("c", PaneRules.activeAfterClose(ids, activeId = "b", closing = "b"))
    }

    @Test
    fun `zamkniecie ostatniej z brzegu cofa sie na poprzednia`() {
        assertEquals("b", PaneRules.activeAfterClose(ids, activeId = "c", closing = "c"))
    }

    @Test
    fun `ostatniej karty nie da sie zamknac`() {
        assertNull(PaneRules.activeAfterClose(listOf("a"), activeId = "a", closing = "a"))
    }

    @Test
    fun `zamkniecie nieistniejacej karty jest ignorowane`() {
        assertNull(PaneRules.activeAfterClose(ids, activeId = "a", closing = "x"))
    }

    @Test
    fun `tytul karty bierze sie z nazwy katalogu`() {
        assertEquals("Mój komputer", PaneRules.tabTitle(null))
        assertEquals("Camera", PaneRules.tabTitle("/storage/emulated/0/DCIM/Camera"))
        assertEquals("Camera", PaneRules.tabTitle("/storage/emulated/0/DCIM/Camera/"))
        assertEquals("Katalog główny", PaneRules.tabTitle("/"))
    }

    @Test
    fun `tytul karty SAF pomija identyfikator dokumentu`() {
        // SAF trzyma w sciezce cos w rodzaju "tree/primary:DCIM/document/primary:DCIM"
        assertEquals("DCIM", PaneRules.tabTitle("/tree/primary:DCIM/document/primary:DCIM"))
        assertEquals("Faktury", PaneRules.tabTitle("/tree/primary:Dokumenty/document/primary:Faktury"))
    }
}
