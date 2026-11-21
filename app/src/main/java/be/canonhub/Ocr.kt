package be.canonhub

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reconnaissance de texte hors ligne (ML Kit, modèle intégré à l'APK). */
object Ocr {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(b: Bitmap): Text = suspendCancellableCoroutine { c ->
        client.process(InputImage.fromBitmap(b, 0))
            .addOnSuccessListener { if (c.isActive) c.resume(it) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(IOException(it.message ?: "OCR impossible")) }
    }

    /** PDF A4 : image visible + calque de texte invisible (copiable, cherchable). */
    fun searchablePdf(pages: List<Pair<Bitmap, Text?>>, t: Pair<Float, Float>?, out: OutputStream) {
        val doc = PdfDocument()
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(1, 255, 255, 255) }
        pages.forEachIndexed { i, (b, tx) ->
            val (wCm, hCm) = if (t == null) 21f to 29.7f else Formats.fit(b.width, b.height, t)
            val pw = (wCm * 72f / 2.54f).toInt(); val ph = (hCm * 72f / 2.54f).toInt()
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val sc = minOf(pw.toFloat() / b.width, ph.toFloat() / b.height)
            val ox = (pw - b.width * sc) / 2
            p.canvas.drawBitmap(b, null, RectF(ox, 0f, ox + b.width * sc, b.height * sc), Paint(Paint.FILTER_BITMAP_FLAG))
            tx?.textBlocks?.forEach { blk ->
                blk.lines.forEach { ln ->
                    val r = ln.boundingBox ?: return@forEach
                    val h = r.height() * sc
                    if (h <= 0f || ln.text.isBlank()) return@forEach
                    ink.textSize = h * 0.85f
                    ink.textScaleX = 1f
                    val w = ink.measureText(ln.text)
                    if (w > 0f) ink.textScaleX = (r.width() * sc) / w
                    p.canvas.drawText(ln.text, ox + r.left * sc, r.bottom * sc - h * 0.15f, ink)
                }
            }
            doc.finishPage(p)
        }
        doc.writeTo(out)
        doc.close()
    }
}
