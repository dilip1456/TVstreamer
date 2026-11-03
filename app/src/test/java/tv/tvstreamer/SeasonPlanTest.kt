package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonPlanTest {
    private fun s(n: String, sid: String = n) = SeasonRef(n, sid, "", null)

    @Test fun ordersSeasonsNumerically() {
        val l = listOf(s("Season 10"), s("Season 2"), s("Extras"), s("Season 1"))
        assertEquals(listOf("Season 1", "Season 2", "Season 10", "Extras"), SeasonPlan.order(l).map { it.name })
    }

    @Test fun firstSeasonIsWantedThenLastPlayedThenFirst() {
        val l = SeasonPlan.order(listOf(s("Season 1"), s("Season 2"), s("Season 3")))
        assertEquals("Season 3", SeasonPlan.first(l, "Season 3", "Season 2")?.name)
        assertEquals("Season 2", SeasonPlan.first(l, null, "Season 2")?.name)
        assertEquals("Season 1", SeasonPlan.first(l, null, "gone")?.name)
    }

    @Test fun nextSeason() {
        val l = listOf(s("Season 2"), s("Season 1"))
        assertEquals("Season 2", SeasonPlan.next(l, "Season 1")?.name)
        assertEquals(null, SeasonPlan.next(l, "Season 2"))
    }

    @Test fun freshness() {
        assertTrue(SeasonPlan.fresh(1000, 2000, 5000))
        assertFalse(SeasonPlan.fresh(1000, 7000, 5000))
        assertFalse(SeasonPlan.fresh(0, 10, 5000))
    }

    @Test fun seasonRefRoundTrip() {
        val r = SeasonRef("Season 1", "77", "/c", listOf(Item("vod", "e1", "Episode 1", "/m", "1", 1, false, season = "Season 1")), "img")
        val back = SeasonRef.from(r.toJson())
        assertEquals(r.name, back.name); assertEquals(r.sid, back.sid); assertEquals(1, back.eps?.size)
        assertEquals(null, SeasonRef.from(s("Season 2").toJson()).eps)
    }
}
