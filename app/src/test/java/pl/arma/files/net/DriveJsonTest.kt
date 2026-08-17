package pl.arma.files.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveJsonTest {

    @Test
    fun `lista plikow z paginacja`() {
        val json = """
            {
              "nextPageToken": "abc123",
              "files": [
                {"id":"1","name":"Zdjecia","mimeType":"application/vnd.google-apps.folder"},
                {"id":"2","name":"raport.pdf","mimeType":"application/pdf",
                 "size":"20480","modifiedTime":"2026-08-15T12:30:00.000Z"}
              ]
            }
        """.trimIndent()
        val (files, token) = DriveJson.parseFileList(json)
        assertEquals("abc123", token)
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertEquals(0L, files[0].size)
        assertEquals(20480L, files[1].size)
    }

    @Test
    fun `brak tokenu strony oznacza koniec wynikow`() {
        val (_, token) = DriveJson.parseFileList("""{"files":[]}""")
        assertNull(token)
    }

    @Test
    fun `rozmiar przychodzi jako lancuch, a dla katalogow go nie ma`() {
        // Drive zwraca "size" tekstem i pomija je dla katalogow oraz dokumentow
        // Google -- bez tego rozmiary bylyby losowe albo wywracalyby parser.
        val (files, _) = DriveJson.parseFileList(
            """{"files":[{"id":"1","name":"Bez rozmiaru","mimeType":"application/vnd.google-apps.document"}]}"""
        )
        assertEquals(0L, files.single().size)
        assertTrue(files.single().isGoogleDoc)
    }

    @Test
    fun `czas RFC 3339 zamienia sie na znacznik czasu`() {
        val parsed = DriveJson.parseTimestamp("2026-08-15T12:30:00.000Z")
        assertTrue("oczekiwano sensownej daty, bylo $parsed", parsed > 1_700_000_000_000L)
    }

    @Test
    fun `pusty lub bledny czas nie wywraca parsera`() {
        assertEquals(0L, DriveJson.parseTimestamp(""))
        assertEquals(0L, DriveJson.parseTimestamp("wczoraj"))
    }

    @Test
    fun `komunikat bledu jest wydobywany z odpowiedzi`() {
        val json = """{"error":{"code":403,"message":"Insufficient permissions"}}"""
        assertEquals("Insufficient permissions", DriveJson.parseError(json))
        assertNull(DriveJson.parseError("""{"files":[]}"""))
        assertNull(DriveJson.parseError("to nie jest JSON"))
    }

    @Test
    fun `wpis bez identyfikatora jest pomijany`() {
        val (files, _) = DriveJson.parseFileList("""{"files":[{"name":"bez id"},{"id":"2","name":"ok"}]}""")
        assertEquals(listOf("ok"), files.map { it.name })
    }
}
