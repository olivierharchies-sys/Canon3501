package be.canonhub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.Color as AColor

/** Mise en page des copies. */
enum class PageLayout(val label: String) {
    One("1 par feuille"),
    Pack("Optimiser (taille réelle)"),
    Two("2 par feuille (réduit)"),
}

/** Document en attente de copie : JPEG du scan, dpi, ou feuille A4 déjà composée (carte R/V). */
class CopyItem(val page: Page, val sheet: Boolean = false)

object CopyLayout {
    private const val W = 2481
    private const val H = 3508
    private const val MM = 300f / 25.4f

    private fun blank(): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(AColor.WHITE) }

    /** Image décodée + dpi effectif (après réduction éventuelle au décodage). */
    private fun load(it: CopyItem): Pair<Bitmap, Float>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(it.page.bytes, 0, it.page.bytes.size, o)
        val b = Images.decode(it.page.bytes, 3508) ?: return null
        return b to it.page.dpi * b.width.toFloat() / o.outWidth.coerceAtLeast(1)
    }

    private fun prep(b: Bitmap, filt: Filter): Bitmap = Calib.forCopy(Images.applyFilter(b, filt))

    private fun fit(c: Canvas, b: Bitmap, r: RectF) {
        val s = minOf(r.width() / b.width, r.height() / b.height)
        val w = b.width * s; val h = b.height * s
        val l = r.left + (r.width() - w) / 2; val t = r.top + (r.height() - h) / 2
        c.drawBitmap(b, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * Compose les feuilles A4 (300 dpi) et les passe une à une à [sink].
     * [target] : format imposé (cm) ou null.
     */
    fun render(items: List<CopyItem>, layout: PageLayout, target: Pair<Float, Float>?, filt: Filter, sink: (Bitmap) -> Unit) {
        val sheets = items.filter { it.sheet }
        val docs = items.filterNot { it.sheet }
        sheets.forEach { it2 -> load(it2)?.let { (b, _) -> sink(prep(b, filt)) } }
        if (docs.isEmpty()) return
        when {
            target != null -> docs.forEach { d ->
                load(d)?.let { (b, _) -> sink(prep(Formats.onA4(Images.autoCrop(Images.deskew(b)), target), filt)) }
            }
            layout == PageLayout.One -> docs.forEach { d -> load(d)?.let { (b, _) -> sink(prep(b, filt)) } }
            layout == PageLayout.Two -> docs.chunked(2).forEach { pair ->
                val out = blank(); val c = Canvas(out)
                val m = 5 * MM
                pair.forEachIndexed { i, d ->
                    load(d)?.let { (b, _) ->
                        val r = Images.rotate(b, 90)
                        val top = if (i == 0) m else H / 2f + m / 2
                        fit(c, prep(r, filt), RectF(m, top, W - m, top + H / 2f - 1.5f * m))
                    }
                }
                if (pair.size == 2) {
                    val p = Paint().apply { color = AColor.LTGRAY; strokeWidth = 2f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 20f), 0f) }
                    c.drawLine(m, H / 2f, W - m, H / 2f, p)
                }
                sink(out)
            }
            else -> pack(docs, filt, sink)
        }
    }

    /** Rangement par étagères à taille réelle : tickets, cartes, petits documents sur le moins de feuilles possible. */
    private fun pack(docs: List<CopyItem>, filt: Filter, sink: (Bitmap) -> Unit) {
        val m = 5 * MM; val gap = 4 * MM
        val uw = W - 2 * m; val uh = H - 2 * m
        // Recadrage et mise à 300 dpi.
        val parts = docs.mapNotNull { d ->
            load(d)?.let { (b, dpi) ->
                var x = Images.autoCrop(Images.deskew(b))
                val k = 300f / dpi
                if (k != 1f) x = Bitmap.createScaledBitmap(x, (x.width * k).toInt().coerceAtLeast(1), (x.height * k).toInt().coerceAtLeast(1), true)
                // Couché si cela tient mieux en largeur.
                if (x.width > uw && x.height <= uw) x = Images.rotate(x, 90)
                if (x.width > uw || x.height > uh) {
                    val s = minOf(uw / x.width, uh / x.height)
                    x = Bitmap.createScaledBitmap(x, (x.width * s).toInt(), (x.height * s).toInt(), true)
                }
                prep(x, filt)
            }
        }.sortedByDescending { it.height }

        var out: Bitmap? = null; var c: Canvas? = null
        var x = m; var y = m; var shelf = 0f
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val cut = Paint().apply { color = AColor.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
        for (b in parts) {
            if (out != null && x + b.width > m + uw + 1) { x = m; y += shelf + gap; shelf = 0f }
            if (out == null || y + b.height > m + uh + 1) {
                out?.let(sink)
                val nb = blank(); out = nb; c = Canvas(nb); x = m; y = m; shelf = 0f
            }
            c!!.drawBitmap(b, x, y, p)
            c.drawRect(x - 1, y - 1, x + b.width + 1, y + b.height + 1, cut)
            x += b.width + gap
            shelf = maxOf(shelf, b.height.toFloat())
        }
        out?.let(sink)
    }

    /** Écrit une feuille A4 dans un PDF (corrigée de l'échelle d'impression). */
    fun addPdfPage(doc: PdfDocument, b: Bitmap, n: Int) {
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
        pg.canvas.drawBitmap(Calib.fixA4(b), null, RectF(0f, 0f, 595f, 842f), Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(pg)
    }
}
