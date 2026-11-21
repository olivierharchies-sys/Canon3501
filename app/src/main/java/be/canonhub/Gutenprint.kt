package be.canonhub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Pilote d'impression intégré : Gutenprint (logiciel libre, GPL v2+), modèle « Canon G3000 series ».
 * Produit le flux Canon avec type de papier, qualité et sans bordure, puis l'envoie en BJNP (port 8611).
 * EXPÉRIMENTAL : la G3000 est marquée expérimentale dans Gutenprint.
 */
object Gutenprint {
    private val DRIVERS = listOf("bjc-PIXMA-G3000", "bjc-G3000-series")

    /** Paramètres proposés à l'écran, dans l'ordre. */
    val PARAMS = listOf(
        "PageSize" to "Format", "MediaType" to "Type de papier", "Resolution" to "Qualité",
        "InputSlot" to "Alimentation", "PrintingMode" to "Couleur",
    )

    val available: Boolean by lazy { runCatching { System.loadLibrary("canonjni"); true }.getOrDefault(false) }
    private var ready = false

    @JvmStatic private external fun nInit(dataPath: String, driver: String): String
    @JvmStatic private external fun nChoices(param: String, kv: Array<String>): Array<String>
    @JvmStatic private external fun nArea(kv: Array<String>): DoubleArray
    @JvmStatic private external fun nBegin(kv: Array<String>, w: Int, h: Int): String
    @JvmStatic private external fun nPage(rgb: ByteArray, w: Int, h: Int): ByteArray?
    @JvmStatic private external fun nEnd(): ByteArray
    @JvmStatic private external fun nLastError(): String

    /** Une option du pilote : nom technique, libellé, valeur par défaut. */
    data class Choice(val name: String, val text: String, val default: Boolean)

