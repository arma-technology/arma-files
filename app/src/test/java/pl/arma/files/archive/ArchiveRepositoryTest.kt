package pl.arma.files.archive

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.arma.files.DIRECTORY_MIME
import pl.arma.files.FileItem
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveRepositoryTest {

    private lateinit var dir: File
    private lateinit var archives: ArchiveRepository
    private val defaultLocale = Locale.getDefault()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("archiwa").toFile()
        archives = ArchiveRepository(
            ApplicationProvider.getApplicationContext(),
            openSource = { uri -> File(uri.path!!).inputStream() },
            sourceSignature = { uri -> File(uri.path!!).let { "${it.length()}|${it.lastModified()}" } },
            localFile = { uri -> File(uri.path!!) }
        )
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
        dir.deleteRecursively()
    }

    /** Drzewo do spakowania: projekt/opis.txt, projekt/rysunki/plan.dxf, notatka.txt. */
    private val tree: Map<String, String> = mapOf(
        "projekt/opis.txt" to "Opis projektu — zażółć gęślą jaźń",
        "projekt/rysunki/plan.dxf" to "0\nSECTION\n".repeat(200),
        "notatka.txt" to "krótka"
    )

    private val source = object : ArchiveWriter.Source {
        override fun children(item: FileItem): List<FileItem> {
            val prefix = item.uri.path!!.trimStart('/') + "/"
            val names = tree.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
            return names.map { node(prefix + it) }
        }

        override fun open(item: FileItem) = tree.getValue(item.uri.path!!.trimStart('/')).byteInputStream()
    }

    private fun node(path: String): FileItem {
        val isDir = tree.keys.any { it.startsWith("$path/") }
        return FileItem(
            Uri.parse("mem:///$path"), path.substringAfterLast('/'),
            if (isDir) DIRECTORY_MIME else "text/plain",
            tree[path]?.toByteArray()?.size?.toLong() ?: 0L, 1_700_000_000_000L, isDir
        )
    }

    private fun pack(format: ArchiveFormat, password: String? = null): File {
        val file = File(dir, "test.${format.extension}")
        ArchiveWriter.write(format, listOf(node("projekt"), node("notatka.txt")), source, password?.toCharArray(), File(dir, "tmp")) {
            FileOutputStream(file)
        }
        return file
    }

    private fun root(file: File): Uri = archives.rootOf(Uri.fromFile(file), file.name)

    private fun child(root: Uri, path: String): Uri = root.buildUpon().path("/$path").build()

    private fun read(uri: Uri): String = archives.openInput(uri).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun assertTreeReadable(file: File) {
        val root = root(file)
        archives.ensureReadable(root)
        val top = archives.listChildren(root).map { it.name to it.isDirectory }.toSet()
        assertEquals(setOf("projekt" to true, "notatka.txt" to false), top)
        val inner = archives.listChildren(child(root, "projekt")).map { it.name }.toSet()
        assertEquals(setOf("opis.txt", "rysunki"), inner)
        tree.forEach { (path, content) -> assertEquals(path, content, read(child(root, path))) }
        // Kolejność odwrotna: czytnik strumieniowy musi zacząć od nowa.
        tree.keys.reversed().forEach { path -> assertEquals(tree[path], read(child(root, path))) }
    }

    @Test
    fun `zip bez hasla`() = assertTreeReadable(pack(ArchiveFormat.ZIP))

    @Test
    fun `7z`() = assertTreeReadable(pack(ArchiveFormat.SEVEN_Z))

    @Test
    fun `tar gz`() = assertTreeReadable(pack(ArchiveFormat.TAR_GZ))

    @Test
    fun `tar xz`() = assertTreeReadable(pack(ArchiveFormat.TAR_XZ))

    @Test
    fun `tar`() = assertTreeReadable(pack(ArchiveFormat.TAR))

    @Test
    fun `zip z haslem wymaga hasla i odrzuca zle`() {
        val root = root(pack(ArchiveFormat.ZIP, password = "tajne"))
        try {
            archives.ensureReadable(root)
            fail("bez hasła nie powinno się dać czytać")
        } catch (e: ArchivePasswordException) {
            assertEquals(false, e.wrong)
        }
        archives.setPassword(root, "zle".toCharArray())
        try {
            archives.ensureReadable(root)
            fail("złe hasło")
        } catch (e: ArchivePasswordException) {
            assertTrue(e.wrong)
        }
        archives.setPassword(root, "tajne".toCharArray())
        archives.ensureReadable(root)
        assertEquals("krótka", read(child(root, "notatka.txt")))
    }

    @Test
    fun `polskie nazwy z zipa z Windows w CP852`() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        val file = File(dir, "windows.zip")
        // zip4j z kodowaniem CP852 nie ustawia flagi UTF-8 — jak Eksplorator Windows.
        ZipOutputStream(FileOutputStream(file), null, charset("IBM852")).use { zip ->
            zip.putNextEntry(ZipParameters().apply { fileNameInZip = "Zażółć/gęślą jaźń.txt" })
            zip.write("treść".toByteArray())
            zip.closeEntry()
        }
        val root = root(file)
        assertEquals(listOf("Zażółć"), archives.listChildren(root).map { it.name })
        assertEquals("treść", read(child(root, "Zażółć/gęślą jaźń.txt")))
    }

    @Test
    fun `pojedynczy plik gz z nazwa z naglowka`() {
        val file = File(dir, "dane.gz")
        val parameters = GzipParameters().apply { fileName = "raport.csv"; modificationTime = 1_700_000_000L }
        GzipCompressorOutputStream(FileOutputStream(file), parameters).use { it.write("a;b\n1;2\n".toByteArray()) }
        val root = root(file)
        assertEquals(listOf("raport.csv"), archives.listChildren(root).map { it.name })
        assertEquals(1_700_000_000_000L, archives.listChildren(root).single().lastModified)
        assertEquals("a;b\n1;2\n", read(child(root, "raport.csv")))
    }

    @Test
    fun `sciezki wychodzace poza archiwum sa pomijane`() {
        val file = File(dir, "zly.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            listOf("../../poza.txt", "/abs/ok.txt", "dobry.txt").forEach { name ->
                zip.putNextEntry(ZipParameters().apply { fileNameInZip = name })
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        val root = root(file)
        assertEquals(setOf("abs", "dobry.txt"), archives.listChildren(root).map { it.name }.toSet())
    }

    @Test
    fun `nazwy po rozszerzeniach`() {
        assertEquals("projekt", ArchiveFormats.baseName("projekt.tar.gz"))
        assertEquals("dane", ArchiveFormats.baseName("dane.7z"))
        assertEquals("raport.csv", ArchiveFormats.innerName("raport.csv.gz"))
        assertEquals("dane.tar", ArchiveFormats.innerName("dane.tgz"))
        assertTrue(ArchiveFormats.canBrowse("a.TAR.ZST"))
        assertTrue(!ArchiveFormats.canBrowse("app.apk") && ArchiveFormats.canOpenAsArchive("app.apk"))
    }
}
