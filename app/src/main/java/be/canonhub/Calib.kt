package be.canonhub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Étalonnage en boucle fermée : on imprime une mire, on la numérise, l'appli mesure
 * l'échelle d'impression, le blanc du scanner et la courbe de gris imprimante+scanner.
 */
object Calib {
    private const val MM = 300f / 25.4f
    private val corners = listOf(20f to 20f, 190f to 20f, 20f to 277f, 190f to 277f)
    private const val DX = 170f
    private const val DY = 257f
    private val ramp = (0..10).map { 255 * it / 10 }
    private const val RAMP_Y = 60f
    private fun rampX(k: Int) = 30f + 14f * k
    private val patches = listOf("Cyan", "Magenta", "Jaune", "Rouge", "Vert", "Bleu")
    private val patchColors = intArrayOf(
        AColor.rgb(0, 255, 255), AColor.rgb(255, 0, 255), AColor.rgb(255, 255, 0),
        AColor.rgb(255, 0, 0), AColor.rgb(0, 255, 0), AColor.rgb(0, 0, 255),
    )
    private const val PATCH_Y = 85f
    private fun patchX(i: Int) = 30f + 22f * i

    // ---- Réglages enregistrés ----
    private fun sp(ctx: Context) = ctx.getSharedPreferences("calib", Context.MODE_PRIVATE)

    data class State(
        val scaleX: Float = 1f, val scaleY: Float = 1f,
        val gains: FloatArray = floatArrayOf(1f, 1f, 1f),
        val curve: List<Int> = emptyList(),
        val printOn: Boolean = false, val scanOn: Boolean = false, val copyOn: Boolean = false,
        val date: Long = 0L,
        /** Matrice couleur 3×4 (lignes R, V, B : coef. r, v, b, décalage) pour les copies. */
        val matrix: FloatArray = IDENT.copyOf(),
        val colorOn: Boolean = false,
    ) {
        fun isDefault() = scaleX == 1f && scaleY == 1f && gains.all { it == 1f } && curve.isEmpty() &&
            !printOn && !scanOn && !copyOn && !colorOn && matrix.contentEquals(IDENT) && date == 0L
    }

    val IDENT = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

    @Volatile var state = State()
        private set
    /** LUT appliquée à chaque scan (balance des blancs) ; null = aucune. */
    @Volatile var scanLut: Array<IntArray>? = null
        private set
    /** LUT appliquée aux copies (compense la courbe imprimante+scanner) ; null = aucune. */
    @Volatile var copyLut: IntArray? = null
        private set
    /** Matrice couleur des copies ; null = aucune. */
    @Volatile var copyMatrix: FloatArray? = null
        private set

