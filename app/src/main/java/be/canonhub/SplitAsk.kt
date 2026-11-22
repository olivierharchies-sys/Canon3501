package be.canonhub

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Vérification des documents détectés : aperçu de la vitre, cadres modifiables
 * (déplacer, redimensionner, ajouter, supprimer). Appel depuis une coroutine.
 */
class SplitAsk {
    /** Cadres en fractions de l'image (gauche, haut, droite, bas). */
    class Q(val img: Bitmap, val boxes: List<FloatArray>, val title: String, val text: String)

    var shown by mutableStateOf<Q?>(null)
        private set
    private var d: CompletableDeferred<List<FloatArray>?>? = null

    /** Cadres validés (fractions), ou null si annulé. */
    suspend fun edit(q: Q): List<FloatArray>? {
        val dd = CompletableDeferred<List<FloatArray>?>()
        d = dd
        shown = q
        return try { dd.await() } finally { shown = null; d = null }
    }

    fun answer(r: List<FloatArray>?) { d?.complete(r) }

    /**
     * Documents [found] détectés sur [img] (scan de [dpi]) : aperçu si moins que [expected] ou si [always].
     * Cadres inchangés : documents détectés (redressés) ; modifiés : découpe des cadres. Null = annulé.
     */
    suspend fun review(img: Bitmap, dpi: Int, expected: Int, found: List<Bitmap>, always: Boolean): List<Bitmap>? {
        val short = expected > 1 && found.size < expected
        if (!short && !always) return found
        val (pv, fr) = withContext(Dispatchers.Default) {
            val k = 1000f / maxOf(img.width, img.height)
            val pv = if (k < 1f) Bitmap.createScaledBitmap(img, (img.width * k).toInt(), (img.height * k).toInt(), true) else img
            val mm = dpi / 25.4f
            pv to Images.docExtents(img, dpi.toFloat(), expected).map {
                floatArrayOf(
                    (it.left * mm / img.width).coerceIn(0f, 1f), (it.top * mm / img.height).coerceIn(0f, 1f),
                    (it.right * mm / img.width).coerceIn(0f, 1f), (it.bottom * mm / img.height).coerceIn(0f, 1f),
                )
            }
        }
        val title = if (short) "${found.size} document(s) sur $expected" else "${found.size} document(s) détecté(s)"
        val text = if (short) "Ajustez les cadres (glisser un coin ou l'intérieur), ajoutez celui qui manque, puis validez."
        else "Vérifiez les cadres. Glisser un coin : taille ; glisser l'intérieur : position."
        val r = edit(Q(pv, fr, title, text)) ?: return null
        val same = r.size == fr.size && r.indices.all { i -> r[i].indices.all { j -> abs(r[i][j] - fr[i][j]) < 0.002f } }
        if (same) return found
        return withContext(Dispatchers.Default) { r.map { Images.cropArea(img, it) } }
    }
}

