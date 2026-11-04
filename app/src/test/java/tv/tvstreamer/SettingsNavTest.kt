package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class SettingsNavTest {
    @Test fun shortSectionTitlesInOrder() {
        assertEquals(listOf("Account", "Portal", "Content", "Player", "Languages", "API keys", "Hidden", "Troubleshooting"),
            SettingsNav.ALL.map { it.title })
        assertTrue(SettingsNav.ALL.all { it.title.length <= 15 })
    }

    @Test fun backGoesUpOneLevel() {
        assertTrue(SettingsNav.back(inDetail = true))
        assertFalse(SettingsNav.back(inDetail = false))
    }
}
