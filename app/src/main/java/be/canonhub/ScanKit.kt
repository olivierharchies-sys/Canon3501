package be.canonhub

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Algorithmes sans dépendance Android (testables sur PC) : détection, redressement, TIFF, PDF.

/** Rectangle orienté : centre, taille, angle en degrés (sens horaire, y vers le bas), dans (-45, 45]. */
data class RotRect(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float, val fill: Float = 1f)

object Detect {
    private fun lum(c: Int) = ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100
    private fun sat(c: Int): Int {
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /** Niveau du fond (capot blanc) : 95e centile de la luminance. */
    private fun bgLevel(l: IntArray): Int {
        val hist = IntArray(256)
        for (v in l) hist[v]++
        var n = 0
        for (v in 255 downTo 0) { n += hist[v]; if (n >= l.size / 20) return v }
        return 255
    }

    /** Masque « objet » : pixels sombres ou colorés, plus les bords (ombre d'une feuille blanche). */
    private fun mask(px: IntArray, w: Int, h: Int): BooleanArray {
        val l = IntArray(px.size) { lum(px[it]) }
        val bg = bgLevel(l)
        // Flou 3×3 avant le gradient : réduit le grain du papier.
        val s = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            var t = 0
            for (dy in -1..1) for (dx in -1..1) t += l[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            s[y * w + x] = t / 9
        }
        val m = BooleanArray(px.size)
        val edge = 2
        for (y in edge until h - edge) for (x in edge until w - edge) {
            val i = y * w + x
            val gx = s[i + 1] - s[i - 1]
            val gy = s[i + w] - s[i - w]
            m[i] = l[i] < bg - 28 || sat(px[i]) > 32 || gx * gx + gy * gy > 14 * 14
        }
        return m
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        // Séparable : horizontal puis vertical.
        val a = BooleanArray(m.size)
        for (y in 0 until h) {
            var last = -10_000
            for (x in 0 until w) { if (m[y * w + x]) last = x; if (x - last <= r) a[y * w + x] = true }
            last = 10_000
            for (x in w - 1 downTo 0) { if (m[y * w + x]) last = x; if (last - x <= r) a[y * w + x] = true }
        }
        val b = BooleanArray(m.size)
        for (x in 0 until w) {
            var last = -10_000
            for (y in 0 until h) { if (a[y * w + x]) last = y; if (y - last <= r) b[y * w + x] = true }
            last = 10_000
            for (y in h - 1 downTo 0) { if (a[y * w + x]) last = y; if (last - y <= r) b[y * w + x] = true }
        }
        return b
    }

    /** Bouche les trous : tout ce qui n'est pas relié au bord de l'image devient objet. */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int) {
        val out = BooleanArray(m.size)
        val st = IntArray(m.size); var top = 0
        fun push(i: Int) { if (!m[i] && !out[i]) { out[i] = true; st[top++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (top > 0) {
            val i = st[--top]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        for (i in m.indices) if (!out[i]) m[i] = true
    }

    private class Comp(var l: Int, var t: Int, var r: Int, var b: Int, val rows: HashMap<Int, IntArray>, var n: Int)

    /** Enveloppe convexe (chaîne monotone). */
    private fun hull(p: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val s = p.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (s.size < 3) return s
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lo = ArrayList<Pair<Float, Float>>()
        for (q in s) { while (lo.size >= 2 && cross(lo[lo.size - 2], lo[lo.size - 1], q) <= 0) lo.removeAt(lo.size - 1); lo += q }
        val up = ArrayList<Pair<Float, Float>>()
        for (q in s.asReversed()) { while (up.size >= 2 && cross(up[up.size - 2], up[up.size - 1], q) <= 0) up.removeAt(up.size - 1); up += q }
        return lo.dropLast(1) + up.dropLast(1)
    }

    /** Rectangle d'aire minimale contenant l'enveloppe (calipers). */
    fun minRect(pts: List<Pair<Float, Float>>): RotRect? {
        val hl = hull(pts)
        if (hl.size < 3) return null
        var best: RotRect? = null; var bestA = Float.MAX_VALUE
        for (i in hl.indices) {
            val a = hl[i]; val b = hl[(i + 1) % hl.size]
            val th = atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())
            val ux = cos(th).toFloat(); val uy = sin(th).toFloat()
            var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
            for (q in hl) {
                val u = q.first * ux + q.second * uy
                val v = -q.first * uy + q.second * ux
                if (u < u0) u0 = u; if (u > u1) u1 = u; if (v < v0) v0 = v; if (v > v1) v1 = v
            }
            val area = (u1 - u0) * (v1 - v0)
            if (area < bestA) {
                bestA = area
                val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
                best = norm(cu * ux - cv * uy, cu * uy + cv * ux, u1 - u0, v1 - v0, Math.toDegrees(th).toFloat())
            }
        }
        return best
    }

    /** Ramène l'angle dans (-45, 45] en échangeant largeur et hauteur si besoin. */
    private fun norm(cx: Float, cy: Float, w: Float, h: Float, deg: Float): RotRect {
        var a = deg; var ww = w; var hh = h
        while (a > 45f) { a -= 90f; val t = ww; ww = hh; hh = t }
        while (a <= -45f) { a += 90f; val t = ww; ww = hh; hh = t }
        return RotRect(cx, cy, ww, hh, a)
    }

    /**
     * Documents posés sur la vitre, en coordonnées de l'image réduite.
     * [gap] : écart (en pixels) en dessous duquel deux zones sont fusionnées.
     */
    fun findDocs(px: IntArray, w: Int, h: Int, gap: Int = maxOf(3, w / 80)): List<RotRect> {
        val r = 2
        val m = dilate(mask(px, w, h), w, h, r)
        fillHoles(m, w, h)
        // Composantes connexes.
        val seen = BooleanArray(m.size)
        val st = IntArray(m.size)
        val comps = ArrayList<Comp>()
        for (s in m.indices) {
            if (!m[s] || seen[s]) continue
            var top = 0; st[top++] = s; seen[s] = true
            val c = Comp(w, h, -1, -1, HashMap(), 0)
            while (top > 0) {
                val i = st[--top]; val x = i % w; val y = i / w
                c.n++
                if (x < c.l) c.l = x; if (x > c.r) c.r = x; if (y < c.t) c.t = y; if (y > c.b) c.b = y
                val row = c.rows.getOrPut(y) { intArrayOf(x, x) }
                if (x < row[0]) row[0] = x; if (x > row[1]) row[1] = x
                if (x > 0 && m[i - 1] && !seen[i - 1]) { seen[i - 1] = true; st[top++] = i - 1 }
                if (x < w - 1 && m[i + 1] && !seen[i + 1]) { seen[i + 1] = true; st[top++] = i + 1 }
                if (y > 0 && m[i - w] && !seen[i - w]) { seen[i - w] = true; st[top++] = i - w }
                if (y < h - 1 && m[i + w] && !seen[i + w]) { seen[i + w] = true; st[top++] = i + w }
            }
            if (c.n >= 6) comps += c
        }
        // Fusion des zones proches (lignes d'un même document).
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in comps.indices) for (j in i + 1 until comps.size) {
                val a = comps[i]; val b = comps[j]
                if (a.l - gap <= b.r && b.l - gap <= a.r && a.t - gap <= b.b && b.t - gap <= a.b) {
                    b.rows.forEach { (y, rr) ->
                        val ra = a.rows[y]
                        if (ra == null) a.rows[y] = rr else { ra[0] = minOf(ra[0], rr[0]); ra[1] = maxOf(ra[1], rr[1]) }
                    }
                    a.l = minOf(a.l, b.l); a.t = minOf(a.t, b.t); a.r = maxOf(a.r, b.r); a.b = maxOf(a.b, b.b); a.n += b.n
                    comps.removeAt(j); merged = true; break@loop
                }
            }
        }
        val minArea = w * h * 0.004f
        val out = comps.mapNotNull { c ->
            val pts = ArrayList<Pair<Float, Float>>(c.rows.size * 4)
            c.rows.forEach { (y, rr) ->
                pts += rr[0].toFloat() to y.toFloat(); pts += (rr[1] + 1).toFloat() to y.toFloat()
                pts += rr[0].toFloat() to (y + 1).toFloat(); pts += (rr[1] + 1).toFloat() to (y + 1).toFloat()
            }
            val rr = minRect(pts) ?: return@mapNotNull null
            // Retire la marge ajoutée par la dilatation.
            val ww = rr.w - 2 * r - 4; val hh = rr.h - 2 * r - 4
            if (ww < 8 || hh < 8 || ww * hh < minArea) null
            else rr.copy(w = ww, h = hh, fill = (c.n / (rr.w * rr.h)).coerceAtMost(1f))
        }
        val band = h / 12f
        return out.sortedWith(compareBy({ (it.cy / band).toInt() }, { it.cx }))
    }

    /** Angle par profil de projection des pixels sombres (texte) ; 0 si incertain. */
    fun projectionAngle(px: IntArray, w: Int, h: Int, range: Float = 8f): Float {
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        val bg = bgLevel(IntArray(px.size) { lum(px[it]) })
        for (y in 0 until h) for (x in 0 until w) if (lum(px[y * w + x]) < minOf(140, bg - 60)) { xs += x; ys += y }
        if (xs.size < 300) return 0f
        val step = maxOf(1, xs.size / 30000)
        fun score(deg: Float): Double {
            val rad = Math.toRadians(deg.toDouble())
            val cs = cos(rad); val sn = sin(rad)
            val hist = IntArray(h + 2 * w)
            var i = 0
            while (i < xs.size) {
                // Point tourné de -deg : une ligne inclinée de +deg devient horizontale.
                val yy = (ys[i] * cs - xs[i] * sn).toInt() + w
                if (yy in hist.indices) hist[yy]++
                i += step
            }
            var sum = 0.0
            for (v in hist) sum += v.toDouble() * v
            return sum
        }
        val base = score(0f)
        var best = 0f; var bestS = base
        var a = -range
        while (a <= range) { val sc = score(a); if (sc > bestS) { bestS = sc; best = a }; a += 0.25f }
        // Affinage au dixième de degré.
        var f = best - 0.2f
        while (f <= best + 0.2f) { val sc = score(f); if (sc > bestS) { bestS = sc; best = f }; f += 0.05f }
        return if (abs(best) >= 0.3f && bestS > base * 1.05) best else 0f
    }

    /**
     * Inclinaison du contenu (degrés, sens horaire). Corriger = tourner de -angle.
     * Objet plein dominant (photo, carte) : angle de son contour. Sinon : lignes de texte.
     */
    fun skew(px: IntArray, w: Int, h: Int): Float {
        val docs = findDocs(px, w, h)
        val big = docs.maxByOrNull { it.w * it.h }
        if (big != null && docs.size == 1 && big.fill > 0.85f && big.w * big.h > w * h * 0.03f &&
            big.w * big.h < w * h * 0.9f && abs(big.angle) in 0.3f..30f
        ) return big.angle
        return projectionAngle(px, w, h)
    }

    /** Page blanche : quasiment aucun pixel marqué hors des bords. */
    fun isBlank(px: IntArray, w: Int, h: Int): Boolean {
        val l = IntArray(px.size) { lum(px[it]) }
        val bg = bgLevel(l)
        val mx = w / 25; val my = h / 25
        var n = 0; var tot = 0
        for (y in my until h - my) for (x in mx until w - mx) {
            tot++
            val i = y * w + x
            if (l[i] < bg - 45 || sat(px[i]) > 45) n++
        }
        return tot > 0 && n < tot * 0.0015f
    }
}

/** Écriture TIFF multipage : couleur/gris en LZW + prédicteur, noir et blanc 1 bit en PackBits. */
object Tiff {
    /** Page : dimensions, dpi, lecture d'une ligne de pixels ARGB. */
    class Page(val w: Int, val h: Int, val dpi: Int, val row: (y: Int, dst: IntArray) -> Unit)

