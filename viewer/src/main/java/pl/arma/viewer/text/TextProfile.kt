package pl.arma.viewer.text

/**
 * Rozpoznanie rodzaju pliku tekstowego. Wzorowane na profilach z TextForge,
 * ale zawężone do tego, czego potrzebuje czytnik: etykieta, sposób podglądu
 * i to, czy warto liczyć spis treści.
 */
data class TextProfile(
    val label: String,
    val kind: Kind,
    val code: Boolean
) {
    enum class Kind { MARKDOWN, CODE, DATA, PLAIN }

    val outlineable: Boolean get() = kind == Kind.MARKDOWN || code

    companion object {
        /**
         * Czy plik warto otwierać czytnikiem. Rozstrzygamy po typie MIME i po
         * rozszerzeniu — samo MIME nie wystarcza, bo FTP i SMB go nie podają,
         * a wtedy każdy plik byłby „application/octet-stream".
         */
        fun isTextFile(fileName: String, mimeType: String): Boolean {
            if (mimeType.startsWith("text/")) return true
            if (mimeType.contains("json") || mimeType.contains("xml") ||
                mimeType.contains("yaml") || mimeType.contains("javascript")
            ) {
                return true
            }
            val name = fileName.substringAfterLast('/').lowercase()
            if (name in NAMED_CODE || name in NAMED_CONFIG) return true
            val extension = name.substringAfterLast('.', "")
            return extension.isNotBlank() && extension in TEXT_EXTENSIONS
        }

        private val TEXT_EXTENSIONS: Set<String> by lazy {
            CODE_EXTENSIONS + setOf(
                "md", "markdown", "mdown", "mkd",
                "json", "jsonc", "json5", "geojson",
                "yaml", "yml", "xml", "xsd", "xsl", "plist", "rss",
                "html", "htm", "xhtml",
                "toml", "ini", "cfg", "conf", "properties", "env", "desktop", "service",
                "csv", "tsv", "log", "diff", "patch",
                "txt", "nfo", "asc", "srt", "vtt", "sub", "lrc", "m3u", "m3u8"
            )
        }

        fun of(fileName: String): TextProfile {
            val name = fileName.substringAfterLast('/').lowercase()
            val extension = name.substringAfterLast('.', "")

            // Pliki bez rozszerzenia, rozpoznawane po samej nazwie.
            if (name in NAMED_CODE) return TextProfile(name.uppercase(), Kind.CODE, code = true)
            if (name in NAMED_CONFIG) {
                return TextProfile(name.removePrefix(".").uppercase(), Kind.DATA, code = true)
            }

            return when (extension) {
                "md", "markdown", "mdown", "mkd" -> TextProfile("MARKDOWN", Kind.MARKDOWN, code = false)
                "json", "jsonc", "json5", "geojson" -> TextProfile("JSON", Kind.DATA, code = true)
                "yaml", "yml" -> TextProfile("YAML", Kind.DATA, code = true)
                "xml", "xsd", "xsl", "plist", "rss" -> TextProfile("XML", Kind.DATA, code = true)
                "html", "htm", "xhtml" -> TextProfile("HTML", Kind.CODE, code = true)
                "toml", "ini", "cfg", "conf", "properties", "env", "desktop", "service" ->
                    TextProfile(extension.uppercase(), Kind.DATA, code = true)
                "csv", "tsv" -> TextProfile(extension.uppercase(), Kind.DATA, code = true)
                "log", "diff", "patch" -> TextProfile(extension.uppercase(), Kind.PLAIN, code = true)
                in CODE_EXTENSIONS -> TextProfile(extension.uppercase(), Kind.CODE, code = true)
                // Pusta etykieta: czytnik podpisze ją przetłumaczonym „Tekst”.
                "txt", "nfo", "asc", "" -> TextProfile("", Kind.PLAIN, code = false)
                else -> TextProfile(extension.uppercase(), Kind.PLAIN, code = false)
            }
        }

        private val NAMED_CODE = setOf(
            "dockerfile", "containerfile", "makefile", "rakefile", "gemfile", "procfile", "cmakelists.txt"
        )

        private val NAMED_CONFIG = setOf(
            ".gitignore", ".gitattributes", ".editorconfig", ".npmrc", ".dockerignore", ".env"
        )

        private val CODE_EXTENSIONS = setOf(
            "kt", "kts", "java", "groovy", "gradle", "py", "pyw", "js", "mjs", "cjs", "ts", "tsx",
            "jsx", "css", "scss", "sass", "less", "sh", "bash", "zsh", "fish", "sql", "c", "h",
            "cpp", "hpp", "cc", "cxx", "cs", "go", "rs", "php", "rb", "swift", "dart", "lua", "r",
            "scala", "clj", "ex", "exs", "erl", "fs", "vb", "pas", "asm", "ps1", "bat", "cmd",
            "vue", "svelte", "tex", "graphql", "proto"
        )
    }
}

/** Pozycja spisu treści: nagłówek Markdown albo deklaracja w kodzie. */
data class OutlineEntry(val title: String, val line: Int, val level: Int)

object TextOutline {

    private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")

    /**
     * Deklaracje warte pokazania w spisie. Świadomie proste dopasowanie po
     * słowach kluczowych, a nie parsowanie — czytnik ma się otwierać od razu,
     * a nie budować drzewo składniowe.
     */
    private val CODE_DECLARATION = Regex(
        "^\\s*(?:@\\w+\\s+)*(?:public|private|internal|protected|open|final|abstract|suspend|static|export|default|async|inline)*\\s*" +
            "(class|interface|object|enum|fun|def|func|function|struct|trait|impl|type)\\s+([\\w`<>.]+)"
    )

    fun of(text: String, profile: TextProfile, limit: Int = 300): List<OutlineEntry> {
        if (!profile.outlineable) return emptyList()
        val entries = mutableListOf<OutlineEntry>()
        var fenced = false

        text.lineSequence().forEachIndexed { index, line ->
            if (entries.size >= limit) return entries

            if (profile.kind == TextProfile.Kind.MARKDOWN) {
                // Wewnątrz bloku kodu „#" to komentarz, a nie nagłówek.
                if (line.trimStart().startsWith("```")) {
                    fenced = !fenced
                    return@forEachIndexed
                }
                if (fenced) return@forEachIndexed
                MARKDOWN_HEADING.find(line)?.let { match ->
                    entries += OutlineEntry(
                        title = match.groupValues[2].trim(),
                        line = index,
                        level = match.groupValues[1].length
                    )
                }
            } else {
                CODE_DECLARATION.find(line)?.let { match ->
                    entries += OutlineEntry(
                        title = "${match.groupValues[1]} ${match.groupValues[2].trim('`')}",
                        line = index,
                        level = 1
                    )
                }
            }
        }
        return entries
    }
}
