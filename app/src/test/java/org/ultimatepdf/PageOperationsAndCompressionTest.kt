package org.ultimatepdf

import org.junit.Assert.*
import org.junit.Test
import org.ultimatepdf.core.pdf.CompressionLevel
import org.ultimatepdf.core.pdf.CompressionResult
import org.ultimatepdf.core.pdf.PageStandard

class PageOperationsAndCompressionTest {

    @Test
    fun `test page standard dimensions`() {
        assertEquals(595.28f, PageStandard.A4.widthPt, 0.01f)
        assertEquals(841.89f, PageStandard.A4.heightPt, 0.01f)

        assertEquals(612.0f, PageStandard.LETTER.widthPt, 0.01f)
        assertEquals(792.0f, PageStandard.LETTER.heightPt, 0.01f)

        assertEquals(612.0f, PageStandard.LEGAL.widthPt, 0.01f)
        assertEquals(1008.0f, PageStandard.LEGAL.heightPt, 0.01f)
    }

    @Test
    fun `test compression result savings calculation`() {
        val result = CompressionResult(
            originalSizeBytes = 1000L,
            compressedSizeBytes = 400L,
            imagesDownsampled = 3
        )
        assertEquals(60, result.savingsPercent)
        assertEquals(3, result.imagesDownsampled)

        val noSavings = CompressionResult(
            originalSizeBytes = 1000L,
            compressedSizeBytes = 1200L,
            imagesDownsampled = 0
        )
        assertEquals(0, noSavings.savingsPercent)
    }

    @Test
    fun `test compression levels qualities`() {
        assertTrue(CompressionLevel.LOW.maxDimension > CompressionLevel.MEDIUM.maxDimension)
        assertTrue(CompressionLevel.MEDIUM.maxDimension > CompressionLevel.HIGH.maxDimension)
        assertTrue(CompressionLevel.LOW.quality > CompressionLevel.MEDIUM.quality)
        assertTrue(CompressionLevel.MEDIUM.quality > CompressionLevel.HIGH.quality)
    }
}
