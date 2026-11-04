package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class EpgCacheTest {
    private val H = 60 * 60_000L
    private val t = 100 * H

    @Test fun expiresWhenNowNextRunsOutNotAfterFixedTtl() {
        val e = Epg.Entry(t, listOf(Prog("a", t - H, t + H), Prog("b", t + H, t + 2 * H), Prog("c", t + 2 * H, t + 3 * H)))
        assertEquals(t + 2 * H, Epg.expires(e))          // when "b" ends only one programme is left
        assertTrue(Epg.fresh(e, t + 90 * 60_000L))        // well past 15 minutes
        assertFalse(Epg.fresh(e, t + 2 * H))
    }

    @Test fun boundsAndEmpty() {
        assertEquals(t + Epg.TTL_MS, Epg.expires(Epg.Entry(t, emptyList())))
        val long = Epg.Entry(t, listOf(Prog("a", t, t + 30 * H), Prog("b", t + 30 * H, t + 40 * H)))
        assertEquals(t + Epg.MAX_TTL_MS, Epg.expires(long))
        val ending = Epg.Entry(t, listOf(Prog("a", t - H, t + 60_000L)))
        assertEquals(t + Epg.MIN_TTL_MS, Epg.expires(ending))
    }

    @Test fun staleIsShownAndRefreshed() {
        val e = Epg.Entry(t - 3 * H, listOf(Prog("a", t - 4 * H, t - 2 * H), Prog("b", t - 2 * H, t + H)))
        assertFalse(Epg.fresh(e, t))
        assertTrue(Epg.stale(e, t))
        assertEquals("b", Epg.now(e.progs, t)?.title)
        assertEquals("x", Epg.pick(listOf("x"), mapOf("x" to e), emptyMap(), t))
    }

    @Test fun missingBeforeStale() {
        val stale = Epg.Entry(t - 3 * H, listOf(Prog("a", t - 4 * H, t + H)))
        assertEquals("new", Epg.pick(listOf("old", "new"), mapOf("old" to stale), emptyMap(), t))
    }

    @Test fun dayScheduleFreshness() {
        val short = Epg.Entry(t, listOf(Prog("a", t, t + H)))
        assertFalse(Epg.dayFresh(short, t))
        val day = Epg.Entry(t, (0 until 10).map { Prog("p$it", t + it * H, t + (it + 1) * H) }, day = true)
        assertTrue(Epg.dayFresh(day, t + H))
        assertEquals("b", Epg.pickDay(listOf("a", "b"), mapOf("a" to day), emptyMap(), t))
    }

    @Test fun mergeKeepsDayAndReplacesChanged() {
        val old = Epg.Entry(t - H, listOf(Prog("x", t - 3 * H, t - 2 * H), Prog("a", t, t + H), Prog("b", t + H, t + 2 * H)), day = true)
        val new = Epg.Entry(t, listOf(Prog("a2", t + 30 * 60_000L, t + H)))
        val m = Epg.merge(old, new, t)
        assertTrue(m.day)
        assertEquals(listOf("a2", "b"), m.progs.map { it.title })    // "x" ended long ago, "a" overlapped by the new answer
    }

    @Test fun diskRoundTripDropsEnded() {
        val c = mapOf("1" to Epg.Entry(t, listOf(Prog("gone", t - 2 * H, t - H), Prog("on", t - H, t + H, "d")), day = true),
            "2" to Epg.Entry(t, listOf(Prog("old", t - 2 * H, t - H))))
        val back = Epg.decode(Epg.encode(c, t), t)
        assertEquals(setOf("1"), back.keys)
        assertEquals(listOf("on"), back["1"]!!.progs.map { it.title })
        assertTrue(back["1"]!!.day)
        assertTrue(Epg.decode("not json", t).isEmpty())
    }

    @Test fun aheadAndNeighbours() {
        assertEquals(listOf(3, 4, 5, 6, 7), Epg.ahead(listOf(3, 4), 8, ahead = 6))
        assertEquals(listOf(1, 9, 2, 8), Epg.neighbours(0, 10))
        assertTrue(Epg.neighbours(0, 1).isEmpty())
        assertEquals(listOf(1), Epg.neighbours(0, 2))
    }
}
