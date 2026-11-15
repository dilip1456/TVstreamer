package tv.tvstreamer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchIndexTest {
    private fun s(q: String, title: String, extra: List<String> = emptyList(), year: String = "") =
        SearchIndex.score(SearchIndex.query(q), title, extra, year)
    private fun vod(id: String, name: String, year: String = "", cast: String = "", director: String = "") =
        Item("vod", id, name, "/media/$id.mpg", "1", 0, false, year = year, cast = cast, director = director)

    @Test fun foldsCaseAccentsPunctuation() {
        assertEquals("amelie", SearchIndex.fold("Amélie"))
        assertEquals("fast and furious", SearchIndex.fold("Fast & Furious!"))
        assertEquals("spider man no way home", SearchIndex.fold("Spider-Man: No Way Home"))
        assertEquals("strasse", SearchIndex.fold("Straße"))
        assertEquals(listOf("wall", "e"), SearchIndex.words("WALL·E"))
    }

    @Test fun queryKeepsYearAsFilterAndDropsNoise() {
        val q = SearchIndex.query("The Batman movie 2022")
        assertEquals(listOf("batman"), q.words)
        assertEquals("2022", q.year)
        // Only noise words: kept, so "the" alone still searches
        assertEquals(listOf("the"), SearchIndex.query("the").words)
    }

    @Test fun matchesCaseAccentsAndPunctuation() {
        assertTrue(s("amelie", "Amélie") > 0)
        assertTrue(s("SPIDER MAN", "Spider-Man: No Way Home") > 0)
        assertTrue(s("fast & furious", "Fast and Furious 7") > 0)
    }

    @Test fun prefixAndJoinedWords() {
        assertTrue(s("bat", "The Batman") > 0)
        assertTrue(s("spiderman", "Spider-Man") > 0)
        assertTrue(s("spider", "Spiderman 3") > 0)
    }

    @Test fun oneTypoOnLongerWords() {
        assertTrue(s("pathan", "Pathaan") > 0)
        assertTrue(s("intersteller", "Interstellar") > 0)
        assertTrue(s("avengrs", "Avengers: Endgame") > 0)
        // Short words need to be right: "cat" does not find "Car"
        assertEquals(0, s("cat", "Cars"))
    }

    @Test fun everyWordMustMatch() {
        assertEquals(0, s("dark knight", "The Dark Tower"))
        assertTrue(s("dark knight", "The Dark Knight Rises") > 0)
    }

    @Test fun yearFilters() {
        assertTrue(s("dune 2021", "Dune", year = "2021") > 0)
        assertEquals(0, s("dune 1984", "Dune", year = "2021"))
        // Unknown year: not filtered out
        assertTrue(s("dune 1984", "Dune") > 0)
    }

    @Test fun exactBeatsPrefixBeatsExtra() {
        val exact = s("dune", "Dune")
        val prefix = s("dune", "Dune: Part Two")
        val extraOnly = s("hindi pathaan", "Pathaan", listOf("Hindi Movies"))
        val inExtra = s("timothee", "Dune", listOf("Timothée Chalamet, Zendaya"))
        assertTrue(exact > prefix)
        assertTrue(prefix > inExtra)
        assertTrue(extraOnly > 0)
        assertTrue(inExtra > 0)
        assertEquals(0, s("zzz", "Dune", listOf("Timothée Chalamet")))
    }

    @Test fun portalDecorationsIgnoredThroughParsedTitle() {
        val i = vod("1", "|EN| The Batman (2022) 4K")
        assertTrue(SearchIndex.score(SearchIndex.query("batman 2022"), i.parsed.title, listOf(i.name), i.parsed.year) > 0)
        assertEquals(0, SearchIndex.score(SearchIndex.query("batman 1989"), i.parsed.title, listOf(i.name), i.parsed.year))
    }

    @Test fun sameTitleNeedsYearsToAgree() {
        assertTrue(SearchIndex.sameTitle("The Dark Knight", "2008", "Dark Knight", "2008"))
        assertTrue(SearchIndex.sameTitle("Jawan", "2023", "Jawan", ""))
        assertTrue(SearchIndex.sameTitle("Pathaan", "2023", "Pathaan", "2022"))
        assertFalse(SearchIndex.sameTitle("Don", "1978", "Don", "2006"))
        assertFalse(SearchIndex.sameTitle("Inception", "2010", "Interstellar", "2014"))
        assertTrue(SearchIndex.sameTitle("Jawan", "2023", "Jawan HD", "2023"))   // quality tag left in the parsed title
    }

    @Test fun personDecision() {
        assertTrue(SearchIndex.personLikely(5, 0, trusted = false))
        assertFalse(SearchIndex.personLikely(1, 0, trusted = false))   // one look-alike title is not enough
        assertFalse(SearchIndex.personLikely(2, 3, trusted = false))   // "pathaan": the title itself wins
        assertTrue(SearchIndex.personLikely(1, 3, trusted = true))
    }

    @Test fun creditHitsFollowCreditsOrderAndYears() {
        val items = listOf(vod("1", "Don (1978)", "1978"), vod("2", "|HI| Jawan (2023) HD"), vod("3", "Don 2006", "2006"), vod("4", "Pathaan"))
        val credits = listOf(People.Credit("Jawan", "2023", false), People.Credit("Pathaan", "2023", false), People.Credit("Don", "2006", false))
        assertEquals(listOf("2", "4", "3"), creditHits(items.map(::searchEntry), credits).map { it.id })
    }

    @Test fun personTitleLabels() {
        val m = Group("a", listOf(vod("1", "Jawan")))
        val t = Group("b", listOf(vod("2", "Show")))
        val isSeries: (Group) -> Boolean = { it.key == "b" }
        assertEquals("Movies with Shah Rukh Khan", personTitle("Shah Rukh Khan", listOf(m), isSeries, false))
        assertEquals("TV Series with X", personTitle("X", listOf(t), isSeries, false))
        assertEquals("With X", personTitle("X", listOf(m, t), isSeries, false))
        assertEquals("Directed by Christopher Nolan", personTitle("Christopher Nolan", listOf(m), isSeries, true))
    }

    @Test fun parsesCinemetaAndTmdb() {
        val c = People.parseCinemeta("""{"metas":[{"name":"Jawan","releaseInfo":"2023"},{"name":"Breaking Bad","releaseInfo":"2008-2013"},{"name":""}]}""", false)
        assertEquals(listOf(People.Credit("Jawan", "2023", false), People.Credit("Breaking Bad", "2008", false)), c)
        assertTrue(People.parseCinemeta("not json", true).isEmpty())
        val body = JSONObject("""{"cast":[{"media_type":"movie","title":"Drive","release_date":"2011-09-15"},
            {"media_type":"tv","name":"Breaking Bad","first_air_date":"2008-01-20"}],
            "crew":[{"media_type":"tv","name":"Breaking Bad","first_air_date":"2008-01-20","job":"Director"},
            {"media_type":"movie","title":"X","release_date":"2020","job":"Producer"}]}""").toString()
        val (credits, directed) = People.parseTmdbCredits(body)
        assertEquals(listOf(People.Credit("Drive", "2011", false), People.Credit("Breaking Bad", "2008", true)), credits)
        assertTrue(directed)   // directing is at least half of this tiny filmography
        assertFalse(People.parseTmdbCredits(JSONObject().put("cast", org.json.JSONArray(List(6) { JSONObject().put("media_type", "movie").put("title", "M$it") }))
            .put("crew", org.json.JSONArray().put(JSONObject().put("media_type", "movie").put("title", "D").put("job", "Director"))).toString()).second)
    }

    @Test fun castAndDirectorSurviveSaveAndLoad() {
        val i = vod("9", "Inception", "2010", cast = "Leonardo DiCaprio", director = "Christopher Nolan")
        val back = Item.from(i.toJson())
        assertEquals("Leonardo DiCaprio", back.cast)
        assertEquals("Christopher Nolan", back.director)
        // Older saved catalogs (no fields) still load
        assertEquals("", Item.from(vod("8", "Old").toJson()).cast)
        assertFalse(vod("8", "Old").toJson().has("ca"))
    }

    @Test fun titleCase() { assertEquals("Shah Rukh Khan", SearchIndex.titleCase("shah  rukh khan")) }
}
