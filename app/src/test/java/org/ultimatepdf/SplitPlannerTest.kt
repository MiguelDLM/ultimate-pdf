package org.ultimatepdf

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ultimatepdf.core.pdf.SplitPlan
import org.ultimatepdf.core.pdf.SplitPlanner

class SplitPlannerTest {

    @Test
    fun everyNPagesLeavesShortLastPart() {
        assertEquals(SplitPlan.Parts(listOf(0..2, 3..5, 6..6)), SplitPlanner.everyNPages(7, 3))
        assertEquals(SplitPlan.Invalid, SplitPlanner.everyNPages(7, 0))
    }

    @Test
    fun intoPartsSpreadsRemainderOverFirstParts() {
        assertEquals(SplitPlan.Parts(listOf(0..3, 4..6, 7..9)), SplitPlanner.intoParts(10, 3))
        assertEquals(SplitPlan.Parts(listOf(0..0, 1..1)), SplitPlanner.intoParts(2, 2))
        // More parts than pages is impossible.
        assertEquals(SplitPlan.Invalid, SplitPlanner.intoParts(2, 3))
    }

    @Test
    fun bySizeGroupsConsecutivePagesUnderLimit() {
        val sizes = listOf(40L, 40, 40, 90, 10, 10)
        assertEquals(SplitPlan.Parts(listOf(0..1, 2..2, 3..4, 5..5)), SplitPlanner.bySize(sizes, 100))
    }

    @Test
    fun bySizeReportsPageLargerThanLimit() {
        assertEquals(SplitPlan.PageTooLarge(1, 150), SplitPlanner.bySize(listOf(10L, 150, 10), 100))
    }

    @Test
    fun bySizeWholeDocumentFitsInOnePart() {
        assertEquals(SplitPlan.Parts(listOf(0..2)), SplitPlanner.bySize(listOf(10L, 10, 10), 1_000))
    }
}

class PageRangeParserTest {
    @Test
    fun parsesListsRangesAndOpenEnds() {
        assertEquals(listOf(0, 1, 2, 6, 8, 9), org.ultimatepdf.core.pdf.parsePageRanges("1-3, 7, 9-", 10))
        assertEquals(listOf(4, 3, 2), org.ultimatepdf.core.pdf.parsePageRanges("5-3", 10))
    }

    @Test
    fun rejectsOutOfRangeAndGarbage() {
        assertEquals(null, org.ultimatepdf.core.pdf.parsePageRanges("0", 10))
        assertEquals(null, org.ultimatepdf.core.pdf.parsePageRanges("11", 10))
        assertEquals(null, org.ultimatepdf.core.pdf.parsePageRanges("a-b", 10))
        assertEquals(null, org.ultimatepdf.core.pdf.parsePageRanges("", 10))
    }
}
