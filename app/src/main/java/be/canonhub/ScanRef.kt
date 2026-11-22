package be.canonhub

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Référence du scanner : une échelle de gris de densités connues (Kodak Q-13, ColorChecker…)
 * donne la vraie réponse du capteur. Sa correction s'applique à tous les scans, y compris
 * aux mires d'étalonnage : le réglage du pilote ne dépend plus des défauts du scanner.
 */
object ScanRef {
    class Preset(val name: String, val dens: List<Float>)

    val presets = listOf(
        Preset("Kodak Q-13 / Q-14 (20 cases)", List(20) { 0.05f + 0.10f * it }),
        Preset("ColorChecker (6 gris)", listOf(0.05f, 0.23f, 0.44f, 0.70f, 1.05f, 1.50f)),
    )

    @Volatile var lut: Array<IntArray>? = null
        private set
    var on = false
        private set
    var date = 0L
        private set
    /** Mesures (R, V, B) et valeurs attendues de la dernière référence. */
    var meas: List<IntArray> = emptyList()
        private set
    var expect: List<Int> = emptyList()
        private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences("scanref", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        on = p.getBoolean("on", false); date = p.getLong("date", 0L)
        expect = p.getString("exp", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        meas = p.getString("meas", "")!!.split(';').mapNotNull { s ->
            s.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }?.toIntArray()
        }
        lut = if (on && meas.size == expect.size && meas.size >= 3) build(meas, expect) else null
    }

    private fun store(ctx: Context) {
        sp(ctx).edit().putBoolean("on", on).putLong("date", date)
            .putString("exp", expect.joinToString(","))
            .putString("meas", meas.joinToString(";") { it.joinToString(",") }).apply()
        load(ctx); Calib.refresh()
    }

    fun setOn(ctx: Context, v: Boolean) { on = v; store(ctx) }

    fun reset(ctx: Context) { on = false; date = 0L; meas = emptyList(); expect = emptyList(); store(ctx) }

    /** Densité optique → valeur sRGB 0–255 attendue d'un scanner juste. */
    fun expected(d: Float): Int {
        val r = 10.0.pow(-d.toDouble())
        val s = if (r <= 0.0031308) 12.92 * r else 1.055 * r.pow(1 / 2.4) - 0.055
        return (s * 255).roundToInt().coerceIn(0, 255)
    }

