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
    )

    @Volatile var state = State()
        private set
    /** LUT appliquée à chaque scan (balance des blancs) ; null = aucune. */
    @Volatile var scanLut: Array<IntArray>? = null
        private set
    /** LUT appliquée aux copies (compense la courbe imprimante+scanner) ; null = aucune. */
    @Volatile var copyLut: IntArray? = null
        private set

    fun load(ctx: Context) {
        val p = sp(ctx)
        state = State(
            p.getFloat("sx", 1f), p.getFloat("sy", 1f),
            floatArrayOf(p.getFloat("gr", 1f), p.getFloat("gg", 1f), p.getFloat("gb", 1f)),
            p.getString("curve", "")!!.split(',').mapNotNull { it.toIntOrNull() },
            p.getBoolean("printOn", false), p.getBoolean("scanOn", false), p.getBoolean("copyOn", false),
            p.getLong("date", 0L),
        )
        rebuild()
    }

    fun save(ctx: Context, s: State) {
        sp(ctx).edit()
            .putFloat("sx", s.scaleX).putFloat("sy", s.scaleY)
            .putFloat("gr", s.gains[0]).putFloat("gg", s.gains[1]).putFloat("gb", s.gains[2])
            .putString("curve", s.curve.joinToString(","))
            .putBoolean("printOn", s.printOn).putBoolean("scanOn", s.scanOn).putBoolean("copyOn", s.copyOn)
            .putLong("date", s.date)
            .apply()
        state = s
        rebuild()
    }

    fun reset(ctx: Context) = save(ctx, State())

    private fun rebuild() {
        val s = state
        scanLut = if (s.scanOn && s.gains.any { it != 1f }) {
            Array(3) { c -> IntArray(256) { v -> (v * s.gains[c]).roundToInt().coerceIn(0, 255) } }
        } else null
        copyLut = if (s.copyOn && s.curve.size == 11) curveLut(s.curve) else null
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

    /** Correction de copie (si activée). */
    fun forCopy(b: Bitmap): Bitmap = copyLut?.let { applyLut(b, it) } ?: b

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
    )

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
        if (lum(raw[0]) > lum(raw[10])) {
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
        return Report(sx, sy, white, gains, curve, warn)
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

    fun commit(s: Calib.State) { Calib.save(ctx, s); st = s; sx = s.scaleX; sy = s.scaleY }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Étalonnage intelligent", Icons.Filled.Build, sub = if (st.date == 0L) "Jamais étalonné" else "Dernier : ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date(st.date))}") {
            Hint("L'appli imprime une mire, la relit au scanner et calcule les corrections : taille réelle à l'impression, blanc du scanner, rendu des gris en copie. Elle signale aussi une couleur pâle.")
        }
        Section("1. Imprimer la mire", Icons.Filled.Print, sub = "Papier A4 blanc ordinaire") {
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                val keep = Calib.state
                // Mire imprimée sans correction d'échelle, pour mesurer la machine telle quelle.
                Calib.save(ctx, keep.copy(printOn = false))
                printBitmap(ctx, "mire", Calib.chart())
                Calib.save(ctx, keep)
            }) { Text("Imprimer la mire") }
            Hint("Dans la fenêtre Android : A4, 100 %, qualité standard.")
        }
        Section("2. Numériser et analyser", Icons.Filled.DocumentScanner, sub = "Mire face contre la vitre") {
            Button(enabled = !busy && ip.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    busy = true; msg = "Numérisation de la mire…"; report = null
                    msg = try {
                        // Scan brut, sans correction déjà appliquée.
                        val keep = Calib.state
                        Calib.save(ctx, keep.copy(scanOn = false))
                        val jpg = try {
                            ScanService.keep(ctx) { Escl.scan(ip, 300, true, Area.A4) }
                        } finally {
                            Calib.save(ctx, keep)
                        }
                        msg = "Analyse…"
                        report = withContext(Dispatchers.Default) {
                            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
                            val b = Images.decode(jpg, 4000) ?: throw IOException("image illisible")
                            Calib.analyze(b, 300f * b.width / o.outWidth.coerceAtLeast(1))
                        }
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
                r.warnings.forEach { Pill(it, MaterialTheme.colorScheme.error) }
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    commit(
                        Calib.State(
                            r.scaleX, r.scaleY, r.gains, r.curve,
                            printOn = true, scanOn = true, copyOn = true, date = System.currentTimeMillis(),
                        ),
                    )
                    report = null; msg = "Étalonnage appliqué."
                }) { Text("Appliquer les corrections") }
            }
        }
        Section("Corrections actives", Icons.Filled.Settings) {
            Toggle("Taille réelle à l'impression", st.printOn) { commit(st.copy(printOn = it)) }
            Toggle("Balance des blancs du scanner", st.scanOn) { commit(st.copy(scanOn = it)) }
            Toggle("Courbe de gris en copie", st.copyOn) { commit(st.copy(copyOn = it, curve = st.curve)) }
            if (st.copyOn && st.curve.size != 11) Hint("Courbe absente : faites l'analyse de la mire.")
            Text("Échelle horizontale ${"%.1f".format(sx * 100)} %", style = MaterialTheme.typography.labelLarge)
            Slider(value = sx, onValueChange = { sx = it }, onValueChangeFinished = { commit(st.copy(scaleX = sx)) }, valueRange = 0.9f..1.05f)
            Text("Échelle verticale ${"%.1f".format(sy * 100)} %", style = MaterialTheme.typography.labelLarge)
            Slider(value = sy, onValueChange = { sy = it }, onValueChangeFinished = { commit(st.copy(scaleY = sy)) }, valueRange = 0.9f..1.05f)
            Hint("Réglage manuel : mesurez la ligne de 170 mm de la mire ; 165 mm → 97,1 %.")
            TextButton(onClick = { Calib.reset(ctx); st = Calib.state; sx = 1f; sy = 1f; msg = "Étalonnage effacé." }) { Text("Tout réinitialiser") }
        }
    }
}
