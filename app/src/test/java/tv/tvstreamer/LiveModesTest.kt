package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class LiveModesTest {
    private fun ch(id: String, cat: String = "1", num: Int = id.toIntOrNull() ?: 0) = Item("itv", id, "Ch $id", "cmd$id", cat, num, false)
    private val a = ch("1"); private val b = ch("2"); private val c = ch("3", cat = "2")
    private val all = listOf(a, b, c)
    private val info = mapOf(a.key to ChInfo(LiveKind.SPORTS, "English"), b.key to ChInfo(LiveKind.NEWS, "Hindi"), c.key to ChInfo(LiveKind.NEWS, "English"))

    @Test fun defaultModeListsEverythingNotHidden() {
        val r = LiveModes.rows(all, LiveModes.DEFAULT, FolderSet(), setOf("ch:${b.key}"), false, null, info)
        assertEquals(listOf("1", "3"), r.map { it.ch.id })
    }

    @Test fun customModeShowsOnlyItsChannels() {
        val f = FolderSet().create("Kids").add("Kids", listOf(c))
        val r = LiveModes.rows(all, "Kids", f, emptySet(), false, null, info)
        assertEquals(listOf("3"), r.map { it.ch.id })
    }

    @Test fun browseAllMarksHidden() {
        val f = FolderSet().create("Kids").add("Kids", listOf(c))
        val r = LiveModes.rows(all, "Kids", f, emptySet(), true, null, info)
        assertEquals(3, r.size)
        assertEquals(listOf(false, false, true), r.map { it.on })
    }

    @Test fun languagesHideOthersButUnsortedPass() {
        val d = ch("4")
        val r = LiveModes.rows(all + d, LiveModes.DEFAULT, FolderSet(), emptySet(), false, setOf("English"), info)
        assertEquals(listOf("1", "3", "4"), r.map { it.ch.id })
        assertEquals(all.size + 1, LiveModes.rows(all + d, LiveModes.DEFAULT, FolderSet(), emptySet(), false, null, info).size)
    }

    @Test fun createEmptyOrFromCurrent() {
        val empty = LiveModes.create(FolderSet(), "Evening", null)
        assertTrue(empty.items("Evening").isEmpty())
        assertTrue("Evening" in empty.folders)
        val cur = LiveModes.create(FolderSet(), "Evening", listOf(a, b))
        assertEquals(listOf("1", "2"), cur.items("Evening").map { it.id })
    }

    @Test fun toggleHidesInDefaultAndEditsCustomMode() {
        val (_, h) = LiveModes.toggle(a, LiveModes.DEFAULT, FolderSet(), emptySet())
        assertEquals(setOf("ch:${a.key}"), h)
        val (_, h2) = LiveModes.toggle(a, LiveModes.DEFAULT, FolderSet(), h)
        assertTrue(h2.isEmpty())
        val f = FolderSet().create("M")
        val (f2, _) = LiveModes.toggle(a, "M", f, emptySet())
        assertEquals(listOf("1"), f2.items("M").map { it.id })
        val (f3, _) = LiveModes.toggle(a, "M", f2, emptySet())
        assertTrue(f3.items("M").isEmpty())
    }

    @Test fun toTopMovesChannelFirst() {
        val f = FolderSet().create("M").add("M", listOf(a, b, c))
        assertEquals(listOf("3", "1", "2"), LiveModes.toTop(c, "M", f).items("M").map { it.id })
    }

    @Test fun sectionsByTypeThenCategoryAndLocalNewsIsNews() {
        val local = ch("9", cat = "3")
        val rows = (all + local).map { LiveModes.Row(it, true) }
        val titles = mapOf("1" to "UK | SPORTS", "2" to "NEWS", "3" to "US LOCAL")
        val s = LiveModes.sections(rows, info, titles, listOf("1", "2", "3"))
        // News sorts before Sports (LiveKind.ORDER); "US LOCAL" lands in News, labelled Local News
        val news = s.filter { it.type == LiveKind.NEWS }
        assertTrue(news.any { it.label == "Local News" && it.rows.map { r -> r.ch.id } == listOf("9") })
        assertTrue(s.any { it.type == LiveKind.SPORTS && it.rows.single().ch.id == "1" })
        // no letter groups
        assertTrue(s.none { it.label.length == 1 })
    }

    @Test fun sectionsKeepModeOrder() {
        val rows = listOf(b, a).map { LiveModes.Row(it.copy(cat = "1"), true) }
        val inf = mapOf(a.key to ChInfo(LiveKind.SPORTS, "English"), b.key to ChInfo(LiveKind.SPORTS, "English"))
        val s = LiveModes.sections(rows, inf, mapOf("1" to "Sports"), listOf("1"), modeOrder = listOf(b.key, a.key))
        assertEquals(listOf("2", "1"), s.single().rows.map { it.ch.id })
        val byNum = LiveModes.sections(rows, inf, mapOf("1" to "Sports"), listOf("1"))
        assertEquals(listOf("1", "2"), byNum.single().rows.map { it.ch.id })
    }

    @Test fun languagesCounted() {
        assertEquals(listOf("English" to 2, "Hindi" to 1), LiveModes.languages(all, info))
    }
}
