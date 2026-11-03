package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHideTest {
    @Test fun entryRoundTrip() {
        val e = HomeHide.entry("movies", "lang:Hindi", "Hindi\tmovies")
        assertEquals("row:movies|lang:Hindi", HomeHide.id(e))
        assertEquals("Hindi movies", HomeHide.title(e))
        assertEquals("movies", HomeHide.tab(e))
    }

    @Test fun hiddenOnlyOnItsTab() {
        val h = setOf(HomeHide.entry("home", "cont", "Continue watching"), "vod:12", "ch:itv:5")
        assertTrue(HomeHide.isHidden(h, "home", "cont"))
        assertFalse(HomeHide.isHidden(h, "movies", "cont"))
        assertEquals(1, HomeHide.entries(h).size)               // categories and channels are listed elsewhere
    }
}
