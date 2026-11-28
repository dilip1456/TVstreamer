package tv.tvstreamer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvChromeTest {
    @Test fun topBarStaysVisibleAfterScroll() {
        assertTrue(TvChrome.topBarVisible(0, 0))
        assertTrue(TvChrome.topBarVisible(0, TvChrome.HIDE_OFFSET))
        assertTrue(TvChrome.topBarVisible(0, TvChrome.HIDE_OFFSET + 1))
        assertTrue(TvChrome.topBarVisible(1, 0))
        assertTrue(TvChrome.topBarVisible(3, 200))
    }

    @Test fun focusKeepsTheTopBarWhileScrolled() {
        assertTrue(TvChrome.topBarVisible(3, 200, topBarFocused = true))
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
