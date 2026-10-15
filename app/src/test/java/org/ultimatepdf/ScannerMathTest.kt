package org.ultimatepdf

import android.graphics.PointF
import org.junit.Assert.*
import org.junit.Test
import org.ultimatepdf.core.scanner.DocumentDetector

class ScannerMathTest {

    @Test
    fun `test ordering quad corners clockwise`() {
        val detector = DocumentDetector()

        // Unordered arbitrary points
        val p1 = PointF(100f, 500f) // Bottom-Left
        val p2 = PointF(10f, 10f)   // Top-Left
        val p3 = PointF(450f, 20f)  // Top-Right
        val p4 = PointF(480f, 520f) // Bottom-Right

        val ordered = detector.orderCorners(listOf(p1, p2, p3, p4))

        assertEquals(p2.x, ordered.topLeft.x, 0.01f)
        assertEquals(p2.y, ordered.topLeft.y, 0.01f)

        assertEquals(p3.x, ordered.topRight.x, 0.01f)
        assertEquals(p3.y, ordered.topRight.y, 0.01f)

        assertEquals(p4.x, ordered.bottomRight.x, 0.01f)
        assertEquals(p4.y, ordered.bottomRight.y, 0.01f)

        assertEquals(p1.x, ordered.bottomLeft.x, 0.01f)
        assertEquals(p1.y, ordered.bottomLeft.y, 0.01f)
    }
}
