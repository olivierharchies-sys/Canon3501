package be.canonhub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Commandes de maintenance Canon (langage BJL, utilisé par les pilotes Canon IJ).
 * EXPÉRIMENTAL : Canon ne documente pas ces commandes ; le résultat dépend du firmware.
 */
enum class Tool(val label: String, val bjl: String, val desc: String, val heavy: Boolean = false, val rui: String? = null) {
    NozzleCheck("Test des buses", "@TestPrint=NozzleCheck",
        "Imprime le motif officiel de vérification des buses. Chargez du papier A4.", rui = "ut_nozzle_check.cgi"),
    CleanAll("Nettoyage des têtes", "@Cleaning=1ALL",
        "Nettoyage standard de toutes les couleurs. Refaites ensuite un test des buses.", rui = "ut_normal_cln.cgi"),
    CleanBlack("Nettoyage noir", "@Cleaning=1K", "Nettoyage limité à la tête noire."),
    CleanColor("Nettoyage couleur", "@Cleaning=1CL", "Nettoyage limité à la tête couleur."),
    DeepClean("Nettoyage en profondeur", "@DeepCleaning=1ALL",
        "Uniquement après deux nettoyages normaux sans effet. Consomme beaucoup d'encre.", heavy = true, rui = "ut_deep_cln.cgi"),
    SystemClean("Nettoyage du système", "",
        "Purge des tubes d'encre : en dernier recours, après plusieurs nettoyages en profondeur. Consomme énormément d'encre.",
        heavy = true, rui = "ut_system_cln.cgi"),
    Alignment("Alignement : imprimer la feuille", "@TestPrint=Auto",
        "Imprime la feuille d'alignement des têtes. Chargez du papier A4 ordinaire.", rui = "ut_frod_print.cgi"),
    AlignScan("Alignement : lire la feuille", "",
        "Posez la feuille d'alignement face imprimée vers le bas sur la vitre, coin repéré en haut à gauche, puis lancez.",
        rui = "ut_frod_scan.cgi"),
    Roller("Nettoyage des rouleaux", "@RollerCleaning",
        "Retirez le papier du bac arrière avant de lancer.", rui = "ut_roller_mf_cln.cgi"),
    Plate("Nettoyage de la platine", "@PlateCleaning",
        "Contre les traces au verso. Pliez une feuille A4 en deux, dépliez-la, chargez-la pli vers le haut.", rui = "ut_platen_cln.cgi"),
}

enum class Transport(val label: String) { Auto("Auto"), Web("Web"), Lpd("LPD 515"), Bjnp("BJNP"), Ipp("IPP"), Raw("Port 9100") }

/** Mémorise la date du dernier lancement de chaque outil. */
class ToolLog(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tools", Context.MODE_PRIVATE)
    fun last(t: Tool): Long = sp.getLong(t.name, 0L)
    fun mark(t: Tool) = sp.edit().putLong(t.name, System.currentTimeMillis()).apply()
    var transport: Transport
        get() = runCatching { Transport.valueOf(sp.getString("transport", "Auto")!!) }.getOrDefault(Transport.Auto)
        set(v) = sp.edit().putString("transport", v.name).apply()
}

object CanonTools {
    /** Séquence de cmdtocanonij2 (cnijfilter2, profil canong3000.ppd) : StartJob, ModeShift, BJL, EndJob. */
    private const val NS = "http://www.canon.com/ns/cmd/2008/07/"
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
    private const val JOB = "00000001"
    internal val XML_START = ("$HEAD<cmd xmlns:ivec=\"${NS}common/\"><ivec:contents><ivec:operation>StartJob</ivec:operation>" +
        "<ivec:param_set servicetype=\"print\"><ivec:jobID>$JOB</ivec:jobID><ivec:bidi>0</ivec:bidi>" +
        "</ivec:param_set></ivec:contents></cmd>").toByteArray()
    internal val XML_MODE = ("$HEAD<cmd xmlns:ivec=\"${NS}common/\" xmlns:vcn=\"${NS}canon/\"><ivec:contents>" +
        "<ivec:operation>VendorCmd</ivec:operation><ivec:param_set servicetype=\"print\"><ivec:jobID>$JOB</ivec:jobID>" +
        "<vcn:ijoperation>ModeShift</vcn:ijoperation><vcn:ijmode>1</vcn:ijmode></ivec:param_set></ivec:contents></cmd>").toByteArray()
    internal val XML_END = ("$HEAD<cmd xmlns:ivec=\"${NS}common/\"><ivec:contents><ivec:operation>EndJob</ivec:operation>" +
        "<ivec:param_set servicetype=\"print\"><ivec:jobID>$JOB</ivec:jobID></ivec:param_set></ivec:contents></cmd>").toByteArray()
    private val BJL_START = byteArrayOf(0x1B, 0x5B, 0x4B, 0x02, 0x00, 0x00, 0x1F) + "BJLSTART\n".toByteArray()
    private val BJL_END = "\nBJLEND\n".toByteArray()

