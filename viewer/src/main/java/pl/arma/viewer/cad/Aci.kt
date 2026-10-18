package pl.arma.viewer.cad

/** Paleta AutoCAD Color Index jako RGB. */
object Aci {
    private val table = IntArray(256).also { t ->
        val fixed = intArrayOf(
            0x000000, 0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF, 0x808080, 0xC0C0C0
        )
        for (i in fixed.indices) t[i] = fixed[i]
        // 10–249: 24 odcienie co 15°, w każdym 5 jasności, a dla nieparzystych
        // połowa nasycenia.
        val values = intArrayOf(255, 204, 153, 127, 76)
        for (i in 10..249) {
            val hue = ((i - 10) / 10) * 15.0
            val shade = (i - 10) % 10
            val v = values[shade / 2]
            val s = if (shade % 2 == 0) 1.0 else 0.5
            t[i] = hsv(hue, s, v)
        }
        val grays = intArrayOf(0x333333, 0x505050, 0x696969, 0x828282, 0xBEBEBE, 0xFFFFFF)
        for (k in grays.indices) t[250 + k] = grays[k]
    }

    private fun hsv(hue: Double, s: Double, v: Int): Int {
        val h = (hue / 60.0) % 6
        val i = h.toInt()
        val f = h - i
        val p = (v * (1 - s)).toInt()
        val q = (v * (1 - s * f)).toInt()
        val t = (v * (1 - s * (1 - f))).toInt()
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return (r shl 16) or (g shl 8) or b
    }

    fun rgb(index: Int): Int = table[index.coerceIn(0, 255)]
}
