package pl.arma.viewer.cad

/**
 * Obiekty rysunku w postaci prawie takiej jak w DXF. Współrzędne obiektów
 * płaskich (łuk, polilinia, tekst, wstawienie, kreskowanie) są w układzie
 * obiektu (OCS) wyznaczonym przez wektor [CadEntity.extrusion].
 */
sealed class CadEntity {
    var layer: String = "0"
    /** Kolor ACI: 0 = BYBLOCK, 256 = BYLAYER. */
    var color: Int = 256
    /** Kolor RGB z grupy 420; ma pierwszeństwo przed ACI. */
    var trueColor: Int? = null
    var lineType: String = "BYLAYER"
    /** Grubość w setnych milimetra; -1 BYLAYER, -2 BYBLOCK, -3 domyślna. */
    var lineWeight: Int = -1
    var lineTypeScale: Double = 1.0
    var invisible: Boolean = false
    /** Krycie 0–255 z grupy 440; 255 to pełne. */
    var alpha: Int = 255
    /** Obiekt z obszaru papieru (grupa 67). */
    var paperSpace: Boolean = false
    var ex: Double = 0.0
    var ey: Double = 0.0
    var ez: Double = 1.0

    val hasOcs: Boolean get() = !(ex == 0.0 && ey == 0.0 && ez > 0.0)
}

class CadLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : CadEntity()

class CadPoint(val x: Double, val y: Double) : CadEntity()

class CadCircle(val cx: Double, val cy: Double, val r: Double) : CadEntity()

/** Łuk przeciwnie do ruchu wskazówek zegara od [start] do [end], w stopniach. */
class CadArc(val cx: Double, val cy: Double, val r: Double, val start: Double, val end: Double) : CadEntity()

/** Elipsa w układzie świata: oś wielka jako wektor od środka, kąty parametryczne w radianach. */
class CadEllipse(
    val cx: Double, val cy: Double,
    val mx: Double, val my: Double,
    val ratio: Double,
    val start: Double, val end: Double
) : CadEntity()

/** Polilinia płaska lub 3D; [bulges] opisują łuki między wierzchołkami. */
class CadPolyline(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val bulges: DoubleArray,
    val closed: Boolean,
    /** Stała szerokość (grupa 43); polilinia szeroka rysuje się jak pasek. */
    val width: Double = 0.0,
    /** Współrzędne w świecie, nie w OCS (polilinia 3D). */
    val world: Boolean = false
) : CadEntity()

class CadSpline(
    val degree: Int,
    val knots: DoubleArray,
    val controlX: DoubleArray,
    val controlY: DoubleArray,
    val weights: DoubleArray,
    val fitX: DoubleArray,
    val fitY: DoubleArray,
    val closed: Boolean
) : CadEntity()

class CadText(
    val x: Double, val y: Double,
    /** Drugi punkt wyrównania (grupy 11/21), używany przy wyrównaniu innym niż lewo-dół. */
    val ax: Double?, val ay: Double?,
    val height: Double,
    /** Obrót w stopniach. */
    val rotation: Double,
    val widthFactor: Double,
    val oblique: Double,
    val text: String,
    val hAlign: Int,
    val vAlign: Int,
    val style: String,
    val mirrorX: Boolean,
    val mirrorY: Boolean
) : CadEntity()

class CadMText(
    val x: Double, val y: Double,
    val height: Double,
    val width: Double,
    /** 1..9: lewo-góra, środek-góra, prawo-góra, … prawo-dół. */
    val attachment: Int,
    /** Obrót w radianach. */
    val rotation: Double,
    val text: String,
    val lineSpacing: Double,
    val style: String
) : CadEntity()

class CadInsert(
    val block: String,
    val x: Double, val y: Double,
    val sx: Double, val sy: Double,
    /** Obrót w stopniach. */
    val rotation: Double,
    val columns: Int, val rows: Int,
    val columnSpacing: Double, val rowSpacing: Double,
    val attributes: List<CadText>
) : CadEntity()

/** Wymiar rysuje się jako anonimowy blok (*D…) przygotowany przez program CAD. */
class CadDimension(val block: String, val text: String, val measurement: Double, val textX: Double, val textY: Double) : CadEntity()

class CadLeader(val xs: DoubleArray, val ys: DoubleArray, val arrow: Boolean) : CadEntity()

/** Kreskowanie: kontury i — dla wzoru — rodziny linii z definicji w pliku. */
class CadHatch(
    val solid: Boolean,
    val loops: List<DoubleArray>,
    val pattern: List<HatchLine>
) : CadEntity()

class HatchLine(
    /** Kąt w stopniach. */
    val angle: Double,
    val baseX: Double, val baseY: Double,
    val offsetX: Double, val offsetY: Double,
    val dashes: DoubleArray
)

/** SOLID/TRACE: wypełniony trój- lub czworokąt (kolejność wierzchołków 1-2-4-3). */
class CadSolid(val xs: DoubleArray, val ys: DoubleArray, val filled: Boolean) : CadEntity()

/** Maska rysowana kolorem tła. */
class CadWipeout(val xs: DoubleArray, val ys: DoubleArray) : CadEntity()

class CadLayer(
    val name: String,
    val color: Int,
    val trueColor: Int?,
    val lineType: String,
    val lineWeight: Int,
    val off: Boolean,
    val frozen: Boolean,
    val locked: Boolean,
    val plot: Boolean
)

/** Wzór linii: długości kresek (+) i przerw (−) w jednostkach rysunku; 0 to kropka. */
class CadLineType(val name: String, val pattern: DoubleArray)

class CadTextStyle(val name: String, val font: String, val height: Double, val widthFactor: Double, val oblique: Double)

class CadBlock(val name: String, val baseX: Double, val baseY: Double, val flags: Int, val entities: MutableList<CadEntity>) {
    /** Blok zewnętrzny (XREF): jego treść jest w innym pliku. */
    val external: Boolean get() = flags and 4 != 0
}

class CadHeader(
    val version: String?,
    val insUnits: Int,
    val lineTypeScale: Double,
    val measurementMetric: Boolean,
    val extMin: DoubleArray?,
    val extMax: DoubleArray?
)
