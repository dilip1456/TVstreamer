package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TagsTest {
    @Test fun categoryLanguages() {
        assertEquals("Hindi", Tags.language("HINDI CAM"))
        assertEquals("Hindi", Tags.language("Hindi 4K"))
        assertEquals("Hindi", Tags.language("Hindi Dubbed"))
        assertEquals("English", Tags.language("EN| ACTION"))
        assertEquals("Telugu", Tags.language("TELUGU MOVIES 2024"))
        assertEquals("Tamil", Tags.language("|TA| Tamil New"))
        assertEquals("Malayalam", Tags.language("MALAYALAM"))
        assertEquals("Punjabi", Tags.language("PUNJABI MOVIES"))
        assertEquals("Spanish", Tags.language("LATINO | PELICULAS"))
        assertEquals("", Tags.language("ACTION MOVIES"))
        assertEquals("", Tags.language("4K UHD"))
        assertEquals("", Tags.language("Netflix Series"))
    }
    @Test fun itemLanguagesAreStrict() {
        assertEquals("", Tags.language("Hindi Medium (2017)", strict = true))
        assertEquals("Hindi", Tags.language("Pathaan (2023) [Hindi] HDCAM", strict = true))
        assertEquals("Hindi", Tags.language("Avatar 2 Hindi Dubbed", strict = true))
        assertEquals("English", Tags.language("EN - The Batman (2022)", strict = true))
    }
    @Test fun quality() {
        assertEquals("CAM", Tags.quality("Pathaan 2023 HDCAM").first)
        assertEquals("CAM", Tags.quality("HINDI CAM").first)
        assertEquals("4K", Tags.quality("Hindi 4K").first)
        assertEquals("FHD", Tags.quality("Movie 1080p BluRay").first)
        assertEquals("HD", Tags.quality("Movie WEB-DL").first)
        assertEquals("TS", Tags.quality("Jawan HDTS").first)
        assertEquals("", Tags.quality("Hindi Dubbed").first)
        assertEquals("", Tags.quality("Cats (2019)").first)       // "cats" is not CAM
        assertEquals("", Tags.quality("Hits of 2020").first)
        assertTrue(Tags.isDub("Hindi Dubbed"))
        assertTrue(Tags.isDub("Avatar (Dual Audio)"))
        assertFalse(Tags.isDub("Dublin Murders"))
    }
    @Test fun titles() {
        assertEquals("The Matrix" to "1999", Tags.parse("EN - The Matrix (1999) [4K]").let { it.title to it.year })
        assertEquals("Inception" to "2010", Tags.parse("4K: Inception 2010").let { it.title to it.year })
        assertEquals("Breaking Bad", Tags.parse("|EN| Breaking Bad S01").title)
        assertEquals("Dune: Part Two" to "2024", Tags.parse("NF - Dune: Part Two (2024) FHD").let { it.title to it.year })
        assertEquals("Avatar" to "2009", Tags.parse("Avatar.2009.1080p.BluRay.x264").let { it.title to it.year })
        assertEquals("Pathaan" to "2023", Tags.parse("Pathaan (2023) Hindi HDCAM").let { it.title to it.year })
        assertEquals("Avatar The Way of Water", Tags.parse("Avatar The Way of Water Hindi Dubbed").title)
        assertEquals("Hindi Medium", Tags.parse("Hindi Medium (2017)").title)
        assertEquals("The Office", Tags.parse("The Office - Season 2").title)
        assertEquals("Spider-Man: No Way Home", Tags.parse("Spider-Man: No Way Home HEVC").title)
        assertEquals("1917" to "2019", Tags.parse("1917 (2019)").let { it.title to it.year })
    }
    @Test fun episodes() {
        assertEquals(1 to 2, Tags.episodeOf("Mirzapur S01E02"))
        assertEquals(3 to 10, Tags.episodeOf("Show 3x10"))
        assertEquals(2 to 5, Tags.episodeOf("Panchayat Season 2 Episode 5"))
        assertTrue(Tags.hasEpisodeMarker("Farzi S01 E03"))
        assertFalse(Tags.hasEpisodeMarker("Ocean's 11 (2001)"))
        assertFalse(Tags.hasEpisodeMarker("2012 (2009)"))
    }
    @Test fun similarity() {
        assertTrue(Tags.similarity("The Batman", "Batman") > 0.9)
        assertTrue(Tags.similarity("Spider-Man: No Way Home", "Spider Man No Way Home") > 0.9)
        assertTrue(Tags.similarity("Pathaan", "Pathan") > 0.6)
        assertTrue(Tags.similarity("Dune", "Dune: Part Two") < 0.6)
    }
    @Test fun dates() {
        assertTrue(Tags.parseDate("2024-05-01 12:30:00") > 0)
        assertTrue(Tags.parseDate("1714566600") > 0)
        assertEquals(0L, Tags.parseDate(""))
        assertEquals(0L, Tags.parseDate("0000-00-00 00:00:00"))
    }
}
