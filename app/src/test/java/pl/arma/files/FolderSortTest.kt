package pl.arma.files

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderSortTest {

    private fun sort(uri: String) = defaultSortFor(Uri.parse(uri))

    @Test
    fun `pobrane w pamieci wbudowanej po dacie`() {
        assertEquals(SortMode.DATE, sort("file:///storage/emulated/0/Download"))
        assertEquals(SortMode.DATE, sort("file:///storage/emulated/0/Download/"))
    }

    @Test
    fun `pobrane otwarte przez SAF po dacie`() {
        assertEquals(
            SortMode.DATE,
            sort("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload")
        )
    }

    @Test
    fun `pozostale foldery po nazwie`() {
        assertEquals(SortMode.NAME, sort("file:///storage/emulated/0/DCIM/Camera"))
        assertEquals(SortMode.NAME, sort("file:///storage/emulated/0/Download/Faktury"))
        assertEquals(SortMode.NAME, defaultSortFor(null))
    }

    @Test
    fun `kazdy folder ma wlasny klucz, karty bez folderu wspolny`() {
        val a = Uri.parse("file:///storage/emulated/0/Download")
        val b = Uri.parse("file:///storage/emulated/0/DCIM")
        assertNotEquals(sortKey(Tab.FILES, a), sortKey(Tab.FILES, b))
        assertEquals("tab:RECENT", sortKey(Tab.RECENT, a))
    }
}
