package pl.arma.viewer.cad

import java.io.InputStream

/** Rysunek bez obiektów obszaru modelu — te idą od razu do [DxfParser.parse]. */
class CadDrawing(
    val header: CadHeader,
    val layers: Map<String, CadLayer>,
    val lineTypes: Map<String, CadLineType>,
    val styles: Map<String, CadTextStyle>,
    val blocks: Map<String, CadBlock>,
    /** Rodzaje obiektów, których nie umiemy narysować, z liczbą wystąpień. */
    val skipped: Map<String, Int>
)

/**
 * Czytnik DXF budujący model rysunku.
 *
 * Obiekty z obszaru modelu nie są gromadzone, tylko przekazywane do [onModel]
 * — duży rysunek ma ich setki tysięcy, a i tak zaraz zamieniają się w łamane.
 */
object DxfParser {

    fun parse(input: InputStream, onModel: (CadEntity) -> Unit): CadDrawing {
        DxfReader(input).use { reader ->
            return Session(reader, onModel).run()
        }
    }

    /** Grupy jednego obiektu: kody i wartości w kolejności z pliku. */
    private class Groups {
        var size = 0
        var codes = IntArray(64)
        var numbers = DoubleArray(64)
        var strings = arrayOfNulls<String>(64)

        fun clear() {
            size = 0
        }

        fun add(reader: DxfReader, intern: (String) -> String) {
            if (size == codes.size) {
                codes = codes.copyOf(size * 2)
                numbers = numbers.copyOf(size * 2)
                strings = strings.copyOf(size * 2)
            }
            val code = reader.code
            codes[size] = code
            when (DxfReader.valueType(code)) {
                DxfReader.ValueType.STRING -> {
                    val s = reader.string
                    // Nazwy warstw, stylów i bloków powtarzają się tysiące razy.
                    strings[size] = if (code == 8 || code == 6 || code == 7 || code == 2) intern(s) else s
                    numbers[size] = 0.0
                }
                DxfReader.ValueType.BINARY -> strings[size] = null
                else -> {
                    numbers[size] = reader.double
                    strings[size] = null
                }
            }
            size++
        }

        fun index(code: Int): Int {
            for (i in 0 until size) if (codes[i] == code) return i
            return -1
        }

        fun d(code: Int, default: Double = 0.0): Double = index(code).let { if (it < 0) default else numbers[it] }
        fun i(code: Int, default: Int = 0): Int = index(code).let { if (it < 0) default else numbers[it].toInt() }
        fun s(code: Int): String? = index(code).let { if (it < 0) null else strings[it] }
        fun has(code: Int) = index(code) >= 0
    }

    private class Session(private val reader: DxfReader, private val onModel: (CadEntity) -> Unit) {
        private val names = HashMap<String, String>()
        private val intern: (String) -> String = { s -> names.getOrPut(s) { s } }
        private val g = Groups()
        private var header = CadHeader(null, 0, 1.0, true, null, null)
        private val layers = LinkedHashMap<String, CadLayer>()
        private val lineTypes = HashMap<String, CadLineType>()
        private val styles = HashMap<String, CadTextStyle>()
        private val blocks = HashMap<String, CadBlock>()
        private val skipped = HashMap<String, Int>()
        private var mleaders = 0

        fun run(): CadDrawing {
            while (reader.next()) {
                if (reader.code == 0 && reader.valueIs("SECTION")) {
                    if (!reader.next()) break
                    when (reader.string) {
                        "HEADER" -> readHeader()
                        "TABLES" -> readTables()
                        "BLOCKS" -> readBlocks()
                        "ENTITIES" -> readEntities(null)
                        else -> skipSection()
                    }
                }
            }
            return CadDrawing(header, layers, lineTypes, styles, blocks, skipped)
        }

        private fun skipSection() {
            while (reader.next()) if (reader.code == 0 && reader.valueIs("ENDSEC")) return
        }

