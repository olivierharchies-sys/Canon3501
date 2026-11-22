package be.canonhub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import android.graphics.Color as AColor

/** Demande de confirmation quand plusieurs documents sont détectés (appel depuis une coroutine). */
class SplitAsk {
    var shown by mutableStateOf<Pair<Bitmap, List<RectF>>?>(null)
        private set
    private var d: CompletableDeferred<Boolean>? = null

    /** Aperçu + cadres (pixels de l'aperçu). Renvoie true = garder séparés, false = un seul document. */
    suspend fun ask(img: Bitmap, boxes: List<RectF>): Boolean {
        val dd = CompletableDeferred<Boolean>()
        d = dd
        shown = img to boxes
        return try { dd.await() } finally { shown = null; d = null }
    }

    fun answer(separate: Boolean) { d?.complete(separate) }

    companion object {
        /** Cadres (mm depuis le coin de la vitre) convertis en pixels d'une image de [dpi]. */
        fun toPx(ext: List<RectF>, dpi: Float): List<RectF> {
            val k = dpi / 25.4f
            return ext.map { RectF(it.left * k, it.top * k, it.right * k, it.bottom * k) }
        }

        /** Zone commune (fractions 0..1 de l'image) de tous les cadres en mm, marge de 2 mm. */
        fun union(ext: List<RectF>, dpi: Float, w: Int, h: Int): FloatArray {
            val k = dpi / 25.4f
            return floatArrayOf(
                ((ext.minOf { it.left } - 2f) * k / w).coerceIn(0f, 1f),
                ((ext.minOf { it.top } - 2f) * k / h).coerceIn(0f, 1f),
                ((ext.maxOf { it.right } + 2f) * k / w).coerceIn(0f, 1f),
                ((ext.maxOf { it.bottom } + 2f) * k / h).coerceIn(0f, 1f),
            )
        }
    }
}

/** Aperçu de la vitre avec les cadres numérotés. */
private fun annotate(src: Bitmap, boxes: List<RectF>): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(out)
    val s = (out.width / 150f).coerceAtLeast(2f)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(229, 57, 53); style = Paint.Style.STROKE; strokeWidth = s }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(229, 57, 53) }
    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; textSize = s * 8; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    boxes.forEachIndexed { i, r ->
        c.drawRect(r, line)
        val cx = r.left + s * 7; val cy = r.top + s * 7
        c.drawCircle(cx, cy, s * 6, fill)
        c.drawText("${i + 1}", cx, cy + s * 3, txt)
    }
    return out
}

@Composable
fun SplitDialog(a: SplitAsk) {
    val s = a.shown ?: return
    val img = remember(s) { annotate(s.first, s.second) }
    AlertDialog(
        onDismissRequest = { a.answer(true) },
        title = { Text("${s.second.size} documents détectés") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Un document coupé en morceaux ? Choisissez « Un seul ».")
                Image(img.asImageBitmap(), "Documents détectés", Modifier.fillMaxWidth().heightIn(max = 380.dp))
            }
        },
        confirmButton = { TextButton({ a.answer(true) }) { Text("${s.second.size} séparés") } },
        dismissButton = { TextButton({ a.answer(false) }) { Text("Un seul") } },
    )
}
