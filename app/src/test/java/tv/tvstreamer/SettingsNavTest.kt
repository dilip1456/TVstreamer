package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class SettingsNavTest {
    @Test fun shortSectionTitlesInOrder() {
        assertEquals(listOf("Account", "Portal", "Library", "Playback", "API Keys", "App"),
            SettingsNav.ALL.map { it.title })
        assertTrue(SettingsNav.ALL.all { it.title.length <= 15 })
    }

    @Test fun backGoesUpOneLevel() {
        assertTrue(SettingsNav.back(inDetail = true))
        assertFalse(SettingsNav.back(inDetail = false))
    }

    @Test fun optionNamesHaveNoBracketsAndNoDuplicates() {
        val all = SettingsLabels.ALL + SettingsNav.ALL.map { it.title }
        all.forEach { n -> assertFalse("brackets in '$n'", n.any { it in "()[]{}" }) }
        assertEquals(SettingsLabels.ALL.size, SettingsLabels.ALL.toSet().size)
    }

    @Test fun optionNamesAreTitleCase() {
        val small = setOf("and", "for", "with", "to", "of", "a", "the", "in", "now")
        SettingsLabels.ALL.forEach { n ->
            n.split(' ').forEachIndexed { i, w ->
                if (i == 0 || w.lowercase() !in small) assertTrue("'$w' in '$n'", w.first().isUpperCase() || w.first().isDigit())
            }
        }
    }

    @Test fun pictureSizeCyclesAndKeepsStoredValues() {
        assertEquals("fill", SettingsLabels.nextAspect("fit"))
        assertEquals("zoom", SettingsLabels.nextAspect("fill"))
        assertEquals("fit", SettingsLabels.nextAspect("zoom"))
        assertEquals("Fit to Screen", SettingsLabels.aspectLabel("fit"))
    }
}
