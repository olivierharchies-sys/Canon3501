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

    /** [folder] : sous-dossier de Canon (classement) ; [base] : nom sans extension. */
    fun savePdf(ctx: Context, folder: String? = null, base: String? = null, write: (java.io.OutputStream) -> Unit): Uri? = runCatching {
        val name = "${base?.takeIf { it.isNotBlank() } ?: stamp()}.pdf"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Canon" + (folder?.let { "/$it" } ?: ""))
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        ctx.contentResolver.openOutputStream(uri)!!.use(write)
        Export.copy(ctx, uri, name, "application/pdf")
        uri
    }.getOrNull()

    fun saveJpeg(ctx: Context, data: ByteArray, folder: String? = null, base: String? = null): Uri? = runCatching {
        val name = "${base?.takeIf { it.isNotBlank() } ?: stamp()}.jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Canon" + (folder?.let { "/$it" } ?: ""))
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        Export.copy(ctx, uri, name, "image/jpeg")
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

    /** Planche A4 (300 dpi) remplie de copies de la photo au format w×h cm, traits de coupe gris. */
    fun photoSheet(src: Bitmap, wCm: Float, hCm: Float): Bitmap {
        val px = 300f / 2.54f
        val pw = 2481; val ph = 3508
        val margin = 0.5f * px; val gap = 0.2f * px
        // Orientation de la case selon la photo.
        val land = src.width > src.height
        val cw = (if (land) maxOf(wCm, hCm) else minOf(wCm, hCm)) * px
        val ch = (if (land) minOf(wCm, hCm) else maxOf(wCm, hCm)) * px
        val cols = ((pw - 2 * margin + gap) / (cw + gap)).toInt().coerceAtLeast(1)
        val rows = ((ph - 2 * margin + gap) / (ch + gap)).toInt().coerceAtLeast(1)
        // Recadrage centré aux proportions de la case.
        val r = cw / ch
        val sr = src.width.toFloat() / src.height
        val crop = if (sr > r) {
            val w = (src.height * r).toInt(); Rect((src.width - w) / 2, 0, (src.width + w) / 2, src.height)
        } else {
            val h = (src.width / r).toInt(); Rect(0, (src.height - h) / 2, src.width, (src.height + h) / 2)
        }
        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(AColor.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val line = Paint().apply { color = AColor.LTGRAY; strokeWidth = 2f }
        for (j in 0 until rows) for (i in 0 until cols) {
            val x = margin + i * (cw + gap); val y = margin + j * (ch + gap)
            c.drawBitmap(src, crop, RectF(x, y, x + cw, y + ch), p)
            c.drawRect(x, y, x + cw, y + ch, line.apply { style = Paint.Style.STROKE })
        }
        return out
    }

    /** Sépare plusieurs photos posées sur la vitre ; liste vide si moins de deux trouvées. */
    fun splitPhotos(src: Bitmap): List<Bitmap> {
        val sw = 400
        val k = src.width.toFloat() / sw
        val sh = (src.height / k).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val ink = BooleanArray(px.size) { i ->
            val c = px[i]
            val r = AColor.red(c); val g = AColor.green(c); val b = AColor.blue(c)
            (r + g + b) / 3 < 230 || maxOf(r, g, b) - minOf(r, g, b) > 30
        }
        // Dilatation : relie les zones claires d'une même photo.
        val mask = BooleanArray(px.size)
        for (y in 0 until sh) for (x in 0 until sw) if (ink[y * sw + x]) {
            for (dy in -3..3) for (dx in -3..3) {
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until sh && xx in 0 until sw) mask[yy * sw + xx] = true
            }
        }
        val seen = BooleanArray(px.size)
        val boxes = ArrayList<Rect>()
        val stack = IntArray(px.size)
        for (start in px.indices) {
            if (!mask[start] || seen[start]) continue
            var top = 0; stack[top++] = start; seen[start] = true
            var l = sw; var t = sh; var r = -1; var b = -1; var n = 0
            while (top > 0) {
                val i = stack[--top]; n++
                val x = i % sw; val y = i / sw
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[top++] = i - 1 }
                if (x < sw - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && mask[i - sw] && !seen[i - sw]) { seen[i - sw] = true; stack[top++] = i - sw }
                if (y < sh - 1 && mask[i + sw] && !seen[i + sw]) { seen[i + sw] = true; stack[top++] = i + sw }
            }
            val bw = r - l + 1; val bh = b - t + 1
            if (bw >= 20 && bh >= 20 && n >= px.size / 100) boxes += Rect(l + 3, t + 3, r - 2, b - 2)
        }
        if (boxes.size < 2) return emptyList()
        boxes.sortWith(compareBy<Rect>({ it.top / 20 }, { it.left }))
        return boxes.mapNotNull { bx ->
            val rr = Rect(
                (bx.left * k).toInt().coerceIn(0, src.width - 1), (bx.top * k).toInt().coerceIn(0, src.height - 1),
                (bx.right * k).toInt().coerceIn(1, src.width), (bx.bottom * k).toInt().coerceIn(1, src.height),
            )
            if (rr.width() <= 0 || rr.height() <= 0) null
            else Bitmap.createBitmap(src, rr.left, rr.top, rr.width(), rr.height())
        }
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

/** Copie automatique des documents enregistrés vers un dossier choisi (Réglages). */
object Export {
    fun copy(ctx: Context, src: Uri, name: String, mime: String) {
        val t = PrinterPrefs(ctx).exportTree
        if (t.isBlank()) return
        runCatching {
            val tree = Uri.parse(t)
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
            )
            val dst = android.provider.DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name) ?: return
            ctx.contentResolver.openInputStream(src)!!.use { i -> ctx.contentResolver.openOutputStream(dst)!!.use { o -> i.copyTo(o) } }
        }
    }

    /** Nom lisible du dossier : « primary:Documents/Scans » → « Documents/Scans ». */
    fun label(tree: String): String = runCatching {
        android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree)).substringAfter(':').ifBlank { "Stockage" }
    }.getOrDefault(tree)
}

/** Texte OCR des PDF cherchables, indexé par URI, pour la recherche dans Documents. */
object OcrIndex {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("ocr", Context.MODE_PRIVATE)
    fun put(ctx: Context, uri: Uri, text: String) {
        if (text.isNotBlank()) sp(ctx).edit().putString(uri.toString(), text).apply()
    }
    fun get(ctx: Context, uri: Uri): String = sp(ctx).getString(uri.toString(), "") ?: ""
    fun remove(ctx: Context, uri: Uri) = sp(ctx).edit().remove(uri.toString()).apply()
}
