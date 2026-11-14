package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeEpisodeTest {
    private fun ep(season: Int, n: Int, finished: Boolean = false) =
        Item("vod", "show:s$season:e$n", "Show S${"%02d".format(season)}E${"%02d".format(n)}", "", "c", n, false,
            series = n, season = "Season $season")

    private val eps = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1))

    @Test fun emptyList() {
        assertEquals(-1, ResumeEpisode.index(emptyList<Item>(), null))
    }

    @Test fun noHistoryPlaysFirst() {
        assertEquals(0, ResumeEpisode.index(eps, null))
        assertEquals("Play S1:E1", ResumeEpisode.playLabel(eps, 0))
    }

    @Test fun resumesCurrentEpisode() {
        assertEquals(2, ResumeEpisode.index(eps, eps[2].key to false))
        assertEquals("Play S1:E3", ResumeEpisode.playLabel(eps, 2))
    }

    @Test fun finishedAdvancesToNext() {
        assertEquals(3, ResumeEpisode.index(eps, eps[2].key to true))
        assertEquals("Play S2:E1", ResumeEpisode.playLabel(eps, 3))
    }

    @Test fun finishedButStillInContinueStays() {
        assertEquals(2, ResumeEpisode.index(eps, eps[2].key to true, setOf(eps[2].key)))
    }

    @Test fun missingLastFallsBackToContinueThenFirst() {
        assertEquals(1, ResumeEpisode.index(eps, "vod:unknown" to false, setOf(eps[1].key)))
        assertEquals(0, ResumeEpisode.index(eps, "vod:unknown" to false))
    }

    @Test fun tagsAndMovieLabel() {
        assertEquals("S1:E2", ResumeEpisode.tag(eps[1]))
        assertEquals("Resume", ResumeEpisode.movieLabel(true))
        assertEquals("Play", ResumeEpisode.movieLabel(false))
        assertEquals("Play", ResumeEpisode.playLabel(emptyList<Item>(), 0))
    }
}
