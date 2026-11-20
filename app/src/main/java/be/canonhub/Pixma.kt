package be.canonhub

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Numérisation Canon PIXMA via BJNP (port 8612), d'après le pilote SANE pixma (mp150, génération 5, G3000 0x1794).
 * EXPÉRIMENTAL : protocole non documenté, testé par rétro-ingénierie.
 */
object PixmaScan {
    private const val PORT = 8612
    private const val UDP_JOB = 0x10
    private const val UDP_CLOSE = 0x11
    private const val TCP_REQ = 0x20
    private const val TCP_SEND = 0x21

    private const val START_SESSION = 0xdb20
    private const val GAMMA = 0xee20
    private const val ABORT = 0xef20
    private const val READ_IMAGE = 0xd420
    private const val SCAN_PARAM_3 = 0xd820
    private const val SCAN_START_3 = 0xd920
    private const val STATUS_3 = 0xda20

    private const val OK = 0x0606
    private const val BUSY = 0x1414
    private const val BLOCK = 512 * 1024

    private const val NS = "http://www.canon.com/ns/cmd/2008/07/"
    private const val XML_START_1 = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><cmd xmlns:ivec=\"${NS}common/\">" +
        "<ivec:contents><ivec:operation>StartJob</ivec:operation><ivec:param_set servicetype=\"scan\">" +
        "<ivec:jobID>00000001</ivec:jobID><ivec:bidi>1</ivec:bidi></ivec:param_set></ivec:contents></cmd>"
    private const val XML_START_2 = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><cmd xmlns:ivec=\"${NS}common/\" xmlns:vcn=\"${NS}canon/\">" +
        "<ivec:contents><ivec:operation>VendorCmd</ivec:operation><ivec:param_set servicetype=\"scan\">" +
        "<ivec:jobID>00000001</ivec:jobID><vcn:ijoperation>ModeShift</vcn:ijoperation><vcn:ijmode>1</vcn:ijmode>" +
        "</ivec:param_set></ivec:contents></cmd>"
    private const val XML_END = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><cmd xmlns:ivec=\"${NS}common/\">" +
        "<ivec:contents><ivec:operation>EndJob</ivec:operation><ivec:param_set servicetype=\"scan\">" +
        "<ivec:jobID>00000001</ivec:jobID></ivec:param_set></ivec:contents></cmd>"

    private class Busy : IOException("scanner occupé")

    /** Session BJNP scanner : UDP pour l'ouverture/fermeture, TCP pour les données. */
    private class Link(private val ip: String) : Closeable {
        private var seq = -1
        private var session = 0
        private var sock: Socket? = null
        private lateinit var ins: DataInputStream
        private lateinit var out: OutputStream

        private fun header(cmd: Int, len: Int, sess: Int = session): ByteArray = ByteBuffer.allocate(16).apply {
            put("BJNP".toByteArray()); put(2.toByte()); put(cmd.toByte())
            putShort(0.toShort()); putShort((++seq).toShort()); putShort(sess.toShort()); putInt(len)
        }.array()

        private fun field(s: String, size: Int): ByteArray {
            val b = ByteArray(size)
            val u = s.toByteArray(Charsets.UTF_16BE)
            System.arraycopy(u, 0, b, 0, minOf(u.size, size - 2))
            return b
        }

