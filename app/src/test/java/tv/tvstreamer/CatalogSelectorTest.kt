package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class CatalogSelectorTest {
    @Test fun mergeLangsSumsColumnsAndPutsOtherLast() {
        val out = CatalogSelector.mergeLangs(
            listOf("Hindi" to 10, Tags.OTHER to 50),
            listOf("Hindi" to 5, "English" to 3),
            listOf("English" to 30)
        )
        assertEquals(listOf("English", "Hindi", Tags.OTHER), out.map { it.name })
        val hi = out.first { it.name == "Hindi" }
        assertEquals(10, hi.movies); assertEquals(5, hi.series); assertEquals(0, hi.live)
    }

    @Test fun initialIsUnionOfTabsAndNullMeansAll() {
        assertEquals(setOf("Hindi", "Tamil", "English"), CatalogSelector.initial(setOf("Hindi"), setOf("Tamil"), setOf("English")))
        assertTrue(CatalogSelector.initial(null, null, null).isEmpty())
        assertNull(CatalogSelector.stored(emptySet()))
        assertEquals(setOf("Hindi"), CatalogSelector.stored(setOf("Hindi")))
    }

    @Test fun vodGroupsFilterByLanguageAndSplitMoviesSeries() {
        val cats = listOf(Cat("1", "HINDI MOVIES", false), Cat("2", "HINDI WEB SERIES", false), Cat("3", "TAMIL MOVIES", false))
        val lang = mapOf("1" to setOf("Hindi"), "2" to setOf("Hindi"), "3" to setOf("Tamil"))
        val (m, s) = CatalogSelector.vodGroups(cats, { it.id == "2" }, { lang.getValue(it.id) }, setOf("Hindi")) { 1 }
        assertEquals(listOf("vod:1"), m.map { it.key })
        assertEquals(listOf("vod:2"), s.map { it.key })
        val (all, _) = CatalogSelector.vodGroups(cats, { it.id == "2" }, { lang.getValue(it.id) }, emptySet()) { 1 }
        assertEquals(listOf("vod:1", "vod:3"), all.map { it.key })
    }

    @Test fun shownOnlyDropsChildrenOfHiddenParents() {
        val g = listOf(
            CatalogGroups.Group("grp:Sports", "Sports", "Live TV", 3),
            CatalogGroups.Group("grp:Sports/Cricket", "Cricket", "Live TV", 2, parent = "grp:Sports", depth = 1),
            CatalogGroups.Group("grp:News", "News", "Live TV", 3),
            CatalogGroups.Group("grp:News/Local", "Local", "Live TV", 1, parent = "grp:News", depth = 1)
        )
        val off = setOf("grp:Sports", "grp:News/Local")
        assertEquals(listOf("grp:News"), CatalogSelector.shownOnly(g) { it !in off }.map { it.key })
        assertEquals(1 to 4, CatalogSelector.tally(g) { it == "grp:News" })
    }

    @Test fun languageCheck() {
        assertTrue(CatalogSelector.langOk(setOf("Hindi"), emptySet()))
        assertTrue(CatalogSelector.langOk(setOf("Hindi", "English"), setOf("English")))
        assertFalse(CatalogSelector.langOk(setOf("Tamil"), setOf("English")))
        assertEquals("All languages", CatalogSelector.langLabel(emptySet()))
        assertEquals("5 languages", CatalogSelector.langLabel(setOf("a", "b", "c", "d", "e")))
    }
}