/** Image avec plusieurs cadres numérotés à déplacer et redimensionner ; [sel] : cadre choisi. */
@Composable
private fun BoxesEditor(img: Bitmap, boxes: List<FloatArray>, sel: Int, onSel: (Int) -> Unit, onChange: (List<FloatArray>) -> Unit) {
    val cur by rememberUpdatedState(boxes)
    val change by rememberUpdatedState(onChange)
    val select by rememberUpdatedState(onSel)
    val accent = MaterialTheme.colorScheme.primary
    val red = Color(0xFFE53935)
    fun hit(x: Float, y: Float): Int =
        cur.indices.filter { x in cur[it][0]..cur[it][2] && y in cur[it][1]..cur[it][3] }
            .minByOrNull { (cur[it][2] - cur[it][0]) * (cur[it][3] - cur[it][1]) } ?: -1
    Box(
        Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height.coerceAtLeast(1))
            .pointerInput(Unit) { detectTapGestures { o -> select(hit(o.x / size.width, o.y / size.height)) } }
            .pointerInput(Unit) {
                var idx = -1; var mode = -1
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        idx = -1; mode = -1
                        var best = Float.MAX_VALUE
                        cur.forEachIndexed { bi, r ->
                            listOf(r[0] to r[1], r[2] to r[1], r[2] to r[3], r[0] to r[3]).forEachIndexed { ci, (cx, cy) ->
                                val dd = hypot(cx * w - o.x, cy * h - o.y)
                                if (dd < best) { best = dd; idx = bi; mode = ci }
                            }
                        }
                        if (best > 32.dp.toPx()) { idx = hit(o.x / w, o.y / h); mode = if (idx >= 0) 4 else -1 }
                        if (idx >= 0) select(idx)
                    },
                    onDrag = { ch, d ->
                        if (idx >= 0 && idx < cur.size) {
                            ch.consume()
                            val dx = d.x / size.width; val dy = d.y / size.height
                            val r = cur[idx].copyOf(); val mn = 0.02f
                            when (mode) {
                                0 -> { r[0] = (r[0] + dx).coerceIn(0f, r[2] - mn); r[1] = (r[1] + dy).coerceIn(0f, r[3] - mn) }
                                1 -> { r[2] = (r[2] + dx).coerceIn(r[0] + mn, 1f); r[1] = (r[1] + dy).coerceIn(0f, r[3] - mn) }
                                2 -> { r[2] = (r[2] + dx).coerceIn(r[0] + mn, 1f); r[3] = (r[3] + dy).coerceIn(r[1] + mn, 1f) }
                                3 -> { r[0] = (r[0] + dx).coerceIn(0f, r[2] - mn); r[3] = (r[3] + dy).coerceIn(r[1] + mn, 1f) }
                                else -> {
                                    val mx = dx.coerceIn(-r[0], 1f - r[2]); val my = dy.coerceIn(-r[1], 1f - r[3])
                                    r[0] += mx; r[2] += mx; r[1] += my; r[3] += my
                                }
                            }
                            change(cur.toMutableList().also { it[idx] = r })
                        }
                    },
                )
            },
    ) {
        Image(img.asImageBitmap(), "Vitre numérisée", Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE; textSize = 13.dp.toPx(); isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            boxes.forEachIndexed { i, r ->
                val c = if (i == sel) accent else red
                val l = r[0] * w; val t = r[1] * h; val rr = r[2] * w; val b = r[3] * h
                drawRect(c.copy(alpha = 0.12f), Offset(l, t), Size(rr - l, b - t))
                drawRect(c, Offset(l, t), Size(rr - l, b - t), style = Stroke(2.dp.toPx()))
                for ((x, y) in listOf(l to t, rr to t, rr to b, l to b)) {
                    drawCircle(Color.White, 8.dp.toPx(), Offset(x, y))
                    drawCircle(c, 6.dp.toPx(), Offset(x, y))
                }
                val cx = l + 14.dp.toPx(); val cy = t + 14.dp.toPx()
                drawCircle(c, 11.dp.toPx(), Offset(cx, cy))
                txt.color = Color.White.toArgb()
                drawContext.canvas.nativeCanvas.drawText("${i + 1}", cx, cy + 5.dp.toPx(), txt)
            }
        }
    }
}

@Composable
fun SplitDialog(a: SplitAsk) {
    val q = a.shown ?: return
    var boxes by remember(q) { mutableStateOf(q.boxes.map { it.copyOf() }) }
    var sel by remember(q) { mutableIntStateOf(-1) }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(q.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(q.text, style = MaterialTheme.typography.bodySmall)
                Box(Modifier.heightIn(max = 460.dp)) {
                    BoxesEditor(q.img, boxes, sel, { sel = it }) { boxes = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        boxes = boxes + floatArrayOf(0.3f, 0.3f, 0.7f, 0.7f); sel = boxes.size - 1
                    }) { Text("Ajouter") }
                    OutlinedButton(enabled = sel in boxes.indices, onClick = {
                        boxes = boxes.filterIndexed { i, _ -> i != sel }; sel = -1
                    }) { Text("Supprimer") }
                }
                if (boxes.isEmpty()) Text("Aucun cadre : la page entière sera gardée.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ a.answer(boxes) }) { Text(if (boxes.isEmpty()) "Page entière" else "Valider (${boxes.size})") } },
        dismissButton = { TextButton({ a.answer(null) }) { Text("Annuler") } },
    )
}

/** Nombre de documents posés sur la vitre (Auto = 0, ou 1 à 8) et vérification systématique des cadres. */
@Composable
fun DocCount(n: Int, check: Boolean, onCheck: (Boolean) -> Unit, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Documents sur la vitre", style = MaterialTheme.typography.labelLarge)
        Chips((0..8).toList(), n, { if (it == 0) "Auto" else "$it" }, onChange)
        Text(
            if (n == 0) "Détection libre." else "Morceaux regroupés si besoin ; cadres à ajuster si moins de $n trouvés.",
            style = MaterialTheme.typography.bodySmall,
        )
        Toggle("Toujours vérifier les cadres", check, onCheck)
    }
}
