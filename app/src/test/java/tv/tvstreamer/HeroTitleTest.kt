package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeroTitleTest {
    @Test fun stripsSeasonEpisodeMarkers() {
        assertEquals("Breaking Bad", HeroTitle.display("Breaking Bad S01E01"))
        assertEquals("Breaking Bad", HeroTitle.display("Breaking Bad S01E01 - Pilot", episode = true))
        assertEquals("The Office", HeroTitle.display("The Office S2 E3"))
        assertEquals("The Office", HeroTitle.display("The Office 1x01 - Pilot", episode = true))
        assertEquals("Lost", HeroTitle.display("Lost Episode 4", episode = true))
    }

    @Test fun prefersCleanMetaTitle() {
        assertEquals("Dune: Part Two", HeroTitle.display("NF - Dune Part Two (2024) FHD", metaTitle = "Dune: Part Two"))
    }

    @Test fun ignoresEpisodeLikeMetaTitle() {
        assertEquals("Breaking Bad", HeroTitle.display("Breaking Bad S01E01 - Pilot", metaTitle = "Pilot S01E01", episode = true))
    }

    @Test fun keepsMovieSubtitles() {
        assertEquals("Dune: Part Two (2024)", HeroTitle.display("NF - Dune: Part Two (2024) FHD"))
        assertEquals("Spider-Man: No Way Home", HeroTitle.display("Spider-Man: No Way Home"))
        assertEquals("The Matrix (1999)", HeroTitle.display("EN - The Matrix (1999) [4K]"))
    }

    @Test fun seriesNameNotEpisodeName() {
        assertFalse(HeroTitle.display("The Bear S01E01 - System", episode = true).contains("System"))
        assertEquals("The Bear", HeroTitle.display("The Bear S01E01 - System", episode = true))
    }
}
