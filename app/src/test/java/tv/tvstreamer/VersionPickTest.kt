package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionPickTest {
    private fun v(id: String) = Item("vod", id, "Film $id", "/media/$id.mpg", "1", 0, false)

    @Test fun lastPlayedWinsThenProgressThenBest() {
        val vs = listOf(v("4k"), v("hd"), v("dub"))
        assertEquals("dub", VersionPick.default(vs, vs[2].key, setOf(vs[1].key))?.id)
        assertEquals("hd", VersionPick.default(vs, null, setOf(vs[1].key))?.id)
        assertEquals("4k", VersionPick.default(vs, "gone", emptySet())?.id)
        assertEquals(null, VersionPick.default(emptyList(), null, emptySet()))
    }

    @Test fun sameLabelsAreNumbered() {
        assertEquals(listOf("HD · Hindi · 1", "4K", "HD · Hindi · 2"), VersionPick.labels(listOf("HD · Hindi", "4K", "HD · Hindi")))
    }

    @Test fun prefKeyDoesNotDependOnOrder() {
        val a = listOf(v("b"), v("a")); val b = listOf(v("a"), v("b"))
        assertEquals(VersionPick.prefKey(a, a[0]), VersionPick.prefKey(b, b[0]))
    }
}
