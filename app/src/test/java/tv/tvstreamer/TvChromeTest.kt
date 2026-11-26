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

    @Test fun focusOverridesScrollPosition() {
        assertTrue(TvChrome.topBarVisible(3, 200, topBarFocused = true))
        assertFalse(TvChrome.topBarVisible(0, 0, railFocused = true))
        assertFalse(TvChrome.topBarVisible(0, 0, topBarFocused = true, railFocused = true))
    }

    @Test fun railOnlyWhenTheTopBarIsHiddenOnTv() {
        assertFalse(TvChrome.railAvailable(tv = true, topBarVisible = true))
        assertTrue(TvChrome.railAvailable(tv = true, topBarVisible = false))
        assertFalse(TvChrome.railAvailable(tv = false, topBarVisible = false))
        // Left on the first tab stays put while the top bar is showing
        assertFalse(TabNav.opensRail(0, TabNav.Dir.LEFT, hasRail = TvChrome.railAvailable(true, true)))
        assertTrue(TabNav.opensRail(0, TabNav.Dir.LEFT, hasRail = TvChrome.railAvailable(true, false)))
    }
}
