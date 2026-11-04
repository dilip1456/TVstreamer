package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class V155Test {
    private fun ch(key: String, cat: String = "") = Item("itv", key, key, "cmd", cat, 0, false)

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun focusTrapAlwaysCancelsExit() {
        assertSame(androidx.compose.ui.focus.FocusRequester.Cancel, FocusTrap.exit())
    }

    @Test fun searchReturnTargets() {
        val m = mutableMapOf("cat:home" to "search", SearchReturn.itemKey("cat:home") to "row|tt1")
        assertEquals("row|tt1", SearchReturn.target(m, "cat:home"))
        assertNull(SearchReturn.target(m, "cat:movies"))
        assertEquals("itv:5", SearchReturn.liveTarget("itv:5", "itv:9"))
        assertEquals("itv:9", SearchReturn.liveTarget("", "itv:9"))
        assertEquals("", SearchReturn.liveTarget("", ""))
    }

    @Test fun leftPanelMirrorsLiveSections() {
        val list = listOf(ch("a", "1"), ch("b", "2"), ch("c", "1"), ch("d", "2"), ch("e", "1"))
        val spans = LiveOverlay.spans(listOf(LiveOverlay.title("Sports", "Cricket") to 3, LiveOverlay.title("Sports", "Sports") to 2))
        assertEquals("Sports › Cricket", spans[0].title)
        assertEquals("Sports", spans[1].title)
        val (ix, at, t) = LiveOverlay.sectionList(spans, LiveOverlay.keysOf(list), 1, list)
        assertEquals(listOf(0, 1, 2), ix); assertEquals(1, at); assertEquals("Sports › Cricket", t)
        val (ix2, at2, t2) = LiveOverlay.sectionList(spans, LiveOverlay.keysOf(list), 4, list)
        assertEquals(listOf(3, 4), ix2); assertEquals(1, at2); assertEquals("Sports", t2)
        // A different list (e.g. from Home): back to the portal category, no section title
        val other = list.reversed()
        val (ix3, _, t3) = LiveOverlay.sectionList(spans, LiveOverlay.keysOf(list), 0, other)
        assertEquals("", t3); assertEquals(listOf(0, 2, 4), ix3)
    }

    @Test fun actorChannelsAreMoviesByActor() {
        for ((cat, name) in listOf("English Actors" to "Tom Cruise", "ENGLISH ACTORS" to "Whitney Hits", "Hindi Actors" to "Shah Rukh Khan",
                "Actors | Brad Pitt" to "Brad Pitt", "USA" to "Tom Cruise Movies", "Movies" to "Actors | Brad Pitt",
                "UK" to "James Bond Collection", "VOD" to "Cinema by Actor: Al Pacino")) {
            val t = LiveKind.type(cat, name)
            assertEquals("$cat / $name", LiveKind.MOVIES, t)
            assertEquals("$cat / $name", Taxonomy.BY_ACTOR, Taxonomy.sub(t, cat, name))
        }
    }

    @Test fun ambiguousWordsDoNotPullMoviesIntoMusic() {
        assertEquals(LiveKind.MOVIES, LiveKind.type("", "Action Hits Movies"))
        assertEquals(LiveKind.MOVIES, LiveKind.type("", "Rock Movies"))
        assertEquals(LiveKind.MOVIES, LiveKind.type("Movies", "Gold Hits"))
        assertEquals(LiveKind.MUSIC, LiveKind.type("", "MTV Hits"))
        assertEquals(LiveKind.MUSIC, LiveKind.type("", "B4U Music"))
        assertEquals(LiveKind.MUSIC, LiveKind.type("", "Box Hits"))
        assertEquals(LiveKind.SPORTS, LiveKind.type("", "ESPN Radio"))
        assertEquals(LiveKind.RELIGIOUS, LiveKind.type("", "Bhakti Music"))
    }

    @Test fun personHeuristicIsStrict() {
        assertTrue(LiveKind.isPersonMovies("Tom Cruise Movies"))
        assertTrue(LiveKind.isPersonMovies("Shah Rukh Khan Films HD"))
        assertFalse(LiveKind.isPersonMovies("Action Movies"))
        assertFalse(LiveKind.isPersonMovies("Hindi Movies"))
        assertFalse(LiveKind.isPersonMovies("Zee Cinema"))
        assertFalse(LiveKind.isPersonMovies("Kishore Kumar Hits"))
        assertFalse(LiveKind.isPersonMovies("Movies"))
    }

    @Test fun auditedMovieSubs() {
        assertEquals("Classic", Taxonomy.sub(LiveKind.MOVIES, "Movies", "TCM"))
        assertNotEquals("Classic", Taxonomy.sub(LiveKind.MOVIES, "Movies", "Gold Coast Action"))   // "gold" alone no longer Classic
        assertEquals("Action", Taxonomy.sub(LiveKind.MOVIES, "Movies", "Gold Coast Action"))
        assertNotEquals("Comedy & Romance", Taxonomy.sub(LiveKind.MOVIES, "Movies", "Love Actually Cinema 2"))
        assertNotEquals("Crime & Investigation", Taxonomy.sub(LiveKind.DOCS, "Docs", "Sky ID Nature"))
        assertEquals("Nature & Wildlife", Taxonomy.sub(LiveKind.DOCS, "Docs", "Sky ID Nature"))
        assertNotEquals("Hindu", Taxonomy.sub(LiveKind.RELIGIOUS, "Religious", "Ram Christian TV"))
        assertEquals("Pop & Hits", Taxonomy.sub(LiveKind.MUSIC, "Music", "Box Hits"))
        assertNotEquals("Pop & Hits", Taxonomy.sub(LiveKind.MUSIC, "Music", "Now Jazz"))
    }
}