        private fun readHeader() {
            var version: String? = null
            var codepage: String? = null
            var insUnits = 0
            var ltScale = 1.0
            var metric = true
            var extMin: DoubleArray? = null
            var extMax: DoubleArray? = null
            var variable: String? = null
            while (reader.next()) {
                val code = reader.code
                if (code == 0 && reader.valueIs("ENDSEC")) break
                if (code == 9) {
                    variable = reader.string
                    continue
                }
                when (variable) {
                    "\$ACADVER" -> if (code == 1) version = reader.string
                    "\$DWGCODEPAGE" -> if (code == 3) codepage = reader.string
                    "\$INSUNITS" -> if (code == 70) insUnits = reader.int
                    "\$LTSCALE" -> if (code == 40) ltScale = reader.double
                    "\$MEASUREMENT" -> if (code == 70) metric = reader.int == 1
                    "\$EXTMIN" -> {
                        if (extMin == null) extMin = DoubleArray(2)
                        if (code == 10) extMin[0] = reader.double
                        if (code == 20) extMin[1] = reader.double
                    }
                    "\$EXTMAX" -> {
                        if (extMax == null) extMax = DoubleArray(2)
                        if (code == 10) extMax[0] = reader.double
                        if (code == 20) extMax[1] = reader.double
                    }
                }
            }
            reader.charset = DxfReader.charsetFor(codepage, version)
            header = CadHeader(version, insUnits, if (ltScale > 0) ltScale else 1.0, metric, extMin, extMax)
        }

        private fun readGroups() {
            g.clear()
            while (reader.next()) {
                if (reader.code == 0) {
                    reader.pushBack()
                    return
                }
                g.add(reader, intern)
            }
        }

        private fun readTables() {
            while (reader.next()) {
                if (reader.code != 0) continue
                when {
                    reader.valueIs("ENDSEC") -> return
                    reader.valueIs("LAYER") -> {
                        readGroups()
                        val name = g.s(2) ?: continue
                        val color = g.i(62, 7)
                        val flags = g.i(70)
                        layers[name] = CadLayer(
                            name = name,
                            color = kotlin.math.abs(color).let { if (it == 0) 7 else it },
                            trueColor = if (g.has(420)) g.i(420) and 0xFFFFFF else null,
                            lineType = g.s(6) ?: "CONTINUOUS",
                            lineWeight = g.i(370, -3),
                            off = color < 0,
                            frozen = flags and 1 != 0,
                            locked = flags and 4 != 0,
                            plot = g.i(290, 1) != 0
                        )
                    }
                    reader.valueIs("LTYPE") -> {
                        readGroups()
                        val name = g.s(2) ?: continue
                        val pattern = ArrayList<Double>()
                        for (k in 0 until g.size) if (g.codes[k] == 49) pattern += g.numbers[k]
                        lineTypes[name.uppercase()] = CadLineType(name, pattern.toDoubleArray())
                    }
                    reader.valueIs("STYLE") -> {
                        readGroups()
                        val name = g.s(2) ?: continue
                        styles[name.uppercase()] = CadTextStyle(
                            name, g.s(3).orEmpty(), g.d(40), g.d(41, 1.0).let { if (it <= 0) 1.0 else it }, g.d(50)
                        )
                    }
                }
            }
        }

        private fun readBlocks() {
            var current: CadBlock? = null
            while (reader.next()) {
                if (reader.code != 0) continue
                when {
                    reader.valueIs("ENDSEC") -> return
                    reader.valueIs("BLOCK") -> {
                        readGroups()
                        val name = g.s(2) ?: g.s(3) ?: ""
                        current = CadBlock(name, g.d(10), g.d(20), g.i(70), ArrayList())
                        blocks[name.uppercase()] = current
                    }
                    reader.valueIs("ENDBLK") -> {
                        readGroups()
                        current = null
                    }
                    else -> {
                        val block = current
                        val type = reader.string
                        val entity = readEntity(type)
                        if (entity != null && block != null) block.entities += entity
                    }
                }
            }
        }

