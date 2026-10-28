package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonNamesTest {
    private val show = listOf("Koffee With Karan")

    @Test fun seasonsDropShowName() {
        assertEquals("Season 1", Names.seasonLabel("Koffee With Karan Season 1", show))
        assertEquals("Season 8", Names.seasonLabel("Koffee With Karan - Season 08", show))
        assertEquals("Season 2", Names.seasonLabel("KOFFEE WITH KARAN: S02", show))
    }

    @Test fun oddSeasonForms() {
        assertEquals("Season 1", Names.seasonLabel("S01"))
        assertEquals("Season 1", Names.seasonLabel("Season 01"))
        assertEquals("Season 1", Names.seasonLabel("Season-1"))
        assertEquals("Season 3", Names.seasonLabel("Series 3"))
        assertEquals("Season 4", Names.seasonLabel("4"))
        assertEquals("Season 2", Names.seasonLabel("Temporada 2"))
        assertEquals("Specials", Names.seasonLabel("Koffee With Karan Specials", show))
    }

    @Test fun episodesDropShowAndMarkers() {
        assertEquals("Episode 2", Names.episodeLabel("Koffee With Karan S01E02", show))
        assertEquals("Episode 5", Names.episodeLabel("Koffee With Karan - Episode 5", show))
        assertEquals("3. Ranveer And Deepika", Names.episodeLabel("Koffee With Karan S08E03 - Ranveer And Deepika", show))
        assertEquals("Episode 7", Names.episodeLabel("Episode 07", show))
        assertEquals("Episode 4", Names.episodeLabel("Koffee With Karan", show, 4))
        assertEquals("Episode 9", Names.episodeLabel("9", show))
        assertEquals("2. Pilot", Names.episodeLabel("Pilot", show, 2))
        assertEquals("Episode 12", Names.episodeLabel("Koffee With Karan Season 1 Episode 12", show))
    }

    @Test fun seasonNumbers() {
        assertEquals(1, Names.seasonNumber("S01E05"))
        assertEquals(10, Names.seasonNumber("Season 10"))
        assertEquals(0, Names.seasonNumber("Specials"))
    }
}