    enum class Mode { BW, Gray, Rgb }

    fun mode(p: Page): Mode {
        val r = IntArray(p.w)
        var bw = true
        for (y in 0 until p.h) {
            p.row(y, r)
            for (c in r) {
                val rr = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                if (rr != g || g != b) return Mode.Rgb
                if (bw && (c and 0xFFFFFF) != 0 && (c and 0xFFFFFF) != 0xFFFFFF) bw = false
            }
        }
        return if (bw) Mode.BW else Mode.Gray
    }

    /** Encodeur LZW au format TIFF (MSB d'abord, changement de taille anticipé). */
    private class Lzw(private val out: ByteArrayOutputStream) {
        private val keys = IntArray(8192); private val vals = IntArray(8192)
        private var next = 258; private var width = 9; private var cur = -1
        private var acc = 0L; private var nb = 0

        init { reset(); emit(256) }

        private fun reset() { keys.fill(-1); next = 258; width = 9 }
        private fun emit(code: Int) {
            acc = (acc shl width) or code.toLong(); nb += width
            while (nb >= 8) { out.write((acc shr (nb - 8)).toInt() and 255); nb -= 8 }
            acc = acc and ((1L shl nb) - 1)
        }
        private fun find(k: Int): Int {
            var i = (k * 0x9E3779B1.toInt() ushr 19) and 8191
            while (true) {
                if (keys[i] == -1) return -1 - i
                if (keys[i] == k) return vals[i]
                i = (i + 1) and 8191
            }
        }
        private fun added() {
            next++
            if (next == 4094) { emit(256); reset() } else if (next > (1 shl width) - 1) width++
        }
        fun put(b: Int) {
            if (cur < 0) { cur = b; return }
            val k = (cur shl 8) or b
            val f = find(k)
            if (f >= 0) { cur = f; return }
            emit(cur)
            val slot = -1 - f
            keys[slot] = k; vals[slot] = next
            added()
            cur = b
        }
        fun finish() {
            if (cur >= 0) { emit(cur); added() }
            emit(257)
            if (nb > 0) out.write((acc shl (8 - nb)).toInt() and 255)
            nb = 0
        }
    }

