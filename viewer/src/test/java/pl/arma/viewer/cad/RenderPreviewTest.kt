package pl.arma.viewer.cad

import android.graphics.Bitmap
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Podgląd renderowania na komputerze: rysunki z ARMA_CAD_RENDER (lista plików
 * rozdzielona „:”) trafiają jako PNG do ARMA_CAD_OUT. Tylko lokalnie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderPreviewTest {
    @Test
    fun render() {
        val list = System.getenv("ARMA_CAD_RENDER")
        val out = System.getenv("ARMA_CAD_OUT")?.let(::File)
        assumeTrue(!list.isNullOrBlank() && out != null)
        out!!.mkdirs()
        for (path in list!!.split(':')) {
            val file = File(path)
            val builder = CadSceneBuilder()
            val drawing = file.inputStream().use { DxfParser.parse(it, builder::collect) }
            val scene = builder.build(drawing)
            val renderer = CadRenderer(scene)
            val visible = scene.layerInitiallyVisible.copyOf()
            val w = 1600
            val h = (w * scene.height / scene.width).toInt().coerceIn(1, 3000)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val start = System.nanoTime()
            renderer.render(bitmap, w / scene.width, 0f, 0f, visible, CadBackground.LIGHT)
            println("${file.name}: ${(System.nanoTime() - start) / 1_000_000} ms")
            File(out, file.nameWithoutExtension + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Fragment w dużym powiększeniu: środek rysunku, 1/8 szerokości.
            val zoom = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
            val scale = 8 * 1200 / scene.width
            renderer.render(zoom, scale, scene.width * scale / 2 - 600, scene.height * scale / 2 - 450, visible, CadBackground.LIGHT)
            File(out, file.nameWithoutExtension + "_zoom.png").outputStream().use { zoom.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
