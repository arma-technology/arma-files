package pl.arma.viewer.text

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Wynik odczytu pliku tekstowego. */
data class TextDocument(
    val text: String,
    val charset: Charset,
    val truncated: Boolean,
    val totalBytes: Long,
    val lineCount: Int
)

/**
 * Odczyt plików tekstowych: rozpoznanie kodowania i odsianie plików binarnych.
 *
 * Rozpoznawanie kodowania jest tu ważniejsze niż mogłoby się wydawać: pliki
 * `.txt`, `.srt` i `.csv` z polskiego Windowsa bywają w CP1250, a potraktowane
 * jako UTF-8 wyglądają jak ciąg znaków zapytania.
 */
object TextReader {

    /** Powyżej tego progu czytamy tylko początek — reszta i tak nie zmieści się na ekranie. */
    const val DEFAULT_LIMIT = 2 * 1024 * 1024

    /**
     * Plik binarny rozpoznajemy po bajcie zerowym. Pliki UTF-16 też je mają,
     * więc najpierw sprawdzamy znacznik kolejności bajtów.
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bomCharset(bytes) != null) return false
        return bytes.take(8192).any { it == 0.toByte() }
    }

    private fun bomCharset(bytes: ByteArray): Charset? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> Charsets.UTF_8
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> null
    }

    private fun bomLength(charset: Charset): Int = when (charset) {
        Charsets.UTF_8 -> 3
        else -> 2
    }

    /**
     * Zamienia bajty na tekst.
     *
     * Kolejność: znacznik kolejności bajtów, potem ścisła próba UTF-8, a gdy ta
     * zawiedzie — CP1250. Ścisła próba jest konieczna, bo zwykłe dekodowanie
     * UTF-8 nie zgłasza błędu, tylko podmienia bajty na znak zastępczy, więc
     * plik w CP1250 „odczytałby się" bez ostrzeżenia, tyle że nieczytelnie.
     */
    fun decode(bytes: ByteArray): Pair<String, Charset> {
        bomCharset(bytes)?.let { charset ->
            val skip = bomLength(charset)
            return bytes.copyOfRange(skip.coerceAtMost(bytes.size), bytes.size)
                .toString(charset) to charset
        }

        strictDecode(bytes, Charsets.UTF_8)?.let { return it to Charsets.UTF_8 }

        val fallback = Charset.forName("windows-1250")
        strictDecode(bytes, fallback)?.let { return it to fallback }

        // Żadne kodowanie nie pasuje w całości — pokazujemy, co się da.
        return bytes.toString(Charsets.UTF_8) to Charsets.UTF_8
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /**
     * Buduje dokument z surowych bajtów.
     *
     * Bajty dekodujemy w całości, a nie porcjami — znak wielobajtowy rozcięty
     * na granicy porcji rozpadłby się na dwa znaki zastępcze, co przy polskich
     * tekstach psuło co któryś wyraz.
     */
    /**
     * Czyta początek pliku lokalnego (do [limit]) jako tekst. Plik binarny
     * (obraz, archiwum…) daje [NotTextException] zamiast sieczki znaków.
     */
    fun readFile(file: java.io.File, limit: Int = DEFAULT_LIMIT): TextDocument {
        val bytes = file.inputStream().use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (buffer.size() < limit) {
                val count = input.read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
                if (count <= 0) break
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        }
        if (looksBinary(bytes)) throw NotTextException()
        return read(bytes, totalBytes = file.length(), limit = limit)
    }

    fun read(bytes: ByteArray, totalBytes: Long, limit: Int = DEFAULT_LIMIT): TextDocument {
        val truncated = totalBytes > limit
        val (text, charset) = decode(bytes)
        return TextDocument(
            text = text,
            charset = charset,
            truncated = truncated,
            totalBytes = totalBytes,
            lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        )
    }
}

/** Plik nie jest tekstem — czytnik pokazuje wtedy komunikat zamiast znaków. */
class NotTextException : IllegalArgumentException("not a text file")
