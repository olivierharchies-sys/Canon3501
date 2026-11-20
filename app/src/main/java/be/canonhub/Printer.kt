package be.canonhub

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Mémorise l'adresse IP de l'imprimante. */
class PrinterPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("printer", Context.MODE_PRIVATE)
    var ip: String
        get() = sp.getString("ip", "") ?: ""
        set(v) = sp.edit().putString("ip", v.trim()).apply()
}

/** Envoie un PDF au service d'impression Android (qui parle à l'imprimante). */
class PdfPrintAdapter(
    private val ctx: Context,
    private val uri: Uri,
    private val name: String,
) : PrintDocumentAdapter() {

    override fun onLayout(
        old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
        cb: LayoutResultCallback, extras: Bundle?,
    ) {
        if (cancel?.isCanceled == true) {
            cb.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        cb.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<PageRange>, dest: ParcelFileDescriptor,
        cancel: CancellationSignal?, cb: WriteResultCallback,
    ) {
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                FileOutputStream(dest.fileDescriptor).use { out -> input.copyTo(out) }
            }
            cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            cb.onWriteFailed(e.message)
        }
    }
}

private fun open(url: String, method: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 5000
        readTimeout = 60000
        instanceFollowRedirects = false
    }

/** Numérisation via eSCL (AirScan), supporté par la plupart des multifonctions Canon récents. */
object Escl {
    private fun settingsXml(dpi: Int, color: Boolean) = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm" xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
  <pwg:Version>2.0</pwg:Version>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
      <pwg:Width>2481</pwg:Width>
      <pwg:Height>3507</pwg:Height>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <scan:InputSource>Platen</scan:InputSource>
  <scan:ColorMode>${if (color) "RGB24" else "Grayscale8"}</scan:ColorMode>
  <scan:XResolution>$dpi</scan:XResolution>
  <scan:YResolution>$dpi</scan:YResolution>
  <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
</scan:ScanSettings>"""

    /** Numérise une page A4 depuis la vitre et renvoie le JPEG. */
    suspend fun scan(ip: String, dpi: Int, color: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val root = "http://$ip"
        val post = open("$root/eSCL/ScanJobs", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "text/xml")
        }
        post.outputStream.use { it.write(settingsXml(dpi, color).toByteArray()) }
        val code = post.responseCode
        if (code != 201) throw IOException("Le scanner a refusé la demande (HTTP $code)")
        val loc = post.getHeaderField("Location")
            ?: throw IOException("Réponse du scanner sans adresse de travail")
        post.disconnect()
        val job = (if (loc.startsWith("http")) loc else root + loc).trimEnd('/')

        var result: ByteArray? = null
        for (i in 0 until 60) {
            val get = open("$job/NextDocument", "GET")
            val c = get.responseCode
            if (c == 200) {
                result = get.inputStream.use { it.readBytes() }
                get.disconnect()
                break
            }
            get.disconnect()
            if (c == 503) delay(1000) else throw IOException("Le scanner a répondu HTTP $c")
        }
        runCatching { open(job, "DELETE").apply { responseCode }.disconnect() }
        result ?: throw IOException("Délai dépassé : le scanner n'a pas rendu l'image")
    }
}

/** Interrogation IPP pour l'état et les niveaux d'encre. */
object Ipp {
    data class Ink(val name: String, val level: Int, val color: Int?)
    data class Info(val model: String, val state: String, val reasons: List<String>, val inks: List<Ink>)

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    private fun i32(b: ByteArray) =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    suspend fun query(ip: String): Info = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        fun w16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        fun attr(tag: Int, name: String, value: String) {
            out.write(tag)
            w16(name.length); out.write(name.toByteArray())
            val b = value.toByteArray(); w16(b.size); out.write(b)
        }
        out.write(2); out.write(0)          // IPP 2.0
        w16(0x000B)                         // Get-Printer-Attributes
        out.write(byteArrayOf(0, 0, 0, 1))  // request-id
        out.write(0x01)                     // operation-attributes-tag
        attr(0x47, "attributes-charset", "utf-8")
        attr(0x48, "attributes-natural-language", "en")
        attr(0x45, "printer-uri", "ipp://$ip/ipp/print")
        listOf(
            "printer-make-and-model", "printer-state", "printer-state-reasons",
            "marker-names", "marker-levels", "marker-colors",
        ).forEachIndexed { i, n -> attr(0x44, if (i == 0) "requested-attributes" else "", n) }
        out.write(0x03)

        val conn = open("http://$ip:631/ipp/print", "POST").apply {
            doOutput = true
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/ipp")
        }
        conn.outputStream.use { it.write(out.toByteArray()) }
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        val d = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        if (d.size < 8 || u16(d, 2) >= 0x0100) throw IOException("Réponse IPP refusée")

        val map = LinkedHashMap<String, MutableList<ByteArray>>()
        var p = 8
        var last = ""
        while (p + 3 <= d.size) {
            val tag = d[p].toInt() and 0xFF
            p++
            if (tag == 0x03) break
            if (tag <= 0x0F) continue
            val nl = u16(d, p); p += 2
            val name = if (nl > 0) String(d, p, nl) else last
            p += nl
            val vl = u16(d, p); p += 2
            val v = d.copyOfRange(p, p + vl); p += vl
            if (nl > 0) last = name
            map.getOrPut(name) { mutableListOf() }.add(v)
        }

        fun strings(k: String) = map[k]?.map { String(it) } ?: emptyList()
        val names = strings("marker-names")
        val levels = map["marker-levels"]?.map { i32(it) } ?: emptyList()
        val colors = strings("marker-colors")
        val inks = names.mapIndexed { i, n ->
            Ink(
                n,
                levels.getOrElse(i) { -1 },
                colors.getOrNull(i)?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() },
            )
        }
        val state = when (map["printer-state"]?.firstOrNull()?.let { i32(it) }) {
            3 -> "Prête"
            4 -> "Impression en cours"
            5 -> "Arrêtée"
            else -> "Inconnu"
        }
        Info(
            model = strings("printer-make-and-model").firstOrNull() ?: "Imprimante",
            state = state,
            reasons = strings("printer-state-reasons").filter { it != "none" },
            inks = inks,
        )
    }
}
