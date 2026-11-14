package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class LiveCollapseTest {
    private fun ch(id: String, name: String, cat: String) =
        Item("itv", id, name, "", cat, id.toIntOrNull() ?: 0, false)

    private fun section(type: String, label: String, n: Int, start: Int = 1) = LiveModes.Section(
        type, label, (start until start + n).map { i ->
            LiveModes.Row(ch("$type-$label-$i", "$label $i", type.lowercase()), true, type, label, "")
        }
    )

    @Test fun typeKeyDoesNotCollideWithSectionKey() {
        assertEquals("Sports/*", LiveCollapse.typeKey(LiveKind.SPORTS))
        assertNotEquals(LiveModes.Section(LiveKind.SPORTS, "Sports", emptyList()).key, LiveCollapse.typeKey(LiveKind.SPORTS))
    }

    @Test fun collapsingSportsHidesEverySportsSubAndChannels() {
        val sports = listOf(section(LiveKind.SPORTS, "Cricket", 3), section(LiveKind.SPORTS, "Tennis", 2))
        val news = listOf(section(LiveKind.NEWS, "Local News", 4), section(LiveKind.NEWS, "World", 1))
        val kids = listOf(section(LiveKind.KIDS, "Cartoons", 2))
        val all = sports + news + kids
        val open = LiveCollapse.layout(all, emptySet())
        // Type header + sub headers + channels for each type
        assertEquals(1 + 2 + 5 + 1 + 2 + 5 + 1 + 1 + 2, open.size)
        assertEquals(listOf("Sports/*", "Sports/Cricket", "Sports/Tennis", "News/*", "News/Local News", "News/World", "Kids/*", "Kids/Cartoons"), open.visibleHeaders)
        assertTrue(LiveCollapse.showSubs(sports))
        assertTrue(LiveCollapse.showSubs(news))
        assertTrue(LiveCollapse.showSubs(kids))

        val foldedSports = LiveCollapse.layout(all, LiveCollapse.collapseAll(listOf(LiveKind.SPORTS)))
        // Sports contributes only its type header; news + kids stay fully open
        assertEquals(1 + (1 + 2 + 5) + (1 + 1 + 2), foldedSports.size)
        assertEquals(open.typeStart[LiveKind.SPORTS], foldedSports.sectionStart["Sports/Cricket"])
        assertEquals(open.typeStart[LiveKind.SPORTS], foldedSports.sectionStart["Sports/Tennis"])
        assertFalse(foldedSports.visibleHeaders.any { it.startsWith("Sports/") && it != "Sports/*" })
        // News still fully visible under News
        assertTrue("News/Local News" in foldedSports.visibleHeaders)
        assertTrue("News/World" in foldedSports.visibleHeaders)
    }

    @Test fun collapsingNewsOrKidsHidesAllTheirSubs() {
        val all = listOf(
            section(LiveKind.NEWS, "Local News", 2),
            section(LiveKind.NEWS, "World", 3),
            section(LiveKind.KIDS, "Cartoons", 2),
            section(LiveKind.KIDS, "Educational", 1)
        )
        val newsDown = LiveCollapse.layout(all, setOf(LiveCollapse.typeKey(LiveKind.NEWS)))
        assertFalse(newsDown.visibleHeaders.any { it.startsWith("News/") && it != "News/*" })
        assertTrue("Kids/Cartoons" in newsDown.visibleHeaders)
        val kidsDown = LiveCollapse.layout(all, setOf(LiveCollapse.typeKey(LiveKind.KIDS)))
        assertFalse(kidsDown.visibleHeaders.any { it.startsWith("Kids/") && it != "Kids/*" })
        assertTrue("News/World" in kidsDown.visibleHeaders)
    }

    @Test fun collapseAllAndExpandAll() {
        val types = listOf(LiveKind.SPORTS, LiveKind.NEWS, LiveKind.KIDS)
        val all = LiveCollapse.collapseAll(types)
        assertEquals(setOf("Sports/*", "News/*", "Kids/*"), all)
        assertEquals(emptySet<String>(), LiveCollapse.expandAll())
        val sections = listOf(section(LiveKind.SPORTS, "Cricket", 3), section(LiveKind.NEWS, "World", 2), section(LiveKind.KIDS, "Cartoons", 1))
        val folded = LiveCollapse.layout(sections, all)
        assertEquals(3, folded.size) // one type header each
        assertEquals(listOf("Sports/*", "News/*", "Kids/*"), folded.visibleHeaders)
        val open = LiveCollapse.layout(sections, LiveCollapse.expandAll())
        assertEquals(3 + 3 + 3 + 2 + 1, open.size) // 3 type headers + 3 sub headers + 3+2+1 channels
    }

    @Test fun subCollapseStillWorksWhenParentIsOpen() {
        val all = listOf(section(LiveKind.SPORTS, "Cricket", 3), section(LiveKind.SPORTS, "Tennis", 2))
        val lay = LiveCollapse.layout(all, setOf("Sports/Cricket"))
        assertTrue("Sports/Cricket" in lay.visibleHeaders)
        assertTrue("Sports/Tennis" in lay.visibleHeaders)
        assertEquals(lay.sectionStart["Sports/Tennis"]!! - lay.sectionStart["Sports/Cricket"]!!, 1)
    }

    @Test fun loneTypeSectionUsesOnlyTheTypeHeader() {
        val rel = listOf(section(LiveKind.RELIGIOUS, "Religious", 4))
        assertFalse(LiveCollapse.showSubs(rel))
        val open = LiveCollapse.layout(rel, emptySet())
        assertEquals(listOf("Religious/*"), open.visibleHeaders)
        assertEquals(5, open.size) // type header + 4 channels
        val closed = LiveCollapse.layout(rel, setOf(LiveCollapse.typeKey(LiveKind.RELIGIOUS)))
        assertEquals(1, closed.size)
    }
}
