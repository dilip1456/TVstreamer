package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class GridNavTest {
    @Test fun rightAtRowEndWrapsToNextRow() {
        assertEquals(5, GridNav.right(index = 4, col = 4, cols = 5, total = 20))
        assertNull(GridNav.right(3, 3, 5, 20))       // not the last column: default move
        assertNull(GridNav.right(19, 4, 5, 20))      // last card: nothing after it
    }

    @Test fun leftAtRowStartGoesToNavElsePreviousRowEnd() {
        assertNull(GridNav.left(5, 0, navTookIt = true))   // the left menu took focus
        assertEquals(4, GridNav.left(5, 0, navTookIt = false))
        assertNull(GridNav.left(0, 0, false))
        assertNull(GridNav.left(6, 1, false))              // inside a row: default move
    }
}
