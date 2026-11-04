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
        assertEquals("Hidden 2 channels", LiveSelect.summary(2, false, LiveModes.DEFAULT))
        assertEquals("Unhidden 1 channel", LiveSelect.summary(1, true, LiveModes.DEFAULT))
        assertEquals("Added 3 channels to Kids", LiveSelect.summary(3, true, "Kids"))
    }

    @Test fun doublePressWindow() {
        assertTrue(DoublePress.isDouble("itv:a", 1000, "itv:a", 1250))
        assertTrue(DoublePress.isDouble("itv:a", 1000, "itv:a", 1000 + DoublePress.WINDOW_MS))
        assertFalse(DoublePress.isDouble("itv:a", 1000, "itv:a", 1000 + DoublePress.WINDOW_MS + 1))
        assertFalse(DoublePress.isDouble("itv:a", 1000, "itv:b", 1100))   // a different channel
        assertFalse(DoublePress.isDouble(null, 0, "itv:a", 100))
        assertFalse(DoublePress.isDouble("itv:a", 1000, "itv:a", 900))    // clock went back
    }
}
