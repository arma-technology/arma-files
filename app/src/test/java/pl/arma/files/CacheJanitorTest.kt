package pl.arma.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheJanitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var janitor: CacheJanitor
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        CacheJanitor.DIRECTORIES.forEach { File(context.cacheDir, it).deleteRecursively() }
        context.getSharedPreferences("cache", Context.MODE_PRIVATE).edit().clear().commit()
        janitor = CacheJanitor(context, CoroutineScope(Dispatchers.Unconfined))
    }

    private fun file(dir: String, name: String, megabytes: Int, ageMinutes: Long): File =
        File(context.cacheDir, "$dir/$name").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(megabytes * 1_048_576))
            setLastModified(now - TimeUnit.MINUTES.toMillis(ageMinutes))
        }

    @Test
    fun `usuwa pliki starsze niz limit wieku`() {
        janitor.maxAgeDays = 3
        val old = file("dwg_dxf", "stary.dxb", 1, TimeUnit.DAYS.toMinutes(4))
        val fresh = file("dwg_dxf", "nowy.dxb", 1, TimeUnit.DAYS.toMinutes(2))
        janitor.trim(now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `po przekroczeniu rozmiaru usuwa najdawniej uzywane`() {
        janitor.maxMegabytes = 100
        val oldest = file("doc_preview", "a.pdf", 60, 300)
        val middle = file("dwg_dxf", "b.dxb", 30, 200)
        val newest = file("image_preview", "c.jpg", 30, 100)
        janitor.trim(now)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
        assertEquals(60L * 1_048_576, janitor.usedBytes())
    }

    @Test
    fun `nie rusza plikow wlasnie uzywanych ani otwartych pdf`() {
        janitor.maxMegabytes = 100
        val inUse = file("doc_preview", "otwarty.pdf", 150, 2)
        val openPdf = file("pdf_work/123", "source.pdf", 20, TimeUnit.HOURS.toMinutes(5))
        val abandoned = file("pdf_work/99", "source.pdf", 1, TimeUnit.DAYS.toMinutes(2))
        File(context.cacheDir, "pdf_work/99").setLastModified(now - TimeUnit.DAYS.toMillis(2))
        janitor.trim(now)
        assertTrue(inUse.exists())
        assertTrue(openPdf.exists())
        assertFalse(abandoned.exists())
    }

    @Test
    fun `wyczysc teraz zostawia tylko otwarte pdf`() {
        val drawing = file("dwg_dxf", "b.dxb", 1, 1)
        val openPdf = file("pdf_work/1", "source.pdf", 1, 1)
        janitor.clear()
        assertFalse(drawing.exists())
        assertTrue(openPdf.exists())
    }
}