        private fun readEntities(@Suppress("UNUSED_PARAMETER") unused: Nothing?) {
            while (reader.next()) {
                if (reader.code != 0) continue
                if (reader.valueIs("ENDSEC")) return
                val type = reader.string
                val entity = readEntity(type) ?: continue
                if (!entity.paperSpace) onModel(entity)
            }
        }

        private fun common(e: CadEntity): CadEntity {
            e.layer = g.s(8) ?: "0"
            e.color = g.i(62, 256)
            if (g.has(420)) e.trueColor = g.i(420) and 0xFFFFFF
            e.lineType = g.s(6) ?: "BYLAYER"
            e.lineWeight = g.i(370, -1)
            e.lineTypeScale = g.d(48, 1.0)
            e.invisible = g.i(60) == 1
            if (g.has(440)) {
                val t = g.i(440)
                // 0x020000TT: przezroczystość ustawiona wprost, TT to krycie.
                if (t and 0x02000000 != 0) e.alpha = t and 0xFF
            }
            e.paperSpace = g.i(67) == 1
            if (g.has(210) || g.has(220) || g.has(230)) {
                e.ex = g.d(210)
                e.ey = g.d(220)
                e.ez = g.d(230, 1.0)
            }
            return e
        }

        private fun readEntity(type: String): CadEntity? {
            readGroups()
            val entity: CadEntity? = when (type) {
                "LINE" -> CadLine(g.d(10), g.d(20), g.d(11), g.d(21))
                "POINT" -> CadPoint(g.d(10), g.d(20))
                "CIRCLE" -> CadCircle(g.d(10), g.d(20), g.d(40))
                "ARC" -> CadArc(g.d(10), g.d(20), g.d(40), g.d(50), g.d(51))
                "ELLIPSE" -> CadEllipse(g.d(10), g.d(20), g.d(11), g.d(21), g.d(40, 1.0), g.d(41), g.d(42, 2 * Math.PI))
                "LWPOLYLINE" -> lwPolyline()
                "POLYLINE" -> polyline()
                "SPLINE" -> spline()
                "TEXT", "ATTRIB" -> text()
                "ATTDEF" -> null
                "MTEXT" -> mtext()
                "INSERT" -> insert()
                "ACAD_TABLE" -> g.s(2)?.let { CadInsert(it, g.d(10), g.d(20), 1.0, 1.0, 0.0, 1, 1, 0.0, 0.0, emptyList()) }
                "DIMENSION", "ARC_DIMENSION", "LARGE_RADIAL_DIMENSION" -> g.s(2)?.let {
                    CadDimension(it, g.s(1).orEmpty(), g.d(42, Double.NaN), g.d(11), g.d(21))
                }
                "LEADER" -> leader()
                "MULTILEADER", "MLEADER" -> mleader()
                "HATCH", "MPOLYGON" -> hatch()
                "SOLID", "TRACE" -> CadSolid(
                    doubleArrayOf(g.d(10), g.d(11), g.d(13, g.d(12)), g.d(12)),
                    doubleArrayOf(g.d(20), g.d(21), g.d(23, g.d(22)), g.d(22)),
                    filled = true
                )
                "3DFACE" -> CadSolid(
                    doubleArrayOf(g.d(10), g.d(11), g.d(12), g.d(13, g.d(12))),
                    doubleArrayOf(g.d(20), g.d(21), g.d(22), g.d(23, g.d(22))),
                    filled = false
                )
                "WIPEOUT" -> wipeout()
                "IMAGE" -> imageFrame()
                "SEQEND", "VIEWPORT", "XLINE", "RAY" -> null
                else -> {
                    skipped[type] = (skipped[type] ?: 0) + 1
                    null
                }
            }
            return entity?.let(::common)
        }