    /** Une trame par commande, comme les écritures séparées de cmdtocanonij2. */
    fun parts(t: Tool): List<ByteArray> = listOf(XML_START, XML_MODE, BJL_START + body(t).toByteArray() + BJL_END, XML_END)

    /** Test des buses : validé tel quel. Autres outils : essai avec ControlMode=Common (ancien format Canon). */
    private fun body(t: Tool): String = if (t == Tool.NozzleCheck) t.bjl else "ControlMode=Common\n${t.bjl}"

    fun payload(t: Tool): ByteArray = parts(t).reduce { a, b -> a + b }

    /** Envoie la commande ; renvoie le transport qui l'a acceptée. */
    suspend fun run(ip: String, t: Tool, mode: Transport, pwd: String = ""): String = withContext(Dispatchers.IO) {
        if (t.bjl.isEmpty() && mode != Transport.Auto && mode != Transport.Web) throw IOException("outil disponible en mode Web uniquement")
        val data = payload(t)
        val web = if (t.rui != null) listOf(Transport.Web) else emptyList()
        val order = if (mode == Transport.Auto) web + (if (t.bjl.isEmpty()) emptyList() else listOf(Transport.Bjnp, Transport.Lpd, Transport.Raw, Transport.Ipp)) else listOf(mode)
        val errors = mutableListOf<String>()
        for (tr in order) {
            try {
                when (tr) {
                    Transport.Lpd -> Lpd.send(ip, data, "Maintenance")
                    Transport.Bjnp -> Bjnp.send(ip, parts(t))
                    Transport.Raw -> raw(ip, data)
                    Transport.Ipp -> Ipp.printRaw(ip, data)
                    Transport.Web -> Rui.start(ip, pwd, t)
                    Transport.Auto -> Unit
                }
                return@withContext tr.label
            } catch (e: Exception) {
                errors += "${tr.label} : ${e.message}"
            }
        }
        throw IOException(errors.joinToString("\n"))
    }

    private fun raw(ip: String, data: ByteArray) {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 9100), 2500)
            s.getOutputStream().apply { write(data); flush() }
        }
    }
}

/** Protocole réseau propriétaire Canon (port 8611), comme cups-bjnp. */
object Bjnp {
    private const val PORT = 8611
    private const val JOB_DETAILS = 0x10
    private const val CLOSE = 0x11
    private const val TCP_PRINT = 0x21

    private fun header(cmd: Int, seq: Int, session: Int, len: Int): ByteArray =
        ByteBuffer.allocate(16).apply {
            put("BJNP".toByteArray()); put(1.toByte()); put(cmd.toByte())
            putShort(0.toShort()); putShort(seq.toShort()); putShort(session.toShort()); putInt(len)
        }.array()

    private fun field(s: String, size: Int): ByteArray {
        val b = ByteArray(size)
        val u = s.toByteArray(Charsets.UTF_16BE)
        System.arraycopy(u, 0, b, 0, minOf(u.size, size - 2))
        return b
    }

