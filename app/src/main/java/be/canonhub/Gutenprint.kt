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
import kotlin.math.ln
import kotlin.math.roundToInt

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

    /** Réglages fins du pilote (écran « Réglage du pilote »). Valeurs neutres = comportement d'origine. */
    data class Tune(
        val brightness: Float = 1f, val contrast: Float = 1f, val gamma: Float = 1f,
        val density: Float = 1f, val saturation: Float = 1f,
        val cyan: Float = 1f, val magenta: Float = 1f, val yellow: Float = 1f,
        val imageType: String = "", val dither: String = "",
        val dpi: Int = 0, val shiftX: Float = 0f, val shiftY: Float = 0f, val scale: Float = 1f,
        val diag: Boolean = false,
    ) {
        /** Réglages d'origine (diagnostic exclu). */
        fun isDefault() = copy(diag = false) == Tune()
        fun resetColors() = Tune().let { d -> copy(brightness = d.brightness, contrast = d.contrast, gamma = d.gamma,
            density = d.density, saturation = d.saturation, cyan = d.cyan, magenta = d.magenta, yellow = d.yellow) }
        fun resetSharp() = copy(dpi = 0, imageType = "", dither = "")
        fun resetPos() = copy(shiftX = 0f, shiftY = 0f, scale = 1f)
    }

    /** Paramètres réels Gutenprint, ajoutés seulement s'ils diffèrent du neutre. */
    private fun Tune.kv(): List<String> = buildList {
        fun f(n: String, v: Float) { if (v != 1f) add("f:$n=$v") }
        f("Brightness", brightness); f("Contrast", contrast); f("Gamma", gamma)
        f("Density", density); f("Saturation", saturation)
        f("Cyan", cyan); f("Magenta", magenta); f("Yellow", yellow)
        if (imageType.isNotEmpty()) add("ImageType=$imageType")
        if (dither.isNotEmpty()) add("DitherAlgorithm=$dither")
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("gp_tune", Context.MODE_PRIVATE)

    fun tune(ctx: Context): Tune = sp(ctx).let { p ->
        val d = Tune()
        Tune(
            p.getFloat("bri", d.brightness), p.getFloat("con", d.contrast), p.getFloat("gam", d.gamma),
            p.getFloat("den", d.density), p.getFloat("sat", d.saturation),
            p.getFloat("cya", d.cyan), p.getFloat("mag", d.magenta), p.getFloat("yel", d.yellow),
            p.getString("img", "") ?: "", p.getString("dit", "") ?: "",
            p.getInt("dpi", 0), p.getFloat("sx", 0f), p.getFloat("sy", 0f), p.getFloat("sc", 1f),
            p.getBoolean("diag", false),
        )
    }

    fun saveTune(ctx: Context, t: Tune) = sp(ctx).edit()
        .putFloat("bri", t.brightness).putFloat("con", t.contrast).putFloat("gam", t.gamma)
        .putFloat("den", t.density).putFloat("sat", t.saturation)
        .putFloat("cya", t.cyan).putFloat("mag", t.magenta).putFloat("yel", t.yellow)
        .putString("img", t.imageType).putString("dit", t.dither)
        .putInt("dpi", t.dpi).putFloat("sx", t.shiftX).putFloat("sy", t.shiftY).putFloat("sc", t.scale)
        .putBoolean("diag", t.diag).apply()

    private fun kv(sel: Map<String, String>, borderless: Boolean, t: Tune = Tune()): Array<String> =
        (sel.filterValues { it.isNotEmpty() }.map { (k, v) -> "$k=$v" } + t.kv() + "b:FullBleed=${if (borderless) 1 else 0}").toTypedArray()

    /** Page d'essai : la mire d'étalonnage, imprimée par le pilote avec les réglages en cours. */
    suspend fun testPage(ctx: Context, ip: String, sel: Map<String, String>, dpi: Int, onStep: (String) -> Unit): String {
        val f = File(ctx.cacheDir, "gp-test.png")
        withContext(Dispatchers.IO) { f.outputStream().use { Calib.chart().compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return try { print(ctx, ip, Uri.fromFile(f), "image/png", sel, false, false, dpi, onStep, truePage = true) } finally { f.delete() }
    }

    /**
     * Paramétrage automatique : à partir de la mire imprimée par le pilote puis relue au scanner,
     * calcule de nouveaux réglages (échelle, position, gamma, densité, équilibre C/M/J).
     * Retourne les réglages proposés et un résumé lisible.
     */
    fun autoTune(t: Tune, r: Calib.Report): Pair<Tune, List<String>> {
        val notes = ArrayList<String>()
        fun c(v: Float, lo: Float, hi: Float) = ((v.coerceIn(lo, hi) * 100).roundToInt() / 100f)
        // Échelle : la mire est imprimée en taille réelle, avec l'échelle en cours.
        val s = (r.scaleX + r.scaleY) / 2
        val scale = c(t.scale / s, 0.9f, 1.1f)
        notes += "Échelle mesurée ${"%.1f".format(s * 100)} % → réglage ${"%.1f".format(scale * 100)} %"
        // Position : centre des repères attendu à (105 ; 148,5) mm.
        var sx = t.shiftX; var sy = t.shiftY
        r.centre?.let { (x, y) ->
            val dx = x - 105f; val dy = y - 148.5f
            sx = (((t.shiftX - dx).coerceIn(-10f, 10f)) * 2).roundToInt() / 2f
            sy = (((t.shiftY - dy).coerceIn(-10f, 10f)) * 2).roundToInt() / 2f
            notes += "Décalage mesuré ${"%+.1f".format(dx)} × ${"%+.1f".format(dy)} mm"
        } ?: run { notes += "Mire à l'envers : position non mesurée" }
        // Gamma : gris mesurés ≈ entrée^g. g > 1 = tons moyens trop sombres → gamma plus élevé (plus clair).
        val lo = r.curve.first().toFloat(); val hi = r.curve.last().toFloat()
        var gamma = t.gamma
        if (hi - lo > 60) {
            val gs = (2..8).mapNotNull { k ->
                val m = (r.curve[k] - lo) / (hi - lo)
                if (m in 0.02f..0.98f) (ln(m.toDouble()) / ln(k / 10.0)).toFloat() else null
            }
            if (gs.isNotEmpty()) {
                val g = gs.average().toFloat()
                gamma = c(t.gamma * g, 0.5f, 2f)
                notes += "Tons moyens : exposant ${"%.2f".format(g)} (1 = juste)"
            }
        }
        // Densité : noir trop clair → plus d'encre ; noir bouché et tons sombres écrasés → moins.
        var density = t.density
        if (lo > 55) density = c(t.density * (1 + (lo - 40) / 300), 0.5f, 1.5f)
        else if (lo < 15 && r.curve[1] < 25) density = c(t.density * 0.95f, 0.5f, 1.5f)
        notes += "Noir mesuré ${lo.roundToInt()} (idéal 20 à 45)"
        // Équilibre : gris moyens (30 à 70 %). Canal trop clair → encre complémentaire insuffisante.
        var cy = t.cyan; var ma = t.magenta; var ye = t.yellow
        val mid = r.greys.slice(3..7.coerceAtMost(r.greys.lastIndex))
        if (mid.isNotEmpty()) {
            fun f(ch: Int): Float {
                val ratios = mid.mapNotNull { g ->
                    val l = (g[0] + g[1] + g[2]) / 3f
                    if (l in 20f..235f && g[ch] in 5..250) (ln(l / 255.0) / ln(g[ch] / 255.0)).toFloat() else null
                }
                return if (ratios.isEmpty()) 1f else ratios.average().toFloat()
            }
            cy = c(t.cyan * f(0), 0.5f, 1.5f); ma = c(t.magenta * f(1), 0.5f, 1.5f); ye = c(t.yellow * f(2), 0.5f, 1.5f)
            notes += "Gris moyen R${mid[mid.size / 2][0]} V${mid[mid.size / 2][1]} B${mid[mid.size / 2][2]}"
        }
        notes += r.warnings
        return t.copy(scale = scale, shiftX = sx, shiftY = sy, gamma = gamma, density = density,
            cyan = cy, magenta = ma, yellow = ye) to notes
    }

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
        truePage: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        init(ctx)
        val t = tune(ctx)
        val dpi = if (t.dpi > 0) t.dpi else dpi
        val k = kv(sel, borderless, t)
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
                    // Décalage (mm) et échelle réglés dans « Réglage du pilote ».
                    val px = dpi / 25.4f
                    // truePage : la feuille entière (taille réelle), marges comprises ; sinon la zone imprimable.
                    val base = if (truePage) RectF(
                        (-a[2] * dpi / 72).toFloat(), (-a[5] * dpi / 72).toFloat(),
                        ((a[0] - a[2]) * dpi / 72).toFloat(), ((a[1] - a[5]) * dpi / 72).toFloat(),
                    ) else RectF(0f, 0f, w.toFloat(), h.toFloat())
                    val cx = base.centerX() + t.shiftX * px; val cy = base.centerY() + t.shiftY * px
                    val bw = base.width() * t.scale / 2; val bh = base.height() * t.scale / 2
                    val box = RectF(cx - bw, cy - bh, cx + bw, cy + bh)
                    val row = IntArray(w)
                    for (i in 0 until pages.count) {
                        withContext(Dispatchers.Main) { onStep("préparation page ${i + 1}/${pages.count}") }
                        page.eraseColor(Color.WHITE)
                        pages.drawInto(i, Canvas(page), box, fill)
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
            // Gutenprint n'entoure pas le flux G3000 du XML Canon (début/fin de travail, passage en mode
            // impression) : sans lui, la G3501 accepte les données mais n'imprime rien. On l'ajoute,
            // comme pour les commandes d'entretien.
            val job = listOf(CanonTools.XML_START, CanonTools.XML_MODE) + chunks(out) + listOf(CanonTools.XML_END)
            Bjnp.send(ip, job, title = "CanonHub", timeout = 120_000)
            if (!t.diag) return@withContext "Envoyé à l'imprimante par le pilote intégré (${out.length() / 1024} Ko)."
            // Diagnostic : début du flux (XML Canon + commandes) et état de l'imprimante 3 s après.
            val head = out.inputStream().use { val b = ByteArray(600); val n = it.read(b); b.copyOf(maxOf(n, 0)) }
            val txt = head.map { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar() else if (c == 0x1B) '␛' else '·' }.joinToString("")
            Thread.sleep(3000)
            val st = runCatching { Bjnp.readable(Bjnp.status(ip)) }.getOrElse { "illisible" }
            "Envoyé à l'imprimante par le pilote intégré (${out.length() / 1024} Ko).\nÉtat : $st\nDébut du flux : $txt"
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
