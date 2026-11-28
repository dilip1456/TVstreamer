package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class ListFocusTest {
    @Test fun waitsUntilTheNextItemIsInTheComposedList() {
        val pending = "mylist" to "c"
        assertFalse(ListFocus.shouldRequest("mylist", pending, listOf("a", "b")))
        assertTrue(ListFocus.shouldRequest("mylist", pending, listOf("a", "c")))
    }

    @Test fun ignoresPendingMeantForAnotherRow() {
        assertFalse(ListFocus.shouldRequest("history", "mylist" to "b", listOf("a", "b")))
        assertFalse(ListFocus.shouldRequest("mylist", null, listOf("a", "b")))
    }

    @Test fun refreshAndSeeAllTilesAreValidTargets() {
        assertTrue(ListFocus.shouldRequest("ai", "ai" to "__refresh__", listOf("a", "__refresh__")))
        assertTrue(ListFocus.shouldRequest("row", "row" to "__seeall__", listOf("a", "__seeall__")))
    }
}
