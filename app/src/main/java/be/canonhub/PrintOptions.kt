package be.canonhub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintAttributes.MediaSize
import java.io.File
import java.io.IOException

/** Formats acceptés par la G3501 (bac arrière). Tailles en millièmes de pouce. */
enum class Paper(val label: String, val media: MediaSize) {
    A4("A4", MediaSize.ISO_A4),
    A5("A5", MediaSize.ISO_A5),
    A6("A6", MediaSize.ISO_A6),
    B5("B5", MediaSize.ISO_B5),
    Letter("Lettre US", MediaSize.NA_LETTER),
    Legal("Légal US", MediaSize.NA_LEGAL),
    P9x13("Photo 9×13", MediaSize("CANON_89X127", "9x13", 3504, 5000)),
    P10x15("Photo 10×15", MediaSize.NA_INDEX_4X6),
    P13x18("Photo 13×18", MediaSize("CANON_127X178", "13x18", 5000, 7008)),
    P20x25("Photo 20×25", MediaSize("CANON_203X254", "20x25", 8000, 10000)),
    Square("Carré 13×13", MediaSize("CANON_127X127", "13x13", 5000, 5000)),
    Card("Carte de visite 55×91", MediaSize("CANON_55X91", "55x91", 2165, 3583)),
    EnvDL("Enveloppe DL", MediaSize("CANON_DL", "DL", 4331, 8661)),
    Env10("Enveloppe n°10", MediaSize("CANON_COM10", "Com10", 4125, 9500)),
}

/** Types de papier Canon de la gamme G3000 : à choisir dans la fenêtre Canon (Android ne transmet pas ce réglage). */
enum class MediaType(val label: String) {
    Plain("Papier ordinaire"),
    GlossyEveryday("Papier Photo Glacé « Everyday Use » (GP-501/601)"),
    Glossy("Papier Photo Glacé (GP-701)"),
    PlusGlossy("Papier Photo Plus Glacé II (PP-201)"),
    ProLuster("Papier Photo Pro Lustré (LU-101)"),
    PlusSemiGloss("Papier Photo Plus Semi-Glacé (SG-201)"),
    ProPlatinum("Papier Photo Pro Platinum (PT-101)"),
    Matte("Papier Photo Mat (MP-101)"),
    HighRes("Papier Haute Résolution (HR-101N)"),
    Envelope("Enveloppe"),
    Transfer("Transfert sur T-shirt (TR-301)"),
    OtherPhoto("Autre papier photo"),
}

enum class Quality(val label: String, val dpi: Int) {
    Draft("Brouillon", 100), Standard("Standard", 150), High("Élevée", 300),
}

enum class Orient(val label: String) { Auto("Auto"), Portrait("Portrait"), Landscape("Paysage") }

data class PrintOpts(
    val paper: Paper = Paper.A4,
    val type: MediaType = MediaType.Plain,
    val quality: Quality = Quality.Standard,
    val orient: Orient = Orient.Auto,
    val color: Boolean = true,
    val borderless: Boolean = false,
    val nUp: Int = 1,
    val fill: Boolean = false,
) {
    /** Rappel pour la fenêtre Canon : réglages qu'Android ne peut pas transmettre. */
    fun save(): String = listOf(paper.name, type.name, quality.name, orient.name, color, borderless, nUp, fill).joinToString("|")

    fun reminder(): String =
        "Dans la fenêtre Canon (Plus d'options) : type « ${type.label} », qualité « ${quality.label} »" +
            (if (borderless) ", sans bordure" else "") + "."
}

fun printOptsOf(s: String): PrintOpts = runCatching {
    val p = s.split("|")
    PrintOpts(Paper.valueOf(p[0]), MediaType.valueOf(p[1]), Quality.valueOf(p[2]), Orient.valueOf(p[3]),
        p[4].toBoolean(), p[5].toBoolean(), p[6].toInt(), p[7].toBoolean())
}.getOrDefault(PrintOpts())

object PrintPrep {
    private const val MILS_TO_PT = 72f / 1000f

    fun attributes(o: PrintOpts, landscape: Boolean): PrintAttributes =
        PrintAttributes.Builder()
            .setMediaSize(if (landscape) o.paper.media.asLandscape() else o.paper.media.asPortrait())
            .setColorMode(if (o.color) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME)
            .apply { if (o.borderless) setMinMargins(PrintAttributes.Margins.NO_MARGINS) }
            .build()

    /** Brouillon : couleurs éclaircies de 40 % (moins d'encre) ; N&B : saturation nulle. */
    private fun paint(o: PrintOpts): Paint {
        val cm = ColorMatrix()
        if (!o.color) cm.setSaturation(0f)
        if (o.quality == Quality.Draft) cm.postConcat(ColorMatrix(floatArrayOf(
            0.6f, 0f, 0f, 0f, 102f,
            0f, 0.6f, 0f, 0f, 102f,
            0f, 0f, 0.6f, 0f, 102f,
            0f, 0f, 0f, 1f, 0f)))
        return Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
    }