    private fun packBits(src: ByteArray, out: ByteArrayOutputStream) {
        var i = 0
        while (i < src.size) {
            var run = 1
            while (i + run < src.size && run < 128 && src[i + run] == src[i]) run++
            if (run >= 3) { out.write(257 - run); out.write(src[i].toInt()); i += run; continue }
            var j = i; var lit = 0
            while (j < src.size && lit < 128) {
                if (j + 2 < src.size && src[j] == src[j + 1] && src[j] == src[j + 2]) break
                j++; lit++
            }
            out.write(lit - 1); out.write(src, i, lit); i += lit
        }
    }

    /** Compresse une page ; renvoie (mode, données). */
    fun encode(p: Page, m: Mode = mode(p)): ByteArray {
        val o = ByteArrayOutputStream()
        val r = IntArray(p.w)
        if (m == Mode.BW) {
            val line = ByteArray((p.w + 7) / 8)
            for (y in 0 until p.h) {
                p.row(y, r); line.fill(0)
                for (x in 0 until p.w) if ((r[x] and 0xFFFFFF) == 0) line[x shr 3] = (line[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
                packBits(line, o)
            }
            return o.toByteArray()
        }
        val lzw = Lzw(o)
        for (y in 0 until p.h) {
            p.row(y, r)
            if (m == Mode.Gray) {
                var prev = 0
                for (x in 0 until p.w) { val g = r[x] shr 8 and 255; lzw.put((g - prev) and 255); prev = g }
            } else {
                var pr = 0; var pg = 0; var pb = 0
                for (x in 0 until p.w) {
                    val c = r[x]; val rr = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                    lzw.put((rr - pr) and 255); lzw.put((g - pg) and 255); lzw.put((b - pb) and 255)
                    pr = rr; pg = g; pb = b
                }
            }
        }
        lzw.finish()
        return o.toByteArray()
    }

    /** Écrit le fichier : pages produites une à une (une seule page compressée en mémoire). */
    fun write(out: OutputStream, count: Int, page: (Int) -> Page) {
        var pos = 8L
        fun le(v: Long, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)).toInt() and 255) }
        out.write('I'.code); out.write('I'.code); le(42, 2); le(8, 4)
        for (k in 0 until count) {
            val p = page(k)
            val m = mode(p)
            val data = encode(p, m)
            val spp = if (m == Mode.Rgb) 3 else 1
            val tags = ArrayList<LongArray>() // tag, type, count, valeur
            val nTags = if (m == Mode.BW) 15 else 16
            val ifdSize = 2 + 12 * nTags + 4
            var extra = pos + ifdSize
            val bpsOff = extra; if (spp == 3) extra += 6
            val xOff = extra; extra += 8
            val yOff = extra; extra += 8
            val dataOff = extra
            val end = dataOff + data.size + (data.size and 1)
            tags += longArrayOf(254, 4, 1, 0)
            tags += longArrayOf(256, 4, 1, p.w.toLong())
            tags += longArrayOf(257, 4, 1, p.h.toLong())
            tags += longArrayOf(258, 3, spp.toLong(), if (spp == 3) bpsOff else if (m == Mode.BW) 1 else 8)
            tags += longArrayOf(259, 3, 1, if (m == Mode.BW) 32773 else 5)
            tags += longArrayOf(262, 3, 1, when (m) { Mode.BW -> 0; Mode.Gray -> 1; Mode.Rgb -> 2 })
            tags += longArrayOf(273, 4, 1, dataOff)
            tags += longArrayOf(277, 3, 1, spp.toLong())
            tags += longArrayOf(278, 4, 1, p.h.toLong())
            tags += longArrayOf(279, 4, 1, data.size.toLong())
            tags += longArrayOf(282, 5, 1, xOff)
            tags += longArrayOf(283, 5, 1, yOff)
            tags += longArrayOf(284, 3, 1, 1)
            tags += longArrayOf(296, 3, 1, 2)
            tags += longArrayOf(297, 3, 2, k.toLong() or (count.toLong() shl 16))
            if (m != Mode.BW) tags += longArrayOf(317, 3, 1, 2)
            le(nTags.toLong(), 2)
            for (t in tags) {
                le(t[0], 2); le(t[1], 2); le(t[2], 4)
                if (t[1] == 3L && t[2] == 1L) { le(t[3], 2); le(0, 2) } else le(t[3], 4)
            }
            le(if (k == count - 1) 0 else end, 4)
            if (spp == 3) { le(8, 2); le(8, 2); le(8, 2) }
            le(p.dpi.toLong(), 4); le(1, 4); le(p.dpi.toLong(), 4); le(1, 4)
            out.write(data)
            if (data.size and 1 == 1) out.write(0)
            pos = end
        }
        out.flush()
    }
}