    fun load(ctx: Context) {
        val p = sp(ctx)
        state = State(
            p.getFloat("sx", 1f), p.getFloat("sy", 1f),
            floatArrayOf(p.getFloat("gr", 1f), p.getFloat("gg", 1f), p.getFloat("gb", 1f)),
            p.getString("curve", "")!!.split(',').mapNotNull { it.toIntOrNull() },
            p.getBoolean("printOn", false), p.getBoolean("scanOn", false), p.getBoolean("copyOn", false),
            p.getLong("date", 0L),
            p.getString("matrix", "")!!.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 12 }?.toFloatArray() ?: IDENT.copyOf(),
            p.getBoolean("colorOn", false),
        )
        ScanRef.load(ctx)
        rebuild()
    }

    /**
     * Numérise la mire posée sur la vitre et l'analyse. Référence scanner appliquée,
     * balance des blancs non (elle est recalculée sur le papier de la mire).
     */
    suspend fun scanChart(ctx: Context, ip: String): Report {
        val keep = state
        save(ctx, keep.copy(scanOn = false))
        val jpg = try { ScanService.keep(ctx) { Escl.scan(ip, 300, true, Area.A4) } } finally { save(ctx, keep) }
        return withContext(Dispatchers.Default) {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
            val b = Images.decode(jpg, 4000) ?: throw IOException("image illisible")
            analyze(b, 300f * b.width / o.outWidth.coerceAtLeast(1))
        }
    }

    /** Applique le résultat de la mire aux copies et au scanner ; échelle conservée si mire du pilote. */
    fun applyReport(ctx: Context, r: Report, viaDriver: Boolean) {
        val old = state
        save(
            ctx,
            State(
                if (viaDriver) old.scaleX else r.scaleX, if (viaDriver) old.scaleY else r.scaleY,
                r.gains, r.curve,
                printOn = if (viaDriver) old.printOn else true, scanOn = true, copyOn = true,
                date = System.currentTimeMillis(), matrix = r.matrix, colorOn = true,
            ),
        )
    }

    fun save(ctx: Context, s: State) {
        sp(ctx).edit()
            .putFloat("sx", s.scaleX).putFloat("sy", s.scaleY)
            .putFloat("gr", s.gains[0]).putFloat("gg", s.gains[1]).putFloat("gb", s.gains[2])
            .putString("curve", s.curve.joinToString(","))
            .putBoolean("printOn", s.printOn).putBoolean("scanOn", s.scanOn).putBoolean("copyOn", s.copyOn)
            .putLong("date", s.date)
            .putString("matrix", s.matrix.joinToString(",")).putBoolean("colorOn", s.colorOn)
            .apply()
        state = s
        rebuild()
    }

    fun reset(ctx: Context) = save(ctx, State())

    /** Scan brut demandé (mesure de la référence du scanner) : aucune LUT. */
    @Volatile private var rawMode = false

    /** Exécute [block] avec des scans bruts (sans référence scanner ni balance des blancs). */
    suspend fun <T> raw(block: suspend () -> T): T {
        rawMode = true; rebuild()
        try { return block() } finally { rawMode = false; rebuild() }
    }

    fun refresh() = rebuild()

    private fun rebuild() {
        val s = state
        // Référence scanner d'abord (réponse du capteur), puis balance des blancs.
        val ref = if (rawMode) null else ScanRef.lut
        val g = !rawMode && s.scanOn && s.gains.any { it != 1f }
        scanLut = if (ref == null && !g) null else Array(3) { c ->
            IntArray(256) { v ->
                val r = ref?.get(c)?.get(v) ?: v
                if (g) (r * s.gains[c]).roundToInt().coerceIn(0, 255) else r
            }
        }
        copyLut = if (s.copyOn && s.curve.size == 11) curveLut(s.curve) else null
        copyMatrix = if (s.colorOn && !s.matrix.contentEquals(IDENT)) s.matrix else null
    }

    /** Inverse de la réponse mesurée : valeur à imprimer pour retrouver le gris lu sur l'original. */
    private fun curveLut(meas: List<Int>): IntArray {
        val m = IntArray(11)
        for (k in 0..10) m[k] = maxOf(meas[k], if (k > 0) m[k - 1] + 1 else 0).coerceAtMost(255)
        return IntArray(256) { s ->
            when {
                s <= m[0] -> 0
                s >= m[10] -> 255
                else -> {
                    var k = 0
                    while (k < 9 && s > m[k + 1]) k++
                    val t = (s - m[k]).toFloat() / (m[k + 1] - m[k]).coerceAtLeast(1)
                    (ramp[k] + t * (ramp[k + 1] - ramp[k])).roundToInt().coerceIn(0, 255)
                }
            }
        }
    }

    fun applyLut(src: Bitmap, lut: IntArray): Bitmap {
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                row[x] = AColor.rgb(lut[AColor.red(c)], lut[AColor.green(c)], lut[AColor.blue(c)])
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** Correction de copie : courbe de gris puis matrice couleur (si activées). */
    fun forCopy(b: Bitmap): Bitmap {
        val lut = copyLut; val mx = copyMatrix
        if (lut == null && mx == null) return b
        val w = b.width; val h = b.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                var r = AColor.red(c); var g = AColor.green(c); var bl = AColor.blue(c)
                if (lut != null) { r = lut[r]; g = lut[g]; bl = lut[bl] }
                if (mx != null) {
                    val r2 = mx[0] * r + mx[1] * g + mx[2] * bl + mx[3]
                    val g2 = mx[4] * r + mx[5] * g + mx[6] * bl + mx[7]
                    val b2 = mx[8] * r + mx[9] * g + mx[10] * bl + mx[11]
                    r = r2.roundToInt().coerceIn(0, 255); g = g2.roundToInt().coerceIn(0, 255); bl = b2.roundToInt().coerceIn(0, 255)
                }
                row[x] = AColor.rgb(r, g, bl)
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    /**
     * Ajuste une matrice 3×4 (moindres carrés régularisés vers l'identité) qui ramène
     * les couleurs relues (après courbe de gris) vers les couleurs envoyées à l'imprimante.
     */
    private fun fitMatrix(meas: List<IntArray>, want: List<IntArray>, lut: IntArray): FloatArray {
        val lam = 0.05 * meas.size * 255.0 * 255.0
        val out = FloatArray(12)
        for (ch in 0..2) {
            val a = Array(4) { DoubleArray(4) }
            val v = DoubleArray(4)
            meas.forEachIndexed { i, m ->
                val x = doubleArrayOf(lut[m[0]].toDouble(), lut[m[1]].toDouble(), lut[m[2]].toDouble(), 1.0)
                val wgt = if (i >= 11) 3.0 else 1.0
                for (r in 0..3) {
                    v[r] += wgt * x[r] * want[i][ch]
                    for (c in 0..3) a[r][c] += wgt * x[r] * x[c]
                }
            }
            for (k in 0..2) { a[k][k] += lam; if (k == ch) v[k] += lam }
            val sol = solve4(a, v) ?: doubleArrayOf(if (ch == 0) 1.0 else 0.0, if (ch == 1) 1.0 else 0.0, if (ch == 2) 1.0 else 0.0, 0.0)
            for (k in 0..2) out[ch * 4 + k] = sol[k].toFloat().coerceIn(-1.5f, 2.5f)
            out[ch * 4 + 3] = sol[3].toFloat().coerceIn(-80f, 80f)
        }
        return out
    }

    private fun solve4(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 4
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            val piv = (col until n).maxBy { kotlin.math.abs(m[it][col]) }
            if (kotlin.math.abs(m[piv][col]) < 1e-9) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0 until n) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** Compense l'échelle d'impression mesurée sur une feuille A4 (centrée). */
    fun fixA4(b: Bitmap): Bitmap {
        val s = state
        if (!s.printOn || (s.scaleX == 1f && s.scaleY == 1f)) return b
        val r = b.height.toFloat() / b.width
        if (r < 1.39f || r > 1.44f) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(AColor.WHITE)
        val w = b.width / s.scaleX; val h = b.height / s.scaleY
        val l = (b.width - w) / 2; val t = (b.height - h) / 2
        c.drawBitmap(b, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ---- Mire ----
    fun chart(): Bitmap {
        val out = Bitmap.createBitmap(2481, 3508, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(AColor.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun sq(cx: Float, cy: Float, size: Float, col: Int) {
            p.color = col; p.style = Paint.Style.FILL
            val h = size / 2 * MM
            c.drawRect(cx * MM - h, cy * MM - h, cx * MM + h, cy * MM + h, p)
        }
        corners.forEach { (x, y) -> sq(x, y, 8f, AColor.BLACK) }
        ramp.forEachIndexed { k, v -> sq(rampX(k), RAMP_Y, 12f, AColor.rgb(v, v, v)) }
        patchColors.forEachIndexed { i, col -> sq(patchX(i), PATCH_Y, 14f, col) }
        // Règles graduées (contrôle à la règle).
        p.color = AColor.BLACK; p.strokeWidth = 3f
        c.drawLine(20 * MM, 110 * MM, 190 * MM, 110 * MM, p)
        c.drawLine(105 * MM, 120 * MM, 105 * MM, 270 * MM, p)
        for (i in 0..17) c.drawLine((20 + 10 * i) * MM, 107 * MM, (20 + 10 * i) * MM, 113 * MM, p)
        for (i in 0..15) c.drawLine(102 * MM, (120 + 10 * i) * MM, 108 * MM, (120 + 10 * i) * MM, p)
        p.textSize = 4.5f * MM
        c.drawText("Mire d'étalonnage CanonHub", 20 * MM, 135 * MM, p.apply { textSize = 6f * MM })
        p.textSize = 3.5f * MM
        listOf(
            "1. Imprimez cette page sans la redimensionner.",
            "2. Posez-la face contre la vitre, ce texte vers le coin repère.",
            "3. Lancez l'analyse dans l'appli (Réglages > Étalonnage).",
            "Ligne horizontale : 170 mm. Ligne verticale : 150 mm.",
        ).forEachIndexed { i, s -> c.drawText(s, 20 * MM, (150 + 8 * i) * MM, p) }
        return out
    }

    // ---- Analyse ----
    data class Report(
        val scaleX: Float, val scaleY: Float, val white: IntArray, val gains: FloatArray,
        val curve: List<Int>, val warnings: List<String>,
        val patchMeas: List<IntArray>, val matrix: FloatArray,
        /** Gris de la rampe (R, V, B après balance des blancs), 0 → 100 %. */
        val greys: List<IntArray> = emptyList(),
        /** Centre des 4 repères sur la vitre (mm) ; null si mire posée à l'envers. */
        val centre: Pair<Float, Float>? = null,
    )

    val patchNames get() = patches
    val patchRgb get() = patchColors

    private fun center(b: Bitmap, x: Float, y: Float, r: Int): IntArray {
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        val cx = x.roundToInt(); val cy = y.roundToInt()
        for (yy in (cy - r)..(cy + r) step 2) for (xx in (cx - r)..(cx + r) step 2) {
            if (xx !in 0 until b.width || yy !in 0 until b.height) continue
            val c = b.getPixel(xx, yy)
            sr += AColor.red(c); sg += AColor.green(c); sb += AColor.blue(c); n++
        }
        if (n == 0) throw IOException("mire hors du scan")
        return intArrayOf((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    /** Repères noirs de 8 mm : composantes sombres compactes, de taille attendue. */
    private fun markers(b: Bitmap, dpi: Float): List<Pair<Float, Float>> {
        val sw = 600
        val k = b.width.toFloat() / sw
        val sh = (b.height / k).toInt()
        val small = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val dark = BooleanArray(px.size) { val c = px[it]; (AColor.red(c) + AColor.green(c) + AColor.blue(c)) / 3 < 90 }
        val exp = 8f / 25.4f * dpi / k
        val seen = BooleanArray(px.size)
        val stack = IntArray(px.size)
        val found = ArrayList<Pair<Float, Float>>()
        for (s0 in px.indices) {
            if (!dark[s0] || seen[s0]) continue
            var top = 0; stack[top++] = s0; seen[s0] = true
            var l = sw; var t = sh; var r = -1; var bo = -1; var n = 0; var sx = 0L; var sy = 0L
            while (top > 0) {
                val i = stack[--top]; n++
                val x = i % sw; val y = i / sw
                sx += x; sy += y
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bo) bo = y
                for (j in intArrayOf(i - 1, i + 1, i - sw, i + sw)) {
                    if (j < 0 || j >= px.size || seen[j] || !dark[j]) continue
                    if ((j == i - 1 && x == 0) || (j == i + 1 && x == sw - 1)) continue
                    seen[j] = true; stack[top++] = j
                }
            }
            val bw = r - l + 1f; val bh = bo - t + 1f
            val ok = bw in exp * 0.6f..exp * 1.3f && bh in exp * 0.6f..exp * 1.3f &&
                n / (bw * bh) > 0.7f && bw / bh in 0.7f..1.4f
            if (ok) found += ((sx.toFloat() / n + 0.5f) * k) to ((sy.toFloat() / n + 0.5f) * k)
        }
        return found
    }

    fun analyze(b: Bitmap, dpi: Float): Report {
        val m = markers(b, dpi)
        if (m.size < 4) throw IOException("repères introuvables (${m.size}/4) : mire bien à plat, face contre la vitre ?")
        var tl = m.minBy { it.first + it.second }; var br = m.maxBy { it.first + it.second }
        var tr = m.maxBy { it.first - it.second }; var bl = m.minBy { it.first - it.second }

        fun at(x: Float, y: Float): Pair<Float, Float> {
            val u = (x - 20f) / DX; val v = (y - 20f) / DY
            return (tl.first + (tr.first - tl.first) * u + (bl.first - tl.first) * v) to
                (tl.second + (tr.second - tl.second) * u + (bl.second - tl.second) * v)
        }
        val rad = (3f / 25.4f * dpi).toInt()
        fun lum(c: IntArray) = (c[0] * 30 + c[1] * 59 + c[2] * 11) / 100
        fun rampRaw() = (0..10).map { k -> at(rampX(k), RAMP_Y).let { (x, y) -> center(b, x, y, rad) } }
        var raw = rampRaw()
        val upright = lum(raw[0]) <= lum(raw[10])
        if (!upright) {
            // Mire posée à l'envers (180°) : on échange les coins.
            val a = tl; tl = br; br = a
            val c = tr; tr = bl; bl = c
            raw = rampRaw()
        }

        val mmPx = 25.4f / dpi
        val sx = (hypot(tr.first - tl.first, tr.second - tl.second) + hypot(br.first - bl.first, br.second - bl.second)) / 2 * mmPx / DX
        val sy = (hypot(bl.first - tl.first, bl.second - tl.second) + hypot(br.first - tr.first, br.second - tr.second)) / 2 * mmPx / DY
        if (sx !in 0.8f..1.1f || sy !in 0.8f..1.1f) throw IOException("échelle mesurée incohérente (${"%.3f".format(sx)} × ${"%.3f".format(sy)})")

        val white = at(105f, 45f).let { (x, y) -> center(b, x, y, rad) }
        val target = 250f
        val gains = FloatArray(3) { (target / white[it].coerceAtLeast(120)).coerceIn(0.9f, 1.4f) }
        val curve = raw.map { c -> ((c[0] * gains[0]) * 0.30f + (c[1] * gains[1]) * 0.59f + (c[2] * gains[2]) * 0.11f).roundToInt().coerceIn(0, 255) }

        val warn = ArrayList<String>()
        if (curve[10] - curve[0] < 150) warn += "Contraste faible : encre noire basse ou buses bouchées."
        val pc = patches.indices.map { i -> at(patchX(i), PATCH_Y).let { (x, y) -> center(b, x, y, rad) } }
        fun weak(name: String, ok: Boolean) { if (!ok) warn += "$name pâle : vérifiez le réservoir et faites un test des buses." }
        weak("Cyan", (pc[0][1] + pc[0][2]) / 2 - pc[0][0] > 50)
        weak("Magenta", (pc[1][0] + pc[1][2]) / 2 - pc[1][1] > 50)
        weak("Jaune", (pc[2][0] + pc[2][1]) / 2 - pc[2][2] > 50)
        if (white.max() - white.min() > 25) warn += "Blanc du papier teinté : la balance des blancs le corrige."
        fun g(c: IntArray) = IntArray(3) { (c[it] * gains[it]).roundToInt().coerceIn(0, 255) }
        val pcG = pc.map { g(it) }
        val meas = raw.map { g(it) } + pcG
        val want = ramp.map { intArrayOf(it, it, it) } + patchColors.map { intArrayOf(AColor.red(it), AColor.green(it), AColor.blue(it)) }
        val mx = fitMatrix(meas, want, curveLut(curve))
        val centre = if (upright) (tl.first + tr.first + bl.first + br.first) / 4 * mmPx to
            (tl.second + tr.second + bl.second + br.second) / 4 * mmPx else null
        return Report(sx, sy, white, gains, curve, warn, pcG, mx, raw.map { g(it) }, centre)
    }
}

/** Écran Étalonnage (Réglages). */
@Composable
fun CalibScreen(m: Modifier, ip: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf(Calib.state) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<Calib.Report?>(null) }
    var sx by remember { mutableFloatStateOf(st.scaleX) }
    var sy by remember { mutableFloatStateOf(st.scaleY) }
    val prefs = remember { PrinterPrefs(ctx) }
    // Mire imprimée par le pilote : son échelle relève de « Réglage du pilote », pas de la fenêtre Android.
    var viaDriver by remember { mutableStateOf(Gutenprint.available && ip.isNotBlank()) }
    var wizard by remember { mutableStateOf(false) }
    if (wizard) CalibWizard(ip, prefs) { wizard = false; st = Calib.state; sx = st.scaleX; sy = st.scaleY }

    var askReset by remember { mutableStateOf(false) }
    if (askReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text("Réinitialiser l'étalonnage ?") },
            text = { Text("Toutes les corrections mesurées ou réglées seront effacées. Vous pourrez refaire l'analyse de la mire.") },
            confirmButton = {
                TextButton(onClick = {
                    Calib.reset(ctx); st = Calib.state; sx = 1f; sy = 1f; report = null
                    msg = "Étalonnage remis par défaut."; askReset = false
                }) { Text("Réinitialiser") }
            },
            dismissButton = { TextButton(onClick = { askReset = false }) { Text("Annuler") } },
        )
    }

    fun commit(s: Calib.State) { Calib.save(ctx, s); st = s; sx = s.scaleX; sy = s.scaleY }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Étalonnage intelligent", Icons.Filled.Build, sub = if (st.date == 0L) "Jamais étalonné" else "Dernier : ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(st.date))}") {
            Hint("L'appli imprime une mire, la relit au scanner et calcule les corrections : taille réelle à l'impression, blanc du scanner, rendu des gris en copie. Elle signale aussi une couleur pâle.")
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { wizard = true }) {
                Text("Assistant d'étalonnage complet")
            }
            Hint("Recommandé : enchaîne automatiquement toutes les étapes dans le bon ordre.")
        }
        CalibGuide()
        ScanRefSection(ip)
        Section("1. Imprimer la mire", Icons.Filled.Print, sub = "Papier A4 blanc ordinaire") {
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                if (Gutenprint.available && ip.isNotBlank()) {
                    // Pilote intégré (BJNP), comme les impressions et copies : la mire mesure ce rendu.
                    busy = true; msg = ""
                    scope.launch {
                        msg = try {
                            PrintQueue.cancelAndroid(ctx)
                            Gutenprint.testPage(ctx, ip, prefs.gpSel, 300) { msg = it }.also { viaDriver = true }
                        } catch (e: Exception) { "Échec du pilote : ${e.message}" }
                        busy = false
                    }
                } else {
                    val keep = Calib.state
                    // Mire imprimée sans correction d'échelle, pour mesurer la machine telle quelle.
                    Calib.save(ctx, keep.copy(printOn = false))
                    printBitmap(ctx, "mire", Calib.chart())
                    Calib.save(ctx, keep)
                    viaDriver = false
                }
            }) { Text("Imprimer la mire") }
            Hint(
                if (Gutenprint.available) "Pilote intégré, taille réelle. Faites d'abord « Réglage du pilote » (échelle, couleurs)."
                else "Dans la fenêtre Android : A4, 100 %, qualité standard.",
            )
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch { msg = PrintQueue.clearAll(ctx, ip, prefs.ruiPassword); busy = false }
            }) { Text("Vider la file d'impression") }
        }
        Section("2. Numériser et analyser", Icons.Filled.DocumentScanner, sub = "Mire face contre la vitre") {
            Button(enabled = !busy && ip.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    busy = true; msg = "Numérisation de la mire…"; report = null
                    msg = try {
                        report = Calib.scanChart(ctx, ip)
                        "Analyse terminée."
                    } catch (e: Exception) {
                        "Échec : ${e.message}"
                    }
                    busy = false
                }
            }) { Text("Numériser la mire") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg)
        }
        report?.let { r ->
            Section("3. Résultats", Icons.Filled.Settings) {
                Text("Échelle d'impression : ${"%.1f".format(r.scaleX * 100)} % × ${"%.1f".format(r.scaleY * 100)} %")
                Text("Blanc du scanner : R${r.white[0]} V${r.white[1]} B${r.white[2]}")
                Text("Gris imprimés puis relus (0 → 100 %) :", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    r.curve.forEachIndexed { k, v ->
                        Column(Modifier.weight(1f)) {
                            val n = 255 * k / 10
                            Box(Modifier.fillMaxWidth().height(16.dp).background(Color(n, n, n)))
                            Box(Modifier.fillMaxWidth().height(16.dp).background(Color(v, v, v)))
                        }
                    }
                }
                Hint("Haut : attendu. Bas : mesuré.")
                Text("Couleurs imprimées puis relues :", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.patchMeas.forEachIndexed { i, c ->
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth().height(16.dp).background(Color(Calib.patchRgb[i])))
                            Box(Modifier.fillMaxWidth().height(16.dp).background(Color(c[0], c[1], c[2])))
                            Text(Calib.patchNames[i], style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                r.warnings.forEach { Pill(it, MaterialTheme.colorScheme.error) }
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    Calib.applyReport(ctx, r, viaDriver)
                    st = Calib.state; sx = st.scaleX; sy = st.scaleY
                    report = null
                    msg = if (viaDriver) "Étalonnage appliqué (échelle : voir Réglage du pilote)." else "Étalonnage appliqué."
                }) { Text("Appliquer les corrections") }
            }
        }
        Section("Corrections actives", Icons.Filled.Settings) {
            Toggle("Taille réelle à l'impression", st.printOn) { commit(st.copy(printOn = it)) }
            Toggle("Balance des blancs du scanner", st.scanOn) { commit(st.copy(scanOn = it)) }
            Toggle("Courbe de gris en copie", st.copyOn) { commit(st.copy(copyOn = it, curve = st.curve)) }
            if (st.copyOn && st.curve.size != 11) Hint("Courbe absente : faites l'analyse de la mire.")
            Toggle("Couleurs en copie (automatique)", st.colorOn) { commit(st.copy(colorOn = it)) }
            if (st.colorOn && st.matrix.contentEquals(Calib.IDENT)) Hint("Correction couleur absente : faites l'analyse de la mire.")
            Text("Échelle horizontale ${"%.1f".format(sx * 100)} %", style = MaterialTheme.typography.labelLarge)
            Slider(value = sx, onValueChange = { sx = it }, onValueChangeFinished = { commit(st.copy(scaleX = sx)) }, valueRange = 0.9f..1.05f)
            Text("Échelle verticale ${"%.1f".format(sy * 100)} %", style = MaterialTheme.typography.labelLarge)
            Slider(value = sy, onValueChange = { sy = it }, onValueChangeFinished = { commit(st.copy(scaleY = sy)) }, valueRange = 0.9f..1.05f)
            Hint("Réglage manuel : mesurez la ligne de 170 mm de la mire ; 165 mm → 97,1 %.")
        }
        Section("Réglages par défaut", Icons.Filled.Settings, sub = "Efface toutes les corrections") {
            Hint("Échelle 100 %, aucune balance des blancs, aucune courbe ni correction couleur.")
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { askReset = true }) {
                Text("Réinitialiser l'étalonnage")
            }
        }
    }
}
