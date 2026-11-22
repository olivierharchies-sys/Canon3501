package be.canonhub

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Types de fichier de sortie d'un scan. */
enum class OutType(val label: String, val ext: String, val mime: String, val lossy: Boolean, val multi: Boolean, val sub: String) {
    PDF("PDF", "pdf", "application/pdf", true, true, "Multipage, universel"),
    TIFF("TIFF", "tif", "image/tiff", false, true, "Multipage, sans perte (archives)"),
    JPEG("JPEG", "jpg", "image/jpeg", true, false, "Photos, léger"),
    PNG("PNG", "png", "image/png", false, false, "Sans perte, lourd"),
    WEBP("WEBP", "webp", "image/webp", true, false, "Très léger, moins compatible"),
}

/** Réglages rapides de numérisation. */
enum class Preset(val label: String) { Photos("Photos"), Document("Document"), Archive("Archive"), Fast("Rapide") }

object Output {
    /** Nom de fichier selon le modèle : {date}, {heure}, {type}. */
    fun name(tpl: String, type: String = "Scan"): String {
        val now = java.util.Date()
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE).format(now)
        val h = java.text.SimpleDateFormat("HH-mm-ss", java.util.Locale.FRANCE).format(now)
        val n = tpl.ifBlank { DEFAULT_NAME }.replace("{date}", d).replace("{heure}", h).replace("{type}", type)
        return Classify.clean(n).ifBlank { Images.stamp() }
    }

    const val DEFAULT_NAME = "Scan_{date}_{heure}"

    /** Image encodée (JPEG, PNG, WEBP). */
    fun encode(b: Bitmap, t: OutType, q: Int): ByteArray {
        val f = when (t) {
            OutType.PNG -> Bitmap.CompressFormat.PNG
            OutType.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        return ByteArrayOutputStream().also { b.compress(f, q, it) }.toByteArray()
    }

    fun tiffPage(b: Bitmap, dpi: Int) = Tiff.Page(b.width, b.height, dpi) { y, d -> b.getPixels(d, 0, b.width, 0, y, b.width, 1) }

    /** Enregistre dans Téléchargements/Canon (PDF, TIFF) ou Images/Canon (images). */
    suspend fun save(ctx: Context, t: OutType, folder: String?, base: String, write: suspend (OutputStream) -> Unit): Uri {
        val name = "$base.${t.ext}"
        val img = t != OutType.PDF && t != OutType.TIFF
        val coll = if (img) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (img) "Pictures/Canon" else "Download/Canon") + (folder?.let { "/$it" } ?: ""))
        }
        val uri = ctx.contentResolver.insert(coll, cv) ?: throw java.io.IOException("enregistrement impossible")
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use { write(it) }
        } catch (e: Throwable) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw e
        }
        Export.copy(ctx, uri, name, t.mime)
        return uri
    }

    /** Poids estimé d'une page à partir de l'aperçu [pv] ; [fullPx] = pixels réels. */
    fun estimatePage(pv: Bitmap, fullPx: Long, t: OutType, q: Int, ocr: Boolean): Long {
        val sample = when (t) {
            OutType.TIFF -> Tiff.encode(tiffPage(pv, 300)).size.toLong()
            OutType.PDF -> Images.jpeg(pv, q).size.toLong()
            else -> encode(pv, t, q).size.toLong()
        }
        var e = SizeEst.extrapolate(sample, pv.width.toLong() * pv.height, fullPx, t.lossy)
        if (t == OutType.PDF) e += 1500 + if (ocr) 4000 else 0
        if (t == OutType.TIFF) e += 300
        return e
    }

    fun shareAll(ctx: Context, uris: List<Uri>, mime: String) {
        if (uris.size == 1) return share(ctx, uris[0], mime)
        runCatching {
            val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
