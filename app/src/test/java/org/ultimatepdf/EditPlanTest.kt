package org.ultimatepdf

import org.junit.Assert.*
import org.junit.Test
import org.ultimatepdf.core.model.*

class EditPlanTest {

    @Test
    fun `test identity plan maintains original page structure`() {
        val plan = EditPlanBuilder.identity(pageCount = 5)
        assertEquals(5, plan.pages.size)
        assertEquals(5, plan.activePages.size)
        assertFalse(plan.hasChanges)
        assertEquals(0, plan.pages[0].sourceIndex)
        assertEquals(4, plan.pages[4].sourceIndex)
    }

    @Test
    fun `test rotate page accumulates degrees correctly`() {
        val ops = listOf(
            RotatePages(90, setOf(0)),
            RotatePages(90, setOf(0))
        )
        val plan = EditPlanBuilder.build(pageCount = 3, ops = ops)
        assertTrue(plan.hasChanges)
        assertEquals(180, plan.pages[0].rotationDelta)
        assertEquals(0, plan.pages[1].rotationDelta)
    }

    @Test
    fun `test delete and restore pages`() {
        val ops = listOf(
            DeletePages(setOf(1)),
            DeletePages(setOf(2))
        )
        val planWithDeletions = EditPlanBuilder.build(pageCount = 4, ops = ops)
        assertEquals(2, planWithDeletions.activePages.size)
        assertEquals(0, planWithDeletions.activePages[0].sourceIndex)
        assertEquals(3, planWithDeletions.activePages[1].sourceIndex)

        // Restore page 1
        val opsWithRestore = ops + RestorePages(setOf(1))
        val planWithRestore = EditPlanBuilder.build(pageCount = 4, ops = opsWithRestore)
        assertEquals(3, planWithRestore.activePages.size)
        assertEquals(listOf(0, 1, 3), planWithRestore.activePages.map { it.sourceIndex })
    }

    @Test
    fun `test reordering moves page correctly`() {
        val ops = listOf(
            MovePage(sourcePosition = 0, targetPosition = 2)
        )
        val plan = EditPlanBuilder.build(pageCount = 4, ops = ops)
        assertTrue(plan.hasChanges)
        // Original order was 0, 1, 2, 3 -> after moving 0 to 2 it should be 1, 2, 0, 3
        assertEquals(1, plan.pages[0].sourceIndex)
        assertEquals(2, plan.pages[1].sourceIndex)
        assertEquals(0, plan.pages[2].sourceIndex)
        assertEquals(3, plan.pages[3].sourceIndex)
    }

    @Test
    fun `test watermark and page numbers registered in plan`() {
        val ops = listOf(
            SetWatermark(WatermarkOptions(text = "TOP SECRET", angleDegrees = 45f)),
            SetPageNumbers(PageNumberOptions(format = "Page {page} / {total}"))
        )
        val plan = EditPlanBuilder.build(pageCount = 2, ops = ops)
        assertTrue(plan.hasChanges)
        assertNotNull(plan.watermark)
        assertEquals("TOP SECRET", plan.watermark?.text)
        assertNotNull(plan.pageNumbers)
        assertEquals("Page {page} / {total}", plan.pageNumbers?.format)
    }

    @Test
    fun `duplicate inserts a copy after each target, keeping rotation`() {
        val ops = listOf(RotatePages(90, setOf(1)), DuplicatePages(setOf(1, 2)))
        val plan = EditPlanBuilder.build(pageCount = 3, ops = ops)
        assertEquals(listOf(0, 1, 1, 2, 2), plan.pages.map { it.sourceIndex })
        assertEquals(90, plan.pages[2].rotationDelta)
    }

    @Test
    fun `targets are plan positions, not original page numbers`() {
        // Move page 3 (source 2) to the front; position 2 now holds source 1.
        val ops = listOf(MovePage(2, 0), DeletePages(setOf(2)))
        val plan = EditPlanBuilder.build(pageCount = 4, ops = ops)
        assertEquals(listOf(2, 0, 3), plan.activePages.map { it.sourceIndex })
    }
}
