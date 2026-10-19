package pl.arma.files.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCodecTest {

    private val nas = RemoteAccount(
        id = "a1b2c3",
        type = RemoteType.SMB,
        label = "NAS Dom",
        host = "192.168.0.10",
        port = 445,
        username = "artur",
        rootPath = "kopie/2026",
        domain = "WORKGROUP",
        share = "dane",
        secure = false,
        enabled = true
    )

    private val ftp = RemoteAccount(
        id = "a9z8",
        type = RemoteType.FTP,
        label = "Serwer firmowy",
        host = "ftp.example.com",
        port = 2121,
        username = "biuro",
        rootPath = "/pub",
        secure = true,
        enabled = false
    )

    @Test
    fun `zapis i odczyt zachowuja wszystkie pola`() {
        assertEquals(listOf(nas, ftp), AccountCodec.decode(AccountCodec.encode(listOf(nas, ftp))))
    }

    @Test
    fun `haslo nie trafia do zapisu, bo idzie do SecretStore`() {
        val text = AccountCodec.encode(listOf(nas.copy(password = "TajneHaslo123")))
        assertTrue(
            "haslo nie moze wyladowac w zwyklych preferencjach",
            !text.contains("TajneHaslo123")
        )
        assertEquals("", AccountCodec.decode(text).single().password)
    }

    @Test
    fun `etykieta z tabulatorem nie rozjezdza formatu`() {
        val nasty = nas.copy(label = "NAS\tDom\nzapasowy")
        assertEquals(listOf(nasty), AccountCodec.decode(AccountCodec.encode(listOf(nasty))))
    }

    @Test
    fun `uszkodzona linia jest pomijana, reszta kont ocalala`() {
        val text = AccountCodec.encode(listOf(nas)) + "\nzepsuta linia\n"
        assertEquals(listOf(nas), AccountCodec.decode(text))
    }

    @Test
    fun `nieznany typ polaczenia nie wywraca calej listy`() {
        val text = AccountCodec.encode(listOf(nas, ftp)).replace("\tFTP\t", "\tGOPHER\t")
        assertEquals(listOf(nas), AccountCodec.decode(text))
    }

    @Test
    fun `pusta lista daje pusty zapis i odczyt`() {
        assertTrue(AccountCodec.decode(AccountCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `domyslne porty zgadzaja sie z protokolami`() {
        assertEquals(21, RemoteAccount.defaultPort(RemoteType.FTP, secure = false))
        assertEquals(22, RemoteAccount.defaultPort(RemoteType.SFTP, secure = false))
        assertEquals(445, RemoteAccount.defaultPort(RemoteType.SMB, secure = false))
        assertEquals(80, RemoteAccount.defaultPort(RemoteType.WEBDAV, secure = false))
        assertEquals(443, RemoteAccount.defaultPort(RemoteType.WEBDAV, secure = true))
    }

    @Test
    fun `nowy identyfikator nadaje sie na autorytet URI`() {
        repeat(50) {
            val id = RemoteAccount.newId()
            assertTrue("nieoczekiwany znak w '$id'", id.all { it.isLetterOrDigit() })
            assertTrue(id.isNotBlank())
        }
    }

    @Test
    fun `konto SMB bez udzialu jest kompletne, bez adresu nie`() {
        assertTrue(nas.configured)
        // Bez udziału konto pokazuje listę udziałów serwera.
        assertTrue(nas.copy(share = "").configured)
        assertTrue(nas.copy(share = "").summary == nas.host)
        assertTrue(!nas.copy(host = "").configured)
        // FTP nie potrzebuje udziału, wystarczy adres.
        assertTrue(ftp.configured)
    }
}
