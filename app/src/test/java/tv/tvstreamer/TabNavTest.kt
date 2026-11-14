package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabNavTest {
    @Test fun rightMovesAcrossTabsAndNeverLeaves() {
        assertEquals(1, TabNav.move(0, 6, TabNav.Dir.RIGHT))
        assertEquals(2, TabNav.move(1, 6, TabNav.Dir.RIGHT))
        assertEquals(5, TabNav.move(5, 6, TabNav.Dir.RIGHT)) // last tab: stay, do not drop into content
    }

    @Test fun leftMovesAcrossTabsAndNeverLeaves() {
        assertEquals(0, TabNav.move(0, 6, TabNav.Dir.LEFT))
        assertEquals(3, TabNav.move(4, 6, TabNav.Dir.LEFT))
    }

    @Test fun onlyDownLeavesTheBar() {
        assertEquals(TabNav.CONTENT, TabNav.move(2, 6, TabNav.Dir.DOWN))
        assertEquals(2, TabNav.move(2, 6, TabNav.Dir.UP))
        assertEquals(2, TabNav.move(2, 6, TabNav.Dir.OTHER))
        assertTrue(TabNav.staysInBar(TabNav.Dir.LEFT))
        assertTrue(TabNav.staysInBar(TabNav.Dir.RIGHT))
        assertTrue(TabNav.staysInBar(TabNav.Dir.UP))
        assertFalse(TabNav.staysInBar(TabNav.Dir.DOWN))
        assertFalse(TabNav.staysInBar(TabNav.Dir.OTHER))
    }

    @Test fun dirAliases() {
        assertEquals(TabNav.Dir.RIGHT, TabNav.dir("DirectionRight"))
        assertEquals(TabNav.Dir.RIGHT, TabNav.dir("Right"))
        assertEquals(TabNav.Dir.DOWN, TabNav.dir("DirectionDown"))
        assertEquals(TabNav.Dir.OTHER, TabNav.dir("Enter"))
    }

    @Test fun emptyBar() {
        assertEquals(TabNav.CONTENT, TabNav.move(0, 0, TabNav.Dir.RIGHT))
    }
}
