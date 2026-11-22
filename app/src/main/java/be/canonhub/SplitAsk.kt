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

/** Question avec aperçu des documents détectés (appel depuis une coroutine). */
class SplitAsk {
    class Q(val img: Bitmap, val boxes: List<RectF>, val title: String, val text: String, val yes: String, val no: String)

    var shown by mutableStateOf<Q?>(null)
        private set
    private var d: CompletableDeferred<Boolean>? = null

    /** Aperçu + cadres (pixels de l'aperçu). Renvoie true pour [Q.yes], false pour [Q.no]. */
    suspend fun ask(q: Q): Boolean {
        val dd = CompletableDeferred<Boolean>()
        d = dd
        shown = q
        return try { dd.await() } finally { shown = null; d = null }
    }

    /** Nombre trouvé inférieur au nombre annoncé : continuer avec ce qui est trouvé ? */
    suspend fun missing(img: Bitmap, boxes: List<RectF>, expected: Int): Boolean = ask(
        Q(
            img, boxes, "${boxes.size} document(s) sur $expected",
            "Documents trop proches ou trop clairs ? Espacez-les d'au moins 5 mm, ou continuez avec les cadres ci-dessous.",
            "Continuer", "Annuler",
        ),
    )

    fun answer(separate: Boolean) { d?.complete(separate) }

    companion object {
        /** Cadres (mm depuis le coin de la vitre) convertis en pixels d'une image de [dpi]. */
        fun toPx(ext: List<RectF>, dpi: Float): List<RectF> {
            val k = dpi / 25.4f
            return ext.map { RectF(it.left * k, it.top * k, it.right * k, it.bottom * k) }
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
    val q = a.shown ?: return
    val img = remember(q) { annotate(q.img, q.boxes) }
    AlertDialog(
        onDismissRequest = { a.answer(false) },
        title = { Text(q.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(q.text)
                Image(img.asImageBitmap(), "Documents détectés", Modifier.fillMaxWidth().heightIn(max = 380.dp))
            }
        },
        confirmButton = { TextButton({ a.answer(true) }) { Text(q.yes) } },
        dismissButton = { TextButton({ a.answer(false) }) { Text(q.no) } },
    )
}

/** Nombre de documents posés sur la vitre : Auto (0) ou 1 à 8. */
@Composable
fun DocCount(n: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Documents sur la vitre", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Chips((0..8).toList(), n, { if (it == 0) "Auto" else "$it" }, onChange)
        Text(
            if (n == 0) "Détection libre." else "Détection guidée : morceaux regroupés si besoin ; aperçu si moins de $n trouvés.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
    }
}
