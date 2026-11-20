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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/** L'imprimante présente un certificat auto-signé : on l'accepte, uniquement pour son adresse locale. */
private val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
    override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
})

internal fun open(url: String, method: String, read: Int = 60000): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    if (conn is javax.net.ssl.HttpsURLConnection) {
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS")
        ssl.init(null, trustAll, java.security.SecureRandom())
        conn.sslSocketFactory = ssl.socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }
    return conn.apply {
        requestMethod = method
        connectTimeout = 3000
        readTimeout = read
        instanceFollowRedirects = false
    }
}

internal fun portOpen(ip: String, port: Int, ms: Int = 2000): Boolean = try {
    java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), ms) }
    true
} catch (e: Exception) {
    false
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

    /** Cherche l'adresse (HTTP puis HTTPS) où le scanner répond en eSCL. */
    private fun findBase(ip: String): String? =
        listOf("http://$ip", "https://$ip").firstOrNull { b ->
            try {
                val c = open("$b/eSCL/ScannerCapabilities", "GET", 5000)
                val ok = c.responseCode == 200
                c.disconnect()
                ok
            } catch (e: Exception) {
                false
            }
        }

    /** Numérise une page A4 depuis la vitre et renvoie le JPEG. */
    suspend fun scan(ip: String, dpi: Int, color: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val root = findBase(ip)
            ?: throw IOException("ce scanner ne répond pas en eSCL (ni HTTP ni HTTPS)")
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

/** Diagnostic de connexion : sondes en parallèle, délais courts. */
object Diag {
    suspend fun run(ip: String): String = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext "Adresse vide"
        val ports = listOf(80 to "page web", 443 to "HTTPS", 631 to "IPP", 8611 to "BJNP Canon", 9100 to "RAW")
        coroutineScope {
            val portJobs = ports.map { (p, n) -> async { "Port $p ($n) : ${if (portOpen(ip, p, 1500)) "ouvert" else "fermé"}" } }
            val esclJobs = listOf("http://$ip", "https://$ip").map { b ->
                async {
                    val r = try {
                        val c = open("$b/eSCL/ScannerCapabilities", "GET", 4000)
                        val code = c.responseCode
                        c.disconnect()
                        "HTTP $code"
                    } catch (e: Exception) {
                        "aucune réponse"
                    }
                    "eSCL ${b.substringBefore(':')} : $r"
                }
            }
            val ippJob = async {
                try {
                    val i = Ipp.query(ip)
                    "IPP : ${i.model} (${Ipp.endpoint ?: "?"})"
                } catch (e: Exception) {
                    "IPP : échec (${e.message})"
                }
            }
            (portJobs.awaitAll() + esclJobs.awaitAll() + ippJob.await()).joinToString("\n")
        }
    }
}

/** Interrogation IPP pour l'état et les niveaux d'encre. */
object Ipp {
    data class Ink(val name: String, val level: Int, val color: Int?)
    data class Info(
        val model: String, val state: String, val message: String,
        val reasons: List<String>, val inks: List<Ink>,
    )

    /** Dernière adresse IPP qui a répondu (ex. http://ip:631/ipp/print). */
    @Volatile var endpoint: String? = null
        private set

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    private fun i32(b: ByteArray) = if (b.size < 4) -1 else
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private val paths = listOf("/ipp/print", "/ipp/printer", "/ipp/port1", "/ipp", "/")

    suspend fun query(ip: String): Info = withContext(Dispatchers.IO) {
        endpoint?.takeIf { it.contains("//$ip:") }?.let { e ->
            runCatching { return@withContext parse(post(e, ip, attrsRequest(e, ip))) }
        }
        var last: Exception = IOException("aucun port IPP ouvert (631, 80, 443)")
        for (port in listOf(631, 80, 443)) {
            if (!portOpen(ip, port)) continue
            for (path in paths) {
                val url = "${if (port == 443) "https" else "http"}://$ip:$port$path"
                try {
                    val info = parse(post(url, ip, attrsRequest(url, ip)))
                    endpoint = url
                    return@withContext info
                } catch (e: Exception) {
                    last = IOException("port $port $path : ${e.message}")
                }
            }
        }
        throw last
    }

    private fun printerUri(url: String) = "ipp://" + url.substringAfter("://")

    private fun attrsRequest(url: String, ip: String): ByteArray = IppWriter(0x000B).apply {
        attr(0x47, "attributes-charset", "utf-8")
        attr(0x48, "attributes-natural-language", "en")
        attr(0x45, "printer-uri", printerUri(url))
        listOf(
            "printer-make-and-model", "printer-state", "printer-state-reasons", "printer-state-message",
            "marker-names", "marker-levels", "marker-colors",
        ).forEachIndexed { i, n -> attr(0x44, if (i == 0) "requested-attributes" else "", n) }
    }.end()

    private fun post(url: String, ip: String, body: ByteArray): ByteArray {
        val conn = open(url, "POST", 8000).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/ipp")
        }
        try {
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val d = conn.inputStream.use { it.readBytes() }
            if (d.size < 8) throw IOException("réponse IPP vide")
            if (u16(d, 2) >= 0x0100) throw IOException("statut IPP 0x%04X".format(u16(d, 2)))
            return d
        } finally {
            conn.disconnect()
        }
    }

    /** Envoie des données brutes (commandes Canon) via une tâche IPP Print-Job. */
    suspend fun printRaw(ip: String, data: ByteArray) = withContext(Dispatchers.IO) {
        if (endpoint?.contains("//$ip:") != true) query(ip)
        val url = endpoint ?: throw IOException("IPP indisponible")
        val req = IppWriter(0x0002).apply {
            attr(0x47, "attributes-charset", "utf-8")
            attr(0x48, "attributes-natural-language", "en")
            attr(0x45, "printer-uri", printerUri(url))
            attr(0x42, "requesting-user-name", "canonhub")
            attr(0x42, "job-name", "Maintenance")
            attr(0x49, "document-format", "application/octet-stream")
        }.end()
        post(url, ip, req + data)
        Unit
    }

    private fun parse(d: ByteArray): Info {
        val map = LinkedHashMap<String, MutableList<ByteArray>>()
        var p = 8
        var last = ""
        while (p < d.size) {
            val tag = d[p].toInt() and 0xFF
            p++
            if (tag == 0x03) break
            if (tag <= 0x0F) continue
            if (p + 2 > d.size) break
            val nl = u16(d, p); p += 2
            if (p + nl + 2 > d.size) break
            val name = if (nl > 0) String(d, p, nl) else last
            p += nl
            val vl = u16(d, p); p += 2
            if (p + vl > d.size) break
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
                colors.getOrNull(i)?.split('#')?.lastOrNull { it.length == 6 }?.let { h ->
                    runCatching { android.graphics.Color.parseColor("#$h") }.getOrNull()
                },
            )
        }
        val state = when (map["printer-state"]?.firstOrNull()?.let { i32(it) }) {
            3 -> "Prête"
            4 -> "Impression en cours"
            5 -> "Arrêtée"
            else -> "Inconnu"
        }
        return Info(
            model = strings("printer-make-and-model").firstOrNull() ?: "Imprimante",
            state = state,
            message = strings("printer-state-message").firstOrNull().orEmpty(),
            reasons = strings("printer-state-reasons").filter { it != "none" },
            inks = inks,
        )
    }
}
