package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class PagingTest {
    @Test fun stableKeepsShownOrderAndAppendsNew() {
        val prev = listOf("a", "b", "c")
        // A new page re-sorted everything ("d" newest went first) and repeated "b"
        val next = listOf("d", "b", "a", "b", "c", "e")
        assertEquals(listOf("a", "b", "c", "d", "e"), Paging.stable(prev, next) { it })
    }

    @Test fun stableDropsGoneEntries() {
        assertEquals(listOf("a", "c"), Paging.stable(listOf("a", "b", "c"), listOf("c", "a")) { it })
    }

    @Test fun appendDedupesById() {
        assertEquals(listOf(1, 2, 3, 4), Paging.append(listOf(1, 2, 3), listOf(3, 2, 4, 4)) { it.toString() })
    }

    @Test fun loadsOncePerSizeNearTheEnd() {
        assertTrue(Paging.shouldLoad(lastVisible = 10, total = 14, more = true, busy = false, askedAt = -1))
        assertFalse(Paging.shouldLoad(10, 14, true, false, askedAt = 14))   // already asked at this size
        assertFalse(Paging.shouldLoad(2, 14, true, false, -1))              // not near the end
        assertFalse(Paging.shouldLoad(10, 14, false, false, -1))            // nothing more
        assertFalse(Paging.shouldLoad(10, 14, true, true, -1))              // busy
    }

    @Test fun repeatedPagesEndPaging() {
        assertFalse(Paging.endAfterEmpty(1)); assertTrue(Paging.endAfterEmpty(2))
    }
}
