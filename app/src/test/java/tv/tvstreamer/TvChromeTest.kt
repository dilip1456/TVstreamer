package tv.tvstreamer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvChromeTest {
    @Test fun topBarShowsAtTheStartOfTheList() {
        assertTrue(TvChrome.topBarVisible(0, 0))
        assertTrue(TvChrome.topBarVisible(0, TvChrome.HIDE_OFFSET))
        assertFalse(TvChrome.topBarVisible(0, TvChrome.HIDE_OFFSET + 1))
        assertFalse(TvChrome.topBarVisible(1, 0))
    }

    @Test fun focusKeepsTheTopBarWhileScrolled() {
        assertTrue(TvChrome.topBarVisible(3, 200, topBarFocused = true))
        // The side menu is an overlay now: it does not force the top bar to un-compose
        assertTrue(TvChrome.topBarVisible(0, 0))
    }

    @Test fun sideMenuIsAlwaysAvailableOnTv() {
        assertTrue(TvChrome.sideMenuEnabled(tv = true))
        assertFalse(TvChrome.sideMenuEnabled(tv = false))
        // Left on the first tab opens the side menu even while the top bar is showing
        assertTrue(TabNav.opensRail(0, TabNav.Dir.LEFT, hasRail = TvChrome.sideMenuEnabled(true)))
        assertFalse(TabNav.opensRail(0, TabNav.Dir.LEFT, hasRail = TvChrome.sideMenuEnabled(false)))
    }
}