        private fun udp(cmd: Int, pl: ByteArray): ByteArray = DatagramSocket().use { s ->
            s.soTimeout = 3000
            val msg = header(cmd, pl.size) + pl
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), PORT))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: SocketTimeoutException) { throw IOException("pas de réponse UDP $PORT") }
            buf.copyOf(p.length)
        }

        fun open() {
            val r = udp(UDP_JOB, ByteArray(8) + field("android", 64) + field("canonhub", 64) + field("Scan", 256))
            if (r.size < 16 || String(r, 0, 4) != "BJNP") throw IOException("réponse BJNP invalide")
            session = u16(r, 10)
            var s = Socket()
            var err: Exception? = null
            for (i in 0 until 3) {
                try { s.connect(InetSocketAddress(ip, PORT), 3000); err = null; break } catch (e: Exception) {
                    err = e; s.close(); s = Socket(); Thread.sleep(500)
                }
            }
            if (err != null) throw IOException("TCP $PORT fermé : ${err.message}")
            s.soTimeout = 30000
            s.tcpNoDelay = true
            sock = s
            ins = DataInputStream(s.getInputStream().buffered())
            out = s.getOutputStream()
        }

        private fun readHeader(cmd: Int): Int {
            val h = ByteArray(16)
            ins.readFully(h)
            if (String(h, 0, 4) != "BJNP") throw IOException("trame BJNP invalide")
            if ((h[5].toInt() and 0xFF) != cmd) throw IOException("réponse BJNP inattendue (0x%02x)".format(h[5]))
            if (u16(h, 8) != (seq and 0xFFFF)) throw IOException("réponse BJNP désynchronisée")
            return ByteBuffer.wrap(h, 12, 4).int
        }

        fun write(data: ByteArray) {
            out.write(header(TCP_SEND, data.size) + data); out.flush()
            val n = readHeader(TCP_SEND)
            val ack = ByteArray(n).also { ins.readFully(it) }
            if (n != 4 || ByteBuffer.wrap(ack).int != data.size) throw IOException("écriture BJNP non confirmée")
        }

        /** Une réponse du scanner (vide si rien de prêt). */
        fun readOnce(): ByteArray {
            out.write(header(TCP_REQ, 0)); out.flush()
            val n = readHeader(TCP_REQ)
            return ByteArray(n).also { ins.readFully(it) }
        }

        fun read(): ByteArray {
            repeat(600) {
                val b = readOnce()
                if (b.isNotEmpty()) return b
                Thread.sleep(50)
            }
            throw IOException("le scanner ne répond pas")
        }

        override fun close() {
            if (sock != null) runCatching { udp(UDP_CLOSE, ByteArray(0)) }
            runCatching { sock?.close() }
        }
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    private fun be16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
    private fun be32(b: ByteArray, i: Int, v: Int) { be16(b, i, v ushr 16); be16(b, i + 2, v and 0xFFFF) }
    private fun be32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)

    /** Commande PIXMA : en-tête 16 octets + données (dernier octet = somme de contrôle). */
    private fun exec(l: Link, cmd: Int, dataOut: ByteArray = ByteArray(0), dataIn: Int = 0): ByteArray {
        if (dataOut.isNotEmpty()) {
            var sum = 0
            for (i in 0 until dataOut.size - 1) sum += dataOut[i]
            dataOut[dataOut.size - 1] = (-sum).toByte()
        }
        val h = ByteArray(16)
        be16(h, 0, cmd)
        be16(h, 14, dataOut.size + dataIn)
        l.write(h + dataOut)
        val r = l.read()
        if (r.size < 2) throw IOException("réponse PIXMA trop courte")
        when (val st = u16(r, 0)) {
            OK -> return r
            BUSY -> throw Busy()
            else -> throw IOException("commande %04x refusée (état %04x)".format(cmd, st))
        }
    }

    private fun xml(l: Link, msg: String): Boolean {
        l.write(msg.toByteArray())
        return String(l.read()).contains("<ivec:response>OK</ivec:response>", ignoreCase = true)
    }

    private fun gamma(): ByteArray = ByteArray(2048 + 8).apply {
        this[0] = 0x10
        be16(this, 2, 0x0804)
        for (i in 0 until 1024) {
            val v = (65535.0 * (i / 1023.0).pow(1 / 2.2) + 0.5).toInt()
            this[4 + 2 * i] = v.toByte(); this[5 + 2 * i] = (v shr 8).toByte()
        }
    }

    private fun scanParam(dpi: Int, w: Int, h: Int): ByteArray = ByteArray(0x38).apply {
        this[0x00] = 1; this[0x01] = 1; this[0x02] = 1; this[0x05] = 1
        be16(this, 0x08, dpi or 0x8000)
        be16(this, 0x0a, dpi or 0x8000)
        be32(this, 0x0c, 0); be32(this, 0x10, 0)
        be32(this, 0x14, w); be32(this, 0x18, h)
        this[0x1c] = 8; this[0x1d] = 24
        this[0x1f] = 1; this[0x20] = 0xff.toByte(); this[0x21] = 0x81.toByte()
        this[0x23] = 2; this[0x24] = 1; this[0x30] = 1
    }

    /** Numérise une zone de la vitre (toujours en couleur, gris converti ensuite) et renvoie un JPEG. */
    suspend fun scan(ip: String, dpi: Int, color: Boolean, area: Area): ByteArray = withContext(Dispatchers.IO) {
        val outW = area.w * dpi / 300
        val rawW = (outW + 31) / 32 * 32
        val lines = area.h * dpi / 300
        val lineBytes = rawW * 3
        val bmp = Bitmap.createBitmap(outW, lines, if (dpi > 300) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        val row = IntArray(outW)
        var y = 0
        val pending = ByteArrayOutputStream()

        fun consume(data: ByteArray, off: Int, len: Int) {
            pending.write(data, off, len)
            val buf = pending.toByteArray()
            var p = 0
            while (buf.size - p >= lineBytes && y < lines) {
                for (x in 0 until outW) {
                    val r = buf[p + 3 * x].toInt() and 0xFF
                    val g = buf[p + 3 * x + 1].toInt() and 0xFF
                    val b = buf[p + 3 * x + 2].toInt() and 0xFF
                    row[x] = if (color) (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    else { val k = (r * 299 + g * 587 + b * 114) / 1000; (0xFF shl 24) or (k shl 16) or (k shl 8) or k }
                }
                bmp.setPixels(row, 0, outW, 0, y, outW, 1)
                y++; p += lineBytes
            }
            pending.reset(); pending.write(buf, p, buf.size - p)
        }

        val l = Link(ip)
        l.open()
        var started = false
        try {
            if (!xml(l, XML_START_1) || !xml(l, XML_START_2)) throw IOException("le scanner a refusé l'ouverture du travail")
            started = true
            var tries = 0
            while (true) {
                try { exec(l, START_SESSION); break } catch (e: Busy) {
                    if (++tries > 10) throw IOException("scanner occupé")
                    Thread.sleep(1000)
                }
            }
            repeat(3) { runCatching { exec(l, GAMMA, gamma()) } }
            exec(l, SCAN_PARAM_3, scanParam(dpi, rawW, lines))
            exec(l, SCAN_START_3)
            for (i in 0 until 120) {
                val st = exec(l, STATUS_3, dataIn = 8)
                if (st.size > 8 && (st[8].toInt() and 0x03) != 0) break
                Thread.sleep(1000)
            }
            Thread.sleep(1000)

            var last = 0
            while ((last and 0x28) != 0x28 && y < lines) {
                val c = ByteArray(16)
                be16(c, 0, READ_IMAGE)
                be32(c, 0x0c, if ((last and 0x20) == 0) BLOCK + 8 else 40)
                l.write(c)
                var r = l.read()
                while (r.size < 16) r += l.read()
                val st = u16(r, 0)
                if (st == BUSY) { Thread.sleep(100); continue }
                if (st != OK) throw IOException("lecture image refusée (état %04x)".format(st))
                last = r[8].toInt() and 0x38
                val size = be32(r, 12)
                var got = r.size - 16
                consume(r, 16, minOf(got, size))
                while (got < size) {
                    val more = l.readOnce()
                    if (more.isEmpty()) { Thread.sleep(10); continue }
                    consume(more, 0, minOf(more.size, size - got))
                    got += more.size
                }
                if (size == 0) Thread.sleep(10)
            }
        } finally {
            runCatching { exec(l, ABORT) }
            if (started) runCatching { xml(l, XML_END) }
            l.close()
        }
        if (y == 0) throw IOException("aucune ligne reçue du scanner")
        val img = if (y < lines) Bitmap.createBitmap(bmp, 0, 0, outW, y) else bmp
        ByteArrayOutputStream().use { o ->
            img.compress(Bitmap.CompressFormat.JPEG, 92, o)
            o.toByteArray()
        }
    }
}
