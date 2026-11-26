package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class CatalogTreeTest {
    private fun g(key: String, parent: String? = null, depth: Int = 0) = CatalogGroups.Group(key, key, "Live TV", 1, parent, depth)

    /** Sports > Cricket, Football > (Football EU); News (no children) */
    private val live = listOf(
        g("grp:Sports"), g("grp:Sports/Cricket", "grp:Sports", 1), g("grp:Sports/Football", "grp:Sports", 1),
        g("grp:Sports/Football/@EU", "grp:Sports/Football", 2), g("grp:News")
    )

    private class Store(initial: Set<String>) {
        val on = initial.toMutableSet()
        fun f(k: String) = k in on
        fun apply(c: CatalogTree.Change) { on += c.on; on -= c.off.toSet() }
    }

    private val all = live.map { it.key }.toSet()

    @Test fun buildsParentsAndChildren() {
        val t = CatalogTree.of(live)
        assertEquals(listOf("grp:Sports", "grp:News"), t.roots.map { it.id })
        assertEquals(listOf("grp:Sports/Cricket", "grp:Sports/Football"), t["grp:Sports"]!!.children)
        assertTrue(t["grp:Sports/Football"]!!.isParent)
        assertFalse(t["grp:News"]!!.isParent)
    }

    @Test fun parentStateFollowsChildren() {
        val t = CatalogTree.of(live)
        assertEquals(CatalogTree.Tri.ON, CatalogTree.state(t, "grp:Sports", Store(all)::f))
        val some = Store(all - "grp:Sports/Cricket")
        assertEquals(CatalogTree.Tri.PARTIAL, CatalogTree.state(t, "grp:Sports", some::f))
        val none = Store(setOf("grp:Sports", "grp:News"))
        assertEquals(CatalogTree.Tri.OFF, CatalogTree.state(t, "grp:Sports", none::f))
        // an unticked stored parent hides its children: they read as unticked
        val parentOff = Store(all - "grp:Sports")
        assertEquals(CatalogTree.Tri.OFF, CatalogTree.state(t, "grp:Sports", parentOff::f))
        assertEquals(CatalogTree.Tri.OFF, CatalogTree.state(t, "grp:Sports/Cricket", parentOff::f))
    }

    @Test fun tickingParentTicksAllChildren() {
        val t = CatalogTree.of(live)
        val s = Store(setOf("grp:News", "grp:Sports", "grp:Sports/Cricket"))
        assertEquals(CatalogTree.Tri.PARTIAL, CatalogTree.state(t, "grp:Sports", s::f))
        s.apply(CatalogTree.toggle(t, "grp:Sports", s::f))
        assertEquals(all, s.on)
        assertEquals(CatalogTree.Tri.ON, CatalogTree.state(t, "grp:Sports", s::f))
    }

    @Test fun untickingParentClearsAllChildren() {
        val t = CatalogTree.of(live)
        val s = Store(all)
        s.apply(CatalogTree.toggle(t, "grp:Sports", s::f))
        assertEquals(setOf("grp:News"), s.on)
    }

    @Test fun lastChildUntickedUnticksParent() {
        val t = CatalogTree.of(live)
        val s = Store(setOf("grp:News", "grp:Sports", "grp:Sports/Cricket"))
        s.apply(CatalogTree.toggle(t, "grp:Sports/Cricket", s::f))
        assertFalse(s.f("grp:Sports"))
        assertEquals(CatalogTree.Tri.OFF, CatalogTree.state(t, "grp:Sports", s::f))
    }

    @Test fun unticking_deepLeafWalksUpEveryLevel() {
        val t = CatalogTree.of(live)
        val s = Store(setOf("grp:Sports", "grp:Sports/Football", "grp:Sports/Football/@EU"))
        s.apply(CatalogTree.toggle(t, "grp:Sports/Football/@EU", s::f))
        assertTrue(s.on.isEmpty())
    }

    @Test fun allChildrenTickedMakesParentTicked() {
        val t = CatalogTree.of(live)
        val s = Store(setOf("grp:Sports", "grp:Sports/Cricket"))
        s.apply(CatalogTree.toggle(t, "grp:Sports/Football", s::f))
        assertEquals(CatalogTree.Tri.ON, CatalogTree.state(t, "grp:Sports", s::f))
    }

    @Test fun tickingChildTicksParentButNotItsSiblings() {
        val t = CatalogTree.of(live)
        // Sports hidden while its children still carry their old ticks
        val s = Store(all - "grp:Sports")
        s.apply(CatalogTree.toggle(t, "grp:Sports/Cricket", s::f))
        assertTrue(s.f("grp:Sports"))
        assertTrue(s.f("grp:Sports/Cricket"))
        assertFalse(s.f("grp:Sports/Football"))
        assertEquals(CatalogTree.Tri.PARTIAL, CatalogTree.state(t, "grp:Sports", s::f))
    }

    @Test fun partialParentPressTicksEverything() {
        val t = CatalogTree.of(live)
        val s = Store(all - "grp:Sports/Football/@EU")
        assertEquals(CatalogTree.Tri.PARTIAL, CatalogTree.state(t, "grp:Sports", s::f))
        val c = CatalogTree.toggle(t, "grp:Sports", s::f)
        assertEquals(listOf("grp:Sports/Football/@EU"), c.on)
        assertTrue(c.off.isEmpty())
    }

    @Test fun languageTreeIsVirtualParent() {
        val groups = listOf(
            CatalogGroups.Group("vod:1", "Action", "Movies", 10), CatalogGroups.Group("vod:2", "Drama", "Movies", 5),
            CatalogGroups.Group("vod:3", "Kids", "Movies", 3)
        )
        val lang = mapOf("vod:1" to "Hindi", "vod:2" to "Tamil", "vod:3" to "Hindi")
        val t = CatalogTree.byLanguage(groups, { lang.getValue(it.key) }, listOf("Tamil", "Hindi"))
        assertEquals(listOf("Tamil", "Hindi"), t.roots.map { it.title })
        val hindi = t.roots.last()
        assertNull(hindi.key)
        assertEquals(13, hindi.count)
        val s = Store(setOf("vod:1"))
        assertEquals(CatalogTree.Tri.PARTIAL, CatalogTree.state(t, hindi.id, s::f))
        s.apply(CatalogTree.toggle(t, hindi.id, s::f))
        assertEquals(setOf("vod:1", "vod:3"), s.on)
        s.apply(CatalogTree.toggle(t, hindi.id, s::f))
        assertTrue(s.on.isEmpty())
        assertEquals(listOf(1, 1), CatalogTree.tally(t, t.roots.first().id, Store(setOf("vod:2"))::f).let { listOf(it.first, it.second) })
    }

    @Test fun otherLanguageGoesLast() {
        val groups = listOf(CatalogGroups.Group("vod:1", "A", "Movies", 1), CatalogGroups.Group("vod:2", "B", "Movies", 1))
        val t = CatalogTree.byLanguage(groups, { if (it.key == "vod:1") Tags.OTHER else "English" })
        assertEquals(listOf("English", Tags.OTHER), t.roots.map { it.title })
    }

    @Test fun visibleRowsFollowFolding() {
        val t = CatalogTree.of(live)
        assertEquals(listOf("grp:Sports", "grp:News"), CatalogTree.visible(t, emptySet()).map { it.id })
        assertEquals(listOf("grp:Sports", "grp:Sports/Cricket", "grp:Sports/Football", "grp:News"),
            CatalogTree.visible(t, setOf("grp:Sports")).map { it.id })
        assertEquals(5, CatalogTree.visible(t, setOf("grp:Sports", "grp:Sports/Football")).size)
        // a child that is open under a folded parent stays hidden
        assertEquals(2, CatalogTree.visible(t, setOf("grp:Sports/Football")).size)
    }

    @Test fun collapseFromChildFoldsParentAndFocusesIt() {
        val t = CatalogTree.of(live)
        val (next, focus) = CatalogTree.collapseFrom(t, "grp:Sports/Football/@EU", setOf("grp:Sports", "grp:Sports/Football"))
        assertEquals("grp:Sports/Football", focus)
        assertEquals(setOf("grp:Sports"), next)
        val (next2, focus2) = CatalogTree.collapseFrom(t, "grp:Sports/Cricket", setOf("grp:Sports", "grp:Sports/Football"))
        assertEquals("grp:Sports", focus2)
        assertTrue(next2.isEmpty())
        assertNull(CatalogTree.collapseFrom(t, "grp:News", setOf("grp:Sports")).second)
    }

    @Test fun loneRootStartsOpen() {
        val one = CatalogTree.of(live.take(4))
        assertEquals(setOf("grp:Sports"), CatalogTree.initialExpanded(one))
        assertTrue(CatalogTree.initialExpanded(CatalogTree.of(live)).isEmpty())
        assertEquals(setOf("x"), CatalogTree.toggleExpanded(emptySet(), "x"))
        assertTrue(CatalogTree.toggleExpanded(setOf("x"), "x").isEmpty())
    }

    @Test fun pickLangPrefersPickedLanguage() {
        assertEquals("Hindi", CatalogSelector.pickLang("Hindi", setOf("Hindi", "Tamil"), emptySet()))
        assertEquals("Tamil", CatalogSelector.pickLang("Hindi", setOf("Hindi", "Tamil"), setOf("Tamil")))
        assertEquals("Hindi", CatalogSelector.pickLang("Hindi", setOf("Hindi"), setOf("Tamil")))
        assertEquals(Tags.OTHER, CatalogSelector.pickLang("", emptySet(), emptySet()))
    }
}
