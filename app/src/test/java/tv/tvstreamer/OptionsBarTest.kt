package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class OptionsBarTest {
    @Test fun everythingFitsOnOneLineWithTheDotsButton() {
        // 3 buttons of 100 + ⋮ 40, gaps 8: 40 + 3*108 = 364
        assertEquals(3, BarFit.count(listOf(100, 100, 100), gap = 8, more = 40, max = 364))
    }

    @Test fun whatDoesNotFitGoesUnderTheDotsNeverASecondLine() {
        assertEquals(2, BarFit.count(listOf(100, 100, 100), gap = 8, more = 40, max = 363))
        assertEquals(0, BarFit.count(listOf(100, 100), gap = 8, more = 40, max = 100))
        // Order is kept: a small later button does not jump ahead of a big one that did not fit
        assertEquals(1, BarFit.count(listOf(100, 300, 20), gap = 8, more = 40, max = 300))
    }

    @Test fun barSitsAtTheBottomByDefaultAndRemembersAMove() {
        assertEquals("bottom", BarFit.pos(null))
        assertEquals("bottom", BarFit.pos(""))
        assertEquals("bottom", BarFit.pos("top"))
        assertEquals("left", BarFit.pos("left"))
        assertEquals("right", BarFit.pos("right"))
        assertEquals("left", BarFit.nextPos("bottom"))
        assertEquals("right", BarFit.nextPos("left"))
        assertEquals("bottom", BarFit.nextPos("right"))
    }
}
