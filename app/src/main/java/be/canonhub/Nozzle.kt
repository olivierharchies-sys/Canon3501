package be.canonhub

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Contrôle automatique du motif de test des buses : la feuille imprimée est relue au scanner,
 * puis comparée à une référence (motif sans défaut enregistré une fois).
 * Une buse bouchée laisse une ligne blanche horizontale : la densité de cette ligne chute.
 */
object Nozzle {
    /** Encres analysées : noir, cyan, magenta, jaune. */
    enum class Ink(val key: Char, val label: String) { K('K', "Noir"), C('C', "Cyan"), M('M', "Magenta"), Y('Y', "Jaune") }

    class Result(val ink: Ink, val defects: Int, val rows: Int, val missing: Boolean) {
        val ok get() = !missing && defects == 0
        fun text() = when {
            missing -> "${ink.label} : absent du motif"
            defects == 0 -> "${ink.label} : OK"
            else -> "${ink.label} : $defects ligne(s) manquante(s)"
        }
    }

    private fun classify(c: Int): Ink? {
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        if (mx < 110 && mx - mn < 50) return Ink.K
        if (mx - mn < 60) return null
        return when (mn) {
            r -> if (b > 120 || g > 120) Ink.C else null
            g -> if (r > 120) Ink.M else null
            else -> if (r > 150 && g > 150) Ink.Y else null
        }
    }