        private fun lwPolyline(): CadEntity {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            val bulges = ArrayList<Double>()
            for (k in 0 until g.size) {
                when (g.codes[k]) {
                    10 -> { xs += g.numbers[k]; bulges += 0.0 }
                    20 -> ys += g.numbers[k]
                    42 -> if (bulges.isNotEmpty()) bulges[bulges.lastIndex] = g.numbers[k]
                }
            }
            val n = minOf(xs.size, ys.size)
            return CadPolyline(
                xs.subList(0, n).toDoubleArray(), ys.subList(0, n).toDoubleArray(), bulges.subList(0, n).toDoubleArray(),
                closed = g.i(70) and 1 != 0, width = g.d(43)
            )
        }

        /** POLYLINE z wierzchołkami VERTEX aż do SEQEND. */
        private fun polyline(): CadEntity? {
            val flags = g.i(70)
            val headerLayer = g.s(8)
            val saved = Groups().also { copy -> copyGroups(g, copy) }
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            val bulges = ArrayList<Double>()
            while (reader.next()) {
                if (reader.code != 0) continue
                if (reader.valueIs("VERTEX")) {
                    readGroups()
                    val vf = g.i(70)
                    // Punkty sterujące ramki krzywej i ściany siatki wielościennej
                    // nie są wierzchołkami łamanej.
                    if (vf and 16 != 0 || (vf and 128 != 0 && vf and 64 == 0)) continue
                    xs += g.d(10)
                    ys += g.d(20)
                    bulges += g.d(42)
                } else if (reader.valueIs("SEQEND")) {
                    readGroups()
                    break
                } else {
                    reader.pushBack()
                    break
                }
            }
            copyGroups(saved, g)
            if (headerLayer == null && xs.isEmpty()) return null
            // Siatki 3D (16) i wielościany (64) rysujemy jako łamaną wierzchołków.
            return CadPolyline(
                xs.toDoubleArray(), ys.toDoubleArray(), bulges.toDoubleArray(),
                closed = flags and 1 != 0, width = g.d(40), world = flags and (8 or 16 or 64) != 0
            )
        }

        private fun copyGroups(from: Groups, to: Groups) {
            to.size = from.size
            if (to.codes.size < from.size) {
                to.codes = IntArray(from.codes.size)
                to.numbers = DoubleArray(from.codes.size)
                to.strings = arrayOfNulls(from.codes.size)
            }
            System.arraycopy(from.codes, 0, to.codes, 0, from.size)
            System.arraycopy(from.numbers, 0, to.numbers, 0, from.size)
            System.arraycopy(from.strings, 0, to.strings, 0, from.size)
        }

        private fun spline(): CadEntity {
            val knots = ArrayList<Double>()
            val cx = ArrayList<Double>()
            val cy = ArrayList<Double>()
            val weights = ArrayList<Double>()
            val fx = ArrayList<Double>()
            val fy = ArrayList<Double>()
            for (k in 0 until g.size) {
                when (g.codes[k]) {
                    40 -> knots += g.numbers[k]
                    10 -> cx += g.numbers[k]
                    20 -> cy += g.numbers[k]
                    41 -> weights += g.numbers[k]
                    11 -> fx += g.numbers[k]
                    21 -> fy += g.numbers[k]
                }
            }
            return CadSpline(
                degree = g.i(71, 3),
                knots = knots.toDoubleArray(),
                controlX = cx.toDoubleArray(), controlY = cy.subList(0, minOf(cy.size, cx.size)).toDoubleArray(),
                weights = weights.toDoubleArray(),
                fitX = fx.toDoubleArray(), fitY = fy.subList(0, minOf(fy.size, fx.size)).toDoubleArray(),
                closed = g.i(70) and 1 != 0
            )
        }

        private fun text(): CadEntity {
            val flags = g.i(71)
            return CadText(
                x = g.d(10), y = g.d(20),
                ax = if (g.has(11)) g.d(11) else null, ay = if (g.has(21)) g.d(21) else null,
                height = g.d(40, 1.0),
                rotation = g.d(50),
                widthFactor = g.d(41, 1.0).let { if (it <= 0) 1.0 else it },
                oblique = g.d(51),
                text = DxfText.specialCodes(g.s(1).orEmpty()),
                hAlign = g.i(72),
                vAlign = g.i(if (g.has(74)) 74 else 73),
                style = g.s(7) ?: "STANDARD",
                mirrorX = flags and 2 != 0,
                mirrorY = flags and 4 != 0
            )
        }