    private fun udp(ip: String, cmd: Int, seq: Int, session: Int, pl: ByteArray): ByteArray =
        DatagramSocket().use { s ->
            s.soTimeout = 2500
            val msg = header(cmd, seq, session, pl.size) + pl
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), PORT))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: SocketTimeoutException) { throw IOException("pas de réponse UDP 8611") }
            buf.copyOf(p.length)
        }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    /** Identifiant IEEE 1284 (modèle, langages) via UDP. */
    fun deviceId(ip: String): String {
        val r = udp(ip, 0x30, 1, 0, ByteArray(0))
        if (r.size < 18 || String(r, 0, 4) != "BJNP") throw IOException("réponse BJNP invalide")
        val len = u16(r, 16)
        val start = 18
        val end = minOf(r.size, start + maxOf(0, len - 2))
        return String(r, start, end - start).trim()
    }

    /** État de l'imprimante (UDP 0x20, comme cups-bjnp) : chaîne brute, format propre au modèle. */
    fun status(ip: String): String {
        val r = udp(ip, 0x20, 1, 0, ByteArray(0))
        if (r.size < 16 || String(r, 0, 4) != "BJNP") throw IOException("réponse BJNP invalide")
        val t = String(r, 16, r.size - 16, Charsets.ISO_8859_1)
        val i = t.indexOf('<')
        return (if (i >= 0) t.substring(i) else t).filter { it >= ' ' }.trim()
    }

    /** Réponse XML GetStatusResponse : <ivec:status> (idle, printing…) et champs Canon (BST, DJS…). */
    fun tag(xml: String, name: String): String =
        Regex("<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1).orEmpty()

    fun readable(xml: String): String {
        val st = tag(xml, "ivec:status").ifEmpty { "?" }
        val det = tag(xml, "ivec:status_detail")
        val f = tag(xml, "cijn:ReadData").split(';').associate { it.substringBefore(':') to it.substringAfter(':', "") }
        return listOfNotNull(st, det.ifEmpty { null }, f["BST"]?.let { "BST $it" }, f["DJS"]?.let { "DJS $it" }).joinToString(" · ")
    }

    /** Champs lisibles de l'identifiant (MFG, MDL, CMD…). */
    fun fields(id: String): List<Pair<String, String>> = id.split(';').mapNotNull { f ->
        val k = f.substringBefore(':', "").trim()
        val v = f.substringAfter(':', "").trim()
        if (k.isEmpty() || v.isEmpty()) null else k to v
    }

    /** Comme Pixma.Link : numéros de message dès 0, connexion TCP en 3 essais, accusé de réception vérifié. */
    fun send(ip: String, parts: Iterable<ByteArray>, title: String = "Maintenance", timeout: Int = 8000) {
        var seq = 0
        val details = ByteArray(8) + field("android", 64) + field("canonhub", 64) + field(title, 256)
        val r = udp(ip, JOB_DETAILS, seq, 0, details)
        if (r.size < 16 || String(r, 0, 4) != "BJNP") throw IOException("réponse BJNP invalide")
        val session = u16(r, 10)
        try {
            var s = Socket()
            var err: Exception? = null
            for (i in 0 until 3) {
                try { s.connect(InetSocketAddress(ip, PORT), 3000); err = null; break } catch (e: Exception) {
                    err = e; s.close(); s = Socket(); Thread.sleep(500)
                }
            }
            if (err != null) throw IOException("TCP $PORT fermé : ${err.message}")
            s.use {
                it.soTimeout = timeout
                it.tcpNoDelay = true
                val o = it.getOutputStream()
                val i = java.io.DataInputStream(it.getInputStream())
                for (data in parts) {
                    // L'imprimante confirme le nombre d'octets acceptés : partiel ou nul quand sa mémoire
                    // est pleine (impression en cours). On renvoie alors le reste, après une pause.
                    var off = 0
                    var idle = 0
                    while (off < data.size) {
                        val chunk = data.copyOfRange(off, minOf(data.size, off + 4096))
                        o.write(header(TCP_PRINT, ++seq, session, chunk.size) + chunk); o.flush()
                        val h = ByteArray(16).also { b -> i.readFully(b) }
                        if (String(h, 0, 4) != "BJNP" || (h[5].toInt() and 0xFF) != TCP_PRINT) throw IOException("accusé BJNP invalide")
                        val n = ByteBuffer.wrap(h, 12, 4).int
                        val ack = ByteArray(n).also { b -> i.readFully(b) }
                        val got = if (n >= 4) ByteBuffer.wrap(ack).int else 0
                        if (got <= 0) {
                            if (++idle > 120) throw IOException("imprimante n'accepte plus de données")
                            Thread.sleep(500)
                        } else { idle = 0; off += minOf(got, chunk.size) }
                    }
                }
            }
        } finally {
            runCatching { udp(ip, CLOSE, ++seq, session, ByteArray(0)) }
        }
    }
}

