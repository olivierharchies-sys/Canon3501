package be.canonhub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.OutputStream
import kotlin.math.abs

/** Formats de sortie (en cm, portrait). */
enum class Fmt(val label: String, val w: Float, val h: Float) {
    Original("Original", 0f, 0f),
    A4("A4", 21f, 29.7f),
    A5("A5", 14.8f, 21f),
    A6("A6", 10.5f, 14.8f),
    P10x15("10×15", 10.2f, 15.2f),
    P13x18("13×18", 12.7f, 17.8f),
    Card("Carte", 8.56f, 5.4f),
    Custom("Perso", 0f, 0f),
}

/** Choix de sortie : format + dimensions personnalisées (hauteur vide = proportions gardées). */
data class OutFmt(val fmt: Fmt = Fmt.Original, val w: String = "12", val h: String = "") {
    /** Taille cible en cm, ou null = taille d'origine. 0 en hauteur = proportions. */
    fun target(): Pair<Float, Float>? = when (fmt) {
        Fmt.Original -> null
        Fmt.Custom -> w.replace(',', '.').toFloatOrNull()?.takeIf { it in 1f..30f }?.let { cw ->
            cw to (h.replace(',', '.').toFloatOrNull()?.takeIf { it in 1f..42f } ?: 0f)
        }
        else -> fmt.w to fmt.h
    }
}

object Formats {
    private const val CM_PT = 72f / 2.54f
    private const val CM_300 = 300f / 2.54f

    /** Taille physique (cm) d'un scan : pixels d'origine / dpi, au prorata du recadrage. */
    fun physical(bytes: ByteArray, dpi: Int, shown: Bitmap, decodedW: Int): Pair<Float, Float> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val k = o.outWidth.toFloat() / decodedW.coerceAtLeast(1)
        val cm = 2.54f / dpi
        return shown.width * k * cm to shown.height * k * cm
    }

    /** Format standard le plus proche (±7 %, orientation libre), sinon dimensions brutes. */
    fun detect(wCm: Float, hCm: Float): String {
        val a = minOf(wCm, hCm); val b = maxOf(wCm, hCm)
        val hit = Fmt.entries.filter { it.w > 0 }.firstOrNull { f ->
            val fa = minOf(f.w, f.h); val fb = maxOf(f.w, f.h)
            abs(a - fa) / fa < 0.07f && abs(b - fb) / fb < 0.07f
        }
        val dims = "%.1f × %.1f cm".format(wCm, hCm)
        return if (hit != null) "${hit.label} ($dims)" else dims
    }

    /** Taille finale (cm) d'une image placée dans la cible, proportions gardées, orientation adaptée. */
    fun fit(imgW: Int, imgH: Int, t: Pair<Float, Float>): Pair<Float, Float> {
        val (tw0, th0) = t
        if (th0 <= 0f) return tw0 to tw0 * imgH / imgW
        val land = imgW > imgH
        val tw = if (land) maxOf(tw0, th0) else minOf(tw0, th0)
        val th = if (land) minOf(tw0, th0) else maxOf(tw0, th0)
        val s = minOf(tw / imgW, th / imgH)
        return imgW * s to imgH * s
    }

    /** Image redimensionnée à la taille cible, à 300 dpi. */
    fun resize(b: Bitmap, t: Pair<Float, Float>): Bitmap {
        val (w, h) = fit(b.width, b.height, t)
        return Bitmap.createScaledBitmap(b, (w * CM_300).toInt().coerceAtLeast(1), (h * CM_300).toInt().coerceAtLeast(1), true)
    }

    /** Feuille A4 blanche (300 dpi) avec l'image à la taille cible, en haut à gauche (repère de coupe). */
    fun onA4(b: Bitmap, t: Pair<Float, Float>): Bitmap {
        val (w, h) = fit(b.width, b.height, t)
        val out = Bitmap.createBitmap(2481, 3508, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val m = 0.5f * CM_300
        c.drawBitmap(b, null, RectF(m, m, m + w * CM_300, m + h * CM_300), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** PDF dont chaque page a la taille cible (ou A4 si null). */
    fun pdf(pages: List<Bitmap>, t: Pair<Float, Float>?, out: OutputStream) {
        val doc = PdfDocument()
        pages.forEachIndexed { i, b ->
            val (wCm, hCm) = if (t == null) 21f to 29.7f else fit(b.width, b.height, t)
            val pw = (wCm * CM_PT).toInt(); val ph = (hCm * CM_PT).toInt()
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val s = minOf(pw.toFloat() / b.width, ph.toFloat() / b.height)
            val dw = b.width * s; val dh = b.height * s
            p.canvas.drawBitmap(b, null, RectF((pw - dw) / 2, 0f, (pw + dw) / 2, dh), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(p)
        }
        doc.writeTo(out)
        doc.close()
    }
}

/** Sélecteur de format de sortie (puces + champs personnalisés). */
@Composable
fun FormatPicker(value: OutFmt, onChange: (OutFmt) -> Unit, detected: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Format de sortie", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fmt.entries.forEach { f ->
                FilterChip(selected = value.fmt == f, onClick = { onChange(value.copy(fmt = f)) }, label = { Text(f.label) })
            }
        }
        if (value.fmt == Fmt.Custom) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value.w, { onChange(value.copy(w = it)) }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Largeur (cm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value.h, { onChange(value.copy(h = it)) }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Hauteur (auto)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
        detected?.let {
            Text(
                "Format détecté : $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
