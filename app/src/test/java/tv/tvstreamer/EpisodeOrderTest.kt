package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeOrderTest {
    private fun ep(name: String, n: Int = 0, season: String = "") =
        Item("vod", "id:$season:$name:$n", name, "", "c", n, false, series = n, season = season)

    @Test fun episodesAscendingWithinSeason() {
        val l = listOf(ep("Episode 3", 3), ep("Episode 1", 1), ep("Episode 10", 10), ep("Episode 2", 2))
        assertEquals(listOf(1, 2, 3, 10), EpisodeOrder.sort(l).map { it.series })
    }

    @Test fun seasonsAscendingThenEpisodes() {
        val l = listOf(ep("E2", 2, "Season 2"), ep("E1", 1, "Season 10"), ep("E1", 1, "Season 2"), ep("E5", 5, "Season 1"), ep("E1", 1, "Season 1"))
        assertEquals(listOf("Season 1" to 1, "Season 1" to 5, "Season 2" to 1, "Season 2" to 2, "Season 10" to 1),
            EpisodeOrder.sort(l).map { it.season to it.series })
        assertEquals(listOf("Season 1", "Season 2", "Season 10"), EpisodeOrder.seasons(l))
    }

    @Test fun numbersFromNames() {
        val l = listOf(ep("Show S01E03"), ep("Show S01E01"), ep("Show S02E01"), ep("Show S01E02"))
        assertEquals(listOf("Show S01E01", "Show S01E02", "Show S01E03", "Show S02E01"), EpisodeOrder.sort(l).map { it.name })
    }

    @Test fun unknownsLastAndStable() {
        val l = listOf(ep("Bonus B"), ep("Episode 2", 2), ep("Bonus A"), ep("Episode 1", 1))
        assertEquals(listOf("Episode 1", "Episode 2", "Bonus B", "Bonus A"), EpisodeOrder.sort(l).map { it.name })
    }
}
