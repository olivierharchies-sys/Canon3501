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

/** Mises en page spéciales. */
enum class Imposition(val label: String, val sub: String) {
    Normal("Normale", "Pages par feuille au choix"),
    Booklet("Livret", "Feuilles pliées en deux ; recto-verso manuel activé"),
    Poster("Affiche", "Première page agrandie sur plusieurs feuilles, 1 cm de collage"),
    Contact("Planche contact", "20 miniatures numérotées par feuille"),
}

/** Mise en page et ajouts : zone gardée, taille imposée (cm), filigrane, en-tête, numéros, miroir. */
data class Extras(
    val mode: Imposition = Imposition.Normal,
    val poster: Int = 2,
    val mirror: Boolean = false,
    val watermark: String = "",
    val numbers: Boolean = false,
    val header: String = "",
    val date: Boolean = false,
    val crop: FloatArray? = null,
    val size: Pair<Float, Float>? = null,
) {
    fun active() = mode != Imposition.Normal || mirror || watermark.isNotBlank() || numbers ||
        header.isNotBlank() || date || crop != null || size != null
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
        fun firstSize(): Pair<Int, Int> = image?.let { it.width to it.height }
            ?: pdf!!.openPage(0).use { it.width to it.height }
        override fun close() { pdf?.close(); pfd?.close() }
    }

    /** Construit un PDF au format choisi (mises en page et ajouts compris) ; renvoie le fichier et l'orientation retenue. */
    fun prepare(ctx: Context, uri: Uri, mime: String, o: PrintOpts, range: String = "", x: Extras = Extras()): Pair<File, Boolean> {
        val posterN = if (x.mode == Imposition.Poster) x.poster.coerceIn(2, 4) else 1
        Source(ctx, uri, mime, (o.quality.dpi * posterN).coerceAtMost(300)).use { src ->
            val order = Gutenprint.parseRange(range, src.count)
            val (fw, fh) = src.firstSize()
            val c0 = x.crop
            val wide = if (c0 == null) fw > fh else fw * (c0[2] - c0[0]) > fh * (c0[3] - c0[1])
            val nUp = when (x.mode) { Imposition.Booklet -> 2; Imposition.Contact -> 20; Imposition.Poster -> 1; else -> o.nUp }
            val landscape = when {
                x.mode == Imposition.Booklet -> true
                o.orient == Orient.Portrait -> false
                o.orient == Orient.Landscape -> true
                x.mode == Imposition.Contact -> false
                nUp == 2 -> !wide
                else -> wide
            }
            val m = o.paper.media
            var w = m.widthMils * MILS_TO_PT; var h = m.heightMils * MILS_TO_PT
            if (landscape) { val t = w; w = h; h = t }
            val margin = if (o.borderless) 0f else 8.5f   // 3 mm
            val top = if (x.header.isNotBlank() || x.date) 16f else 0f
            val bottom = if (x.numbers) 16f else 0f
            val area = RectF(margin, margin + top, w - margin, h - margin - bottom)
            val pt = paint(o)
            fun bmp(i: Int): Bitmap = Images.cropArea(src.page(i), x.crop)
            fun done(b: Bitmap) { if (mime == "application/pdf") b.recycle() }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 8f; textAlign = Paint.Align.CENTER }

            // Chaque feuille est une fonction de dessin.
            val sheets = ArrayList<(Canvas) -> Unit>()
            fun sheet(f: (Canvas) -> Unit) { sheets.add(f) }
            when (x.mode) {
                Imposition.Booklet -> {
                    val cells = cells(2, area)
                    val n = order.size
                    val pad = (n + 3) / 4 * 4
                    fun side(l: Int, r: Int): (Canvas) -> Unit = { c ->
                        listOf(l, r).forEachIndexed { k, idx ->
                            if (idx < n) { val b = bmp(order[idx]); draw(c, b, cells[k], false, pt); done(b) }
                        }
                    }
                    for (s in 0 until pad / 4) {
                        sheet(side(pad - 1 - 2 * s, 2 * s))
                        sheet(side(2 * s + 1, pad - 2 - 2 * s))
                    }
                }
                Imposition.Poster -> {
                    val n = posterN
                    val ov = 28.35f   // 1 cm de recouvrement pour coller
                    val iw = area.width(); val ih = area.height()
                    val tw = n * iw - (n - 1) * ov; val th = n * ih - (n - 1) * ov
                    val guide = Paint().apply {
                        color = Color.LTGRAY; strokeWidth = 0.6f; style = Paint.Style.STROKE
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
                    }
                    val first = order.firstOrNull() ?: 0
                    for (r in 0 until n) for (col in 0 until n) sheet { c ->
                        val b = bmp(first)
                        val s = minOf(tw / b.width, th / b.height)
                        val dw = b.width * s; val dh = b.height * s
                        val ox = (tw - dw) / 2; val oy = (th - dh) / 2
                        c.save(); c.clipRect(area)
                        c.translate(area.left - col * (iw - ov), area.top - r * (ih - ov))
                        c.drawBitmap(b, null, RectF(ox, oy, ox + dw, oy + dh), pt)
                        c.restore()
                        done(b)
                        if (col > 0) c.drawLine(area.left + ov, area.top, area.left + ov, area.bottom, guide)
                        if (r > 0) c.drawLine(area.left, area.top + ov, area.right, area.top + ov, guide)
                        c.drawText("Ligne ${r + 1} · colonne ${col + 1}", area.right - 50f, area.bottom - 4f, label)
                    }
                }
                else -> {
                    val cells = cells(nUp, area)
                    val contact = x.mode == Imposition.Contact
                    order.chunked(cells.size).forEach { chunk ->
                        sheet { c ->
                            chunk.forEachIndexed { k, idx ->
                                val b = bmp(idx)
                                var r = cells[k]
                                if (contact) {
                                    c.drawText("${idx + 1}", r.centerX(), r.bottom - 2f, label)
                                    r = RectF(r.left, r.top, r.right, r.bottom - 11f)
                                }
                                val sz = x.size
                                if (nUp == 1 && sz != null && !contact) {
                                    // Taille imposée : centrée en haut de la zone, réduite seulement si elle déborde.
                                    val (cw, ch) = Formats.fit(b.width, b.height, sz)
                                    val k2 = minOf(1f, r.width() / (cw * CM_PT), r.height() / (ch * CM_PT))
                                    val dw = cw * CM_PT * k2; val dh = ch * CM_PT * k2
                                    c.drawBitmap(b, null, RectF(r.centerX() - dw / 2, r.top, r.centerX() + dw / 2, r.top + dh), pt)
                                } else draw(c, b, r, o.fill && nUp == 1, pt)
                                done(b)
                            }
                        }
                    }
                }
            }

            val doc = PdfDocument()
            sheets.forEachIndexed { i, drawSheet ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w.toInt(), h.toInt(), i + 1).create())
                val c = page.canvas
                c.save()
                if (x.mirror) c.scale(-1f, 1f, w / 2, 0f)
                drawSheet(c)
                additions(c, x, w, h, margin, i + 1, sheets.size)
                c.restore()
                doc.finishPage(page)
            }
            val f = File(ctx.cacheDir, "print-${System.currentTimeMillis()}.pdf")
            f.outputStream().use { doc.writeTo(it) }
            doc.close()
            return f to landscape
        }
    }

    private const val CM_PT = 72f / 2.54f

    /** En-tête, date, numéros de feuille, filigrane. */
    private fun additions(c: Canvas, x: Extras, w: Float, h: Float, m: Float, n: Int, total: Int) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
        if (x.header.isNotBlank()) c.drawText(x.header, m + 4f, m + 11f, tp)
        if (x.date) {
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date()), w - m - 4f, m + 11f, tp)
        }
        if (x.numbers) {
            tp.textAlign = Paint.Align.CENTER
            c.drawText("$n / $total", w / 2, h - m - 5f, tp)
        }
        val wm = x.watermark.trim()
        if (wm.isNotEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(60, 120, 120, 120); textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 100f
            }
            val diag = kotlin.math.hypot(w, h)
            p.textSize = (100f * diag * 0.7f / p.measureText(wm).coerceAtLeast(1f)).coerceAtMost(150f)
            c.save()
            c.rotate(-Math.toDegrees(kotlin.math.atan2(h.toDouble(), w.toDouble())).toFloat(), w / 2, h / 2)
            c.drawText(wm, w / 2, h / 2 + p.textSize / 3, p)
            c.restore()
        }
    }

    /** Grille de [n] cases dans [a] : 1, 2, 4, 6, 9 ou 20 (planche contact). */
    private fun cells(n: Int, a: RectF): List<RectF> {
        val land = a.width() >= a.height()
        val (cols, rows) = when (n) {
            2 -> if (land) 2 to 1 else 1 to 2
            4 -> 2 to 2
            6 -> if (land) 3 to 2 else 2 to 3
            9 -> 3 to 3
            20 -> if (land) 5 to 4 else 4 to 5
            else -> 1 to 1
        }
        val gap = if (n > 1) 6f else 0f
        val cw = (a.width() - gap * (cols - 1)) / cols; val ch = (a.height() - gap * (rows - 1)) / rows
        return (0 until rows).flatMap { r ->
            (0 until cols).map { c ->
                val l = a.left + c * (cw + gap); val t = a.top + r * (ch + gap)
                RectF(l, t, l + cw, t + ch)
            }
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
