package pl.arma.viewer.cad

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Strumień par „kod grupy – wartość” z pliku DXF, tekstowego albo binarnego.
 *
 * Wartości trzymamy jako surowe bajty i zamieniamy na tekst dopiero na
 * żądanie: strona kodowa ($DWGCODEPAGE) jest w nagłówku, a ten trzeba
 * najpierw przeczytać tym samym czytnikiem.
 */
class DxfReader(input: InputStream) : Closeable {

    private val stream = BufferedInputStream(input, 1 shl 16)
    private var binary = false
    /** Binarny DXF sprzed R13 ma jednobajtowe kody grup. */
    private var shortCodes = false
    private var buffer = ByteArray(256)
    private var length = 0
    private var pushedBack = false

    var charset: Charset = Charsets.UTF_8

    var code: Int = -1
        private set

    /** Wartość liczbowa bieżącej grupy, gdy w binarnym DXF przyszła jako liczba. */
    private var number = 0.0
    private var numeric = false

    init {
        stream.mark(32)
        val head = ByteArray(22)
        val read = stream.read(head)
        binary = read == 22 && String(head, 0, 18, Charsets.ISO_8859_1) == "AutoCAD Binary DXF"
        if (!binary) {
            stream.reset()
        } else {
            // Pierwsza grupa to zawsze „0 SECTION”: kod dwubajtowy daje 00 00,
            // jednobajtowy (R12) od razu literę S.
            stream.mark(4)
            val a = stream.read()
            val b = stream.read()
            shortCodes = a == 0 && b != 0
            stream.reset()
        }
    }

    val isBinary: Boolean get() = binary

    /** Przechodzi do następnej grupy; false na końcu pliku. */
    fun next(): Boolean {
        if (pushedBack) {
            pushedBack = false
            return true
        }
        return if (binary) nextBinary() else nextText()
    }

    /** Następne [next] zwróci jeszcze raz bieżącą grupę. */
    fun pushBack() {
        pushedBack = true
    }

    val string: String
        get() = if (numeric) formatNumber() else decode()

    val double: Double
        get() = if (numeric) number else parseDouble()

    val int: Int
        get() = if (numeric) number.toInt() else parseLong().toInt()

    val long: Long
        get() = if (numeric) number.toLong() else parseLong()

    /** Czy bieżąca wartość to dokładnie [text] (bez zamiany bajtów na String). */
    fun valueIs(text: String): Boolean {
        if (numeric || length != text.length) return false
        for (i in 0 until length) if (buffer[i].toInt().toChar() != text[i]) return false
        return true
    }

    private fun decode(): String {
        val raw = String(buffer, 0, length, charset)
        return if (raw.indexOf("\\U+") >= 0 || raw.indexOf("\\M+") >= 0) DxfText.unescapeUnicode(raw) else raw
    }

    private fun formatNumber(): String =
        if (number == Math.floor(number) && kotlin.math.abs(number) < 1e15) number.toLong().toString() else number.toString()

    private fun parseDouble(): Double {
        val text = String(buffer, 0, length, Charsets.ISO_8859_1).trim()
        return text.toDoubleOrNull() ?: text.replace(',', '.').toDoubleOrNull() ?: 0.0
    }

    private fun parseLong(): Long {
        val text = String(buffer, 0, length, Charsets.ISO_8859_1).trim()
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong() ?: 0L
    }

    // ---- tekstowy DXF ------------------------------------------------------

    private fun nextText(): Boolean {
        if (!readLine()) return false
        var c = 0
        var sign = 1
        var any = false
        for (i in 0 until length) {
            val b = buffer[i].toInt()
            when {
                b == '-'.code -> sign = -1
                b in '0'.code..'9'.code -> { c = c * 10 + (b - '0'.code); any = true }
                b == ' '.code || b == '\t'.code -> Unit
                else -> throw IOException("Invalid DXF group code")
            }
        }
        if (!any) {
            // Puste linie na końcu pliku.
            return if (readLine()) nextText() else false
        }
        code = sign * c
        numeric = false
        if (!readLine()) return false
        // Nazwy (typy obiektów, sekcje, warstwy) bywają zapisane ze spacjami
        // na brzegach przez starsze programy; w tekście spacje są istotne.
        if (code == 0 || code == 2 || code == 8 || code == 9) trimBuffer()
        return true
    }

    private fun trimBuffer() {
        var start = 0
        while (start < length && buffer[start].toInt() == ' '.code) start++
        var end = length
        while (end > start && (buffer[end - 1].toInt() == ' '.code || buffer[end - 1].toInt() == '\t'.code)) end--
        if (start > 0) System.arraycopy(buffer, start, buffer, 0, end - start)
        length = end - start
    }

    /** Linia bez znaków końca; true, gdy cokolwiek przeczytano. */
    private fun readLine(): Boolean {
        length = 0
        var b = stream.read()
        if (b < 0) return false
        while (b >= 0 && b != '\n'.code) {
            if (b != '\r'.code) {
                if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                buffer[length++] = b.toByte()
            }
            b = stream.read()
        }
        // Spacje wiodące są częścią wartości (np. w tekście), ale nie końcowe
        // w kodach — kod parsujemy osobno, a wartości obcinamy tylko liczbowe.
        return true
    }

