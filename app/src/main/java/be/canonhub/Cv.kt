package be.canonhub

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Détection des documents sur la vitre avec OpenCV (bibliothèque native arm64).
 * Fond du capot estimé localement (ombres), contours Canny (feuille blanche sur capot blanc),
 * séparation des documents qui se touchent. Null si OpenCV indisponible : repli sur [Detect].
 */
object Cv {
    val ready: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }

    fun findDocs(px: IntArray, w: Int, h: Int): List<RotRect>? {
        if (!ready || w < 32 || h < 32) return null
        return runCatching { detect(px, w, h) }.getOrNull()
    }

    private class Piece(var l: Int, var t: Int, var r: Int, var b: Int, val pts: ArrayList<Point>, var area: Double, var ink: Double, var single: Boolean = true)

    private fun detect(px: IntArray, w: Int, h: Int): List<RotRect> {
        val mats = ArrayList<Mat>()
        fun <T : Mat> keep(m: T): T { mats += m; return m }
        fun m(): Mat = keep(Mat())
        try {
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            val rgba = m(); Utils.bitmapToMat(bmp, rgba); bmp.recycle()
            val gray = m(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val rgb = m(); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val hsv = m(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val sat = m(); Core.extractChannel(hsv, sat, 1)
            val blur = m(); Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

            // Fond local : fermeture large (efface ce qui est sombre et petit) puis lissage.
            val kb = ((w / 10) or 1).toDouble()
            val bg = m()
            Imgproc.morphologyEx(blur, bg, Imgproc.MORPH_CLOSE, keep(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kb, kb))))
            Imgproc.blur(bg, bg, Size(kb, kb))

            // Encre : plus sombre que le fond local, ou colorée.
            val diff = m(); Core.subtract(bg, blur, diff)
            val dark = m(); Imgproc.threshold(diff, dark, 22.0, 255.0, Imgproc.THRESH_BINARY)
            val col = m(); Imgproc.threshold(sat, col, 40.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_or(dark, col, dark)
            // Bords : ombre légère d'une feuille blanche.
            val edges = m(); Imgproc.Canny(blur, edges, 20.0, 60.0)
            val mask = m(); Core.bitwise_or(dark, edges, mask)

            clearBorders(mask, w, h)
            clearBorders(dark, w, h)

            val kc = maxOf(3, w / 150).toDouble()
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, keep(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kc, kc))))

            // Trous bouchés : chaque contour extérieur rempli.
            val filled = keep(Mat.zeros(h, w, CvType.CV_8UC1))
            val outer = contours(mask, m())
            Imgproc.drawContours(filled, outer, -1, Scalar(255.0), -1)
            outer.forEach { it.release() }
            // Ouverture : coupe les ponts fins entre documents proches, retire les poussières.
            val ko = maxOf(3, w / 100).toDouble()
            Imgproc.morphologyEx(filled, filled, Imgproc.MORPH_OPEN, keep(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(ko, ko))))

            val pieces = ArrayList<Piece>()
            val cs = contours(filled, m())
            for (c in cs) {
                val rc = Imgproc.boundingRect(c)
                val area = Imgproc.contourArea(c)
                if (area >= 6.0) {
                    val (a, k) = inkIn(c, rc, dark)
                    pieces += Piece(rc.x, rc.y, rc.x + rc.width - 1, rc.y + rc.height - 1, ArrayList(c.toList()), a, k)
                }
                c.release()
            }

            // Fusion des zones proches (lignes d'une même page) ; texte : écart plus large.
            val gap = maxOf(3, w / 80); val textGap = w / 17
            fun sparse(p: Piece) = p.ink < p.area * 0.45
            var merged = true
            while (merged) {
                merged = false
                loop@ for (i in pieces.indices) for (j in i + 1 until pieces.size) {
                    val a = pieces[i]; val b = pieces[j]
                    val g = if (sparse(a) && sparse(b)) textGap else gap
                    if (a.l - g <= b.r && b.l - g <= a.r && a.t - g <= b.b && b.t - g <= a.b) {
                        a.l = minOf(a.l, b.l); a.t = minOf(a.t, b.t); a.r = maxOf(a.r, b.r); a.b = maxOf(a.b, b.b)
                        a.pts += b.pts; a.area += b.area; a.ink += b.ink; a.single = false
                        pieces.removeAt(j); merged = true; break@loop
                    }
                }
            }

            val minArea = w * h * 0.004f
            val out = ArrayList<RotRect>()
            for (p in pieces) {
                val rr = rect(p.pts) ?: continue
                // Documents qui se touchent : forme peu rectangulaire → séparation par érosion.
                val fill = (p.area / (rr.w * rr.h)).toFloat()
                if (p.single && fill < 0.82f && p.area > minArea * 4 && !sparse(p)) {
                    val parts = split(filled, dark, p, minArea, w)
                    if (parts.size >= 2) { out += parts; continue }
                }
                if (rr.w < 8 || rr.h < 8 || rr.w * rr.h < minArea) continue
                out += rr.copy(fill = fill.coerceAtMost(1f), ink = (p.ink / p.area.coerceAtLeast(1.0)).toFloat())
            }
            val band = h / 12f
            return out.sortedWith(compareBy({ (it.cy / band).toInt() }, { it.cx }))
        } finally {
            mats.forEach { it.release() }
        }
    }

    private fun contours(src: Mat, hier: Mat): List<MatOfPoint> {
        val l = ArrayList<MatOfPoint>()
        val tmp = src.clone()
        Imgproc.findContours(tmp, l, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        tmp.release()
        return l
    }

    /** Surface du contour et nombre de pixels d'encre qu'il contient (calcul sur sa seule boîte). */
    private fun inkIn(c: MatOfPoint, rc: Rect, dark: Mat): Pair<Double, Double> {
        val sm = Mat.zeros(rc.height, rc.width, CvType.CV_8UC1)
        val hier = Mat()
        Imgproc.drawContours(sm, listOf(c), 0, Scalar(255.0), -1, Imgproc.LINE_8, hier, 0, Point(-rc.x.toDouble(), -rc.y.toDouble()))
        val sub = dark.submat(rc)
        val and = Mat()
        Core.bitwise_and(sub, sm, and)
        val r = Core.countNonZero(sm).toDouble() to Core.countNonZero(and).toDouble()
        sm.release(); hier.release(); sub.release(); and.release()
        return r
    }

    /** Rectangle d'aire minimale, même convention que [Detect.minRect]. */
    private fun rect(pts: List<Point>): RotRect? {
        if (pts.size < 3) return null
        val mp = MatOfPoint2f(*pts.toTypedArray())
        val r = Imgproc.minAreaRect(mp)
        mp.release()
        val q = arrayOfNulls<Point>(4); r.points(q)
        val p0 = q[0] ?: return null; val p1 = q[1] ?: return null; val p2 = q[2] ?: return null
        val ww = hypot(p1.x - p0.x, p1.y - p0.y).toFloat()
        val hh = hypot(p2.x - p1.x, p2.y - p1.y).toFloat()
        val deg = Math.toDegrees(atan2(p1.y - p0.y, p1.x - p0.x)).toFloat()
        return Detect.norm(r.center.x.toFloat(), r.center.y.toFloat(), ww, hh, deg)
    }

    /** Érosions croissantes jusqu'à séparer la forme en au moins deux documents, puis regonflage. */
    private fun split(filled: Mat, dark: Mat, p: Piece, minArea: Float, w: Int): List<RotRect> {
        val rc = Rect(p.l, p.t, p.r - p.l + 1, p.b - p.t + 1)
        val comp = filled.submat(rc).clone()
        val res = ArrayList<RotRect>()
        try {
            for (i in 1..5) {
                val k = (w / 60 * i).coerceAtLeast(3).toDouble()
                val ker = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k, k))
                val er = Mat(); Imgproc.erode(comp, er, ker)
                val hier = Mat()
                val cs = contours(er, hier).filter { Imgproc.contourArea(it) >= minArea * 0.3 }
                er.release(); hier.release()
                if (cs.size >= 2) {
                    for (c in cs) {
                        // Regonfle la partie de la même érosion, limitée à la forme d'origine.
                        val one = Mat.zeros(comp.size(), CvType.CV_8UC1)
                        Imgproc.drawContours(one, listOf(c), 0, Scalar(255.0), -1)
                        Imgproc.dilate(one, one, ker)
                        Core.bitwise_and(one, comp, one)
                        val h2 = Mat()
                        val back = contours(one, h2).maxByOrNull { Imgproc.contourArea(it) }
                        if (back != null) {
                            val pts = back.toList().map { Point(it.x + rc.x, it.y + rc.y) }
                            val area = Imgproc.contourArea(back)
                            val sh = MatOfPoint(*pts.toTypedArray())
                            val (a, inkN) = inkIn(sh, Imgproc.boundingRect(sh), dark)
                            sh.release()
                            rect(pts)?.takeIf { it.w >= 8 && it.h >= 8 && it.w * it.h >= minArea }?.let { r ->
                                res += r.copy(fill = (area / (r.w * r.h)).toFloat().coerceAtMost(1f), ink = (inkN / a.coerceAtLeast(1.0)).toFloat())
                            }
                        }
                        one.release(); h2.release()
                    }
                    cs.forEach { it.release() }
                    ker.release()
                    return res
                }
                cs.forEach { it.release() }
                ker.release()
            }
            return emptyList()
        } finally {
            comp.release()
        }
    }

    /** Efface le cadre de la vitre (bord de 2 px, lignes et ombres longues près des bords). */
    private fun clearBorders(mask: Mat, w: Int, h: Int) {
        val b = ByteArray(w * h)
        mask.get(0, 0, b)
        val z: Byte = 0
        for (x in 0 until w) for (y in intArrayOf(0, 1, h - 2, h - 1)) b[y * w + x] = z
        for (y in 0 until h) for (x in intArrayOf(0, 1, w - 2, w - 1)) b[y * w + x] = z
        val bw = maxOf(4, w / 30); val bh = maxOf(4, h / 30)
        for (k in 0 until bh) for (y in intArrayOf(k, h - 1 - k)) {
            var n = 0
            for (x in 0 until w) if (b[y * w + x] != z) n++
            if (n > w * 0.75f) for (x in 0 until w) b[y * w + x] = z
        }
        for (k in 0 until bw) for (x in intArrayOf(k, w - 1 - k)) {
            var n = 0
            for (y in 0 until h) if (b[y * w + x] != z) n++
            if (n > h * 0.75f) for (y in 0 until h) b[y * w + x] = z
        }
        mask.put(0, 0, b)
    }
}