/** Impression LPD (RFC 1179), file « auto » annoncée par l'imprimante. */
object Lpd {
    private fun ack(i: java.io.InputStream, step: String) {
        val b = i.read()
        if (b != 0) throw IOException("refus LPD ($step, code $b)")
    }

    fun send(ip: String, data: ByteArray, job: String) {
        var last: Exception = IOException("LPD indisponible")
        for (queue in listOf("auto", "raw", "lp")) {
            try {
                sendQueue(ip, queue, data, job)
                return
            } catch (e: Exception) {
                last = IOException("file $queue : ${e.message}")
            }
        }
        throw last
    }

    private fun command(ip: String, line: String): String =
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 515), 2500)
            s.soTimeout = 5000
            s.getOutputStream().apply { write(line.toByteArray()); flush() }
            val r = runCatching { s.getInputStream().readBytes() }.getOrDefault(ByteArray(0))
            String(r, Charsets.ISO_8859_1).trim()
        }

    /** État de la file LPD (format long, RFC 1179 commande 4). */
    fun status(ip: String, queue: String = "auto"): String = command(ip, "\u0004$queue \n")

    /** Retire les tâches de la file (commande 5, agent « root » = toutes). */
    fun removeAll(ip: String, queue: String = "auto"): String = command(ip, "\u0005$queue root\n")

    private fun sendQueue(ip: String, queue: String, data: ByteArray, job: String) {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 515), 2500)
            s.soTimeout = 8000
            val o = s.getOutputStream()
            val i = s.getInputStream()
            val n = (System.currentTimeMillis() % 1000).toString().padStart(3, '0')
            val host = "android"
            val df = "dfA$n$host"
            val cf = "cfA$n$host"
            o.write("\u0002$queue\n".toByteArray()); o.flush(); ack(i, "file")
            val ctl = "H$host\nPcanonhub\nJ$job\nl$df\nU$df\nN$job\n".toByteArray()
            o.write("\u0002${ctl.size} $cf\n".toByteArray()); o.flush(); ack(i, "contrôle")
            o.write(ctl); o.write(0); o.flush(); ack(i, "contrôle reçu")
            o.write("\u0003${data.size} $df\n".toByteArray()); o.flush(); ack(i, "données")
            o.write(data); o.write(0); o.flush(); ack(i, "données reçues")
        }
    }
}

/** Suivi d'un outil lancé : compare l'état BJNP à celui d'avant l'envoi. */
object ToolWatch {
    suspend fun snapshot(ip: String): String? = withContext(Dispatchers.IO) { runCatching { Bjnp.status(ip) }.getOrNull() }

    /** Interroge toutes les 2 s ; onUpdate(état lisible, réponse brute). */
    suspend fun follow(ip: String, baseXml: String?, onUpdate: (String, String) -> Unit) {
        val base = baseXml?.let { Bjnp.readable(it) }
        val start = System.currentTimeMillis()
        var seenBusy = false
        var fails = 0
        while (System.currentTimeMillis() - start < 15 * 60_000L) {
            delay(2000)
            val sec = (System.currentTimeMillis() - start) / 1000
            val full = snapshot(ip)
            val st = full?.let { Bjnp.readable(it) }
            if (st == null) {
                if (++fails >= 5) { onUpdate("état indisponible (pas de réponse BJNP)", ""); return }
                continue
            }
            fails = 0
            val changed = base != null && st != base
            if (changed) seenBusy = true
            when {
                base == null -> { onUpdate("état reçu ($sec s), pas de référence avant envoi", st); if (sec >= 60) return }
                changed -> onUpdate("en cours… ($sec s)", st)
                seenBusy -> { onUpdate("terminé ($sec s)", st); return }
                sec >= 60 -> { onUpdate("aucun changement d'état détecté", st); return }
                else -> onUpdate("en attente du démarrage… ($sec s)", st)
            }
        }
        onUpdate("suivi arrêté après 15 min", "")
    }
}

