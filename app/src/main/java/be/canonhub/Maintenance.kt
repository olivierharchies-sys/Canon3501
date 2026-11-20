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
    CleanAll("Nettoyage des têtes", "@Cleaning=All",
        "Nettoyage standard de toutes les couleurs. Refaites ensuite un test des buses."),
    CleanBlack("Nettoyage noir", "@Cleaning=Black", "Nettoyage limité à la tête noire."),
    CleanColor("Nettoyage couleur", "@Cleaning=Color", "Nettoyage limité à la tête couleur."),
    DeepClean("Nettoyage en profondeur", "@DeepCleaning=All",
        "Uniquement après deux nettoyages normaux sans effet. Consomme beaucoup d'encre.", heavy = true),
    Alignment("Alignement des têtes", "@TestPrint=HeadAlignment",
        "Imprime la feuille d'alignement. Chargez du papier A4."),
    Roller("Nettoyage des rouleaux", "@RollerCleaning",
        "Retirez le papier du bac arrière avant de lancer."),
    Plate("Nettoyage de la platine", "@PlateCleaning",
        "Contre les traces au verso. Pliez une feuille A4 en deux, dépliez-la, chargez-la pli vers le haut."),
}

enum class Transport(val label: String) { Auto("Auto"), Bjnp("BJNP"), Ipp("IPP"), Raw("Port 9100") }

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
    private val START = byteArrayOf(0x1B, 0x5B, 0x4B, 0x02, 0x00, 0x00, 0x1F) +
        "BJLSTART\nControlMode=Common\n".toByteArray()
    private val END = "BJLEND\n".toByteArray()

    fun payload(t: Tool): ByteArray = START + "${t.bjl}\n".toByteArray() + END

    /** Envoie la commande ; renvoie le transport qui l'a acceptée. */
    suspend fun run(ip: String, t: Tool, mode: Transport): String = withContext(Dispatchers.IO) {
        val data = payload(t)
        val order = if (mode == Transport.Auto) listOf(Transport.Bjnp, Transport.Raw, Transport.Ipp) else listOf(mode)
        val errors = mutableListOf<String>()
        for (tr in order) {
            try {
                when (tr) {
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

    fun send(ip: String, data: ByteArray) {
        val details = ByteArray(8) + field("android", 64) + field("canonhub", 64) + field("Maintenance", 256)
        val r = udp(ip, JOB_DETAILS, 1, 0, details)
        if (r.size < 16 || String(r, 0, 4) != "BJNP") throw IOException("réponse BJNP invalide")
        if (u16(r, 6) != 0) throw IOException("imprimante occupée (code ${u16(r, 6)})")
        val session = u16(r, 10)
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, PORT), 2500)
                s.soTimeout = 4000
                s.getOutputStream().apply { write(header(TCP_PRINT, 2, session, data.size) + data); flush() }
                runCatching { s.getInputStream().read(ByteArray(64)) }
            }
        } finally {
            runCatching { udp(ip, CLOSE, 3, session, ByteArray(0)) }
        }
    }
}
