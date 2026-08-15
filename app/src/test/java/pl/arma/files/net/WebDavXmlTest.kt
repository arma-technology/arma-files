package pl.arma.files.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlTest {

    private val nextcloud = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/remote.php/dav/files/artur/</d:href>
            <d:propstat>
              <d:prop>
                <d:getlastmodified>Fri, 15 Aug 2025 10:00:00 GMT</d:getlastmodified>
                <d:resourcetype><d:collection/></d:resourcetype>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/artur/raport.pdf</d:href>
            <d:propstat>
              <d:prop>
                <d:displayname>raport.pdf</d:displayname>
                <d:getcontentlength>20480</d:getcontentlength>
                <d:getlastmodified>Fri, 15 Aug 2025 12:30:00 GMT</d:getlastmodified>
                <d:getcontenttype>application/pdf</d:getcontenttype>
                <d:resourcetype/>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `rozpoznaje katalog i plik`() {
        val responses = WebDavXml.parse(nextcloud)
        assertEquals(2, responses.size)
        assertTrue(responses[0].isDirectory)
        assertEquals(0L, responses[0].size)
        assertTrue(!responses[1].isDirectory)
        assertEquals(20480L, responses[1].size)
        assertEquals("raport.pdf", responses[1].displayName)
    }

    @Test
    fun `data modyfikacji jest parsowana`() {
        val modified = WebDavXml.parse(nextcloud)[1].modified
        assertTrue("oczekiwano sensownej daty, bylo $modified", modified > 1_700_000_000_000L)
    }

    @Test
    fun `inny prefiks przestrzeni nazw nie przeszkadza`() {
        // Apache mod_dav uzywa prefiksow lp1/D, a niektore serwery zadnego.
        val apache = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:lp1="DAV:">
              <D:response>
                <D:href>/dane/zdjecia/</D:href>
                <D:propstat>
                  <D:prop>
                    <lp1:resourcetype><D:collection/></lp1:resourcetype>
                    <lp1:getcontentlength>0</lp1:getcontentlength>
                  </D:prop>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val single = WebDavXml.parse(apache).single()
        assertTrue(single.isDirectory)
        assertEquals("/dane/zdjecia/", single.href)
    }

    @Test
    fun `katalog oznaczony samym typem tresci tez jest katalogiem`() {
        val xml = """
            <multistatus xmlns="DAV:">
              <response>
                <href>/pliki/</href>
                <propstat><prop>
                  <getcontenttype>httpd/unix-directory</getcontenttype>
                </prop></propstat>
              </response>
            </multistatus>
        """.trimIndent()
        assertTrue(WebDavXml.parse(xml).single().isDirectory)
    }

    @Test
    fun `brak rozmiaru daje zero zamiast wywrotki`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/plik.bin</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val single = WebDavXml.parse(xml).single()
        assertEquals(0L, single.size)
        assertEquals(0L, single.modified)
    }

    @Test
    fun `odpowiedz bez zasobow daje pusta liste`() {
        assertTrue(WebDavXml.parse("<d:multistatus xmlns:d=\"DAV:\"/>").isEmpty())
    }
}