/**
 * Interface web de l'imprimante (Remote UI, https://ip/rui/), relevée sur la G3501 :
 * - connexion : POST sendpw.cgi, NAMAE = SHA-256 hexa du mot de passe, IDTYPE = 2 ;
 * - lancement : POST ut_xxx.cgi → <RESULT>RW_OK|RW_POLLING|RW_NG|LOCKED</RESULT>, <JOB_NUM> ;
 * - suivi : POST get_job_status.cgi JOB_NUM=n → <getJobState> 1 en cours, 2 fini, 3 erreur, 4 annulé, 5 erreur en cours ;
 * - SBID ajouté aux requêtes s'il figure dans la page (jeton de session).
 */
object Rui {
    private val cookies = mutableMapOf<String, String>()
    private var sbid = ""
    private var lastCode = 0

    private fun tag(x: String, n: String): String? = Regex("<$n>([^<]*)</$n>").find(x)?.groupValues?.get(1)

    private fun req(ip: String, path: String, body: String?): String {
        val c = open("https://$ip/rui/$path", if (body == null) "GET" else "POST", 15000)
        try {
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            // Comme Chrome Android : certaines pages varient selon le navigateur et la page d'origine.
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            c.setRequestProperty("Referer", "https://$ip/rui/logon_manager.html")
            c.setRequestProperty("Origin", "https://$ip")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            lastCode = code
            c.headerFields.filterKeys { it != null && it.equals("Set-Cookie", true) }.values.flatten().forEach {
                val kv = it.substringBefore(';')
                if ('=' in kv) cookies[kv.substringBefore('=').trim()] = kv.substringAfter('=').trim()
            }
            return ((if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText()).orEmpty()
        } finally { c.disconnect() }
    }

    private fun data(d: String) = if (sbid.isEmpty()) d else (if (d.isEmpty()) "" else "$d&") + "SBID=$sbid"

    /** Champ caché SBID, guillemets facultatifs (le HTML brut peut différer de ce qu'affiche le navigateur). */
    private fun findSbid(page: String): String {
        val i = page.indexOf("SBID")
        if (i < 0) return ""
        val tagStart = page.lastIndexOf('<', i).coerceAtLeast(0)
        val tagEnd = page.indexOf('>', i).let { if (it < 0) page.length else it }
        val tagTxt = page.substring(tagStart, tagEnd)
        return Regex("""value\s*=\s*["']?([0-9A-Za-z]+)""").find(tagTxt)?.groupValues?.get(1)
            ?: Regex("""SBID["']?\s*[:=,]\s*["']([0-9A-Za-z]{16,})""").find(page)?.groupValues?.get(1) ?: ""
    }

    private fun login(ip: String, pwd: String) {
        if (pwd.isBlank()) throw IOException("mot de passe administrateur requis")
        if (!pwd.matches(Regex("[0-9A-Za-z]{4,32}"))) throw IOException("mot de passe : 4 à 32 lettres ou chiffres")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pwd.toByteArray())
            .joinToString("") { "%02x".format(it) }
        cookies.clear(); sbid = ""
        runCatching { req(ip, "logon_manager.html", null) }   // cookie de session initial, comme le navigateur
        val sent = req(ip, "sendpw.cgi", "NAMAE=$hash&IDTYPE=2")
        val sendCode = lastCode
        sbid = findSbid(sent)
        val page = if (sbid.isEmpty()) runCatching { req(ip, "index.html", null) }.getOrDefault("") else ""
        if (sbid.isEmpty()) sbid = findSbid(page)
        if (sbid.isEmpty()) {
            val why = if (lastCode == 404) "interface web indisponible : fermez la session ouverte dans un navigateur, ou redémarrez l'imprimante"
                else "connexion refusée : vérifiez le mot de passe"
            val diag = "sendpw $sendCode [${sent.replace(Regex("\\s+"), " ").take(160)}] · index $lastCode, ${page.length} car. " +
                (if ("logon" in page) "(renvoie vers la connexion) " else "") + "· cookies ${cookies.keys}"
            throw IOException("$why\n$diag")
        }
    }

    /** Session absente ou expirée : RW_SES_NG, SES_ERR_URL, LOCKED ou même 404. */
    private fun stale(r: String) = "SES_ERR_URL" in r || lastCode == 404 || tag(r, "RESULT") in setOf("LOCKED", "RW_SES_NG")

