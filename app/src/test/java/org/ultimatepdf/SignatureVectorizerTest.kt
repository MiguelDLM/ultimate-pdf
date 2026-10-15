package org.ultimatepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ultimatepdf.core.model.PdfPoint
import org.ultimatepdf.core.model.SignatureShape
import org.ultimatepdf.core.signature.SignatureVectorizer
import kotlin.math.hypot

class SignatureVectorizerTest {

    /** Paper with a light-to-dark shadow gradient, a dark ring, and a one-pixel speck. */
    private fun photo(width: Int = 300, height: Int = 200): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val paper = 235 - (x * 60 / width) // shadow towards the right
        val r = hypot((x - 150).toDouble(), (y - 100).toDouble())
        when {
            r in 30.0..45.0 -> 40 // ink ring
            x == 20 && y == 20 -> 30 // dust speck
            else -> paper
        }
    }

    @Test
    fun ringBecomesOuterOutlineAndHole() {
        val shape = SignatureVectorizer.vectorize(photo(), 300, 200, sensitivity = 0.5f)
        assertNotNull(shape)
        shape!!
        // The speck is removed and the ring yields its outer boundary plus the hole.
        assertEquals(2, shape.contours.size)
        assertTrue(shape.filled)
        assertEquals(1f, shape.aspect, 0.05f)
        shape.contours.flatten().forEach { p ->
            assertTrue(p.x in -0.01f..1.01f && p.y in -0.01f..1.01f)
        }
    }

    @Test
    fun blankPaperWithShadowHasNoSignature() {
        val blank = IntArray(300 * 200) { i -> 235 - (i % 300) * 60 / 300 }
        assertNull(SignatureVectorizer.vectorize(blank, 300, 200, sensitivity = 0.5f))
    }

    @Test
    fun detectsAreaAroundInkIgnoringBorderShadow() {
        val w = 300
        val h = 200
        val gray = photo(w, h)
        // Dark table edge along the left border must not count as signature.
        for (y in 0 until h) for (x in 0 until 10) gray[y * w + x] = 20
        val area = SignatureVectorizer.detectSignatureArea(gray, w, h, 0.5f)
        assertNotNull(area)
        area!!
        assertTrue("left ${area[0]}", area[0] > 0.3f && area[0] < 0.4f)
        assertTrue("right ${area[2]}", area[2] > 0.6f && area[2] < 0.7f)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val shape = SignatureShape(
            contours = listOf(listOf(PdfPoint(0f, 0f), PdfPoint(0.5f, 1f), PdfPoint(1f, 0.25f))),
            filled = false,
            strokeWidth = 0.02f,
            aspect = 3.5f
        )
        val decoded = SignatureShape.decode(shape.encode())
        assertEquals(shape, decoded)
    }
}