    /** Profil vertical de chaque encre : part de pixels encrés par ligne, sur l'emprise de l'encre. */
    fun profiles(b: Bitmap): Map<Ink, FloatArray> {
        val w = b.width; val h = b.height
        val rowCnt = Array(4) { IntArray(h) }
        val colCnt = Array(4) { IntArray(w) }
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val k = classify(row[x]) ?: continue
                rowCnt[k.ordinal][y]++; colCnt[k.ordinal][x]++
            }
        }
        val out = HashMap<Ink, FloatArray>()
        for (ink in Ink.entries) {
            val rc = rowCnt[ink.ordinal]; val cc = colCnt[ink.ordinal]
            val minR = maxOf(4, w / 200); val minC = maxOf(4, h / 300)
            val t = rc.indexOfFirst { it >= minR }; val bt = rc.indexOfLast { it >= minR }
            val l = cc.indexOfFirst { it >= minC }; val r = cc.indexOfLast { it >= minC }
            if (t < 0 || l < 0 || bt - t < 20 || r - l < 20) continue
            val width = (r - l + 1).toFloat()
            out[ink] = FloatArray(bt - t + 1) { rc[t + it] / width }
        }
        return out
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "nozzle_ref.txt")

    fun hasRef(ctx: Context) = file(ctx).exists()

    fun saveRef(ctx: Context, p: Map<Ink, FloatArray>) =
        file(ctx).writeText(p.entries.joinToString("\n") { (k, v) -> "${k.key}:" + v.joinToString(",") })

    fun loadRef(ctx: Context): Map<Ink, FloatArray> = runCatching {
        file(ctx).readLines().mapNotNull { ln ->
            val ink = Ink.entries.firstOrNull { it.key == ln.firstOrNull() } ?: return@mapNotNull null
            ink to ln.substringAfter(':').split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun resample(a: FloatArray, n: Int): FloatArray = FloatArray(n) { i ->
        val f = if (n <= 1) 0f else i * (a.size - 1f) / (n - 1)
        val i0 = f.toInt().coerceIn(0, a.size - 1); val i1 = (i0 + 1).coerceAtMost(a.size - 1)
        a[i0] + (a[i1] - a[i0]) * (f - i0)
    }

    private fun smooth(a: FloatArray) = FloatArray(a.size) { i ->
        (a[(i - 1).coerceAtLeast(0)] + a[i] + a[(i + 1).coerceAtMost(a.size - 1)]) / 3f
    }

    /** Compare au motif de référence : lignes dont la densité tombe sous 55 % de la référence. */
    fun compare(ref: Map<Ink, FloatArray>, cur: Map<Ink, FloatArray>): List<Result> = ref.map { (ink, r0) ->
        val c0 = cur[ink] ?: return@map Result(ink, 0, r0.size, true)
        val r = smooth(r0); val c = smooth(resample(c0, r0.size))
        // Recalage fin (décalage de la feuille sur la vitre, échelle légèrement différente).
        var best = 0; var bestS = -1.0
        for (s in -10..10) {
            var sum = 0.0
            for (i in r.indices) { val j = i + s; if (j in c.indices) sum += r[i] * c[j] }
            if (sum > bestS) { bestS = sum; best = s }
        }
        var defects = 0; var run = 0
        for (i in r.indices) {
            val j = i + best
            val bad = j in c.indices && r[i] > 0.08f && c[j] < r[i] * 0.55f
            if (bad) { run++; if (run == 1) defects++ } else run = 0
        }
        // Encre presque absente sur tout le motif : buses très bouchées.
        val total = c.sum() / r.sum().coerceAtLeast(0.001f)
        Result(ink, defects, r.size, total < 0.3f)
    }

    /** Nettoyage conseillé selon les encres en défaut ; null si tout va bien. */
    fun advice(res: List<Result>): Tool? {
        val k = res.any { it.ink == Ink.K && !it.ok }
        val col = res.any { it.ink != Ink.K && !it.ok }
        return when {
            k && col -> Tool.CleanAll
            k -> Tool.CleanBlack
            col -> Tool.CleanColor
            else -> null
        }
    }

    /** Numérise la feuille posée face imprimée contre la vitre (300 dpi couleur). */
    suspend fun scan(ctx: Context, ip: String): Map<Ink, FloatArray> {
        val jpg = ScanService.keep(ctx) { Escl.scan(ip, 300, true, Area.A4) }
        return withContext(Dispatchers.Default) {
            val b = Images.decode(jpg, 4000) ?: throw IOException("image illisible")
            profiles(b).also { if (it.isEmpty()) throw IOException("aucun motif trouvé sur la vitre") }
        }
    }
}

/** Section « Contrôle automatique des buses » de l'écran Entretien. */
@Composable
fun NozzleAuto(ip: String, enabled: Boolean, print: () -> Unit, clean: (Tool) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<List<Nozzle.Result>>(emptyList()) }
    var last by remember { mutableStateOf<Map<Nozzle.Ink, FloatArray>?>(null) }
    var hasRef by remember { mutableStateOf(Nozzle.hasRef(ctx)) }

    Section("Contrôle automatique des buses", Icons.Filled.Build, sub = if (hasRef) "Référence enregistrée" else "Référence à enregistrer") {
        Hint("1. Imprimez le motif. 2. Posez-le face imprimée contre la vitre, coin repère en haut à gauche, toujours dans le même sens. 3. Analysez.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled && !busy, modifier = Modifier.weight(1f), onClick = print) { Text("1. Imprimer le motif") }
            Button(enabled = enabled && !busy && ip.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                busy = true; msg = "Lecture du motif…"; res = emptyList()
                scope.launch {
                    msg = try {
                        val cur = Nozzle.scan(ctx, ip)
                        last = cur
                        if (!hasRef) {
                            "Motif lu (${cur.keys.joinToString { it.label }}). Pas encore de référence : si le motif est parfait à l'œil, enregistrez-le."
                        } else {
                            res = Nozzle.compare(Nozzle.loadRef(ctx), cur)
                            if (res.all { it.ok }) "Buses en bon état." else "Défauts détectés."
                        }
                    } catch (e: Exception) { "Échec : ${e.message}" }
                    busy = false
                }
            }) { Text("2. Analyser") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)
        res.forEach { r ->
            Text(r.text(), color = if (r.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        }
        Nozzle.advice(res)?.let { t ->
            Button(enabled = enabled && !busy, modifier = Modifier.fillMaxWidth(), onClick = { clean(t) }) { Text("Lancer : ${t.label}") }
            Hint("Après le nettoyage, réimprimez le motif et analysez de nouveau.")
        }
        last?.let { cur ->
            TextButton(enabled = !busy, onClick = {
                Nozzle.saveRef(ctx, cur); hasRef = true; res = emptyList()
                msg = "Référence enregistrée : les prochains motifs seront comparés à celui-ci."
            }) { Text(if (hasRef) "Remplacer la référence par ce motif" else "Enregistrer ce motif comme référence") }
        }
    }
}