    /** Courbe par canal : mesuré → attendu, interpolée, croissante, ancrée en 0 et 255. */
    private fun build(meas: List<IntArray>, exp: List<Int>): Array<IntArray> = Array(3) { c ->
        val pts = meas.indices.map { meas[it][c].toFloat() to exp[it].toFloat() }.sortedBy { it.first }
        val k = ArrayList<Pair<Float, Float>>().apply { add(0f to 0f) }
        for (p in pts) if (p.first > k.last().first + 1 && p.second > k.last().second) k += p
        if (k.last().first < 254f) k += 255f to 255f
        IntArray(256) { v ->
            val i = k.indexOfFirst { it.first >= v }.coerceAtLeast(1)
            val (x0, y0) = k[i - 1]; val (x1, y1) = k[i]
            (y0 + (y1 - y0) * (v - x0) / (x1 - x0).coerceAtLeast(1f)).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Mesure les cases dans le cadre [r] (l, h, d, b normalisés) : bande divisée en [n] cases égales
     * dans le sens le plus long, médiane du centre de chaque case. Case la plus claire en premier.
     */
    fun measure(b: Bitmap, r: FloatArray, n: Int): List<IntArray> {
        val l = r[0] * b.width; val t = r[1] * b.height
        val w = (r[2] - r[0]) * b.width; val h = (r[3] - r[1]) * b.height
        val horiz = w >= h
        val cells = (0 until n).map { k ->
            val (x0, x1, y0, y1) = if (horiz) listOf(l + w * k / n, l + w * (k + 1) / n, t, t + h)
            else listOf(l, l + w, t + h * k / n, t + h * (k + 1) / n)
            // Centre de la case (50 %) : évite bords et séparations.
            val cx0 = (x0 + (x1 - x0) / 4).toInt(); val cx1 = (x1 - (x1 - x0) / 4).toInt().coerceAtMost(b.width - 1)
            val cy0 = (y0 + (y1 - y0) / 4).toInt(); val cy1 = (y1 - (y1 - y0) / 4).toInt().coerceAtMost(b.height - 1)
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            val step = maxOf(1, (cx1 - cx0) / 20)
            var y = cy0
            while (y <= cy1) {
                var x = cx0
                while (x <= cx1) {
                    val p = b.getPixel(x, y)
                    rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
                    x += step
                }
                y += step
            }
            if (rs.isEmpty()) throw IOException("cadre trop petit")
            intArrayOf(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
        }
        fun lum(c: IntArray) = c[0] * 30 + c[1] * 59 + c[2] * 11
        return if (lum(cells.first()) < lum(cells.last())) cells.reversed() else cells
    }

    /** Enregistre la référence mesurée ; retourne un résumé (écart avant correction). */
    fun record(ctx: Context, m: List<IntArray>, dens: List<Float>): String {
        val exp = dens.map(::expected)
        val lums = m.map { (it[0] * 30 + it[1] * 59 + it[2] * 11) / 100 }
        if (lums.zipWithNext().count { (a, b) -> b > a + 3 } > m.size / 4) throw IOException("cases non décroissantes : cadrez uniquement la bande de gris")
        if (lums.first() - lums.last() < 80) throw IOException("contraste trop faible : mauvais cadre ou mauvais modèle d'échelle")
        val dev = m.indices.maxOf { i -> m[i].maxOf { abs(it - exp[i]) } }
        meas = m; expect = exp; on = true; date = System.currentTimeMillis()
        store(ctx)
        return "Référence enregistrée (${m.size} cases). Écart max avant correction : $dev niveaux sur 255."
    }

    /** Scan brut A4 150 dpi pour la référence. */
    suspend fun scan(ctx: Context, ip: String): Bitmap {
        val jpg = Calib.raw { ScanService.keep(ctx) { Escl.scan(ip, 150, true, Area.A4) } }
        return withContext(Dispatchers.Default) { Images.decode(jpg, 2000) } ?: throw IOException("image illisible")
    }
}

/** Section « Référence du scanner » : choix du modèle, scan, cadrage, mesure. */
@Composable
fun ScanRefSection(ip: String, onDone: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(0) }   // -1 = personnalisé
    var nTxt by remember { mutableStateOf("20") }
    var dMinTxt by remember { mutableStateOf("0.05") }
    var dStepTxt by remember { mutableStateOf("0.10") }
    var img by remember { mutableStateOf<Bitmap?>(null) }
    var rect by remember { mutableStateOf(floatArrayOf(0.05f, 0.05f, 0.95f, 0.15f)) }
    var busy by remember { mutableStateOf(false) }
    KeepAwake(busy)
    var msg by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(ScanRef.on) }

    fun dens(): List<Float> = if (preset >= 0) ScanRef.presets[preset].dens else {
        val n = nTxt.toIntOrNull()?.coerceIn(3, 40) ?: throw IOException("nombre de cases invalide")
        val d0 = dMinTxt.replace(',', '.').toFloatOrNull() ?: throw IOException("densité de départ invalide")
        val ds = dStepTxt.replace(',', '.').toFloatOrNull() ?: throw IOException("pas invalide")
        List(n) { d0 + ds * it }
    }

    Section("Référence du scanner (facultatif)", Icons.Filled.DocumentScanner,
        sub = if (ScanRef.date == 0L) "Aucune" else if (on) "Active" else "Désactivée") {
        Hint("Nécessite une échelle de gris du commerce (Kodak Q-13, ColorChecker…). Rend le réglage du pilote indépendant des défauts du scanner.")
        OptChips("Modèle", ScanRef.presets.indices.toList() + -1, preset,
            { if (it < 0) "Personnalisé" else ScanRef.presets[it].name }) { preset = it }
        if (preset < 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nTxt, { nTxt = it }, Modifier.weight(1f), label = { Text("Cases") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(dMinTxt, { dMinTxt = it }, Modifier.weight(1f), label = { Text("D départ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(dStepTxt, { dStepTxt = it }, Modifier.weight(1f), label = { Text("Pas D") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Hint("Densités indiquées sur la fiche de l'échelle (D départ = case la plus claire).")
        }
        Hint("1. Posez l'échelle face contre la vitre, près du coin repère. 2. Numérisez. 3. Cadrez uniquement les cases, de la première à la dernière. 4. Mesurez.")
        Button(enabled = !busy && ip.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true; msg = "Numérisation brute…"
            scope.launch {
                msg = try { img = ScanRef.scan(ctx, ip); "Cadrez la bande de gris." } catch (e: Exception) { "Échec : ${e.message}" }
                busy = false
            }
        }) { Text("Numériser l'échelle de gris") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        img?.let { b ->
            CropEditor(b, rect) { rect = it }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                msg = try {
                    val d = dens()
                    ScanRef.record(ctx, ScanRef.measure(b, rect, d.size), d).also { on = true; img = null; onDone(it) }
                } catch (e: Exception) { "Mesure refusée : ${e.message}" }
            }) { Text("Mesurer et appliquer") }
        }
        if (msg.isNotEmpty()) Text(msg)
        if (ScanRef.meas.isNotEmpty()) {
            Text("Attendu (haut) / mesuré (bas) :", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                ScanRef.meas.forEachIndexed { i, c ->
                    Column(Modifier.weight(1f)) {
                        val e = ScanRef.expect.getOrElse(i) { 0 }
                        Box(Modifier.fillMaxWidth().height(14.dp).background(Color(e, e, e)))
                        Box(Modifier.fillMaxWidth().height(14.dp).background(Color(c[0], c[1], c[2])))
                    }
                }
            }
            Toggle("Appliquer la référence aux scans", on) { ScanRef.setOn(ctx, it); on = it }
            TextButton(onClick = { ScanRef.reset(ctx); on = false; msg = "Référence effacée." }) { Text("Effacer la référence") }
        }
    }
}
