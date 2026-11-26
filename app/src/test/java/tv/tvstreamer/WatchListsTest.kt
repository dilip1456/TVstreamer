package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchListsTest {
    private fun item(id: String, name: String = "Title $id") = Item("vod", id, name, "", "1", 0, false, year = "2001")
    private val my = WatchList(WatchLists.MY, WatchLists.MY_NAME, listOf(item("1")))

    @Test fun createRenameDeleteAndUniqueNames() {
        var (l, id) = WatchLists.create(listOf(my), "  Weekend   Movies ", now = 5)
        assertEquals("wl5", id)
        assertEquals("Weekend Movies", l.last().name)
        val (l2, id2) = WatchLists.create(l, "weekend movies", now = 5)
        assertEquals("wl6", id2)
        assertEquals("weekend movies 2", l2.last().name)
        assertNull(WatchLists.create(l2, "   ").second)
        l = WatchLists.rename(l2, id!!, "Kids")
        assertEquals("Kids", l.first { it.id == id }.name)
        assertEquals(l, WatchLists.rename(l, id, " "))
        l = WatchLists.delete(l, id)
        assertFalse(l.any { it.id == id })
        assertEquals(l, WatchLists.delete(l, WatchLists.MY))   // My List stays
    }

    @Test fun addRemoveContainsAndNoDuplicates() {
        var (l, id) = WatchLists.create(listOf(my), "Later", now = 1)
        l = WatchLists.add(l, id!!, item("2"))
        l = WatchLists.add(l, id, item("2"))
        assertEquals(1, l.first { it.id == id }.items.size)
        assertTrue(WatchLists.contains(l, "vod:2"))
        assertEquals(setOf("vod:1", "vod:2"), WatchLists.keys(l))
        assertEquals(listOf(id), WatchLists.containing(l, "vod:2").map { it.id })
        l = WatchLists.add(l, id, item("1"))
        l = WatchLists.removeEverywhere(l, "vod:1")
        assertFalse(WatchLists.contains(l, "vod:1"))
        l = WatchLists.remove(l, id, "vod:2")
        assertFalse(WatchLists.contains(l, "vod:2"))
    }

    @Test fun jsonRoundTripKeepsUserListsOnlyAndDropsBadEntries() {
        val lists = listOf(my, WatchList("wl1", "Later", listOf(item("2"), item("3"))), WatchList("wl2", "Empty"))
        val back = WatchLists.fromJson(WatchLists.toJson(lists))
        assertEquals(listOf("wl1", "wl2"), back.map { it.id })
        assertEquals(listOf("vod:2", "vod:3"), back[0].items.map { it.key })
        assertEquals(emptyList<WatchList>(), WatchLists.fromJson("not json"))
        assertEquals(emptyList<WatchList>(), WatchLists.fromJson(""))
        val bad = """[{"id":"wl1","name":"A"},{"id":"wl1","name":"Dup"},{"id":"","name":"X"},{"id":"mylist","name":"Y"},{"id":"wl3","name":"  "}]"""
        assertEquals(listOf("A"), WatchLists.fromJson(bad).map { it.name })
    }

    @Test fun defaultFallsBackToMyList() {
        val lists = listOf(my, WatchList("wl1", "Later"))
        assertEquals("wl1", WatchLists.defaultId("wl1", lists))
        assertEquals(WatchLists.MY, WatchLists.defaultId("gone", lists))
        assertEquals(WatchLists.MY, WatchLists.defaultId("", lists))
    }

    @Test fun listedTitlesLeaveSuggestions() {
        val lists = listOf(my, WatchList("wl1", "Later", listOf(item("3"))))
        assertEquals(listOf("vod:2"), WatchLists.notListed(listOf(item("1"), item("2"), item("3")), lists).map { it.key })
    }

    @Test fun labelsHaveNoBrackets() {
        val labels = listOf(WatchLists.ADD, WatchLists.REMOVE, WatchLists.OTHER, WatchLists.NEW, WatchLists.SET_DEFAULT,
            WatchLists.pickerLabel(my, true, true), AiSuggest.REFRESH, AiSuggest.WATCHED)
        labels.forEach { assertFalse(it, it.contains('(') || it.contains(')') || it.contains('[')) }
        assertEquals("✓  My List  ·  Default", WatchLists.pickerLabel(my, true, true))
        assertEquals("My List", WatchLists.pickerLabel(my, false, false))
    }
}

class AiSuggestTest {
    private fun item(id: String) = Item("vod", id, "Title $id", "", "1", 0, false, year = "2001")

    @Test fun remainingSkipsDislikedListedAndWatched() {
        val fb = AiFeedback.toggle(AiFeedbackState(), "vod:1", "Title 1 (2001)", AiVote.DISLIKED)
        val left = AiSuggest.remaining(listOf(item("1"), item("2"), item("3"), item("4"), item("4")), fb, setOf("vod:2"), setOf("vod:3"))
        assertEquals(listOf("vod:4"), left.map { it.key })
        assertTrue(AiSuggest.allReviewed(true, emptyList()))
        assertFalse(AiSuggest.allReviewed(false, emptyList()))
        assertFalse(AiSuggest.allReviewed(true, left))
    }

    @Test fun shownHistoryIsCappedAndNewestLast() {
        val (k, t) = AiSuggest.remember(listOf("vod:1", "vod:2"), listOf("A"), listOf(item("2"), item("3")), { it.name }, 3)
        assertEquals(listOf("vod:1", "vod:2", "vod:3"), k)
        assertEquals(listOf("A", "Title 2", "Title 3"), t)
        val (k2, _) = AiSuggest.remember(k, t, listOf(item("4")), { it.name }, 3)
        assertEquals(listOf("vod:2", "vod:3", "vod:4"), k2)
    }

    @Test fun categoryRefreshWaitsTenMinutes() {
        assertEquals(0L, CatRefresh.wait(0L, 1000L))
        assertEquals(CatRefresh.GAP_MS - 1000L, CatRefresh.wait(5000L, 6000L))
        assertEquals(0L, CatRefresh.wait(5000L, 5000L + CatRefresh.GAP_MS))
    }

    @Test fun refreshPromptListsAlreadySuggested() {
        val lib = listOf(LibEntry(1, "X", "2001", "movie", "x"))
        val p = GeminiParse.suggestPrompt(listOf("W"), emptyList(), lib, shown = listOf("Old Pick (2001)"))
        assertTrue(p.contains("ALREADY SUGGESTED"))
        assertTrue(p.contains("Old Pick (2001)"))
        assertFalse(GeminiParse.suggestPrompt(listOf("W"), emptyList(), lib).contains("ALREADY SUGGESTED"))
    }
}
