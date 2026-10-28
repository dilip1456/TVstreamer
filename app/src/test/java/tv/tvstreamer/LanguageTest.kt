package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Realistic Stalker category and title names: which language a Movies / Series language filter puts them under. */
class LanguageTest {
    private fun cat(s: String) = Tags.languageOrOther(s)
    private fun title(s: String) = Tags.language(s, strict = true)

    @Test fun indianCategoriesMergeIntoOneLanguage() {
        for (c in listOf("HINDI CAM", "HINDI 4K", "HINDI HD MOVIES", "Hindi Dubbed", "IN| BOLLYWOOD", "IN | HINDI MOVIES 2024",
            "BOLLYWOOD HD", "|IN| NEW RELEASES", "[IN] Movies", "HINDI WEB SERIES", "HOLLYWOOD HINDI DUBBED"))
            assertEquals(c, "Hindi", cat(c))
        assertEquals("Tamil", cat("TAMIL MOVIES"))
        assertEquals("Tamil", cat("|TA| Tamil New"))
        assertEquals("Tamil", cat("KOLLYWOOD"))
        assertEquals("Tamil", cat("TAMIL DUBBED 4K"))
        assertEquals("Telugu", cat("TOLLYWOOD TELUGU"))
        assertEquals("Telugu", cat("TE| TELUGU CAM"))
        assertEquals("Malayalam", cat("MOLLYWOOD"))
        assertEquals("Kannada", cat("SANDALWOOD KANNADA"))
        assertEquals("Punjabi", cat("PUNJABI MOVIES"))
    }

    @Test fun dubLanguageIsTheAudioLanguage() {
        assertEquals("Hindi", cat("HOLLYWOOD HINDI DUBBED"))
        assertEquals("Tamil", cat("ENGLISH MOVIES TAMIL DUB"))
        assertEquals("Telugu", cat("Movies dubbed in Telugu"))
        assertEquals("Hindi", title("Avatar 2 Hindi Dubbed"))
        assertEquals("Tamil", title("Oppenheimer (2023) Tamil Dub"))
    }

    @Test fun otherLanguages() {
        assertEquals("English", cat("EN| ACTION"))
        assertEquals("English", cat("HOLLYWOOD"))
        assertEquals("English", cat("ENGLISH 4K"))
        assertEquals("Spanish", cat("LATINO | PELICULAS"))
        assertEquals("Arabic", cat("AR| ARABIC MOVIES"))
        assertEquals("Turkish", cat("TR| TURK DIZI"))
        assertEquals("Korean", cat("K-DRAMA"))
        assertEquals("Urdu", cat("PK| PAKISTANI DRAMA"))
    }

    @Test fun codesOnlyCountAtTagPositions() {
        // "IN", "IT", "US", "TE" are also words: only a prefix / bracket tag is a language
        assertEquals(Tags.OTHER, cat("MOVIES IN 4K"))
        assertEquals(Tags.OTHER, cat("BEST OF US"))
        assertEquals(Tags.OTHER, cat("IT CRIME"))
        assertEquals("Italian", cat("IT| FILM"))
        assertEquals("English", cat("US| NETFLIX"))
    }

    @Test fun unknownIsOther() {
        assertEquals(Tags.OTHER, cat("ACTION MOVIES"))
        assertEquals(Tags.OTHER, cat("4K UHD"))
        assertEquals(Tags.OTHER, cat("Netflix Series"))
        assertEquals(Tags.OTHER, cat("NEW RELEASES"))
        assertEquals(Tags.OTHER, Tags.languageOrOther(""))
    }

    @Test fun titlesOnlyUseTags() {
        assertEquals("", title("Hindi Medium (2017)"))
        assertEquals("", title("English Vinglish (2012)"))
        assertEquals("", title("The French Dispatch (2021)"))
        assertEquals("Hindi", title("Pathaan (2023) [Hindi] HDCAM"))
        assertEquals("Hindi", title("Pathaan Hindi"))
        assertEquals("Hindi", title("Jawan (2023) Hindi 4K"))
        assertEquals("Tamil", title("|TA| Leo (2023)"))
        assertEquals("Hindi", title("IN| Animal (2023)"))
        assertEquals("English", title("EN - The Batman (2022)"))
        assertEquals("", title("Inception (2010)"))
    }

    @Test fun failureWindowTriggersOnceThenResets() {
        val w = FailureWindow(limit = 3, window = 60_000, minGap = 1_000)
        val t0 = 10_000_000L
        assertFalse(w.hit(t0, 0)); assertFalse(w.hit(t0 + 1000, 0))
        assertTrue(w.hit(t0 + 2000, 0))
        assertEquals(0, w.count)
        // Spread out: old failures fall out of the window
        assertFalse(w.hit(t0 + 100_000, 0)); assertFalse(w.hit(t0 + 200_000, 0)); assertFalse(w.hit(t0 + 300_000, 0))
        // Just refreshed: no new refresh yet
        val v = FailureWindow(2, 60_000, 30 * 60_000L)
        assertFalse(v.hit(t0, t0 - 1000)); assertFalse(v.hit(t0 + 1, t0 - 1000))
    }
}