/**
 * PDF minimal : une image JPEG par page, calque de texte invisible optionnel (OCR),
 * chiffrement AES-128 optionnel (mot de passe à l'ouverture).
 */
class PdfOut(private val out: OutputStream, password: String? = null) {
    /** Ligne de texte invisible : coordonnées en points depuis le coin haut-gauche. */
    class Line(val text: String, val x: Float, val baseline: Float, val size: Float, val width: Float)

    private var pos = 0L
    private val offsets = HashMap<Int, Long>()
    private var nextObj = 5
    private val kids = ArrayList<Int>()
    private val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private var oVal = ByteArray(0); private var uVal = ByteArray(0)
    private val key: ByteArray? = password?.takeIf { it.isNotEmpty() }?.let { setupKey(it) }

    private fun raw(b: ByteArray) { out.write(b); pos += b.size }
    private fun raw(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))

    init { raw("%PDF-1.6\n"); raw(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte())) }

    private fun obj(n: Int, dict: String) { offsets[n] = pos; raw("$n 0 obj\n$dict\nendobj\n") }
    private fun stream(n: Int, dict: String, data: ByteArray) {
        val d = key?.let { aes(n, data) } ?: data
        offsets[n] = pos
        raw("$n 0 obj\n<< $dict /Length ${d.size} >>\nstream\n"); raw(d); raw("\nendstream\nendobj\n")
    }

    fun page(wPt: Float, hPt: Float, jpeg: ByteArray, imgW: Int, imgH: Int, x: Float, y: Float, dw: Float, dh: Float, lines: List<Line> = emptyList()) {
        val pg = nextObj++; val ct = nextObj++; val im = nextObj++
        stream(im, "/Type /XObject /Subtype /Image /Width $imgW /Height $imgH /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg)
        val sb = StringBuilder()
        sb.append("q ${f(dw)} 0 0 ${f(dh)} ${f(x)} ${f(hPt - y - dh)} cm /Im0 Do Q\n")
        if (lines.isNotEmpty()) {
            sb.append("BT 3 Tr\n")
            for (l in lines) {
                val bytes = winAnsi(l.text)
                if (bytes.isEmpty() || l.size <= 0f) continue
                val tz = (l.width / (bytes.size * 0.5f * l.size) * 100f).coerceIn(10f, 1000f)
                sb.append("/F1 ${f(l.size)} Tf ${f(tz)} Tz 1 0 0 1 ${f(l.x)} ${f(hPt - l.baseline)} Tm <")
                for (b in bytes) sb.append("%02X".format(b.toInt() and 255))
                sb.append("> Tj\n")
            }
            sb.append("ET\n")
        }
        stream(ct, "/Filter /FlateDecode", deflate(sb.toString().toByteArray(Charsets.ISO_8859_1)))
        obj(pg, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(wPt)} ${f(hPt)}] " +
            "/Resources << /XObject << /Im0 $im 0 R >> /Font << /F1 3 0 R >> >> /Contents $ct 0 R >>")
        kids += pg
    }

    fun close() {
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count ${kids.size} /Kids [${kids.joinToString(" ") { "$it 0 R" }}] >>")
        obj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        if (key != null) obj(4, "<< /Filter /Standard /V 4 /R 4 /Length 128 " +
            "/CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV2 /Length 16 >> >> /StmF /StdCF /StrF /StdCF " +
            "/O <${hex(oVal)}> /U <${hex(uVal)}> /P $PERMS >>")
        val size = nextObj
        val xref = pos
        val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (i in 1 until size) {
            val o = offsets[i]
            sb.append(if (o != null) "%010d 00000 n \n".format(o) else "0000000000 65535 f \n")
        }
        val idh = hex(id)
        sb.append("trailer\n<< /Size $size /Root 1 0 R /ID [<$idh><$idh>]${if (key != null) " /Encrypt 4 0 R" else ""} >>\nstartxref\n$xref\n%%EOF\n")
        raw(sb.toString())
        out.flush()
    }

    // ---------- Chiffrement (gestionnaire standard, révision 4, AESV2) ----------

    private fun md5(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("MD5").run { parts.forEach { update(it) }; digest() }

    private fun pad(pw: String): ByteArray {
        val b = pw.toByteArray(Charsets.ISO_8859_1).take(32).toByteArray()
        return b + PAD.copyOfRange(0, 32 - b.size)
    }

    private fun rc4(k: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) { j = (j + s[i] + (k[i % k.size].toInt() and 255)) and 255; val t = s[i]; s[i] = s[j]; s[j] = t }
        var i = 0; j = 0
        return ByteArray(data.size) { n ->
            i = (i + 1) and 255; j = (j + s[i]) and 255
            val t = s[i]; s[i] = s[j]; s[j] = t
            (data[n].toInt() xor s[(s[i] + s[j]) and 255]).toByte()
        }
    }

    private fun rc4x20(k: ByteArray, data: ByteArray): ByteArray {
        var d = rc4(k, data)
        for (i in 1..19) d = rc4(ByteArray(k.size) { (k[it].toInt() xor i).toByte() }, d)
        return d
    }

    private fun setupKey(pw: String): ByteArray {
        // Propriétaire = utilisateur : un seul mot de passe.
        var h = md5(pad(pw))
        repeat(50) { h = md5(h) }
        oVal = rc4x20(h.copyOf(16), pad(pw))
        val p = PERMS
        val pb = byteArrayOf((p and 255).toByte(), (p shr 8 and 255).toByte(), (p shr 16 and 255).toByte(), (p shr 24 and 255).toByte())
        var k = md5(pad(pw), oVal, pb, id)
        repeat(50) { k = md5(k.copyOf(16)) }
        k = k.copyOf(16)
        uVal = rc4x20(k, md5(PAD, id)) + ByteArray(16)
        return k
    }

    private fun aes(n: Int, data: ByteArray): ByteArray {
        val k = md5(key!!, byteArrayOf((n and 255).toByte(), (n shr 8 and 255).toByte(), (n shr 16 and 255).toByte(), 0, 0),
            "sAlT".toByteArray(Charsets.ISO_8859_1))
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(k, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return iv + c.doFinal(data)
    }

    companion object {
        private const val PERMS = -4
        private val PAD = intArrayOf(
            0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
        ).map { it.toByte() }.toByteArray()

        private fun f(v: Float) = "%.2f".format(java.util.Locale.US, v)
        private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 255) }

        fun deflate(b: ByteArray): ByteArray {
            val d = Deflater(6); d.setInput(b); d.finish()
            val o = ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!d.finished()) o.write(buf, 0, d.deflate(buf))
            d.end()
            return o.toByteArray()
        }

        private val SPECIAL = mapOf(
            '€' to 0x80, '‚' to 0x82, '„' to 0x84, '…' to 0x85, '‘' to 0x91, '’' to 0x92, '“' to 0x93, '”' to 0x94,
            '•' to 0x95, '–' to 0x96, '—' to 0x97, 'œ' to 0x9C, 'Œ' to 0x8C, 'Ÿ' to 0x9F, '™' to 0x99,
        )

        fun winAnsi(s: String): ByteArray = s.map { c ->
            when {
                c.code in 32..126 || c.code in 160..255 -> c.code
                else -> SPECIAL[c] ?: '?'.code
            }.toByte()
        }.toByteArray()
    }
}

