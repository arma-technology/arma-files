package pl.arma.files

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashStoreTest {

    private fun root(path: String): String? =
        TrashStore.volumeRootFor(Uri.parse(path))?.toString()

    @Test
    fun `pamiec wbudowana rozpoznana po punkcie montowania`() {
        assertEquals(
            "file:///storage/emulated/0",
            root("file:///storage/emulated/0/Download/raport.pdf")
        )
    }

    @Test
    fun `profil drugiego uzytkownika ma wlasny korzen`() {
        assertEquals(
            "file:///storage/emulated/10",
            root("file:///storage/emulated/10/Dokumenty/plik.txt")
        )
    }

    @Test
    fun `karta SD rozpoznana po identyfikatorze woluminu`() {
        assertEquals(
            "file:///storage/1A2B-3C4D",
            root("file:///storage/1A2B-3C4D/DCIM/zdjecie.jpg")
        )
    }

    @Test
    fun `sam korzen nosnika zwraca sam siebie`() {
        assertEquals("file:///storage/emulated/0", root("file:///storage/emulated/0"))
    }

    @Test
    fun `zasoby sieciowe maja korzen udzialu`() {
        assertEquals(
            RemoteRepository.smbRoot().toString(),
            root("${RemoteRepository.SMB_SCHEME}://smb/kopie/2026/plik.txt")
        )
        assertEquals(
            RemoteRepository.ftpRoot().toString(),
            root("${RemoteRepository.FTP_SCHEME}://ftp/pub/plik.txt")
        )
    }

    @Test
    fun `dla SAF nie da sie wskazac nosnika, wiec brak kosza`() {
        assertNull(root("content://com.android.externalstorage.documents/tree/primary%3ADCIM"))
    }

    @Test
    fun `sciezka spoza znanych punktow montowania nie daje korzenia`() {
        assertNull(root("file:///data/local/tmp/plik.txt"))
    }
}
