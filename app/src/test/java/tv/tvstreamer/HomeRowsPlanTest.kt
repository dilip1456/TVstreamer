package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsPlanTest {
    private fun movie(id: String, locked: Boolean = false) = Item("vod", id, "Movie $id", "", "1", 0, locked, img = "http://x/$id.jpg")
    private fun series(id: String) = Item("vod", id, "Show $id", "", "1", -1, false)
    private fun ep(id: String) = Item("vod", id, "Show S01E02", "", "1", 2, false, series = 2, season = "1", pid = "s1")
    private fun ch(id: String, locked: Boolean = false) = Item("itv", id, "Channel $id", "", "1", 5, locked)

    @Test fun ordersSectionsAndSkipsLockedFinishedAndDuplicates() {
        val cont = listOf(
            HomeRowsPlan.Resume(movie("a"), 600_000, 6_000_000, 10),
            HomeRowsPlan.Resume(movie("b"), 5_900_000, 6_000_000, 9),   // finished
            HomeRowsPlan.Resume(movie("c"), 10_000, 6_000_000, 8),      // barely started
            HomeRowsPlan.Resume(movie("d", locked = true), 600_000, 6_000_000, 7),
            HomeRowsPlan.Resume(ep("e"), 600_000, 2_400_000, 6)
        )
        val out = HomeRowsPlan.build(cont, listOf(ch("1"), ch("2", locked = true)), listOf(movie("a"), series("s"), movie("z", locked = true), ch("9")))
        assertEquals(listOf("vod:a", "vod:e", "itv:1", "vod:s"), out.map { it.item.key })
        assertEquals(listOf(HomeRowsPlan.Section.CONTINUE, HomeRowsPlan.Section.CONTINUE, HomeRowsPlan.Section.LIVE, HomeRowsPlan.Section.RECOMMENDED), out.map { it.section })
        assertTrue(out[0].play); assertTrue(out[2].play); assertFalse(out[3].play)
    }

    @Test fun limitsEachSection() {
        val rec = (1..30).map { movie("r$it") }
        assertEquals(10, HomeRowsPlan.build(emptyList(), emptyList(), rec).size)
        assertEquals(3, HomeRowsPlan.build(emptyList(), emptyList(), rec, maxRec = 3).size)
    }

    @Test fun signatureIgnoresSecondsButNotMinutes() {
        val a = HomeRowsPlan.build(listOf(HomeRowsPlan.Resume(movie("a"), 600_000, 6_000_000, 1)), emptyList(), emptyList())
        val b = HomeRowsPlan.build(listOf(HomeRowsPlan.Resume(movie("a"), 610_000, 6_000_000, 2)), emptyList(), emptyList())
        val c = HomeRowsPlan.build(listOf(HomeRowsPlan.Resume(movie("a"), 700_000, 6_000_000, 3)), emptyList(), emptyList())
        assertEquals(HomeRowsPlan.signature(a), HomeRowsPlan.signature(b))
        assertTrue(HomeRowsPlan.signature(a) != HomeRowsPlan.signature(c))
    }

    @Test fun linkRoundTripsOddIds() {
        val i = Item("vod", "12:a/b c&d", "X", "", "1", 0, false)
        val l = HomeRowsPlan.parse(HomeRowsPlan.link(i, play = false))!!
        assertEquals("vod", l.kind); assertEquals("12:a/b c&d", l.id); assertFalse(l.play)
        assertTrue(HomeRowsPlan.parse(HomeRowsPlan.link(ch("7"), play = true))!!.play)
        assertNull(HomeRowsPlan.parse("https://example.com"))
        assertNull(HomeRowsPlan.parse("tvstreamer://item?k=bad&i=1"))
        assertNull(HomeRowsPlan.parse(null))
    }

    @Test fun leftText() {
        assertEquals("35 min left", HomeRowsPlan.leftText(0, 35 * 60_000L))
        assertEquals("1 h 5 min left", HomeRowsPlan.leftText(0, 65 * 60_000L))
        assertEquals("", HomeRowsPlan.leftText(10, 0))
    }
}