    /** POST avec connexion automatique si la session manque. */
    @Synchronized
    fun call(ip: String, pwd: String, path: String, body: String): String {
        var r = req(ip, path, data(body))
        if (stale(r)) { login(ip, pwd); r = req(ip, path, data(body)) }
        if (stale(r)) throw IOException("session refusée par l'imprimante")
        return r
    }

    /** Toutes les balises simples de la réponse XML (la dernière l'emporte). */
    fun fields(x: String): Map<String, String> =
        Regex("<([A-Za-z0-9_]+)>([^<]*)</\\1>").findAll(x).associate { it.groupValues[1] to it.groupValues[2] }

    /** Lecture d'une page de réglages (GETINFO=0). */
    suspend fun read(ip: String, pwd: String, path: String, body: String = "GETINFO=0"): Map<String, String> =
        withContext(Dispatchers.IO) { fields(call(ip, pwd, path, body)) }

    /** Écriture d'un réglage ; attend la fin si l'imprimante répond RW_POLLING. */
    suspend fun write(ip: String, pwd: String, path: String, body: String): String = withContext(Dispatchers.IO) {
        val r = call(ip, pwd, path, body)
        val res = tag(r, "RESULT") ?: throw IOException("réponse inattendue (HTTP $lastCode)")
        if ("NG" in res) throw IOException("refusé par l'imprimante ($res)")
        if (res == "RW_POLLING") {
            repeat(15) {
                delay(1500)
                when (tag(req(ip, "get_job_status.cgi", data("")), "getJobState")) {
                    "2" -> return@withContext "OK"
                    "3" -> throw IOException("l'imprimante signale une erreur")
                }
            }
        }
        "OK"
    }

    /** Lance un travail (outil, vérification firmware…) ; renvoie le numéro de travail. */
    fun start(ip: String, pwd: String, url: String): String {
        val r = call(ip, pwd, url, "")
        return when (tag(r, "RESULT")) {
            "RW_OK", "RW_POLLING" -> tag(r, "JOB_NUM") ?: "0"
            "RW_NG" -> throw IOException("refusé par l'imprimante (${tag(r, "DETAIL") ?: "occupée ou en erreur"})")
            else -> throw IOException("réponse inattendue : ${tag(r, "RESULT") ?: "HTTP $lastCode"}")
        }
    }

    fun start(ip: String, pwd: String, t: Tool): String =
        start(ip, pwd, t.rui ?: throw IOException("pas d'équivalent dans l'interface web"))

    /** Lance puis suit un travail ; renvoie (message final, dernière réponse XML). */
    suspend fun job(ip: String, pwd: String, url: String, onUpdate: (String) -> Unit): Pair<String, String> = withContext(Dispatchers.IO) {
        val job = start(ip, pwd, url)
        val t0 = System.currentTimeMillis()
        delay(800)
        while (System.currentTimeMillis() - t0 < 15 * 60_000L) {
            val sec = (System.currentTimeMillis() - t0) / 1000
            val s = req(ip, "get_job_status.cgi", data("JOB_NUM=$job"))
            if (stale(s)) throw IOException("session perdue pendant le suivi")
            val msg = when (tag(s, "getJobState")) {
                "1" -> "en cours… ($sec s)"
                "5" -> "erreur imprimante : vérifiez l'écran et le papier ($sec s)"
                "2" -> return@withContext "terminé ($sec s)" to s
                "3" -> throw IOException("l'imprimante signale une erreur")
                "4" -> return@withContext "annulé" to s
                else -> "état inconnu : ${s.take(80)}"
            }
            withContext(Dispatchers.Main) { onUpdate(msg) }
            delay(2500)
        }
        "suivi arrêté après 15 min" to ""
    }

    /** Lance puis suit l'outil ; renvoie le message final. */
    suspend fun run(ip: String, pwd: String, t: Tool, onUpdate: (String) -> Unit): String =
        job(ip, pwd, t.rui ?: throw IOException("pas d'équivalent dans l'interface web"), onUpdate).first

    /** Annule le travail en cours (bouton Stop de la page web). */
    suspend fun cancel(ip: String, pwd: String): String = withContext(Dispatchers.IO) {
        val r = call(ip, pwd, "job_cancel.cgi", "")
        if (tag(r, "RESULT") == "RW_OK") "annulation demandée" else "annulation refusée (${tag(r, "RESULT") ?: "HTTP $lastCode"})"
    }
}
