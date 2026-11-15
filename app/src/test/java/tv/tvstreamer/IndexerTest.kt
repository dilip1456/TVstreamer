package tv.tvstreamer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 1.71: full title index of the selected categories, gentle and resumable. */
class IndexerTest {
    /** A portal with [pages] pages of 14 titles per category; counts every page request. */
    private class FakePortal(val pages: Map<String, Int>, val per: Int = 14) {
        val calls = mutableListOf<Pair<String, Int>>()
        fun page(cat: String, p: Int): Stalker.VodPage {
            calls += cat to p
            val n = pages[cat] ?: 0
            if (p > n) return Stalker.VodPage(emptyList(), n * per, per)
            val items = List(per) { i -> Item("vod", "$cat-${(p - 1) * per + i}", "Title $cat ${(p - 1) * per + i}", "", cat, if (cat.startsWith("s")) -1 else 0, false) }
            return Stalker.VodPage(items, n * per, per)
        }
    }

    private class Saved {
        val items = LinkedHashMap<String, Item>()
        fun save(list: List<Item>): Int { var n = 0; for (i in list) if (items.putIfAbsent(i.key, i) == null) n++; return n }
    }

    private fun indexer(next: MutableMap<String, Int> = HashMap(), ended: MutableSet<String> = HashSet(), dayMax: Int = 1000, clock: () -> Long = { 0L }) =
        CatalogIndexer(next, ended, HashMap(), dayMax, clock, { 0L })

    @Test fun indexesEverySelectedCategoryAndNothingElse() = runBlocking {
        val portal = FakePortal(mapOf("m1" to 3, "m2" to 2, "s1" to 2, "other" to 5))
        val saved = Saved()
        val ix = indexer()
        val stop = ix.run({ listOf("m1", "m2", "s1") }, { true }, { c, p -> portal.page(c, p) }, { _, l -> saved.save(l) })
        assertEquals(CatalogIndexer.Stop.DONE, stop)
        assertEquals(7, portal.calls.size)                       // 3 + 2 + 2 pages, each once
        assertTrue(portal.calls.none { it.first == "other" })    // not selected: never asked
        assertEquals((3 + 2 + 2) * 14, saved.items.size)
        // First pages of every category come first, so each shows something early
        assertEquals(setOf("m1", "m2", "s1"), portal.calls.take(3).map { it.first }.toSet())
        assertEquals(3 to 3, ix.progress(listOf("m1", "m2", "s1")))
        // Series are saved as show entries only (no season / episode requests)
        assertTrue(saved.items.values.filter { it.cat == "s1" }.all { it.isSeries })
        // Done: running again asks nothing
        ix.run({ listOf("m1", "m2", "s1") }, { true }, { c, p -> portal.page(c, p) }, { _, l -> saved.save(l) })
        assertEquals(7, portal.calls.size)
    }

    @Test fun resumesFromTheSavedCursorAfterARestart() = runBlocking {
        val portal = FakePortal(mapOf("m1" to 5))
        val saved = Saved()
        val next = HashMap<String, Int>(); val ended = HashSet<String>()
        var budget = 2
        // Session 1: two pages, then the portal "fails" (session cap / not answering)
        indexer(next, ended).run({ listOf("m1") }, { true }, { c, p -> if (budget-- > 0) portal.page(c, p) else null }, { _, l -> saved.save(l) })
        assertEquals(listOf("m1" to 1, "m1" to 2), portal.calls)
        assertEquals(3, next["m1"])
        // Session 2: a new indexer with the saved cursor continues at page 3
        indexer(HashMap(next), HashSet(ended)).run({ listOf("m1") }, { true }, { c, p -> portal.page(c, p) }, { _, l -> saved.save(l) })
        assertEquals(listOf(1, 2, 3, 4, 5), portal.calls.map { it.second })
        assertEquals(5 * 14, saved.items.size)
    }

    @Test fun pausedMeansNoPortalCalls() = runBlocking {
        val portal = FakePortal(mapOf("m1" to 3))
        val stop = indexer().run({ listOf("m1") }, { false }, { c, p -> portal.page(c, p) }, { _, _ -> 0 })
        assertEquals(CatalogIndexer.Stop.PAUSED, stop)
        assertEquals(0, portal.calls.size)
        // Pausing mid-way (e.g. a video starts) stops after the current page
        var allowed = 2
        val p2 = FakePortal(mapOf("m1" to 5))
        val s2 = indexer().run({ listOf("m1") }, { allowed-- > 0 }, { c, p -> p2.page(c, p) }, { _, _ -> 14 })
        assertEquals(CatalogIndexer.Stop.PAUSED, s2)
        assertEquals(2, p2.calls.size)
    }

    @Test fun dailyLimitSpreadsAcrossDays() = runBlocking {
        val portal = FakePortal(mapOf("m1" to 10))
        val saved = Saved()
        var now = 5 * 86_400_000L
        val ix = indexer(dayMax = 4, clock = { now })
        assertEquals(CatalogIndexer.Stop.DAY_LIMIT, ix.run({ listOf("m1") }, { true }, { c, p -> portal.page(c, p) }, { _, l -> saved.save(l) }))
        assertEquals(4, portal.calls.size)
        // Saved and restored day counter: still limited the same day
        val again = indexer(dayMax = 4, clock = { now }).also { it.decodeDay(ix.encodeDay()) }
        assertEquals(0, again.dayLeft())
        now += 86_400_000L
        assertEquals(4, again.dayLeft())
    }

    @Test fun selectionChangesStartNewAndStopDeselectedCategories() = runBlocking {
        val portal = FakePortal(mapOf("hindi" to 4, "tamil" to 4))
        val saved = Saved()
        var wanted = listOf("hindi")
        var pagesLeft = 2
        val next = HashMap<String, Int>(); val ended = HashSet<String>()
        indexer(next, ended).run({ wanted }, { pagesLeft-- > 0 }, { c, p -> portal.page(c, p) }, { _, l -> saved.save(l) })
        assertEquals(listOf("hindi" to 1, "hindi" to 2), portal.calls)
        // User swaps Hindi for Tamil: Tamil starts at page 1, Hindi is not asked again
        wanted = listOf("tamil")
        indexer(next, ended).run({ wanted }, { true }, { c, p -> portal.page(c, p) }, { _, l -> saved.save(l) })
        assertEquals(listOf("tamil" to 1, "tamil" to 2, "tamil" to 3, "tamil" to 4), portal.calls.drop(2))
        // Hindi's saved titles are kept (hidden by the language filter), and it resumes at page 3 if picked again
        assertEquals(3, next["hindi"])
    }

    @Test fun portalRepeatingPagesEndsTheCategory() = runBlocking {
        // A portal that answers page 1 for every page number (claims 50 pages)
        var calls = 0
        val one = List(14) { Item("vod", "x$it", "X $it", "", "m1", 0, false) }
        val saved = Saved()
        indexer().run({ listOf("m1") }, { true }, { _, _ -> calls++; Stalker.VodPage(one, 700, 14) }, { _, l -> saved.save(l) })
        assertEquals(3, calls)   // page 1 new, then two pages with nothing new: stop
    }
}
