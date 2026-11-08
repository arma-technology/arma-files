package pl.arma.files

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeUtilsTest {
    @Test
    fun audioFromPhonesAndItunesIsAudio() {
        assertEquals("audio/mp4", guessMime("Nagranie 12.M4A"))
        assertEquals("audio/mp4", guessMime("ksiazka.m4b"))
        assertEquals("audio/aac", guessMime("a.aac"))
        assertEquals("audio/opus", guessMime("glosowka.opus"))
        assertEquals("audio/amr", guessMime("rozmowa.amr"))
    }

    @Test
    fun videoContainers() {
        assertEquals("video/quicktime", guessMime("IMG_0001.MOV"))
        assertEquals("video/3gpp", guessMime("stary.3gp"))
        assertEquals("video/x-msvideo", guessMime("film.avi"))
        assertEquals("video/mp2t", guessMime("00001.MTS"))
        assertEquals("video/mpeg", guessMime("VTS_01_1.VOB"))
    }

    @Test
    fun typeScriptStaysText() {
        assertEquals("application/octet-stream", guessMime("index.ts"))
    }
}
