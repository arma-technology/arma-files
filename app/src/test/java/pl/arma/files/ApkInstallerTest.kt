package pl.arma.files

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkInstallerTest {

    private fun item(name: String, mime: String) = FileItem(
        uri = Uri.parse("file:///storage/emulated/0/Download/$name"),
        name = name,
        mimeType = mime,
        size = 1024L,
        lastModified = 0L,
        isDirectory = false
    )

    @Test
    fun `rozpoznaje APK po typie MIME`() {
        assertTrue(isApk(item("cokolwiek", APK_MIME)))
    }

    @Test
    fun `rozpoznaje APK po rozszerzeniu, gdy MIME jest ogolny`() {
        // FTP i SMB nie podaja typu MIME, wiec zostaje sama nazwa pliku.
        assertTrue(isApk(item("aplikacja.apk", "application/octet-stream")))
        assertTrue(isApk(item("APLIKACJA.APK", "application/octet-stream")))
    }

    @Test
    fun `nie bierze za APK plikow o podobnych nazwach`() {
        assertFalse(isApk(item("notatki.apk.txt", "text/plain")))
        assertFalse(isApk(item("apkarat.zip", "application/zip")))
        assertFalse(isApk(item("zdjecie.jpg", "image/jpeg")))
    }
}
