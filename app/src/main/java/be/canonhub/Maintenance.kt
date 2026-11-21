package be.canonhub

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
enum class Tool(val label: String, val bjl: String, val desc: String, val heavy: Boolean = false) {
    NozzleCheck("Test des buses", "@TestPrint=NozzleCheck",
        "Imprime le motif officiel de vérification des buses. Chargez du papier A4."),
    CleanAll("Nettoyage des têtes", "@Cleaning=1ALL",
        "Nettoyage standard de toutes les couleurs. Refaites ensuite un test des buses."),
    CleanBlack("Nettoyage noir", "@Cleaning=1K", "Nettoyage limité à la tête noire."),
    CleanColor("Nettoyage couleur", "@Cleaning=1CL", "Nettoyage limité à la tête couleur."),
    DeepClean("Nettoyage en profondeur", "@DeepCleaning=1ALL",
        "Uniquement après deux nettoyages normaux sans effet. Consomme beaucoup d'encre.", heavy = true),
    Alignment("Alignement des têtes", "@TestPrint=Auto",
        "Imprime la feuille d'alignement. Chargez du papier A4."),
    Roller("Nettoyage des rouleaux", "@RollerCleaning",
        "Retirez le papier du bac arrière avant de lancer."),
    Plate("Nettoyage de la platine", "@PlateCleaning",
        "Contre les traces au verso. Pliez une feuille A4 en deux, dépliez-la, chargez-la pli vers le haut."),
}

enum class Transport(val label: String) { Auto("Auto"), Lpd("LPD 515"), Bjnp("BJNP"), Ipp("IPP"), Raw("Port 9100") }

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
    private val XML_START = ("$HEAD<cmd xmlns:ivec=\"${NS}common/\"><ivec:contents><ivec:operation>StartJob</ivec:operation>" +
        "<ivec:param_set servicetype=\"print\"><ivec:jobID>$JOB</ivec:jobID><ivec:bidi>0</ivec:bidi>" +
        "</ivec:param_set></ivec:contents></cmd>").toByteArray()
    private val XML_MODE = ("$HEAD<cmd xmlns:ivec=\"${NS}common/\" xmlns:vcn=\"${NS}canon/\"><ivec:contents>" +
        "<ivec:operation>VendorCmd</ivec:operation><ivec:param_set servicetype=\"print\"><ivec:jobID>$JOB</ivec:jobID>" +
        "<vcn:ijoperation>ModeShift</vcn:ijoperation><vcn:ijmode>1</vcn:ijmode></ivec:param_set></ivec:contents></cmd>").toByteArray()
    private val XML_END = ("$HEAD<cmd xmlns:ivec=\"${NS}common/\"><ivec:contents><ivec:operation>EndJob</ivec:operation>" +
        "<ivec:param_set servicetype=\"print\"><ivec:jobID>$JOB</ivec:jobID></ivec:param_set></ivec:contents></cmd>").toByteArray()
    private val BJL_START = byteArrayOf(0x1B, 0x5B, 0x4B, 0x02, 0x00, 0x00, 0x1F) + "BJLSTART\n".toByteArray()
    private val BJL_END = "\nBJLEND\n".toByteArray()

    fun payload(t: Tool): ByteArray = XML_START + XML_MODE + BJL_START + t.bjl.toByteArray() + BJL_END + XML_END

    /** Envoie la commande ; renvoie le transport qui l'a acceptée. */
    suspend fun run(ip: String, t: Tool, mode: Transport): String = withContext(Dispatchers.IO) {
        val data = payload(t)
        val order = if (mode == Transport.Auto) listOf(Transport.Bjnp, Transport.Lpd, Transport.Raw, Transport.Ipp) else listOf(mode)
        val errors = mutableListOf<String>()
        for (tr in order) {
            try {
                when (tr) {
                    Transport.Lpd -> Lpd.send(ip, data, "Maintenance")
                    Transport.Bjnp -> Bjnp.send(ip, data)
                    Transport.Raw -> raw(ip, data)
                    Transport.Ipp -> Ipp.printRaw(ip, data)
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

    /** Champs lisibles de l'identifiant (MFG, MDL, CMD…). */
    fun fields(id: String): List<Pair<String, String>> = id.split(';').mapNotNull { f ->
        val k = f.substringBefore(':', "").trim()
        val v = f.substringAfter(':', "").trim()
        if (k.isEmpty() || v.isEmpty()) null else k to v
    }

    /** Comme Pixma.Link : numéros de message dès 0, connexion TCP en 3 essais, accusé de réception vérifié. */
    fun send(ip: String, data: ByteArray) {
        var seq = 0
        val details = ByteArray(8) + field("android", 64) + field("canonhub", 64) + field("Maintenance", 256)
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
                it.soTimeout = 8000
                it.tcpNoDelay = true
                val o = it.getOutputStream()
                val i = java.io.DataInputStream(it.getInputStream())
                o.write(header(TCP_PRINT, ++seq, session, data.size) + data); o.flush()
                val h = ByteArray(16).also { b -> i.readFully(b) }
                if (String(h, 0, 4) != "BJNP" || (h[5].toInt() and 0xFF) != TCP_PRINT) throw IOException("accusé BJNP invalide")
                val n = ByteBuffer.wrap(h, 12, 4).int
                val ack = ByteArray(n).also { b -> i.readFully(b) }
                if (n != 4 || ByteBuffer.wrap(ack).int != data.size) throw IOException("envoi BJNP non confirmé")
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