        private fun mtext(): CadEntity {
            val raw = StringBuilder()
            for (k in 0 until g.size) if (g.codes[k] == 3) raw.append(g.strings[k].orEmpty())
            raw.append(g.s(1).orEmpty())
            val rotation = if (g.has(11)) kotlin.math.atan2(g.d(21), g.d(11)) else Math.toRadians(g.d(50))
            return CadMText(
                x = g.d(10), y = g.d(20),
                height = g.d(40, 1.0),
                width = g.d(41),
                attachment = g.i(71, 1),
                rotation = rotation,
                text = DxfText.mtextPlain(raw.toString()),
                lineSpacing = g.d(44, 1.0).let { if (it <= 0) 1.0 else it },
                style = g.s(7) ?: "STANDARD"
            )
        }

        private fun insert(): CadEntity? {
            val block = g.s(2) ?: return null
            val hasAttributes = g.i(66) == 1
            val saved = Groups().also { copyGroups(g, it) }
            val attributes = ArrayList<CadText>()
            if (hasAttributes) {
                while (reader.next()) {
                    if (reader.code != 0) continue
                    if (reader.valueIs("ATTRIB")) {
                        val attribute = readEntity("ATTRIB") as? CadText
                        if (attribute != null && !attribute.invisible && g.i(70) and 1 == 0) attributes += attribute
                    } else if (reader.valueIs("SEQEND")) {
                        readGroups()
                        break
                    } else {
                        reader.pushBack()
                        break
                    }
                }
            }
            copyGroups(saved, g)
            return CadInsert(
                block = block,
                x = g.d(10), y = g.d(20),
                sx = g.d(41, 1.0), sy = g.d(42, 1.0),
                rotation = g.d(50),
                columns = g.i(70, 1).coerceAtLeast(1), rows = g.i(71, 1).coerceAtLeast(1),
                columnSpacing = g.d(44), rowSpacing = g.d(45),
                attributes = attributes
            )
        }

        private fun leader(): CadEntity {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            for (k in 0 until g.size) {
                when (g.codes[k]) {
                    10 -> xs += g.numbers[k]
                    20 -> ys += g.numbers[k]
                }
            }
            val n = minOf(xs.size, ys.size)
            return CadLeader(xs.subList(0, n).toDoubleArray(), ys.subList(0, n).toDoubleArray(), g.i(71, 1) == 1)
        }

        /**
         * MLEADER: linie odniesienia z sekcji „LEADER_LINE{” i treść MTEXT z grupy
         * 304. Rysujemy je jako blok złożony z polilinii i tekstu.
         */
        private fun mleader(): CadEntity? {
            val name = "*MLEADER${mleaders++}"
            val entities = ArrayList<CadEntity>()
            var inLine = false
            var inLeader = false
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            var leaderX = Double.NaN
            var leaderY = Double.NaN
            var text: String? = null
            var textX = 0.0
            var textY = 0.0
            var height = Double.NaN
            for (k in 0 until g.size) {
                val c = g.codes[k]
                val s = g.strings[k]
                when {
                    c == 302 && s == "LEADER{" -> { inLeader = true; leaderX = Double.NaN; leaderY = Double.NaN }
                    c == 303 -> inLeader = false
                    c == 304 && s == "LEADER_LINE{" -> { inLine = true; xs.clear(); ys.clear() }
                    c == 305 -> {
                        inLine = false
                        if (!leaderX.isNaN()) { xs += leaderX; ys += leaderY }
                        if (xs.size >= 2) {
                            entities += CadPolyline(xs.toDoubleArray(), ys.subList(0, xs.size).toDoubleArray(), DoubleArray(xs.size), false)
                        }
                    }
                    c == 304 && !inLine -> text = s
                    c == 10 && inLine -> xs += g.numbers[k]
                    c == 20 && inLine -> ys += g.numbers[k]
                    c == 10 && inLeader && !inLine -> leaderX = g.numbers[k]
                    c == 20 && inLeader && !inLine -> leaderY = g.numbers[k]
                    c == 12 -> textX = g.numbers[k]
                    c == 22 -> textY = g.numbers[k]
                    c == 41 && height.isNaN() -> height = g.numbers[k]
                }
            }
            text?.let {
                entities += CadMText(textX, textY, if (height.isNaN() || height <= 0) 2.5 else height, 0.0, 1, 0.0, DxfText.mtextPlain(it), 1.0, "STANDARD")
            }
            if (entities.isEmpty()) return null
            blocks[name] = CadBlock(name, 0.0, 0.0, 0, entities.onEach { it.layer = "0"; it.color = 0 }.toMutableList())
            return CadInsert(name, 0.0, 0.0, 1.0, 1.0, 0.0, 1, 1, 0.0, 0.0, emptyList())
        }

