package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.99.5: Left on the leftmost top tab opens the side menu on TV (including while the top bar is showing). */
class V199Test {
    @Test fun leftOnFirstTabOpensTheSideMenu() {
        assertTrue(TabNav.opensRail(0, TabNav.Dir.LEFT, hasRail = true))
        assertFalse(TabNav.opensRail(1, TabNav.Dir.LEFT, hasRail = true))
        assertFalse(TabNav.opensRail(0, TabNav.Dir.RIGHT, hasRail = true))
        assertFalse(TabNav.opensRail(0, TabNav.Dir.LEFT, hasRail = false))   // phone: no side menu, Left stays put
        assertEquals(0, TabNav.move(0, 6, TabNav.Dir.LEFT))
    }

    @Test fun liveSummaryLine() {
        assertEquals("1,234 channels", LiveSummary.line(LiveModes.DEFAULT, 1234, null, LiveModes.View.ACTIVE))
        assertEquals("Sports  ·  1 channel  ·  English, Hindi  ·  Only hidden",
            LiveSummary.line("Sports", 1, setOf("Hindi", "English"), LiveModes.View.HIDDEN))
    }

    @Test fun languagesButton() {
        assertEquals("Languages", LiveSummary.langButton(null))
        assertEquals("Languages", LiveSummary.langButton(emptySet()))
        assertEquals("English, Hindi", LiveSummary.langButton(setOf("Hindi", "English")))
        assertEquals("3 languages", LiveSummary.langButton(setOf("A", "B", "C")))
    }

    @Test fun summaryHasNoBrackets() {
        val s = LiveSummary.line("Kids", 12, setOf("English"), LiveModes.View.ALL)
        assertFalse(s.contains('(') || s.contains(')') || s.contains('['))
    }
}
