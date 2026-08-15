package pl.arma.files.net

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser odpowiedzi PROPFIND.
 *
 * Serwery WebDAV używają różnych prefiksów przestrzeni nazw (`D:`, `d:`,
 * `lp1:` albo żadnego), więc elementy dopasowujemy po nazwie lokalnej.
 * Korzystamy z `DocumentBuilder` zamiast androidowego `XmlPullParser`, dzięki
 * czemu parser jest zwykłym kodem JVM i ma testy bez emulatora.
 */
object WebDavXml {

    /** Odpowiedź serwera dla jednego zasobu. [href] jest zakodowany procentowo. */
    data class Response(
        val href: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long,
        val displayName: String?
    )

    fun parse(xml: String): List<Response> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Odpowiedź pochodzi z sieci — blokujemy encje zewnętrzne, żeby
            // serwer nie mógł zmusić klienta do czytania lokalnych plików.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        return document.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) }
                .filterIsInstance<Element>()
                .filter { localName(it) == "response" }
                .mapNotNull(::toResponse)
        }
    }

    private fun toResponse(element: Element): Response? {
        val href = firstText(element, "href")?.trim() ?: return null
        val collection = descendants(element).any { localName(it) == "collection" }
        val length = firstText(element, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
        val contentType = firstText(element, "getcontenttype")?.trim().orEmpty()
        val modified = firstText(element, "getlastmodified")?.let(::parseDate) ?: 0L
        val display = firstText(element, "displayname")?.trim()?.takeIf { it.isNotBlank() }
        return Response(
            href = href,
            // Niektóre serwery nie zwracają <collection/>, ale oznaczają katalog
            // typem treści httpd/unix-directory.
            isDirectory = collection || contentType == "httpd/unix-directory",
            size = if (collection) 0L else length,
            modified = modified,
            displayName = display
        )
    }

    private fun descendants(element: Element): List<Element> {
        val nodes = element.getElementsByTagName("*")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun firstText(element: Element, name: String): String? =
        descendants(element).firstOrNull { localName(it) == name }?.let(::textOf)

    private fun textOf(element: Element): String {
        val builder = StringBuilder()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                builder.append(child.nodeValue)
            }
        }
        return builder.toString()
    }

    private fun localName(node: Node): String =
        node.nodeName.substringAfterLast(':').lowercase(Locale.ROOT)

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX"
    )

    private fun parseDate(value: String): Long {
        val trimmed = value.trim()
        DATE_FORMATS.forEach { pattern ->
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                return format.parse(trimmed)?.time ?: 0L
            }
        }
        return 0L
    }
}
