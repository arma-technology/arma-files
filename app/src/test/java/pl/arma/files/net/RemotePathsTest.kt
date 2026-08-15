package pl.arma.files.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemotePathsTest {

    @Test
    fun `normalizacja usuwa nadmiarowe ukosniki i kropki`() {
        assertEquals("/a/b", RemotePaths.normalize("//a///b/"))
        assertEquals("/a/b", RemotePaths.normalize("a/./b"))
        assertEquals("/", RemotePaths.normalize(""))
        assertEquals("/", RemotePaths.normalize("/"))
    }

    @Test
    fun `backslash traktowany jest jak separator`() {
        assertEquals("/kopie/2026", RemotePaths.normalize("kopie\\2026"))
    }

    @Test
    fun `wyjscie w gore drzewa jest odrzucane`() {
        assertThrows(IllegalArgumentException::class.java) { RemotePaths.normalize("/a/../../etc") }
        assertThrows(IllegalArgumentException::class.java) { RemotePaths.normalize("..") }
    }

    @Test
    fun `katalog startowy konta doklejany jest przed sciezke`() {
        assertEquals("/pub/dane/plik.txt", RemotePaths.absolute("/pub", "/dane/plik.txt"))
        assertEquals("/dane/plik.txt", RemotePaths.absolute("/", "/dane/plik.txt"))
        assertEquals("/pub", RemotePaths.absolute("/pub", "/"))
        assertEquals("/pub", RemotePaths.absolute("pub/", ""))
    }

    @Test
    fun `rodzic i nazwa`() {
        assertEquals("/a", RemotePaths.parentOf("/a/b"))
        assertEquals("/", RemotePaths.parentOf("/a"))
        assertEquals("/", RemotePaths.parentOf("/"))
        assertEquals("b", RemotePaths.nameOf("/a/b"))
        assertEquals("", RemotePaths.nameOf("/"))
    }

    @Test
    fun `sklejanie z nazwa dziecka`() {
        assertEquals("/a/b", RemotePaths.child("/a", "b"))
        assertEquals("/b", RemotePaths.child("/", "b"))
    }

    @Test
    fun `nazwa dziecka nie moze przemycic sciezki`() {
        // Ta sama ochrona co przy tworzeniu plikow lokalnie: pojedynczy segment
        // nie moze wyprowadzic operacji poza katalog docelowy.
        assertThrows(IllegalArgumentException::class.java) { RemotePaths.child("/a", "../b") }
        assertThrows(IllegalArgumentException::class.java) { RemotePaths.child("/a", "b/c") }
        assertThrows(IllegalArgumentException::class.java) { RemotePaths.child("/a", "b\\c") }
        assertThrows(IllegalArgumentException::class.java) { RemotePaths.child("/a", "..") }
    }
}