        private fun hatch(): CadEntity {
            val solid = g.i(70) == 1
            val loops = ArrayList<DoubleArray>()
            val pattern = ArrayList<HatchLine>()
            var k = g.index(91)
            if (k >= 0) {
                val loopCount = g.numbers[k].toInt()
                k++
                repeat(loopCount) {
                    // Szukamy znacznika typu konturu (92).
                    while (k < g.size && g.codes[k] != 92) k++
                    if (k >= g.size) return@repeat
                    val pathType = g.numbers[k].toInt()
                    k++
                    val pts = Doubles()
                    if (pathType and 2 != 0) {
                        // Kontur jako polilinia: 72 ma łuki, 73 zamknięcie, 93 liczba wierzchołków.
                        var hasBulge = false
                        var count = 0
                        while (k < g.size && g.codes[k] != 93) {
                            if (g.codes[k] == 72) hasBulge = g.numbers[k] != 0.0
                            k++
                        }
                        if (k < g.size) { count = g.numbers[k].toInt(); k++ }
                        val vx = ArrayList<Double>()
                        val vy = ArrayList<Double>()
                        val vb = ArrayList<Double>()
                        while (k < g.size && vx.size <= count) {
                            when (g.codes[k]) {
                                10 -> { if (vx.size == count) break; vx += g.numbers[k]; vb += 0.0 }
                                20 -> vy += g.numbers[k]
                                42 -> if (hasBulge && vb.isNotEmpty()) vb[vb.lastIndex] = g.numbers[k]
                                97, 92, 75 -> break
                            }
                            k++
                        }
                        val n = minOf(vx.size, vy.size)
                        CadGeometry.bulgePolyline(vx.subList(0, n).toDoubleArray(), vy.subList(0, n).toDoubleArray(), vb.subList(0, n).toDoubleArray(), true, pts)
                    } else {
                        while (k < g.size && g.codes[k] != 93) k++
                        val edges = if (k < g.size) g.numbers[k++].toInt() else 0
                        repeat(edges) {
                            while (k < g.size && g.codes[k] != 72) k++
                            if (k >= g.size) return@repeat
                            val edgeType = g.numbers[k].toInt()
                            k++
                            fun next(code: Int): Double {
                                while (k < g.size && g.codes[k] != code) k++
                                return if (k < g.size) g.numbers[k++] else 0.0
                            }
                            when (edgeType) {
                                1 -> {
                                    val x1 = next(10); val y1 = next(20); val x2 = next(11); val y2 = next(21)
                                    if (pts.isEmpty()) { pts += x1; pts += y1 }
                                    pts += x2; pts += y2
                                }
                                2 -> {
                                    val cx = next(10); val cy = next(20); val r = next(40)
                                    val a0 = next(50); val a1 = next(51); val ccw = next(73) != 0.0
                                    CadGeometry.arcPoints(cx, cy, r, a0, a1, ccw, pts)
                                }
                                3 -> {
                                    val cx = next(10); val cy = next(20); val mx = next(11); val my = next(21)
                                    val ratio = next(40); val a0 = next(50); val a1 = next(51); val ccw = next(73) != 0.0
                                    CadGeometry.ellipseEdge(cx, cy, mx, my, ratio, a0, a1, ccw, pts)
                                }
                                4 -> {
                                    val degree = next(94).toInt()
                                    while (k < g.size && g.codes[k] != 95) k++
                                    val knotCount = if (k < g.size) g.numbers[k++].toInt() else 0
                                    val controlCount = if (k < g.size && g.codes[k] == 96) g.numbers[k++].toInt() else 0
                                    val knots = DoubleArray(knotCount) { next(40) }
                                    val cxs = DoubleArray(controlCount)
                                    val cys = DoubleArray(controlCount)
                                    for (c in 0 until controlCount) { cxs[c] = next(10); cys[c] = next(20) }
                                    CadGeometry.nurbs(degree, knots, cxs, cys, DoubleArray(0), pts)
                                }
                            }
                        }
                    }
                    if (pts.size >= 6) loops += pts.toArray()
                }
            }
            if (!solid) {
                // Definicja wzoru: 78 liczba linii, potem 53/43/44/45/46/79/49.
                val start = g.index(78)
                if (start >= 0) {
                    var p = start + 1
                    val lines = g.numbers[start].toInt()
                    repeat(lines) {
                        while (p < g.size && g.codes[p] != 53) p++
                        if (p >= g.size) return@repeat
                        val angle = g.numbers[p++]
                        fun next(code: Int): Double = if (p < g.size && g.codes[p] == code) g.numbers[p++] else 0.0
                        val bx = next(43); val by = next(44); val ox = next(45); val oy = next(46)
                        val dashCount = next(79).toInt()
                        val dashes = DoubleArray(dashCount) { next(49) }
                        pattern += HatchLine(angle, bx, by, ox, oy, dashes)
                    }
                }
            }
            return CadHatch(solid, loops, pattern)
        }

