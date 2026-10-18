package pl.arma.viewer.cad

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Prawdziwe rysunki z katalogu wskazanego w ARMA_CAD_SAMPLES (DXF/DXB).
 * Nie ma ich w repozytorium — test uruchamia się tylko lokalnie, żeby
 * sprawdzić czytnik na plikach z ZWCAD, AutoCAD i LibreDWG.
 */
class RealDrawingsTest {

    @Test
    fun `wszystkie rysunki daja sie przeczytac`() {
        val dir = System.getenv("ARMA_CAD_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.extension.lowercase() in setOf("dxf", "dxb") }!!.sortedBy { it.length() }
        val report = StringBuilder()
        var failures = 0
        for (file in files) {
            val start = System.nanoTime()
            val result = runCatching {
                val builder = CadSceneBuilder()
                val drawing = file.inputStream().use { DxfParser.parse(it, builder::collect) }
                builder.build(drawing) to builder.truncated
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            result.onSuccess { (scene, truncated) ->
                report.append(
                    String.format(
                        "%-50s %7d ms  linie %8d  wypełnienia %6d  teksty %6d  punkty %9d  warstwy %4d  %s%s\n",
                        file.name.take(50), ms, scene.strokes.count, scene.fills.count, scene.texts.size,
                        scene.strokes.points, scene.layerNames.size,
                        if (truncated) "[UCIĘTE] " else "",
                        scene.skipped.entries.joinToString { "${it.key}:${it.value}" }
                    )
                )
            }.onFailure {
                failures++
                report.append("${file.name}: BŁĄD ${it::class.simpleName}: ${it.message}\n")
            }
        }
        println(report)
        File(System.getProperty("java.io.tmpdir"), "arma_cad_report.txt").writeText(report.toString())
        assert(failures == 0) { report.toString() }
    }
}
