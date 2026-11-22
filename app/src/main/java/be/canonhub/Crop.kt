package be.canonhub

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/** Image avec un cadre à déplacer (glisser dedans) et redimensionner (glisser un coin). Zone en fractions 0..1. */
@Composable
fun CropEditor(img: Bitmap, rect: FloatArray, onChange: (FloatArray) -> Unit) {
    val cur by rememberUpdatedState(rect)
    val change by rememberUpdatedState(onChange)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height.coerceAtLeast(1))
            .pointerInput(Unit) {
                var mode = -1
                detectDragGestures(
                    onDragStart = { o ->
                        val r = cur; val w = size.width.toFloat(); val h = size.height.toFloat()
                        val pts = listOf(r[0] * w to r[1] * h, r[2] * w to r[1] * h, r[2] * w to r[3] * h, r[0] * w to r[3] * h)
                        val d = pts.map { hypot(it.first - o.x, it.second - o.y) }
                        val i = d.indices.minByOrNull { d[it] } ?: 0
                        mode = when {
                            d[i] < 40.dp.toPx() -> i
                            o.x / w in r[0]..r[2] && o.y / h in r[1]..r[3] -> 4
                            else -> -1
                        }
                    },
                    onDrag = { ch, d ->
                        if (mode >= 0) {
                            ch.consume()
                            val dx = d.x / size.width; val dy = d.y / size.height
                            val r = cur.copyOf(); val mn = 0.03f
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
                            change(r)
                        }
                    },
                )
            },
    ) {
        Image(img.asImageBitmap(), null, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val l = rect[0] * w; val t = rect[1] * h; val r = rect[2] * w; val b = rect[3] * h
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset.Zero, Size(w, t))
            drawRect(shade, Offset(0f, b), Size(w, h - b))
            drawRect(shade, Offset(0f, t), Size(l, b - t))
            drawRect(shade, Offset(r, t), Size(w - r, b - t))
            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
            // Tiers : aide au cadrage.
            for (k in 1..2) {
                drawLine(Color.White.copy(alpha = 0.5f), Offset(l + (r - l) * k / 3, t), Offset(l + (r - l) * k / 3, b), 1f)
                drawLine(Color.White.copy(alpha = 0.5f), Offset(l, t + (b - t) * k / 3), Offset(r, t + (b - t) * k / 3), 1f)
            }
            for ((x, y) in listOf(l to t, r to t, r to b, l to b)) {
                drawCircle(Color.White, 11.dp.toPx(), Offset(x, y))
                drawCircle(accent, 8.dp.toPx(), Offset(x, y))
            }
        }
    }
}

/**
 * Fenêtre plein écran : choix d'une zone et de la taille de sortie.
 * [load] fournit l'image entière ; [fullCm] sa taille réelle en cm (null si inconnue).
 */
@Composable
fun CropDialog(
    init: FloatArray?, fullCm: Pair<Float, Float>?, out: OutFmt, onOut: (OutFmt) -> Unit,
    load: suspend () -> Bitmap?, onDone: (FloatArray?) -> Unit, onCancel: () -> Unit,
) {
    val img by produceState<Bitmap?>(null) { value = withContext(Dispatchers.Default) { runCatching { load() }.getOrNull() } }
    var rect by remember { mutableStateOf(init?.copyOf() ?: floatArrayOf(0f, 0f, 1f, 1f)) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Zone à garder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Hint("Glissez un coin pour redimensionner, l'intérieur pour déplacer.")
                val b = img
                if (b == null) Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else CropEditor(b, rect) { rect = it }
                // Proportions courantes : le cadre s'ajuste autour de son centre.
                val ratios = listOf("Libre" to 0f, "Carte" to 85.6f / 54f, "10×15" to 1.5f, "13×18" to 1.4f, "A4" to 1.414f, "Carré" to 1f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ratios.forEach { (n, q) ->
                        FilterChip(selected = false, label = { Text(n) }, onClick = {
                            val im = img
                            if (q > 0f && im != null) rect = fitRatio(rect, q, im.width.toFloat() / im.height)
                            else if (q == 0f) rect = floatArrayOf(0f, 0f, 1f, 1f)
                        })
                    }
                }
                fullCm?.let { (w, h) ->
                    Text(
                        "Zone : %.1f × %.1f cm".format((rect[2] - rect[0]) * w, (rect[3] - rect[1]) * h),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("Taille de sortie", style = MaterialTheme.typography.labelLarge)
                FormatPicker(out, onOut)
                Hint("« Original » = taille réelle de la zone. « Perso » : largeur seule = proportions gardées.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text("Annuler") }
                    OutlinedButton(onClick = { onDone(null) }, modifier = Modifier.weight(1f)) { Text("Tout garder") }
                    Button(onClick = {
                        val full = rect[0] <= 0.001f && rect[1] <= 0.001f && rect[2] >= 0.999f && rect[3] >= 0.999f
                        onDone(if (full) null else rect)
                    }, modifier = Modifier.weight(1f)) { Text("Valider") }
                }
            }
        }
    }
}

/** Cadre aux proportions [q] (largeur/hauteur réelles, orientation du cadre actuel), centré sur l'actuel. */
private fun fitRatio(r: FloatArray, q0: Float, imgRatio: Float): FloatArray {
    val cw = r[2] - r[0]; val ch = r[3] - r[1]
    val land = cw * imgRatio >= ch
    val q = if (land) maxOf(q0, 1f / q0) else minOf(q0, 1f / q0)
    // En fractions : largeur/hauteur = q / imgRatio.
    var w = cw; var h = w * imgRatio / q
    if (h > 1f) { h = 1f; w = h * q / imgRatio }
    if (w > 1f) { w = 1f; h = w * imgRatio / q }
    val cx = ((r[0] + r[2]) / 2).coerceIn(w / 2, 1f - w / 2)
    val cy = ((r[1] + r[3]) / 2).coerceIn(h / 2, 1f - h / 2)
    return floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
}
