package pl.arma.files.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScannerTest {

    @Test
    fun `siec 24 daje 253 adresy bez wlasnego`() {
        val hosts = LanScanner.subnetHosts("192.168.0.10", 24)
        assertEquals(253, hosts.size)
        assertTrue("adres wlasny pomijamy", "192.168.0.10" !in hosts)
        assertTrue("adres sieci pomijamy", "192.168.0.0" !in hosts)
        assertTrue("rozgloszeniowy pomijamy", "192.168.0.255" !in hosts)
        assertTrue("192.168.0.1" in hosts)
        assertTrue("192.168.0.254" in hosts)
    }

    @Test
    fun `mniejsza podsiec ogranicza zakres`() {
        val hosts = LanScanner.subnetHosts("10.0.0.5", 29)
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.6"), hosts)
    }

    @Test
    fun `duza siec nie jest skanowana`() {
        // Skanowanie /8 to 16 milionow adresow -- nie zaczynamy tego w ogole.
        assertTrue(LanScanner.subnetHosts("10.0.0.5", 8).isEmpty())
        assertTrue(LanScanner.subnetHosts("10.0.0.5", 16).isEmpty())
    }

    @Test
    fun `podsiec 22 miesci sie w limicie`() {
        assertEquals(1021, LanScanner.subnetHosts("172.16.4.9", 22).size)
    }

    @Test
    fun `bledne dane wejsciowe daja pusta liste`() {
        assertTrue(LanScanner.subnetHosts("nie-adres", 24).isEmpty())
        assertTrue(LanScanner.subnetHosts("192.168.0.999", 24).isEmpty())
        assertTrue(LanScanner.subnetHosts("192.168.0.1", 31).isEmpty())
        assertTrue(LanScanner.subnetHosts("192.168.0.1", 0).isEmpty())
    }
}