/** Estimation de poids : ≈ octets d'un aperçu, extrapolés à la taille réelle. */
object SizeEst {
    /** [sample] octets pour [samplePx] pixels ; [fullPx] pixels par page ; [lossy] : compression JPEG/WEBP. */
    fun extrapolate(sample: Long, samplePx: Long, fullPx: Long, lossy: Boolean): Long {
        if (samplePx <= 0) return 0
        val ratio = fullPx.toDouble() / samplePx
        // Plus de pixels = image plus lisse : le poids croît moins vite que la surface.
        val e = if (lossy) 0.82 else 0.93
        return (sample * Math.pow(ratio, e)).toLong()
    }

    fun label(bytes: Long): String = when {
        bytes < 1024 -> "$bytes o"
        bytes < 1024 * 1024 -> "%.0f Ko".format(bytes / 1024.0)
        else -> "%.1f Mo".format(java.util.Locale.FRANCE, bytes / 1024.0 / 1024.0)
    }
}

/**
 * Corrections automatiques des couleurs, dépoussiérage et netteté.
 * Traitement ligne par ligne ([rowIn] lit une ligne, [rowOut] l'écrit) : peu de mémoire.
 */
object Enhance {
    enum class Mode(val label: String, val hint: String) {
        None("Aucune", ""),
        Auto("Auto", "Niveaux, balance des blancs et luminosité ajustés."),
        Faded("Photo délavée", "Vieille photo couleur : dominante jaune ou rouge retirée, couleurs ravivées."),
        OldBw("Photo N&B ancienne", "Tons neutres, contraste rétabli, jaunissement supprimé."),
        Document("Document", "Fond blanc, texte noir et net."),
    }

