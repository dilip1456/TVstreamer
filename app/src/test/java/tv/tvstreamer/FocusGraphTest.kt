package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.tvstreamer.FocusGraph.At
import tv.tvstreamer.FocusGraph.Go

class FocusGraphTest {
    private val L = TabNav.Dir.LEFT
    private val R = TabNav.Dir.RIGHT
    private val U = TabNav.Dir.UP
    private val D = TabNav.Dir.DOWN

    @Test fun leftAtEveryLeftEdgeOpensTheSideMenuOnTv() {
        for (at in listOf(At.TOP_FIRST, At.HERO, At.HERO_PLAY, At.ROW_FIRST, At.GRID_FIRST_COL, At.PAGE)) {
            assertEquals(at.name, Go.SIDE_MENU, FocusGraph.dest(at, L, tv = true))
        }
        assertEquals(Go.DEFAULT, FocusGraph.dest(At.ROW_OTHER, L, tv = true))
        assertEquals(Go.DEFAULT, FocusGraph.dest(At.HERO_OTHER_BTN, L, tv = true))
        assertEquals(Go.DEFAULT, FocusGraph.dest(At.GRID_OTHER, L, tv = true))
        assertEquals(Go.DEFAULT, FocusGraph.dest(At.TOP_OTHER, L, tv = true))
        // Phone: no side menu
        assertEquals(Go.DEFAULT, FocusGraph.dest(At.ROW_FIRST, L, tv = false))
        assertEquals(Go.DEFAULT, FocusGraph.dest(At.HERO, L, tv = false))
    }

    @Test fun upFromTheHeroGoesToTheTopMenu() {
        assertEquals(Go.TOP_MENU, FocusGraph.dest(At.HERO, U, tv = true))
        assertEquals(Go.TOP_MENU, FocusGraph.dest(At.HERO_PLAY, U, tv = true))
        assertEquals(Go.TOP_MENU, FocusGraph.dest(At.HERO_OTHER_BTN, U, tv = true))
        assertEquals(Go.HERO_PLAY, FocusGraph.dest(At.HERO, D, tv = true))
    }

    @Test fun rightOrBackClosesTheOpenSideMenuAndLeftStays() {
        assertEquals(Go.CLOSE_MENU, FocusGraph.dest(At.SIDE_MENU, R, tv = true, sideOpen = true))
        assertEquals(Go.CLOSE_MENU, FocusGraph.dest(At.SIDE_MENU, TabNav.Dir.OTHER, tv = true, sideOpen = true))
        assertEquals(Go.STAY, FocusGraph.dest(At.SIDE_MENU, L, tv = true, sideOpen = true))
        assertEquals(Go.STAY, FocusGraph.dest(At.SIDE_MENU, U, tv = true, sideOpen = true))
        assertEquals(Go.STAY, FocusGraph.dest(At.SIDE_MENU, D, tv = true, sideOpen = true))
        // While the menu is open, page Left does not layer another action on top
        assertEquals(Go.STAY, FocusGraph.dest(At.ROW_FIRST, L, tv = true, sideOpen = true))
    }

    @Test fun gridLeftColumnOpensTheMenuOnTvOnly() {
        assertTrue(GridNav.leftOpensMenu(0, tv = true))
        assertFalse(GridNav.leftOpensMenu(1, tv = true))
        assertFalse(GridNav.leftOpensMenu(0, tv = false))
        // Phone still wraps to the previous row when the menu does not take Left
        assertEquals(Integer.valueOf(3), GridNav.left(4, 0, navTookIt = false))
        assertEquals(null, GridNav.left(4, 0, navTookIt = true))
    }

    @Test fun longPressMenuGoesToAppHomeByDefault() {
        assertEquals(Act.HOME, Keys.defaultLong[android.view.KeyEvent.KEYCODE_MENU])
        assertEquals(Act.DEFAULT, Keys.defaultShort.getOrElse(android.view.KeyEvent.KEYCODE_MENU) { Act.DEFAULT })
    }
}
