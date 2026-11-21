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

data class Profile(val name: String, val ip: String)

/** Réglages persistants : imprimante courante, profils, alertes. */
class PrinterPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("printer", Context.MODE_PRIVATE)
    var ip: String
        get() = sp.getString("ip", "") ?: ""
        set(v) = sp.edit().putString("ip", v.trim()).apply()

    var profiles: List<Profile>
        get() = (sp.getString("profiles", "") ?: "").lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size == 2 && p[1].isNotBlank()) Profile(p[0], p[1]) else null
        }
        set(v) = sp.edit().putString("profiles", v.joinToString("\n") { "${it.name.replace('\t', ' ')}\t${it.ip}" }).apply()

    fun addProfile(name: String, ip: String) {
        profiles = profiles.filter { it.ip != ip.trim() } + Profile(name.ifBlank { ip.trim() }, ip.trim())
    }

    fun removeProfile(ip: String) { profiles = profiles.filter { it.ip != ip } }

    var directPrint: Boolean
        get() = sp.getBoolean("direct", false)
        set(v) = sp.edit().putBoolean("direct", v).apply()
    var inkAlert: Boolean
        get() = sp.getBoolean("inkAlert", false)
        set(v) = sp.edit().putBoolean("inkAlert", v).apply()
    var inkThreshold: Int
        get() = sp.getInt("inkThreshold", 15)
        set(v) = sp.edit().putInt("inkThreshold", v).apply()
    var clogReminder: Boolean
        get() = sp.getBoolean("clog", true)
        set(v) = sp.edit().putBoolean("clog", v).apply()
    var clogDays: Int
        get() = sp.getInt("clogDays", 14)
        set(v) = sp.edit().putInt("clogDays", v).apply()
    var lastPrint: Long
        get() = sp.getLong("lastPrint", 0L)
        set(v) = sp.edit().putLong("lastPrint", v).apply()
    var lastInk: String
        get() = sp.getString("lastInk", "") ?: ""
        set(v) = sp.edit().putString("lastInk", v).apply()
    var lastInkNotify: Long
        get() = sp.getLong("lastInkNotify", 0L)
        set(v) = sp.edit().putLong("lastInkNotify", v).apply()
    var ippHint: String
        get() = sp.getString("ippHint", "") ?: ""
        set(v) = sp.edit().putString("ippHint", v).apply()
    var esclHint: String
        get() = sp.getString("esclHint", "") ?: ""
        set(v) = sp.edit().putString("esclHint", v).apply()

    /** Mémorise les adresses annoncées par mDNS et les transmet aux modules. */
    fun applyHints(f: Discovery.Found) {
        f.ippUrl()?.let { ippHint = it }
        f.esclRoot()?.let { esclHint = it }
        loadHints()
    }

    fun loadHints() {
        Ipp.hint = ippHint.ifBlank { null }
        Escl.hint = esclHint.ifBlank { null }
    }

    var askedNotif: Boolean
        get() = sp.getBoolean("askedNotif", false)
        set(v) = sp.edit().putBoolean("askedNotif", v).apply()
    var lastClogNotify: Long
        get() = sp.getLong("lastClogNotify", 0L)
        set(v) = sp.edit().putLong("lastClogNotify", v).apply()
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

/** Zones de numérisation, en 1/300 de pouce, depuis le coin de la vitre. */
enum class Area(val label: String, val w: Int, val h: Int) {
    A4("A4", 2481, 3507),
    A5("A5", 1748, 2480),
    Photo("10×15", 1181, 1772),
    Card("Carte", 1012, 638),
}