        private fun wipeout(): CadEntity? {
            val ix = g.d(10); val iy = g.d(20)
            val ux = g.d(11); val uy = g.d(21)
            val vx = g.d(12); val vy = g.d(22)
            val bx = ArrayList<Double>()
            val by = ArrayList<Double>()
            for (k in 0 until g.size) {
                when (g.codes[k]) {
                    14 -> bx += g.numbers[k]
                    24 -> by += g.numbers[k]
                }
            }
            if (bx.size == 2 && by.size >= 2) {
                // Prostokąt opisany dwoma narożnikami.
                val (x0, x1) = bx[0] to bx[1]
                val (y0, y1) = by[0] to by[1]
                bx.clear(); by.clear()
                bx += listOf(x0, x1, x1, x0); by += listOf(y0, y0, y1, y1)
            }
            val n = minOf(bx.size, by.size)
            if (n < 3) return null
            val xs = DoubleArray(n)
            val ys = DoubleArray(n)
            for (k in 0 until n) {
                val u = bx[k] + .5
                val v = .5 - by[k]
                xs[k] = ix + ux * u + vx * v
                ys[k] = iy + uy * u + vy * v
            }
            return CadWipeout(xs, ys)
        }

        private fun imageFrame(): CadEntity {
            val ix = g.d(10); val iy = g.d(20)
            val ux = g.d(11); val uy = g.d(21)
            val vx = g.d(12); val vy = g.d(22)
            val w = g.d(13, 1.0); val h = g.d(23, 1.0)
            return CadPolyline(
                doubleArrayOf(ix, ix + ux * w, ix + ux * w + vx * h, ix + vx * h),
                doubleArrayOf(iy, iy + uy * w, iy + uy * w + vy * h, iy + vy * h),
                DoubleArray(4), closed = true, world = true
            )
        }
    }
}