    /** Décompresse les données XML (une fois par version de l'appli) puis charge le modèle G3000. */
    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        if (!available) throw IOException("pilote intégré absent de cette version (compilation CI)")
        val dir = File(ctx.filesDir, "gutenprint")
        val stamp = File(dir, ".version")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toString()
        if (!stamp.exists() || stamp.readText() != ver) {
            dir.deleteRecursively(); dir.mkdirs()
            ZipInputStream(ctx.assets.open("gutenprint-data.zip")).use { zin ->
                generateSequence { zin.nextEntry }.forEach { e ->
                    val f = File(dir, e.name)
                    if (!f.canonicalPath.startsWith(dir.canonicalPath)) return@forEach
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                }
            }
            stamp.writeText(ver)
        }
        // Dossier qui contient les fichiers XML (papers.xml, printers/…).
        val xml = dir.walkTopDown().firstOrNull { it.name == "papers.xml" }?.parentFile
            ?: dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "xml" } ?: dir
        var err = ""
        for (d in DRIVERS) { err = nInit(xml.absolutePath, d); if (err.isEmpty()) break }
        if (err.isNotEmpty()) throw IOException("Gutenprint : $err")
        ready = true
    }

    private fun kv(sel: Map<String, String>, borderless: Boolean): Array<String> =
        (sel.filterValues { it.isNotEmpty() }.map { (k, v) -> "$k=$v" } + "b:FullBleed=${if (borderless) 1 else 0}").toTypedArray()

    /** Choix proposés par le pilote pour un paramètre (dépendent des autres réglages). */
    suspend fun choices(ctx: Context, param: String, sel: Map<String, String>): List<Choice> = withContext(Dispatchers.IO) {
        init(ctx)
        nChoices(param, kv(sel, false)).map { line ->
            val def = line.startsWith("*")
            val parts = line.removePrefix("*").split('\t', limit = 2)
            val n = parts[0]
            val t = parts.getOrElse(1) { n }
            Choice(n, t, def)
        }
    }

    /**
     * Imprime un PDF ou une image : pages rendues à la taille de la zone imprimable, converties par Gutenprint,
     * envoyées en BJNP. onStep reçoit la progression.
     */
    suspend fun print(
        ctx: Context, ip: String, uri: Uri, mime: String,
        sel: Map<String, String>, borderless: Boolean, fill: Boolean, dpi: Int,
        onStep: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        init(ctx)
        val k = kv(sel, borderless)
        val a = nArea(k)    // largeur, hauteur page, gauche, droite, bas, haut (points)
        val wPt = a[3] - a[2]; val hPt = a[4] - a[5]
        if (wPt <= 0 || hPt <= 0) throw IOException("format refusé par le pilote")
        val w = (wPt * dpi / 72).toInt(); val h = (hPt * dpi / 72).toInt()
        val out = File(ctx.cacheDir, "gp-${System.currentTimeMillis()}.bin")
        try {
            Pages(ctx, uri, mime).use { pages ->
                val err = nBegin(k, w, h)
                if (err.isNotEmpty()) throw IOException("pilote : $err")
                out.outputStream().buffered().use { o ->
                    val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val rgb = ByteArray(w * h * 3)
                    val row = IntArray(w)
                    for (i in 0 until pages.count) {
                        withContext(Dispatchers.Main) { onStep("préparation page ${i + 1}/${pages.count}") }
                        page.eraseColor(Color.WHITE)
                        pages.drawInto(i, Canvas(page), RectF(0f, 0f, w.toFloat(), h.toFloat()), fill)
                        var p = 0
                        for (y in 0 until h) {
                            page.getPixels(row, 0, w, 0, y, w, 1)
                            for (c in row) { rgb[p++] = (c shr 16).toByte(); rgb[p++] = (c shr 8).toByte(); rgb[p++] = c.toByte() }
                        }
                        val data = nPage(rgb, w, h) ?: throw IOException("pilote : ${nLastError().ifEmpty { "page refusée" }}")
                        o.write(data)
                    }
                    o.write(nEnd())
                    page.recycle()
                }
            }
            withContext(Dispatchers.Main) { onStep("envoi à l'imprimante (${out.length() / 1024} Ko)…") }
            Bjnp.send(ip, chunks(out), title = "CanonHub", timeout = 120_000)
            "Envoyé à l'imprimante par le pilote intégré (${out.length() / 1024} Ko)."
        } finally {
            out.delete()
        }
    }

    /** Lecture du fichier par blocs de 64 Ko, une trame BJNP par bloc. */
    private fun chunks(f: File, size: Int = 65536) = Iterable {
        object : Iterator<ByteArray> {
            val ins = f.inputStream().buffered()
            var next = read()
            fun read(): ByteArray? {
                val b = ByteArray(size); val n = ins.read(b)
                return if (n <= 0) { ins.close(); null } else b.copyOf(n)
            }
            override fun hasNext() = next != null
            override fun next(): ByteArray { val r = next ?: throw NoSuchElementException(); next = read(); return r }
        }
    }

    /** Pages d'un PDF ou image unique, dessinées dans une case (ajustée ou remplie). */
    private class Pages(ctx: Context, uri: Uri, mime: String) : AutoCloseable {
        private val pfd: ParcelFileDescriptor? = if (mime == "application/pdf") ctx.contentResolver.openFileDescriptor(uri, "r") else null
        private val pdf = pfd?.let { PdfRenderer(it) }
        private val image: Bitmap? = if (pdf == null) {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("fichier illisible")
            Images.decode(raw, 4000) ?: throw IOException("image illisible")
        } else null
        val count = pdf?.pageCount ?: 1

        fun drawInto(i: Int, c: Canvas, r: RectF, fill: Boolean) {
            val b = image ?: pdf!!.openPage(i).use { p ->
                val s = minOf(r.width() / p.width, r.height() / p.height) * (if (fill) 1.5f else 1f)
                val bmp = Bitmap.createBitmap((p.width * s).toInt().coerceAtLeast(1), (p.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                bmp
            }
            val sx = r.width() / b.width; val sy = r.height() / b.height
            val s = if (fill) maxOf(sx, sy) else minOf(sx, sy)
            val dw = b.width * s; val dh = b.height * s
            val dst = RectF(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2, r.centerY() + dh / 2)
            c.save(); c.clipRect(r); c.drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
            if (b !== image) b.recycle()
        }

        override fun close() { pdf?.close(); pfd?.close() }
    }
}
