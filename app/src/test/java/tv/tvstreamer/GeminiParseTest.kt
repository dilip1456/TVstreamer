package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiParseTest {
    private val lib = listOf(
        LibEntry(1, "The Matrix", "1999", "movie", "matrix"),
        LibEntry(2, "Breaking Bad", "2008", "series", "bb"),
        LibEntry(3, "Amélie", "2001", "movie", "amelie"),
        LibEntry(4, "Inception", "2010", "movie", "inception"),
    )

    @Test fun responseTextJoinsPartsAndSkipsThoughts() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"{\"ids\":"},{"text":"[1,2]}"}]}}]}"""
        assertEquals("{\"ids\":[1,2]}", GeminiParse.responseText(body))
        assertEquals("", GeminiParse.responseText("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals("", GeminiParse.responseText("not json"))
    }

    @Test fun errorMessageReadsGoogleError() {
        assertEquals("API key not valid. Please pass a valid API key.",
            GeminiParse.errorMessage("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""))
        assertEquals("", GeminiParse.errorMessage("<html>"))
    }

    @Test fun jsonInHandlesFencesProseAndArrays() {
        assertEquals(listOf(4, 1), GeminiParse.picksFromText("```json\n{\"ids\":[4,1]}\n```").first)
        assertEquals(listOf(2), GeminiParse.picksFromText("Sure! Here you go: {\"ids\":[2]} Enjoy.").first)
        assertEquals(listOf(3, 1), GeminiParse.picksFromText("[3, 1]").first)
        assertEquals(listOf(3), GeminiParse.picksFromText("Picks: [3]").first)
        assertNull(GeminiParse.jsonIn("no json here"))
    }

    @Test fun picksAcceptNumbersStringsAndObjects() {
        val o = GeminiParse.jsonIn("""{"picks":[1,"2",{"id":"4","title":"Inception"},{"title":"Amélie (2001)"},"Some Title"]}""")!!
        val (ids, titles) = GeminiParse.picks(o)
        assertEquals(listOf(1, 2, 4), ids)
        assertEquals(listOf("Amélie (2001)", "Some Title"), titles)
    }

    @Test fun assistantSearchAndSuggest() {
        val s = GeminiParse.assistant("""{"action":"search","query":"tom hanks"}""")
        assertNotNull(s); assertEquals("search", s!!.action); assertEquals("tom hanks", s.query)
        val g = GeminiParse.assistant("""```json
            {"action":"suggest","ids":[4,1],"message":"Mind-bending picks"}
            ```""")
        assertEquals("suggest", g!!.action); assertEquals(listOf(4, 1), g.ids); assertEquals("Mind-bending picks", g.message)
        // No action but ids -> suggest; search without a query and nothing else -> null
        assertEquals("suggest", GeminiParse.assistant("""{"ids":[2]}""")!!.action)
        assertEquals("suggest", GeminiParse.assistant("""{"action":"search","ids":[2]}""")!!.action)
        assertNull(GeminiParse.assistant("""{"action":"search"}"""))
        assertNull(GeminiParse.assistant("I can't help with that"))
    }

    @Test fun mapKeepsOrderDropsUnknownAndDuplicates() {
        assertEquals(listOf("inception", "matrix"), GeminiParse.map(listOf(4, 99, 1, 4), emptyList(), lib))
        // Titles matched accent / case / year insensitively; ids first
        assertEquals(listOf("bb", "amelie", "matrix"),
            GeminiParse.map(listOf(2), listOf("amelie (2001)", "THE MATRIX", "Unknown Film", "Breaking Bad"), lib))
        assertEquals(listOf("matrix"), GeminiParse.map(listOf(1, 2, 3), emptyList(), lib, limit = 1))
        assertTrue(GeminiParse.map(emptyList(), emptyList(), lib).isEmpty())
    }

    @Test fun promptsListLibraryOnly() {
        val p = GeminiParse.suggestPrompt(listOf("Inception (2010)"), emptyList(), lib)
        assertTrue(p.contains("1|The Matrix|1999|movie"))
        assertTrue(p.contains("2|Breaking Bad|2008|series"))
        assertTrue(p.contains("ONLY from the LIBRARY"))
        assertEquals("7|A/B||movie", GeminiParse.libraryLines(listOf(LibEntry(7, "A|B", "", "movie", 0))))
        val a = GeminiParse.assistantPrompt("say \"hi\"", emptyList(), lib)
        assertTrue(a.contains("say 'hi'"))
    }
}