    /** Le fichier peut-il partir tel quel ? */
    fun needsPrep(mime: String, o: PrintOpts) =
        mime != "application/pdf" || o.quality == Quality.Draft || o.nUp > 1 || !o.color

    /** Source paginée : pages d'un PDF ou image unique, rendues à la demande. */
    private class Source(ctx: Context, uri: Uri, mime: String, private val dpi: Int) : AutoCloseable {
        private val pfd: ParcelFileDescriptor? =
            if (mime == "application/pdf") ctx.contentResolver.openFileDescriptor(uri, "r") else null
        private val pdf = pfd?.let { PdfRenderer(it) }
        private val image: Bitmap? = if (pdf == null) {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("fichier illisible")
            Images.decode(raw, 4000) ?: throw IOException("image illisible")
        } else null
        val count = pdf?.pageCount ?: 1
        fun page(i: Int): Bitmap {
            image?.let { return it }
            val p = pdf!!.openPage(i)
            val w = (p.width * dpi / 72).coerceAtLeast(1)
            val h = (p.height * dpi / 72).coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            p.close()
            return b
        }
        fun firstIsWide(): Boolean = image?.let { it.width > it.height }
            ?: pdf!!.openPage(0).use { it.width > it.height }
        override fun close() { pdf?.close(); pfd?.close() }
    }

    /** Construit un PDF au format choisi ; renvoie le fichier et l'orientation retenue. */
    fun prepare(ctx: Context, uri: Uri, mime: String, o: PrintOpts, range: String = ""): Pair<File, Boolean> {
        Source(ctx, uri, mime, o.quality.dpi).use { src ->
            val landscape = when (o.orient) {
                Orient.Portrait -> false
                Orient.Landscape -> true
                Orient.Auto -> if (o.nUp == 2) !src.firstIsWide() else src.firstIsWide()
            }
            val m = o.paper.media
            var w = m.widthMils * MILS_TO_PT; var h = m.heightMils * MILS_TO_PT
            if (landscape) { val t = w; w = h; h = t }
            val margin = if (o.borderless) 0f else 8.5f   // 3 mm
            val cells = cells(o.nUp, w, h, margin)
            val doc = PdfDocument()
            val pt = paint(o)
            val order = Gutenprint.parseRange(range, src.count)
            var idx = 0; var sheet = 0
            while (idx < order.size) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w.toInt(), h.toInt(), ++sheet).create())
                for (cell in cells) {
                    if (idx >= order.size) break
                    val b = src.page(order[idx++])
                    draw(page.canvas, b, cell, o.fill && o.nUp == 1, pt)
                    if (mime == "application/pdf") b.recycle()
                }
                doc.finishPage(page)
            }
            val f = File(ctx.cacheDir, "print-${System.currentTimeMillis()}.pdf")
            f.outputStream().use { doc.writeTo(it) }
            doc.close()
            return f to landscape
        }
    }

    /** Cases de la feuille : 1, 2 (côte à côte sur la longueur) ou 4 (2×2) pages par feuille. */
    private fun cells(n: Int, w: Float, h: Float, m: Float): List<RectF> {
        val iw = w - 2 * m; val ih = h - 2 * m; val gap = if (n > 1) 6f else 0f
        return when (n) {
            2 -> if (w >= h) listOf(RectF(m, m, m + (iw - gap) / 2, m + ih), RectF(m + (iw + gap) / 2, m, m + iw, m + ih))
                 else listOf(RectF(m, m, m + iw, m + (ih - gap) / 2), RectF(m, m + (ih + gap) / 2, m + iw, m + ih))
            4 -> {
                val cw = (iw - gap) / 2; val ch = (ih - gap) / 2
                listOf(
                    RectF(m, m, m + cw, m + ch), RectF(m + cw + gap, m, m + iw, m + ch),
                    RectF(m, m + ch + gap, m + cw, m + ih), RectF(m + cw + gap, m + ch + gap, m + iw, m + ih),
                )
            }
            else -> listOf(RectF(m, m, m + iw, m + ih))
        }
    }

    /** Ajuste (tout visible, centré) ou remplit (recadrage) l'image dans la case. */
    private fun draw(c: Canvas, b: Bitmap, r: RectF, fill: Boolean, p: Paint) {
        val sx = r.width() / b.width; val sy = r.height() / b.height
        val s = if (fill) maxOf(sx, sy) else minOf(sx, sy)
        val dw = b.width * s; val dh = b.height * s
        val dst = RectF(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2, r.centerY() + dh / 2)
        c.save(); c.clipRect(r)
        c.drawBitmap(b, null, dst, p)
        c.restore()
    }
}
