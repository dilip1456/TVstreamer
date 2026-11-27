package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class FocusStayTest {
    @Test fun nextItemAfterARemoval() {
        val keys = listOf("a", "b", "c")
        assertEquals("c", FocusStay.afterRemove(keys, "b"))
        assertEquals("b", FocusStay.afterRemove(keys, "a"))
        assertEquals("b", FocusStay.afterRemove(keys, "c"))
    }

    @Test fun lastRemainingItemOrNothing() {
        assertEquals(null, FocusStay.afterRemove(listOf("only"), "only"))
        assertEquals(null, FocusStay.afterRemove(listOf("a", "b"), "missing"))
        assertEquals(null, FocusStay.afterRemove(emptyList(), "a"))
    }

    @Test fun agreesWithTheSuggestionsRowHelper() {
        val keys = listOf("a", "b", "c")
        for (k in keys) {
            val stay = FocusStay.afterRemove(keys, k)
            val ai = AiSuggest.focusAfterRemoval(keys, k)
            assertTrue(ai is AiSuggest.FocusAfter.Item)
            assertEquals((ai as AiSuggest.FocusAfter.Item).key, stay)
        }
        assertEquals(AiSuggest.FocusAfter.Refresh, AiSuggest.focusAfterRemoval(listOf("only"), "only"))
        assertEquals(null, FocusStay.afterRemove(listOf("only"), "only"))
    }

    @Test fun bulkRemoveKeepsFocusWhenItStaysAndMovesWhenItLeaves() {
        val keys = listOf("a", "b", "c", "d")
        assertEquals("c", FocusStay.afterRemoveMany(keys, setOf("b"), "c"))
        assertEquals("c", FocusStay.afterRemoveMany(keys, setOf("b"), "b"))
        assertEquals("c", FocusStay.afterRemoveMany(keys, setOf("a", "b"), "a"))
        assertEquals("c", FocusStay.afterRemoveMany(keys, setOf("d"), "d"))
        assertEquals("b", FocusStay.afterRemoveMany(keys, setOf("c", "d"), "d"))
        assertEquals(null, FocusStay.afterRemoveMany(keys, keys.toSet(), "b"))
        assertEquals("a", FocusStay.afterRemoveMany(keys, emptySet(), "a"))
    }

    @Test fun unfavouriteInFavouritesMovesToTheNextFavourite() {
        val favs = listOf("itv:1", "itv:2", "itv:3")
        assertEquals("itv:3", FocusStay.afterRemove(favs, "itv:2"))
        assertEquals("itv:2", FocusStay.afterRemove(favs, "itv:3"))
    }
}