    // ---- binarny DXF -------------------------------------------------------

    private fun nextBinary(): Boolean {
        val first = stream.read()
        if (first < 0) return false
        code = if (shortCodes) {
            if (first == 255) readShort() else first
        } else {
            val second = stream.read()
            if (second < 0) return false
            first or (second shl 8)
        }
        when (valueType(code)) {
            ValueType.STRING -> {
                numeric = false
                readCString()
            }
            ValueType.DOUBLE -> {
                numeric = true
                number = java.lang.Double.longBitsToDouble(readLongLe())
            }
            ValueType.INT16 -> {
                numeric = true
                number = readShort().toShort().toDouble()
            }
            ValueType.INT32 -> {
                numeric = true
                number = readIntLe().toDouble()
            }
            ValueType.INT64 -> {
                numeric = true
                number = readLongLe().toDouble()
            }
            ValueType.BOOL -> {
                numeric = true
                number = stream.read().toDouble()
            }
            ValueType.BINARY -> {
                numeric = false
                val size = stream.read()
                length = 0
                ensure(size)
                readFully(size)
            }
        }
        return true
    }

    private fun readCString() {
        length = 0
        var b = stream.read()
        while (b > 0) {
            if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[length++] = b.toByte()
            b = stream.read()
        }
    }

    private fun ensure(size: Int) {
        if (buffer.size < size) buffer = ByteArray(maxOf(size, buffer.size * 2))
    }

    private fun readFully(size: Int) {
        var off = 0
        while (off < size) {
            val n = stream.read(buffer, off, size - off)
            if (n < 0) throw IOException("Truncated DXF file")
            off += n
        }
        length = size
    }

    private fun readShort(): Int {
        val a = stream.read()
        val b = stream.read()
        if (a < 0 || b < 0) throw IOException("Truncated DXF file")
        return a or (b shl 8)
    }

    private fun readIntLe(): Int {
        var v = 0
        for (i in 0 until 4) {
            val b = stream.read()
            if (b < 0) throw IOException("Truncated DXF file")
            v = v or (b shl (8 * i))
        }
        return v
    }

    private fun readLongLe(): Long {
        var v = 0L
        for (i in 0 until 8) {
            val b = stream.read()
            if (b < 0) throw IOException("Truncated DXF file")
            v = v or (b.toLong() shl (8 * i))
        }
        return v
    }

    override fun close() = stream.close()

    enum class ValueType { STRING, DOUBLE, INT16, INT32, INT64, BOOL, BINARY }

    companion object {
        /** Typ wartości według zakresu kodu grupy (DXF Reference, „Group Code Value Types”). */
        fun valueType(code: Int): ValueType = when (code) {
            in 0..9 -> ValueType.STRING
            in 10..59 -> ValueType.DOUBLE
            in 60..79 -> ValueType.INT16
            in 90..99 -> ValueType.INT32
            100, 101, 102, 105 -> ValueType.STRING
            in 110..149 -> ValueType.DOUBLE
            in 160..169 -> ValueType.INT64
            in 170..179 -> ValueType.INT16
            in 210..239 -> ValueType.DOUBLE
            in 270..289 -> ValueType.INT16
            in 290..299 -> ValueType.BOOL
            in 300..309 -> ValueType.STRING
            in 310..319 -> ValueType.BINARY
            in 320..369 -> ValueType.STRING
            in 370..389 -> ValueType.INT16
            in 390..399 -> ValueType.STRING
            in 400..409 -> ValueType.INT16
            in 410..419 -> ValueType.STRING
            in 420..429 -> ValueType.INT32
            in 430..439 -> ValueType.STRING
            in 440..459 -> ValueType.INT32
            in 460..469 -> ValueType.DOUBLE
            in 470..481 -> ValueType.STRING
            999 -> ValueType.STRING
            in 1000..1003 -> ValueType.STRING
            1004 -> ValueType.BINARY
            in 1005..1009 -> ValueType.STRING
            in 1010..1059 -> ValueType.DOUBLE
            in 1060..1070 -> ValueType.INT16
            1071 -> ValueType.INT32
            else -> ValueType.STRING
        }

        /** Strona kodowa z $DWGCODEPAGE („ANSI_1250”) albo null. */
        fun charsetFor(codepage: String?, version: String?): Charset {
            // Od AutoCAD 2007 (AC1021) DXF jest zawsze w UTF-8.
            val v = version?.removePrefix("AC")?.toIntOrNull() ?: 0
            if (v >= 1021) return Charsets.UTF_8
            val name = codepage?.uppercase()?.let {
                when {
                    it.startsWith("ANSI_") -> "windows-" + it.removePrefix("ANSI_")
                    it.startsWith("DOS") -> "IBM" + it.removePrefix("DOS")
                    it == "UTF8" || it == "UTF-8" -> "UTF-8"
                    else -> null
                }
            }
            return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charset.forName("windows-1252")
        }
    }
}
