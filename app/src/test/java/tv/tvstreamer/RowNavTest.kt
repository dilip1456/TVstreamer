package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class RowNavTest {
    private val L = TabNav.Dir.LEFT
    private val R = TabNav.Dir.RIGHT

    @Test fun leftOnFirstCardOpensMenuOnTv() = assertEquals(RowNav.Action.TO_MENU, RowNav.onKey(0, 10, L, hasMenu = true))
    @Test fun leftOnFirstCardStaysWithoutMenu() = assertEquals(RowNav.Action.STAY, RowNav.onKey(0, 10, L, hasMenu = false))
    @Test fun rightOnLastCardNeverLeavesRow() = assertEquals(RowNav.Action.STAY, RowNav.onKey(9, 10, R, hasMenu = true))
    @Test fun middleCardsUseDefaultMove() {
        assertEquals(RowNav.Action.NONE, RowNav.onKey(3, 10, L, true))
        assertEquals(RowNav.Action.NONE, RowNav.onKey(3, 10, R, true))
    }
    @Test fun upDownAreLeftToPage() {
        assertEquals(RowNav.Action.NONE, RowNav.onKey(0, 10, TabNav.Dir.UP, true))
        assertEquals(RowNav.Action.NONE, RowNav.onKey(9, 10, TabNav.Dir.DOWN, true))
    }
    @Test fun nothingFocusedIsIgnored() = assertEquals(RowNav.Action.NONE, RowNav.onKey(-1, 10, R, true))
    @Test fun singleCardRow() {
        assertEquals(RowNav.Action.STAY, RowNav.onKey(0, 1, R, true))
        assertEquals(RowNav.Action.TO_MENU, RowNav.onKey(0, 1, L, true))
    }
}

class FocusPivotTest {
    @Test fun spanIsViewportSizedAndStartsBeforeTheRow() {
        val (a, b) = FocusPivot.span(0f, 30f, 900f)
        assertEquals(-30f, a, 0.001f)
        assertEquals(870f, b, 0.001f)
        assertEquals(900f, b - a, 0.001f)
    }

    @Test fun pivotPxIsTwelvePercentClampedToTenFifteen() {
        assertEquals(0f, FocusPivot.pivotPx(0f), 0.001f)
        assertEquals(192f, FocusPivot.pivotPx(1600f), 0.001f)
        assertEquals(160f, FocusPivot.pivotPx(1600f, 0.05f), 0.001f) // clamped to 10 %
        assertEquals(240f, FocusPivot.pivotPx(1600f, 0.30f), 0.001f) // clamped to 15 %
    }

    @Test fun scrollByMovesTheItemToThePivot() {
        assertEquals(0f, FocusPivot.scrollBy(192f, 192f), 0.001f)
        assertEquals(608f, FocusPivot.scrollBy(800f, 192f), 0.001f)
        assertEquals(-80f, FocusPivot.scrollBy(80f, 160f), 0.001f)
    }
}
