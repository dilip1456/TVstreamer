package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroOrderTest {
    private data class T(val id: String, val added: Long, val ok: Boolean = true)

    @Test fun newestByAddedDateExcludesHistory() {
        val items = listOf(
            T("old", 100), T("mid", 200), T("new", 300), T("watched", 400), T("hidden", 500, ok = false)
        )
        val got = HeroOrder.newest(items, { it.added }, { it.id }, excludeKeys = setOf("watched"), ok = { it.ok }, max = 8)
        assertEquals(listOf("new", "mid", "old"), got.map { it.id })
        assertFalse(got.any { it.id == "watched" })
        assertFalse(got.any { it.id == "hidden" })
    }

    @Test fun datesLeadItemsWithoutDatesAndCap() {
        val items = (1..10).map { T("n$it", (10 - it) * 10L) } + T("nodate", 0)
        val got = HeroOrder.newest(items, { it.added }, { it.id }, max = 5)
        assertEquals(listOf("n1", "n2", "n3", "n4", "n5"), got.map { it.id })
        assertFalse(got.any { it.id == "nodate" })
        val onlyUndated = HeroOrder.newest(listOf(T("a", 0), T("b", 0)), { it.added }, { it.id })
        assertEquals(listOf("a", "b"), onlyUndated.map { it.id })
    }

    @Test fun eachShowOnce() {
        val items = listOf(T("s", 30), T("s", 20), T("m", 10))
        assertEquals(listOf("s", "m"), HeroOrder.newest(items, { it.added }, { it.id }).map { it.id })
    }

    @Test fun historyKeysCoverEpisodesAndParent() {
        val ep = Item("vod", "ep1", "Show S01E01", "", "c", 1, false, series = 1, season = "Season 1", pid = "show1")
        val keys = HeroOrder.historyKeys(listOf(ep))
        assertTrue(keys.contains(ep.key))
        assertTrue(keys.contains("s:show1"))
        assertTrue(keys.contains("vod:show1"))
        assertEquals("s:show1", HeroOrder.showKey(ep))
        val movie = Item("vod", "m1", "Dune", "", "c", 0, false)
        assertEquals("vod:m1", HeroOrder.showKey(movie))
    }

    @Test fun heroExcludesHistoryShowWhenPickingNewest() {
        val show = Item("vod", "show1", "Show", "", "c", -1, false, added = "2024-06-01 00:00:00")
        val movie = Item("vod", "m1", "Dune", "", "c", 0, false, added = "2024-05-01 00:00:00")
        val ep = Item("vod", "ep1", "Show S01E01", "", "c", 1, false, series = 1, season = "Season 1", pid = "show1")
        val exclude = HeroOrder.historyKeys(listOf(ep))
        val got = HeroOrder.newest(listOf(show, movie), { it.addedMs }, { HeroOrder.showKey(it) }, exclude)
        assertEquals(listOf("vod:m1"), got.map { it.key })
    }

    @Test fun stepsWrap() {
        assertEquals(1, HeroOrder.next(0, 3)); assertEquals(0, HeroOrder.next(2, 3)); assertEquals(0, HeroOrder.next(5, 0))
    }

    @Test fun pausesAfterInteraction() {
        assertFalse(HeroOrder.shouldRotate(1, 100_000, 0))
        assertTrue(HeroOrder.shouldRotate(3, 100_000, 0))
        assertFalse(HeroOrder.shouldRotate(3, 100_000, 100_000 - 2_000))
        assertTrue(HeroOrder.shouldRotate(3, 100_000, 100_000 - HeroOrder.PAUSE_MS))
    }
}
