package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerPrefsTest {
    private fun ep(name: String, n: Int, season: String) = Item("vod", "s1:$season:$n", name, "", "c", n, false, series = n, season = season)

    @Test fun seriesChoiceWinsFieldByField() {
        val g = TrackPrefs.Choice("en", "off")
        assertEquals(g, TrackPrefs.resolve(g, null))
        assertEquals(TrackPrefs.Choice("ta", "off"), TrackPrefs.resolve(g, TrackPrefs.Choice("ta", "")))
        assertEquals(TrackPrefs.Choice("en", "en"), TrackPrefs.resolve(g, TrackPrefs.Choice("", "en")))
    }

    @Test fun perSeriesJsonRoundTripAndPartialUpdate() {
        var j = TrackPrefs.write("", "vod:1", "hi", null)
        j = TrackPrefs.write(j, "vod:1", null, "en")
        j = TrackPrefs.write(j, "vod:2", null, "off")
        assertEquals(TrackPrefs.Choice("hi", "en"), TrackPrefs.read(j, "vod:1"))
        assertEquals(TrackPrefs.Choice("", "off"), TrackPrefs.read(j, "vod:2"))
        assertNull(TrackPrefs.read(j, "vod:3"))
        assertNull(TrackPrefs.read("garbage", "vod:1"))
    }

    @Test fun perSeriesJsonIsCapped() {
        var j = ""
        for (i in 1..5) j = TrackPrefs.write(j, "vod:$i", "en", null, max = 3)
        assertNull(TrackPrefs.read(j, "vod:1"))
        assertNull(TrackPrefs.read(j, "vod:2"))
        assertEquals("en", TrackPrefs.read(j, "vod:5")?.audio)
    }

    @Test fun languageCodesCompare() {
        assertTrue(TrackPrefs.sameLang("en", "eng"))
        assertTrue(TrackPrefs.sameLang("pt-BR", "por"))
        assertFalse(TrackPrefs.sameLang("en", "fr"))
        assertFalse(TrackPrefs.sameLang("", "en"))
    }

    @Test fun nextEpisodeCrossesSeasons() {
        val eps = listOf(ep("E2", 2, "Season 2"), ep("E1", 1, "Season 1"), ep("E2", 2, "Season 1"), ep("E1", 1, "Season 2"))
        assertEquals("s1:Season 1:2", NextEpisode.after(eps, eps[1])?.id)
        assertEquals("s1:Season 2:1", NextEpisode.after(eps, eps[2])?.id)   // last of season 1 -> first of season 2
        assertNull(NextEpisode.after(eps, eps[0]))                           // finale
        assertNull(NextEpisode.after(eps, ep("X", 9, "Season 9")))           // not in the show
    }

    @Test fun countdownOnlyInTheLastTenSeconds() {
        val d = 40 * 60_000L
        assertNull(NextEpisode.countdown(d - 30_000, d))
        assertEquals(10, NextEpisode.countdown(d - 10_000, d))
        assertEquals(3, NextEpisode.countdown(d - 2_500, d))
        assertNull(NextEpisode.countdown(20_000, 30_000))   // clips under a minute never count down
        assertNull(NextEpisode.countdown(0, d))
    }

    @Test fun subtitleSizesGrow() {
        val f = TrackPrefs.SIZES.map { TrackPrefs.subFraction(it) }
        assertEquals(f.sorted(), f)
        assertEquals(0.0533f, TrackPrefs.subFraction("whatever"), 0.0001f)
    }
}
