package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class AdultContentTest {
    @Test fun defaultListsLockedAndUnlocked() {
        assertTrue(AdultContent.listOk(locked = false, showAdult = true))
        assertTrue(AdultContent.listOk(locked = true, showAdult = true))
    }

    @Test fun turningOffHidesOnlyLocked() {
        assertTrue(AdultContent.listOk(locked = false, showAdult = false))
        assertFalse(AdultContent.listOk(locked = true, showAdult = false))
    }

    @Test fun missingPortalCatsAreKeptAsSections() {
        val cats = listOf(Cat("1", "News", false))
        val ch = listOf(
            Item("itv", "a", "Aaj Tak", "", "1", 1, false),
            Item("itv", "b", "Adult 1", "", "99", 2, true)
        )
        val all = LiveCatalog.withMissingCats(cats, ch)
        assertEquals(listOf("1", "99"), all.map { it.id })
        assertTrue(all.first { it.id == "99" }.locked)
        assertEquals(cats, LiveCatalog.withMissingCats(cats, ch.take(1)))
    }

    @Test fun defaultModeRowsIncludeEveryCategory() {
        val info = mapOf(
            "itv:a" to ChInfo(LiveKind.NEWS, "Hindi"),
            "itv:b" to ChInfo(LiveKind.ADULT, "English"),
            "itv:c" to ChInfo(LiveKind.OTHER, "Other")
        )
        val ch = listOf(
            Item("itv", "a", "Aaj Tak", "", "1", 1, false),
            Item("itv", "b", "Adult 1", "", "99", 2, true),
            Item("itv", "c", "Odd One", "", "5", 3, false)
        )
        val titles = mapOf("1" to "HINDI NEWS", "99" to "ADULT XXX", "5" to "VIP")
        val rows = LiveModes.rows(ch, LiveModes.DEFAULT, FolderSet(), emptySet(), LiveModes.View.ALL, null, info, titles)
        assertEquals(3, rows.size)
        val sections = LiveModes.sections(rows, info, titles, listOf("1", "99", "5"), null)
        val types = sections.map { it.type }.toSet()
        assertTrue(types.containsAll(setOf(LiveKind.NEWS, LiveKind.ADULT, LiveKind.OTHER)))
        assertEquals(3, sections.sumOf { it.rows.size })
    }
}
