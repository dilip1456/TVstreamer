package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class LiveSelectTest {
    private fun ch(id: String) = Item("itv", id, "Ch $id", "", "1", 0, false)
    private val a = ch("a"); private val b = ch("b"); private val c = ch("c")

    @Test fun toggleAndSelectSection() {
        assertEquals(setOf("itv:a"), LiveSelect.toggle(emptySet(), "itv:a"))
        assertEquals(emptySet<String>(), LiveSelect.toggle(setOf("itv:a"), "itv:a"))
        val keys = listOf("itv:a", "itv:b")
        assertEquals(setOf("itv:a", "itv:b", "itv:c"), LiveSelect.selectAll(setOf("itv:c"), keys))
        assertEquals(setOf("itv:c"), LiveSelect.selectAll(setOf("itv:a", "itv:b", "itv:c"), keys))
    }

    @Test fun targetsOnlyChannelsThatChange() {
        val rows = listOf(LiveModes.Row(a, true), LiveModes.Row(b, false), LiveModes.Row(c, true))
        val sel = setOf("itv:a", "itv:b")
        assertEquals(listOf(a), LiveSelect.targets(rows, sel, on = false))
        assertEquals(listOf(b), LiveSelect.targets(rows, sel, on = true))
    }

    @Test fun bulkHideAndUnhideInDefault() {
        val f = FolderSet(emptyMap(), emptyList())
        val (_, h) = LiveSelect.apply(listOf(a, b), false, LiveModes.DEFAULT, f, setOf("x"))
        assertEquals(setOf("x", "ch:itv:a", "ch:itv:b"), h)
        val (_, h2) = LiveSelect.apply(listOf(a, c), true, LiveModes.DEFAULT, f, h)
        assertEquals(setOf("x", "ch:itv:b"), h2)
    }

    @Test fun bulkInCustomModeChangesOnlyThatMode() {
        val f = FolderSet(emptyMap(), emptyList()).create("Kids").add("Kids", listOf(a))
        val (f2, h) = LiveSelect.apply(listOf(a, b, c), true, "Kids", f, emptySet())
        assertEquals(listOf("itv:a", "itv:b", "itv:c"), f2.items("Kids").map { it.key })
        assertTrue(h.isEmpty())
        val (f3, _) = LiveSelect.apply(listOf(a, c), false, "Kids", f2, emptySet())
        assertEquals(listOf("itv:b"), f3.items("Kids").map { it.key })
    }

    @Test fun summaries() {
        assertEquals("Hid 2 channels", LiveSelect.summary(2, false, LiveModes.DEFAULT))
        assertEquals("Showed 1 channel", LiveSelect.summary(1, true, LiveModes.DEFAULT))
        assertEquals("Added 3 channels to Kids", LiveSelect.summary(3, true, "Kids"))
        assertEquals("Removed 1 channel from Kids", LiveSelect.summary(1, false, "Kids"))
    }

    @Test fun doublePressWindow() {
        assertTrue(DoublePress.isDouble("itv:a", 1000, "itv:a", 1250))
        assertTrue(DoublePress.isDouble("itv:a", 1000, "itv:a", 1000 + DoublePress.WINDOW_MS))
        assertFalse(DoublePress.isDouble("itv:a", 1000, "itv:a", 1000 + DoublePress.WINDOW_MS + 1))
        assertFalse(DoublePress.isDouble("itv:a", 1000, "itv:b", 1100))   // a different channel
        assertFalse(DoublePress.isDouble(null, 0, "itv:a", 100))
        assertFalse(DoublePress.isDouble("itv:a", 1000, "itv:a", 900))    // clock went back
    }

    @Test fun applyRowsShowsChannelHiddenOnlyByItsGroup() {
        val sports = Taxonomy.typeKey("Sports")
        val hidden = setOf(sports)
        val row = LiveModes.Row(a, on = false, type = "Sports", sub = "Football", region = "")
        val (_, h) = LiveSelect.applyRows(listOf(row), setOf(a.key), true, LiveModes.DEFAULT, FolderSet(), hidden)
        assertTrue(LiveModes.showKey(a) in h)
        assertTrue(sports in h)                 // the group stays hidden
        assertFalse("ch:${a.key}" in h)
        // Already shown: no second change
        val rowOn = row.copy(on = true)
        val (_, h2) = LiveSelect.applyRows(listOf(rowOn), setOf(a.key), true, LiveModes.DEFAULT, FolderSet(), h)
        assertEquals(h, h2)
    }

    @Test fun applyRowsHidesVisibleAndDropsShowException() {
        val sports = Taxonomy.typeKey("Sports")
        val hidden = setOf(sports, LiveModes.showKey(a))
        val row = LiveModes.Row(a, on = true, type = "Sports", sub = "Football", region = "")
        val (_, h) = LiveSelect.applyRows(listOf(row), setOf(a.key), false, LiveModes.DEFAULT, FolderSet(), hidden)
        assertFalse(LiveModes.showKey(a) in h)
        assertTrue("ch:${a.key}" in h)
        assertTrue(sports in h)
    }

    @Test fun applyRowsBulkAppliesOnlySelectedThatChange() {
        val hidden = setOf("ch:${b.key}")
        val rows = listOf(
            LiveModes.Row(a, true, "Sports", "Football", ""),
            LiveModes.Row(b, false, "Sports", "Football", ""),
            LiveModes.Row(c, true, "News", "Local News", "")
        )
        val (_, h) = LiveSelect.applyRows(rows, setOf(a.key, b.key), true, LiveModes.DEFAULT, FolderSet(), hidden)
        assertFalse("ch:${b.key}" in h)
        assertFalse("ch:${a.key}" in h)
        assertFalse("ch:${c.key}" in h)         // c was not selected
    }

    @Test fun selectScopePrefersSectionThenTypeThenView() {
        val ra = LiveModes.Row(a, true, LiveKind.SPORTS, "Football", "")
        val rb = LiveModes.Row(b, true, LiveKind.SPORTS, "Cricket", "")
        val rc = LiveModes.Row(c, true, LiveKind.NEWS, "Local News", "")
        val sportsFoot = LiveModes.Section(LiveKind.SPORTS, "Football", listOf(ra))
        val sportsCric = LiveModes.Section(LiveKind.SPORTS, "Cricket", listOf(rb))
        val news = LiveModes.Section(LiveKind.NEWS, "Local News", listOf(rc))
        val sections = listOf(sportsFoot, sportsCric, news)
        val view = listOf(a.key, b.key, c.key)
        assertEquals(listOf(a.key), LiveSelect.selectScope(sportsFoot.key, sections, view))
        assertEquals(listOf(a.key, b.key), LiveSelect.selectScope(LiveCollapse.typeKey(LiveKind.SPORTS), sections, view))
        assertEquals(view, LiveSelect.selectScope("", sections, view))
        assertEquals(view, LiveSelect.selectScope("missing", sections, view))
        val scopeSec = LiveSelect.selectScope(sportsFoot.key, sections, view)
        assertEquals("Select section", LiveSelect.selectAllLabel(emptySet(), scopeSec, false))
        assertEquals("Clear section", LiveSelect.selectAllLabel(setOf(a.key), scopeSec, false))
        assertEquals("Select all", LiveSelect.selectAllLabel(emptySet(), view, true))
        assertEquals("Clear all", LiveSelect.selectAllLabel(view.toSet(), view, true))
        assertTrue(LiveSelect.scopeIsView(view, view))
        assertFalse(LiveSelect.scopeIsView(scopeSec, view))
    }
}