    /** Table de correspondance par canal, saturation, conversion en gris. */
    class Lut(val r: IntArray, val g: IntArray, val b: IntArray, val sat: Float, val gray: Boolean)

    private fun lum(r: Int, g: Int, b: Int) = (r * 30 + g * 59 + b * 11) / 100

    private fun pct(hist: IntArray, total: Int, p: Float, fromTop: Boolean): Int {
        val lim = (total * p).toInt(); var n = 0
        if (fromTop) { for (v in 255 downTo 0) { n += hist[v]; if (n > lim) return v } }
        else for (v in 0..255) { n += hist[v]; if (n > lim) return v }
        return if (fromTop) 255 else 0
    }

    /** Calcule la correction sur un échantillon réduit ; null si rien à faire. */
    fun plan(px: IntArray, m: Mode): Lut? {
        if (m == Mode.None || m == Mode.Document || px.isEmpty()) return null
        val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256); val hl = IntArray(256)
        for (c in px) {
            val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            hr[r]++; hg[g]++; hb[b]++; hl[lum(r, g, b)]++
        }
        val n = px.size
        val clip = when (m) { Mode.Faded -> 0.01f; Mode.OldBw -> 0.005f; else -> 0.003f }
        // Force de la correction par canal (1 = balance complète, 0 = niveaux communs).
        val k = when (m) { Mode.Auto -> 0.6f; else -> 1f }
        val lo0 = pct(hl, n, clip, false); val hi0 = pct(hl, n, clip, true)
        fun map(h: IntArray): IntArray {
            var lo = pct(h, n, clip, false) * k + lo0 * (1 - k)
            var hi = pct(h, n, clip, true) * k + hi0 * (1 - k)
            if (hi - lo < 30f) { val c = (hi + lo) / 2; lo = c - 15; hi = c + 15 }
            return IntArray(256) { v -> ((v - lo) * 255f / (hi - lo)).toInt().coerceIn(0, 255) }
        }
        val gray = m == Mode.OldBw
        val r = if (gray) map(hl) else map(hr)
        val g = if (gray) r else map(hg)
        val b = if (gray) r else map(hb)
        // Gamma : ramène la luminosité moyenne vers le milieu.
        var sum = 0.0
        for (v in 0..255) sum += hl[v].toDouble() * lum(r[v], g[v], b[v])
        val mean = (sum / n / 255.0).coerceIn(0.05, 0.95)
        val gm = (Math.log(0.47) / Math.log(mean)).coerceIn(0.75, 1.35)
        fun gam(a: IntArray) = IntArray(256) { (255.0 * Math.pow(a[it] / 255.0, gm)).toInt().coerceIn(0, 255) }
        val sat = when (m) { Mode.Faded -> 1.3f; Mode.Auto -> 1.08f; else -> 1f }
        return Lut(gam(r), if (gray) gam(r) else gam(g), if (gray) gam(r) else gam(b), sat, gray)
    }

    fun apply(row: IntArray, t: Lut) {
        for (i in row.indices) {
            val c = row[i]; val a = c and -0x1000000
            var r = c shr 16 and 255; var g = c shr 8 and 255; var b = c and 255
            if (t.gray) {
                val v = t.r[lum(r, g, b)]; r = v; g = v; b = v
            } else {
                r = t.r[r]; g = t.g[g]; b = t.b[b]
                if (t.sat != 1f) {
                    val l = lum(r, g, b)
                    r = (l + (r - l) * t.sat).toInt().coerceIn(0, 255)
                    g = (l + (g - l) * t.sat).toInt().coerceIn(0, 255)
                    b = (l + (b - l) * t.sat).toInt().coerceIn(0, 255)
                }
            }
            row[i] = a or (r shl 16) or (g shl 8) or b
        }
    }

    /** Cache de lignes autour de la ligne courante (bords répétés). */
    private class Rows(val w: Int, val h: Int, n: Int, val rowIn: (Int, IntArray) -> Unit) {
        private val px = Array(n) { IntArray(w) }; private val lm = Array(n) { IntArray(w) }
        private val idx = IntArray(n) { -1 }
        private fun slot(y: Int): Int {
            val yy = y.coerceIn(0, h - 1); val s = yy % px.size
            if (idx[s] != yy) {
                rowIn(yy, px[s]); idx[s] = yy
                val p = px[s]; val l = lm[s]
                for (x in 0 until w) { val c = p[x]; l[x] = lum(c shr 16 and 255, c shr 8 and 255, c and 255) }
            }
            return s
        }
        fun px(y: Int) = px[slot(y)]
        fun lum(y: Int) = lm[slot(y)]
    }

    /** Dépoussiérage : petites taches isolées sur fond régulier remplacées par leur entourage. */
    fun dust(w: Int, h: Int, rowIn: (Int, IntArray) -> Unit, rowOut: (Int, IntArray) -> Unit) {
        val rows = Rows(w, h, 9, rowIn)
        val col = IntArray(w); val out = IntArray(w)
        for (y in 0 until h) {
            col.fill(0)
            for (dy in -4..4) { val l = rows.lum(y + dy); for (x in 0 until w) col[x] += l[x] }
            val cur = rows.px(y); val cl = rows.lum(y)
            System.arraycopy(cur, 0, out, 0, w)
            var box = 0
            for (x in -4..4) box += col[x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                if (x > 0) box += col[(x + 4).coerceAtMost(w - 1)] - col[(x - 5).coerceAtLeast(0)]
                val l = cl[x]
                if (abs(l * 81 - box) <= 28 * 81) continue
                // Anneau à distance 3 : doit être uniforme.
                var s = 0; var s2 = 0; var sr = 0; var sg = 0; var sb = 0
                for (dy in -3..3) {
                    val pr = rows.px(y + dy); val lr = rows.lum(y + dy)
                    val step = if (dy == -3 || dy == 3) 1 else 6
                    var dx = -3
                    while (dx <= 3) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val v = lr[xx]; s += v; s2 += v * v
                        val c = pr[xx]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255
                        dx += step
                    }
                }
                val mean = s / 24f
                val varr = s2 / 24f - mean * mean
                if (varr < 14f * 14f && abs(l - mean) > 30f) {
                    out[x] = (cur[x] and -0x1000000) or ((sr / 24) shl 16) or ((sg / 24) shl 8) or (sb / 24)
                }
            }
            rowOut(y, out)
        }
    }

    /** Accentuation (masque flou 3×3) ; [amount] 0..2. */
    fun sharpen(w: Int, h: Int, amount: Float, rowIn: (Int, IntArray) -> Unit, rowOut: (Int, IntArray) -> Unit) {
        val rows = Rows(w, h, 3, rowIn)
        val out = IntArray(w)
        for (y in 0 until h) {
            val a = rows.px(y - 1); val c = rows.px(y); val b = rows.px(y + 1)
            for (x in 0 until w) {
                val x0 = (x - 1).coerceAtLeast(0); val x1 = (x + 1).coerceAtMost(w - 1)
                var r = 0; var g = 0; var bl = 0
                for (p in intArrayOf(a[x0], a[x], a[x1], c[x0], c[x], c[x1], b[x0], b[x], b[x1])) {
                    r += p shr 16 and 255; g += p shr 8 and 255; bl += p and 255
                }
                val p = c[x]
                fun sh(v: Int, blur: Int): Int { val d = v - blur / 9; return if (abs(d) < 3) v else (v + d * amount).toInt().coerceIn(0, 255) }
                out[x] = (p and -0x1000000) or (sh(p shr 16 and 255, r) shl 16) or (sh(p shr 8 and 255, g) shl 8) or sh(p and 255, bl)
            }
            rowOut(y, out)
        }
    }
}
