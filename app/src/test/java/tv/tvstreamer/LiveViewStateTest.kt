package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test
import tv.tvstreamer.LiveModes.View

class LiveViewStateTest {
    private val cats = mapOf("s" to "UK | SPORTS", "k" to "KIDS", "n" to "NZ: SPORTS")
    private fun ch(id: String, name: String, cat: String) = Item("itv", id, name, "", cat, id.toInt(), false)
    private val cricket = ch("1", "Sky Sports Cricket", "s")
    private val golf = ch("2", "Sky Sports Golf", "s")
    private val nz = ch("3", "Sky Sport Rugby", "n")
    private val toon = ch("4", "Cartoon Network", "k")
    private val all = listOf(cricket, golf, nz, toon)
    private val info = mapOf(cricket.key to ChInfo(LiveKind.SPORTS, "English"), golf.key to ChInfo(LiveKind.SPORTS, "English"),
        nz.key to ChInfo(LiveKind.SPORTS, "English"), toon.key to ChInfo(LiveKind.KIDS, "English"))
    private fun rows(hidden: Set<String>, v: View) = LiveModes.rows(all, LiveModes.DEFAULT, FolderSet(), hidden, v, null, info, cats)

    @Test fun threeViews() {
        val h = setOf("ch:${golf.key}")
        assertEquals(listOf(cricket, nz, toon), rows(h, View.ACTIVE).map { it.ch })
        assertEquals(listOf(golf), rows(h, View.HIDDEN).map { it.ch })
        val a = rows(h, View.ALL)
        assertEquals(4, a.size)
        assertFalse(a.first { it.ch == golf }.on)
        assertEquals(View.HIDDEN, View.ACTIVE.next()); assertEquals(View.ALL, View.HIDDEN.next()); assertEquals(View.ACTIVE, View.ALL.next())
    }

    @Test fun hiddenCategoriesHideTheirChannels() {
        val h = setOf(Taxonomy.typeKey(LiveKind.KIDS), Taxonomy.regionKey(LiveKind.SPORTS, "New Zealand"))
        assertEquals(listOf(cricket, golf), rows(h, View.ACTIVE).map { it.ch })
        assertEquals(setOf(nz, toon), rows(h, View.HIDDEN).map { it.ch }.toSet())
    }

    @Test fun treeHasSubsRegionsAndHiddenOnlyWhenShown() {
        val h = setOf(Taxonomy.subKey(LiveKind.SPORTS, "Golf"))
        val active = LiveModes.tree(rows(h, View.ACTIVE), h, View.ACTIVE)
        val sports = active.first { it.type == LiveKind.SPORTS }
        assertEquals(2, sports.count)
        assertEquals(listOf("Cricket", "Rugby", "UK & Ireland", "New Zealand"), sports.children.map { it.label })
        assertTrue(sports.children.last().region)
        val shown = LiveModes.tree(rows(h, View.ALL), h, View.ALL).first { it.type == LiveKind.SPORTS }
        assertTrue(shown.children.first { it.label == "Golf" }.hidden)
        // Hidden-only view: just the hidden golf channel, its group marked hidden
        val hid = LiveModes.tree(rows(h, View.HIDDEN), h, View.HIDDEN)
        assertEquals(listOf(LiveKind.SPORTS), hid.map { it.type })
    }

    @Test fun sectionsBySubCategoryAndSplitWhenLarge() {
        val s = LiveModes.sections(rows(emptySet(), View.ACTIVE), info, cats, listOf("s", "n", "k"))
        assertEquals(listOf("Sports/Cricket", "Sports/Golf", "Sports/Rugby", "Kids/Cartoons"), s.map { it.key })
        val many = (100 until 100 + LiveModes.SPLIT_AT + 5).map { ch("$it", "Some Sport $it", if (it % 2 == 0) "s" else "n") }
        val inf = many.associate { it.key to ChInfo(LiveKind.SPORTS, "English") }
        val r = LiveModes.rows(many, LiveModes.DEFAULT, FolderSet(), emptySet(), View.ACTIVE, null, inf, cats)
        val ss = LiveModes.sections(r, inf, cats, listOf("s", "n"))
        assertEquals(2, ss.size)
        assertTrue(ss.all { it.label.startsWith("Other · ") })
        assertEquals(many.size, ss.sumOf { it.rows.size })
    }
}
