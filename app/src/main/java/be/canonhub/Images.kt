package be.canonhub

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.Color as AColor

/** Filtres de document. */
enum class Filter(val label: String) { None("Original"), Gray("Gris"), Doc("Document"), BW("Noir et blanc") }

/** Page numérisée : JPEG brut, résolution, rotation en degrés. */
class Page(val bytes: ByteArray, val dpi: Int, val rot: Int = 0) {
    fun rotated(): Page = Page(bytes, dpi, (rot + 90) % 360)
}

/** Traitements d'image et fabrication de PDF. */
object Images {
    /** Nom de fichier daté : Scan_2026-11-21_01-10-05. */
    fun stamp(prefix: String = "Scan"): String =
        prefix + "_" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.FRANCE).format(java.util.Date())


    fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Luminosité -100..100, contraste 0,5..2. */
    fun adjust(src: Bitmap, brightness: Float, contrast: Float): Bitmap {
        if (brightness == 0f && contrast == 1f) return src
        val t = (1f - contrast) * 128f + brightness
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** Retire les marges blanches autour du document. */
    fun autoCrop(src: Bitmap): Bitmap {
        val sw = 300
        val k = src.width.toFloat() / sw
        val sh = (src.height / k).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        fun dark(x: Int, y: Int): Boolean {
            val c = px[y * sw + x]
            return (AColor.red(c) + AColor.green(c) + AColor.blue(c)) / 3 < 215
        }
        var l = sw; var r = -1; var t = sh; var b = -1
        for (y in 0 until sh) for (x in 0 until sw) if (dark(x, y)) {
            if (x < l) l = x; if (x > r) r = x
            if (y < t) t = y; if (y > b) b = y
        }
        if (r < 0 || (r - l) < sw / 10 || (b - t) < sh / 10) return src
        val m = 4
        val rect = Rect(
            ((l - m).coerceAtLeast(0) * k).toInt(),
            ((t - m).coerceAtLeast(0) * k).toInt(),
            ((r + m).coerceAtMost(sw - 1) * k).toInt().coerceAtMost(src.width),
            ((b + m).coerceAtMost(sh - 1) * k).toInt().coerceAtMost(src.height),
        )
        if (rect.width() <= 0 || rect.height() <= 0) return src
        return Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
    }

    /** Angle d'inclinaison (degrés) par profil de projection des pixels sombres ; 0 si incertain. */
    fun skewAngle(src: Bitmap): Float {
        val sw = 500
        val k = src.width.toFloat() / sw
        val sh = (src.height / k).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = px[y * sw + x]
            if ((AColor.red(c) + AColor.green(c) + AColor.blue(c)) / 3 < 140) { xs += x; ys += y }
        }
        if (xs.size < 300) return 0f
        val step = maxOf(1, xs.size / 30000)
        fun score(deg: Float): Double {
            val r = Math.toRadians(deg.toDouble())
            val cs = kotlin.math.cos(r); val sn = kotlin.math.sin(r)
            val off = sw
            val hist = IntArray(sh + 2 * sw)
            var i = 0
            while (i < xs.size) {
                val yy = (ys[i] * cs - xs[i] * sn).toInt() + off
                if (yy in hist.indices) hist[yy]++
                i += step
            }
            var sum = 0.0
            for (v in hist) sum += v.toDouble() * v
            return sum
        }
        val base = score(0f)
        var best = 0f; var bestS = base
        var a = -6f
        while (a <= 6f) {
            val sc = score(a)
            if (sc > bestS) { bestS = sc; best = a }
            a += 0.25f
        }
        return if (kotlin.math.abs(best) >= 0.3f && bestS > base * 1.1) best else 0f
    }

    /** Redresse un document posé de travers (fond blanc). */
    fun deskew(src: Bitmap): Bitmap {
        val a = skewAngle(src)
        if (a == 0f) return src
        val m = android.graphics.Matrix().apply { setRotate(a) }
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(r)
        val out = Bitmap.createBitmap(r.width().toInt(), r.height().toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(AColor.WHITE)
        m.postTranslate(-r.left, -r.top)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun process(
        bytes: ByteArray, maxDim: Int, crop: Boolean, bright: Float, contrast: Float,
        filt: Filter = Filter.None, rot: Int = 0,
    ): Bitmap? {
        var b = decode(bytes, maxDim) ?: return null
        b = rotate(b, rot)
        if (crop) b = autoCrop(deskew(b))
        b = applyFilter(b, filt)
        return adjust(b, bright, contrast)
    }

    /** Rotation par quarts de tour (90, 180, 270). */
    fun rotate(src: Bitmap, deg: Int): Bitmap {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return src
        val m = android.graphics.Matrix().apply { setRotate(d.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Fond estimé : image réduite, dilatée (efface le texte), puis agrandie. */
    private fun background(src: Bitmap): Bitmap {
        val sw = (src.width / 16).coerceAtLeast(8)
        val sh = (src.height / 16).coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        var px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(2) {
            val o = IntArray(px.size)
            for (y in 0 until sh) for (x in 0 until sw) {
                var r = 0; var g = 0; var b = 0
                for (dy in -2..2) {
                    val yy = (y + dy).coerceIn(0, sh - 1)
                    for (dx in -2..2) {
                        val c = px[yy * sw + (x + dx).coerceIn(0, sw - 1)]
                        r = maxOf(r, AColor.red(c)); g = maxOf(g, AColor.green(c)); b = maxOf(b, AColor.blue(c))
                    }
                }
                o[y * sw + x] = AColor.rgb(r, g, b)
            }
            px = o
        }
        val bg = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(bg, src.width, src.height, true)
    }

    /** Filtres de document : gris, suppression du fond, noir et blanc. */
    fun applyFilter(src: Bitmap, f: Filter): Bitmap {
        if (f == Filter.None) return src
        if (f == Filter.Gray) {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val cm = ColorMatrix().apply { setSaturation(0f) }
            Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
            return out
        }
        val w = src.width; val h = src.height
        val bg = background(src)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w); val brow = IntArray(w)
        fun lv(v: Int, b: Int): Int {
            val n = v * 255 / maxOf(b, 40)
            return ((n - 30) * 255 / 200).coerceIn(0, 255)
        }
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            bg.getPixels(brow, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]; val b = brow[x]
                val r = lv(AColor.red(c), AColor.red(b))
                val g = lv(AColor.green(c), AColor.green(b))
                val bl = lv(AColor.blue(c), AColor.blue(b))
                row[x] = if (f == Filter.BW) {
                    if ((r * 30 + g * 59 + bl * 11) / 100 < 150) AColor.BLACK else AColor.WHITE
                } else AColor.rgb(r, g, bl)
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        bg.recycle()
        return out
    }

    fun jpeg(b: Bitmap, q: Int = 90): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    private fun drawFit(c: Canvas, b: Bitmap, w: Float, h: Float) {
        val sc = minOf(w / b.width, h / b.height)
        val dw = b.width * sc
        val dh = b.height * sc
        c.drawBitmap(b, null, RectF((w - dw) / 2, 0f, (w + dw) / 2, dh), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** PDF A4, une image par page. */
    fun pdf(pages: List<Bitmap>, out: java.io.OutputStream) {
        val doc = PdfDocument()
        pages.forEachIndexed { i, b ->
            val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            drawFit(p.canvas, b, 595f, 842f)
            doc.finishPage(p)
        }
        doc.writeTo(out)
        doc.close()
    }

    /** PDF A4 à partir de scans JPEG, une page décodée à la fois. */
    fun <T> pdfFromScans(scans: List<T>, out: java.io.OutputStream, f: (T) -> Bitmap?) {
        val doc = PdfDocument()
        scans.forEachIndexed { i, s ->
            val b = f(s) ?: return@forEachIndexed
            val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            drawFit(p.canvas, b, 595f, 842f)
            doc.finishPage(p)
            b.recycle()
        }
        doc.writeTo(out)
        doc.close()
    }

    fun savePdf(ctx: Context, write: (java.io.OutputStream) -> Unit): Uri? = runCatching {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${stamp()}.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Canon")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        ctx.contentResolver.openOutputStream(uri)!!.use(write)
        uri
    }.getOrNull()

    fun saveJpeg(ctx: Context, data: ByteArray): Uri? = runCatching {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Canon")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        uri
    }.getOrNull()

    /** Recto et verso d'une carte sur une feuille A4 (300 dpi), taille réelle. */
    fun cardSheet(front: Bitmap, back: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(2481, 3507, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(AColor.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val x = (2481 - Area.Card.w) / 2f
        c.drawBitmap(front, null, RectF(x, 300f, x + Area.Card.w, 300f + Area.Card.h), p)
        c.drawBitmap(back, null, RectF(x, 1300f, x + Area.Card.w, 1300f + Area.Card.h), p)
        return out
    }

    /** Nombre de pages d'un PDF. */
    fun pdfPages(ctx: Context, uri: Uri): Int = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { fd -> PdfRenderer(fd).use { it.pageCount } }
    }.getOrDefault(0)

    /** Extrait les pages impaires ou paires d'un PDF (recto-verso manuel). */
    fun splitPdf(ctx: Context, uri: Uri, odd: Boolean, reverse: Boolean): File? {
        val fd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        fd.use {
            PdfRenderer(fd).use { r ->
                var idx = (0 until r.pageCount).filter { (it % 2 == 0) == odd }
                if (idx.isEmpty()) return null
                if (reverse) idx = idx.reversed()
                val doc = PdfDocument()
                idx.forEachIndexed { n, i ->
                    r.openPage(i).use { pg ->
                        val bw = pg.width * 150 / 72
                        val bh = pg.height * 150 / 72
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AColor.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val out = doc.startPage(PdfDocument.PageInfo.Builder(pg.width, pg.height, n + 1).create())
                        out.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pg.width.toFloat(), pg.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                        doc.finishPage(out)
                        bmp.recycle()
                    }
                }
                val f = File(ctx.cacheDir, if (odd) "recto.pdf" else "verso.pdf")
                f.outputStream().use { doc.writeTo(it) }
                doc.close()
                return f
            }
        }
    }
}