/** Numérisation via eSCL (AirScan), supporté par la plupart des multifonctions Canon récents. */
object Escl {
    private fun settingsXml(dpi: Int, color: Boolean, a: Area) = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm" xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
  <pwg:Version>2.0</pwg:Version>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
      <pwg:Width>${a.w}</pwg:Width>
      <pwg:Height>${a.h}</pwg:Height>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <scan:InputSource>Platen</scan:InputSource>
  <scan:ColorMode>${if (color) "RGB24" else "Grayscale8"}</scan:ColorMode>
  <scan:XResolution>$dpi</scan:XResolution>
  <scan:YResolution>$dpi</scan:YResolution>
  <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
</scan:ScanSettings>"""

    /** Racine eSCL annoncée par mDNS (ex. http://ip:80/eSCL). */
    @Volatile var hint: String? = null

    fun roots(ip: String): List<String> =
        (listOfNotNull(hint?.takeIf { it.contains("//$ip:") }) +
            listOf("http://$ip/eSCL", "https://$ip/eSCL", "http://$ip:8080/eSCL", "http://$ip/escl")).distinct()

    /** Cherche la racine où le scanner répond en eSCL. */
    private fun findBase(ip: String): String? =
        roots(ip).firstOrNull { b ->
            try {
                val c = open("$b/ScannerCapabilities", "GET", 5000)
                val ok = c.responseCode == 200
                c.disconnect()
                ok
            } catch (e: Exception) {
                false
            }
        }

    /** Numérise une zone de la vitre et renvoie le JPEG. */
    suspend fun scan(ip: String, dpi: Int, color: Boolean, area: Area = Area.A4, progress: (Float) -> Unit = {}): ByteArray = withContext(Dispatchers.IO) {
        val root = findBase(ip) ?: return@withContext try {
            PixmaScan.scan(ip, dpi, color, area, progress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("eSCL absent ; BJNP 8612 : ${e.message}")
        }
        val post = open("$root/ScanJobs", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "text/xml")
        }
        post.outputStream.use { it.write(settingsXml(dpi, color, area).toByteArray()) }
        val code = post.responseCode
        if (code != 201) throw IOException("Le scanner a refusé la demande (HTTP $code)")
        val loc = post.getHeaderField("Location")
            ?: throw IOException("Réponse du scanner sans adresse de travail")
        post.disconnect()
        val job = URL(URL("$root/"), loc).toString().trimEnd('/')

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
    /** Sonde UDP BJNP (8611 imprimante, 8612 scanner). */
    private fun bjnpUdp(ip: String, port: Int, dev: Int): String = try {
        java.net.DatagramSocket().use { s ->
            s.soTimeout = 1500
            val h = byteArrayOf('B'.code.toByte(), 'J'.code.toByte(), 'N'.code.toByte(), 'P'.code.toByte(), dev.toByte(), 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)
            s.send(java.net.DatagramPacket(h, h.size, java.net.InetAddress.getByName(ip), port))
            val buf = ByteArray(512)
            s.receive(java.net.DatagramPacket(buf, buf.size))
            "répond"
        }
    } catch (e: Exception) {
        "muet"
    }

    suspend fun run(ctx: Context, ip: String): String = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext "Adresse vide"
        val ports = listOf(80 to "page web", 443 to "HTTPS", 631 to "IPP", 8611 to "BJNP TCP", 8612 to "BJNP scan TCP", 9100 to "RAW", 515 to "LPD")
        coroutineScope {
            val mdns = async {
                runCatching { Discovery.search(ctx, 5) }.getOrDefault(emptyList()).firstOrNull { it.ip == ip }
            }
            val portJobs = ports.map { (p, n) -> async { "Port $p ($n) : ${if (portOpen(ip, p, 1500)) "ouvert" else "fermé"}" } }
            val udpJobs = listOf(
                async { "BJNP UDP 8611 (impression) : ${bjnpUdp(ip, 8611, 1)}" },
                async { "BJNP UDP 8612 (scanner) : ${bjnpUdp(ip, 8612, 2)}" },
            )
            val found = mdns.await()
            if (found != null) PrinterPrefs(ctx).applyHints(found)
            val esclJobs = Escl.roots(ip).map { b ->
                async {
                    val r = try {
                        val c = open("$b/ScannerCapabilities", "GET", 4000)
                        val code = c.responseCode
                        c.disconnect()
                        "HTTP $code"
                    } catch (e: Exception) {
                        "aucune réponse"
                    }
                    "eSCL ${b.substringAfter("//").substringAfter('/')} (${b.substringBefore(':')}) : $r"
                }
            }
            val idLine = try {
                "Identifiant BJNP :\n" + Bjnp.fields(Bjnp.deviceId(ip)).joinToString("\n") { "  ${it.first} : ${it.second}" }
            } catch (e: Exception) {
                "Identifiant BJNP : ${e.message}"
            }
            val ippLine = try {
                val i = Ipp.query(ip)
                "IPP OK : ${i.model}\n  ${Ipp.endpoint ?: "?"}"
            } catch (e: Exception) {
                "IPP : échec\n${e.message}"
            }
            val mdnsLine = if (found == null) "mDNS : rien annoncé pour $ip" else "mDNS « ${found.name} » :\n" + found.summary().prependIndent("  ")
            (portJobs.awaitAll() + udpJobs.awaitAll() + esclJobs.awaitAll() + idLine + ippLine + mdnsLine).joinToString("\n")
        }
    }
}

/** Petit utilitaire pour assembler les requêtes IPP. */
class IppWriter(op: Int) {
    private val out = ByteArrayOutputStream()
    private fun w16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
    init {
        out.write(2); out.write(0); w16(op)
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(0x01)
    }
    fun group(tag: Int) = out.write(tag)
    fun attr(tag: Int, name: String, value: String) {
        out.write(tag)
        val n = name.toByteArray(); w16(n.size); out.write(n)
        val b = value.toByteArray(); w16(b.size); out.write(b)
    }
    fun int(name: String, v: Int, tag: Int = 0x21) {
        out.write(tag)
        val n = name.toByteArray(); w16(n.size); out.write(n)
        w16(4); out.write(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
    }
    fun end(): ByteArray { out.write(0x03); return out.toByteArray() }
}

/** Interrogation et commandes IPP. */
object Ipp {
    data class Ink(val name: String, val level: Int, val color: Int?)
    data class Info(
        val model: String, val state: String, val message: String,
        val reasons: List<String>, val inks: List<Ink>,
    )
    data class Job(val id: Int, val name: String, val state: String, val user: String)
    class Attr(val tag: Int, val values: MutableList<ByteArray> = mutableListOf())

    /** Dernière adresse IPP qui a répondu (ex. http://ip:631/ipp/print). */
    @Volatile var endpoint: String? = null
        private set

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    private fun i32(b: ByteArray) = if (b.size < 4) -1 else
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private val paths = listOf("/ipp/print", "/ipp/printer", "/ipp", "/ipp/port1", "/printer", "/")
    private fun printerUri(url: String) = "ipp://" + url.substringAfter("://")

    private fun base(op: Int, url: String) = IppWriter(op).apply {
        attr(0x47, "attributes-charset", "utf-8")
        attr(0x48, "attributes-natural-language", "en")
        attr(0x45, "printer-uri", printerUri(url))
    }

    private fun requested(w: IppWriter, names: List<String>) =
        names.forEachIndexed { i, n -> w.attr(0x44, if (i == 0) "requested-attributes" else "", n) }

    private val infoAttrs = listOf(
        "printer-make-and-model", "printer-state", "printer-state-reasons", "printer-state-message",
        "marker-names", "marker-levels", "marker-colors",
    )

    private fun infoRequest(url: String) = base(0x000B, url).apply { requested(this, infoAttrs) }.end()

    /** Adresse IPP annoncée par mDNS. */
    @Volatile var hint: String? = null

    private fun candidates(ip: String): List<String> {
        val list = mutableListOf<String>()
        hint?.takeIf { it.contains("//$ip:") }?.let { list += it }
        endpoint?.takeIf { it.contains("//$ip:") }?.let { list += it }
        if (!portOpen(ip, 631, 1500)) return list.distinct()
        for (port in listOf(631, 80, 443)) {
            if (!portOpen(ip, port, 1500)) continue
            val scheme = if (port == 443) "https" else "http"
            paths.forEach { list += "$scheme://$ip:$port$it" }
        }
        return list.distinct()
    }

    suspend fun query(ip: String): Info = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val urls = candidates(ip)
        if (urls.isEmpty()) throw IOException("IPP non géré par cette imprimante (port 631 fermé)")
        for (url in urls) {
            try {
                val info = toInfo(printerGroup(post(url, infoRequest(url), 5000)))
                endpoint = url
                return@withContext info
            } catch (e: Exception) {
                errors += "  ${url.substringAfter("//$ip")} : ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("\n"))
    }

    private suspend fun ensure(ip: String): String {
        if (endpoint?.contains("//$ip:") != true) query(ip)
        return endpoint ?: throw IOException("IPP indisponible")
    }

    private fun post(url: String, body: ByteArray, read: Int = 30000): ByteArray {
        val conn = open(url, "POST", read).apply {
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

    /** Découpe la réponse en groupes (tag de groupe, attributs). */
    private fun groups(d: ByteArray): List<Pair<Int, LinkedHashMap<String, Attr>>> {
        val res = mutableListOf<Pair<Int, LinkedHashMap<String, Attr>>>()
        var cur = LinkedHashMap<String, Attr>()
        var p = 8
        var last = ""
        while (p < d.size) {
            val tag = d[p].toInt() and 0xFF
            p++
            if (tag == 0x03) break
            if (tag <= 0x0F) {
                cur = LinkedHashMap(); res += tag to cur
                continue
            }
            if (p + 2 > d.size) break
            val nl = u16(d, p); p += 2
            if (p + nl + 2 > d.size) break
            val name = if (nl > 0) String(d, p, nl) else last
            p += nl
            val vl = u16(d, p); p += 2
            if (p + vl > d.size) break
            val v = d.copyOfRange(p, p + vl); p += vl
            if (nl > 0) last = name
            cur.getOrPut(name) { Attr(tag) }.values.add(v)
        }
        return res
    }

    private fun printerGroup(d: ByteArray) = LinkedHashMap<String, Attr>().apply {
        groups(d).filter { it.first == 0x04 }.forEach { putAll(it.second) }
    }

    private fun text(a: Attr?, sep: String = ", "): String = if (a == null) "" else a.values.joinToString(sep) { v ->
        when (a.tag) {
            0x21, 0x23 -> i32(v).toString()
            0x22 -> if (v.firstOrNull()?.toInt() == 1) "oui" else "non"
            0x32 -> if (v.size >= 9) "${i32(v.copyOfRange(0, 4))}×${i32(v.copyOfRange(4, 8))}" else "?"
            0x33 -> if (v.size >= 8) "${i32(v.copyOfRange(0, 4))}–${i32(v.copyOfRange(4, 8))}" else "?"
            0x31 -> "(date)"
            0x30 -> if (v.all { it in 32..126 }) String(v) else "(${v.size} octets)"
            else -> String(v)
        }
    }

    private fun strings(m: Map<String, Attr>, k: String) = m[k]?.values?.map { String(it) } ?: emptyList()

    private fun toInfo(map: Map<String, Attr>): Info {
        val names = strings(map, "marker-names")
        val levels = map["marker-levels"]?.values?.map { i32(it) } ?: emptyList()
        val colors = strings(map, "marker-colors")
        val inks = names.mapIndexed { i, n ->
            Ink(
                n,
                levels.getOrElse(i) { -1 },
                colors.getOrNull(i)?.split('#')?.lastOrNull { it.length == 6 }?.let { h ->
                    runCatching { android.graphics.Color.parseColor("#$h") }.getOrNull()
                },
            )
        }
        val state = when (map["printer-state"]?.values?.firstOrNull()?.let { i32(it) }) {
            3 -> "Prête"
            4 -> "Impression en cours"
            5 -> "Arrêtée"
            else -> "Inconnu"
        }
        return Info(
            model = strings(map, "printer-make-and-model").firstOrNull() ?: "Imprimante",
            state = state,
            message = strings(map, "printer-state-message").firstOrNull().orEmpty(),
            reasons = strings(map, "printer-state-reasons").filter { it != "none" },
            inks = inks,
        )
    }

    /** Fiche détaillée de l'imprimante (firmware, compteurs, formats…). */
    suspend fun details(ip: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val url = ensure(ip)
        val req = base(0x000B, url).apply { requested(this, listOf("all")) }.end()
        val m = printerGroup(post(url, req))
        val labels = listOf(
            "printer-make-and-model" to "Modèle",
            "printer-info" to "Description",
            "printer-name" to "Nom",
            "printer-location" to "Emplacement",
            "printer-firmware-name" to "Firmware",
            "printer-firmware-string-version" to "Version firmware",
            "printer-serial-number" to "Numéro de série",
            "printer-uuid" to "UUID",
            "printer-device-id" to "Identifiant",
            "printer-impressions-completed" to "Pages imprimées",
            "printer-media-sheets-completed" to "Feuilles imprimées",
            "printer-pages-completed" to "Pages (total)",
            "printer-up-time" to "Allumée depuis (s)",
            "color-supported" to "Couleur",
            "sides-supported" to "Recto-verso",
            "media-ready" to "Papier chargé",
            "media-supported" to "Formats papier",
            "document-format-supported" to "Formats acceptés",
            "printer-resolution-supported" to "Résolutions (dpi)",
            "ipp-versions-supported" to "Versions IPP",
            "printer-more-info" to "Page web",
            "printer-uri-supported" to "Adresses IPP",
        )
        val res = labels.mapNotNull { (k, l) -> m[k]?.let { l to text(it) } }.toMutableList()
        res += "Point d'accès" to url
        res
    }

    /** Formats acceptés pour l'impression directe. */
    suspend fun formats(ip: String): List<String> = withContext(Dispatchers.IO) {
        val url = ensure(ip)
        val req = base(0x000B, url).apply { requested(this, listOf("document-format-supported")) }.end()
        strings(printerGroup(post(url, req)), "document-format-supported")
    }

    /** Envoie des données brutes (commandes Canon) via une tâche IPP Print-Job. */
    suspend fun printRaw(ip: String, data: ByteArray) {
        printDoc(ip, data, "application/octet-stream", "Maintenance")
    }

    /** Impression directe d'un document (JPEG, PDF…) ; renvoie l'identifiant de tâche. */
    suspend fun printDoc(
        ip: String, data: ByteArray, mime: String, name: String,
        copies: Int = 1, color: Boolean? = null,
    ): Int = withContext(Dispatchers.IO) {
        val url = ensure(ip)
        val req = base(0x0002, url).apply {
            attr(0x42, "requesting-user-name", "canonhub")
            attr(0x42, "job-name", name)
            attr(0x49, "document-format", mime)
            if (copies > 1 || color != null) {
                group(0x02)
                if (copies > 1) int("copies", copies)
                if (color != null) attr(0x44, "print-color-mode", if (color) "color" else "monochrome")
            }
        }.end()
        val r = groups(post(url, req + data))
        r.firstOrNull { it.first == 0x05 }?.second?.get("job-id")?.values?.firstOrNull()?.let { i32(it) } ?: 0
    }

    private fun jobState(s: Int) = when (s) {
        3 -> "En attente"
        4 -> "Suspendue"
        5 -> "En cours"
        6 -> "Arrêtée"
        7 -> "Annulée"
        8 -> "Abandonnée"
        9 -> "Terminée"
        else -> "État $s"
    }

    suspend fun jobs(ip: String, done: Boolean = false): List<Job> = withContext(Dispatchers.IO) {
        val url = ensure(ip)
        val req = base(0x000A, url).apply {
            attr(0x44, "which-jobs", if (done) "completed" else "not-completed")
            requested(this, listOf("job-id", "job-name", "job-state", "job-originating-user-name"))
        }.end()
        groups(post(url, req)).filter { it.first == 0x02 }.map { (_, m) ->
            Job(
                id = m["job-id"]?.values?.firstOrNull()?.let { i32(it) } ?: 0,
                name = strings(m, "job-name").firstOrNull() ?: "Sans nom",
                state = jobState(m["job-state"]?.values?.firstOrNull()?.let { i32(it) } ?: 0),
                user = strings(m, "job-originating-user-name").firstOrNull().orEmpty(),
            )
        }
    }

    suspend fun cancel(ip: String, id: Int) = withContext(Dispatchers.IO) {
        val url = ensure(ip)
        val req = base(0x0008, url).apply {
            int("job-id", id)
            attr(0x42, "requesting-user-name", "canonhub")
        }.end()
        post(url, req)
        Unit
    }

    /** Texte court des niveaux, pour widget et notifications. */
    fun inkText(i: Info): String = if (i.inks.isEmpty()) "Niveaux non communiqués" else
        i.inks.joinToString("  ") { "${short(it.name)} ${if (it.level in 0..100) "${it.level}%" else "?"}" }

    fun short(n: String): String {
        val l = n.lowercase()
        return when {
            "black" in l || "noir" in l || l.startsWith("bk") || l.startsWith("pgbk") -> "N"
            "cyan" in l -> "C"
            "magenta" in l -> "M"
            "yellow" in l || "jaune" in l -> "J"
            else -> n.take(3)
        }
    }
}
