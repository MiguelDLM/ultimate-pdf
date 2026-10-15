package org.ultimatepdf.core.signature

import org.ultimatepdf.core.model.PdfPoint
import org.ultimatepdf.core.model.SignatureShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns a photo of a signature on paper into filled vector outlines.
 *
 * Pipeline: adaptive threshold (tolerates shadows and uneven light) → removal of specks →
 * boundary tracing into closed polygons (holes included) → Douglas-Peucker simplification →
 * Chaikin smoothing. The result is normalized to the ink's bounding box.
 *
 * Works on a plain luminance array so it runs off the main thread and in JVM unit tests.
 */
object SignatureVectorizer {

    /** Longest side the photo is reduced to before tracing; enough detail for a signature. */
    const val WORK_SIZE = 900

    class Mask(val width: Int, val height: Int, val ink: BooleanArray) {
        operator fun get(x: Int, y: Int) = x in 0 until width && y in 0 until height && ink[y * width + x]
    }

    /**
     * Marks ink pixels: darker than their surroundings by more than [sensitivity] (0..1, higher
     * keeps fainter strokes). Uses a Bradley local-mean threshold over an integral image.
     */
    fun threshold(gray: IntArray, width: Int, height: Int, sensitivity: Float): Mask {
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += gray[y * width + x]
                integral[(y + 1) * (width + 1) + x + 1] = integral[y * (width + 1) + x + 1] + rowSum
            }
        }
        val half = max(8, max(width, height) / 16)
        // Low sensitivity demands a big contrast; high sensitivity accepts faint ink.
        val t = (0.42f - sensitivity * 0.37f).coerceIn(0.03f, 0.45f)
        val ink = BooleanArray(width * height)
        for (y in 0 until height) {
            val y0 = max(0, y - half)
            val y1 = minOf(height - 1, y + half)
            for (x in 0 until width) {
                val x0 = max(0, x - half)
                val x1 = minOf(width - 1, x + half)
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (width + 1) + x1 + 1] - integral[y0 * (width + 1) + x1 + 1] -
                    integral[(y1 + 1) * (width + 1) + x0] + integral[y0 * (width + 1) + x0]
                val g = gray[y * width + x]
                // The absolute cap stops paper texture in bright, flat areas from becoming ink.
                ink[y * width + x] = g.toLong() * count < sum * (1f - t) && g < 215
            }
        }
        return Mask(width, height, ink)
    }

    /**
     * Removes 8-connected ink blobs smaller than [minArea] pixels (dust, paper grain), and
     * optionally blobs touching the image border (paper edges and shadows in a full photo).
     */
    fun removeSpecks(mask: Mask, minArea: Int, dropBorderBlobs: Boolean = false): Mask {
        val w = mask.width
        val h = mask.height
        val ink = mask.ink.copyOf()
        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val blob = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!ink[start] || visited[start]) continue
            var top = 0
            var size = 0
            var touchesBorder = false
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val i = stack[--top]
                blob[size++] = i
                val x = i % w
                val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesBorder = true
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val n = ny * w + nx
                    if (ink[n] && !visited[n]) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
            }
            if (size < minArea || (dropBorderBlobs && touchesBorder)) for (k in 0 until size) ink[blob[k]] = false
        }
        return Mask(w, h, ink)
    }

    /** Bounding box of the ink as (left, top, right, bottom) in pixels, or null if empty. */
    fun inkBounds(mask: Mask): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask.ink[y * mask.width + x]) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * Traces the boundary between ink and paper into closed polygons on the pixel-corner grid.
     * Each ink pixel contributes the sides that face paper, oriented clockwise, so outer
     * outlines and holes come out with opposite winding and fill correctly with even-odd.
     */
    fun traceContours(mask: Mask): List<List<PdfPoint>> {
        val w = mask.width
        val stride = w + 1
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        // Up to two outgoing edges per grid vertex (two only at diagonal "saddle" corners).
        val out1 = IntArray(stride * (mask.height + 1)) { -1 }
        val out2 = IntArray(stride * (mask.height + 1)) { -1 }
        fun addEdge(x0: Int, y0: Int, x1: Int, y1: Int) {
            val from = y0 * stride + x0
            val index = starts.size
            starts += from
            ends += y1 * stride + x1
            if (out1[from] < 0) out1[from] = index else out2[from] = index
        }
        for (y in 0 until mask.height) for (x in 0 until w) {
            if (!mask[x, y]) continue
            if (!mask[x, y - 1]) addEdge(x, y, x + 1, y)
            if (!mask[x + 1, y]) addEdge(x + 1, y, x + 1, y + 1)
            if (!mask[x, y + 1]) addEdge(x + 1, y + 1, x, y + 1)
            if (!mask[x - 1, y]) addEdge(x, y + 1, x, y)
        }

        val used = BooleanArray(starts.size)
        val contours = ArrayList<List<PdfPoint>>()
        for (first in starts.indices) {
            if (used[first]) continue
            val loop = ArrayList<PdfPoint>()
            var edge = first
            while (!used[edge]) {
                used[edge] = true
                val v = starts[edge]
                loop += PdfPoint((v % stride).toFloat(), (v / stride).toFloat())
                val next = ends[edge]
                val a = out1[next]
                val b = out2[next]
                edge = when {
                    a < 0 -> -1
                    b < 0 || used[b] -> a
                    used[a] -> b
                    // At a saddle keep turning the same way so touching pixels stay separate.
                    else -> if (isRightTurn(edge, a, starts, ends, stride)) a else b
                }
                if (edge < 0) break
            }
            if (loop.size >= 4) contours += loop
        }
        return contours
    }

    private fun isRightTurn(from: Int, to: Int, starts: List<Int>, ends: List<Int>, stride: Int): Boolean {
        val dx1 = ends[from] % stride - starts[from] % stride
        val dy1 = ends[from] / stride - starts[from] / stride
        val dx2 = ends[to] % stride - starts[to] % stride
        val dy2 = ends[to] / stride - starts[to] / stride
        return dx1 * dy2 - dy1 * dx2 > 0
    }

    /** Douglas-Peucker on a closed polygon. */
    fun simplify(points: List<PdfPoint>, epsilon: Float): List<PdfPoint> {
        if (points.size < 4) return points
        // Split the ring at its farthest point from the start so both halves are open chains.
        val far = points.indices.maxBy { dist2(points[it], points[0]) }
        val a = rdp(points.subList(0, far + 1), epsilon)
        val b = rdp(points.subList(far, points.size) + points[0], epsilon)
        return a.dropLast(1) + b.dropLast(1)
    }

    private fun rdp(points: List<PdfPoint>, epsilon: Float): List<PdfPoint> {
        if (points.size < 3) return points
        val first = points.first()
        val last = points.last()
        var index = 0
        var maxDist = 0f
        for (i in 1 until points.lastIndex) {
            val d = segmentDistance(points[i], first, last)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        return if (maxDist > epsilon) {
            rdp(points.subList(0, index + 1), epsilon).dropLast(1) + rdp(points.subList(index, points.size), epsilon)
        } else {
            listOf(first, last)
        }
    }

    /** Chaikin corner cutting on a closed polygon: rounds the pixel staircase into curves. */
    fun smooth(points: List<PdfPoint>, iterations: Int = 2): List<PdfPoint> {
        var current = points
        repeat(iterations) {
            if (current.size < 3) return current
            val next = ArrayList<PdfPoint>(current.size * 2)
            for (i in current.indices) {
                val p = current[i]
                val q = current[(i + 1) % current.size]
                next += PdfPoint(p.x * 0.75f + q.x * 0.25f, p.y * 0.75f + q.y * 0.25f)
                next += PdfPoint(p.x * 0.25f + q.x * 0.75f, p.y * 0.25f + q.y * 0.75f)
            }
            current = next
        }
        return current
    }

    /**
     * Finds where the signature is in a whole photo, as (left, top, right, bottom) fractions,
     * ignoring paper edges and shadows that reach the border. Null if nothing looks like ink.
     */
    fun detectSignatureArea(gray: IntArray, width: Int, height: Int, sensitivity: Float): FloatArray? {
        val mask = removeSpecks(threshold(gray, width, height, sensitivity), max(6, width * height / 5_000), dropBorderBlobs = true)
        val b = inkBounds(mask) ?: return null
        val padX = (b[2] - b[0]) * 0.08f + width * 0.02f
        val padY = (b[3] - b[1]) * 0.15f + height * 0.02f
        return floatArrayOf(
            ((b[0] - padX) / width).coerceIn(0f, 1f),
            ((b[1] - padY) / height).coerceIn(0f, 1f),
            ((b[2] + padX) / width).coerceIn(0f, 1f),
            ((b[3] + padY) / height).coerceIn(0f, 1f)
        )
    }

    /** Full pipeline. Returns null when no ink is found. */
    fun vectorize(gray: IntArray, width: Int, height: Int, sensitivity: Float): SignatureShape? {
        val raw = threshold(gray, width, height, sensitivity)
        val minArea = max(6, width * height / 20_000)
        val mask = removeSpecks(raw, minArea)
        val bounds = inkBounds(mask) ?: return null
        val left = bounds[0].toFloat()
        val top = bounds[1].toFloat()
        val bw = (bounds[2] - bounds[0]).toFloat().coerceAtLeast(1f)
        val bh = (bounds[3] - bounds[1]).toFloat().coerceAtLeast(1f)
        val contours = traceContours(mask)
            .map { smooth(simplify(it, 0.9f)) }
            .filter { it.size >= 3 }
            .map { contour -> contour.map { PdfPoint((it.x - left) / bw, (it.y - top) / bh) } }
        if (contours.isEmpty()) return null
        return SignatureShape(contours = contours, filled = true, strokeWidth = 0f, aspect = bw / bh)
    }

    private fun dist2(a: PdfPoint, b: PdfPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun segmentDistance(p: PdfPoint, a: PdfPoint, b: PdfPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return sqrt(dist2(p, a))
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / sqrt(len2)
    }
}
