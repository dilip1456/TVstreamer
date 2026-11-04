package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V156Test {
    @Test fun cleansSpecNames() {
        assertEquals("CNN", ChannelTitle.pretty("US: CNN HD (English)"))
        assertEquals("Star Plus", ChannelTitle.pretty("IN | Star Plus Hindi"))
        assertEquals("BBC One", ChannelTitle.pretty("UK - BBC One FHD"))
        assertEquals("Geo News", ChannelTitle.pretty("PK| Geo News Urdu"))
    }
    @Test fun keepsBrandLanguageNames() {
        assertEquals("Zee Tamil", ChannelTitle.pretty("Zee Tamil HD"))
        assertEquals("Colors Kannada", ChannelTitle.pretty("IN: Colors Kannada"))
        assertEquals("Fox Sports", ChannelTitle.pretty("Fox Sports 4K | USA"))
        assertEquals("BBC News", ChannelTitle.pretty("English: BBC News"))
    }
    @Test fun langTag() {
        assertEquals("", ChannelTitle.langTag(Tags.OTHER)); assertEquals("", ChannelTitle.langTag(null))
        assertEquals("Hindi", ChannelTitle.langTag("Hindi"))
    }
    @Test fun collapseToggleAndPersist() {
        val a = LiveCollapse.toggle(emptySet(), listOf("News/World"))
        assertEquals(setOf("News/World"), a)
        assertEquals(emptySet<String>(), LiveCollapse.toggle(a, listOf("News/World")))
        assertEquals(setOf("A", "B"), LiveCollapse.toggle(setOf("A"), listOf("A", "B")))
        assertEquals(a, LiveCollapse.decode(LiveCollapse.encode(a)))
        assertEquals(emptySet<String>(), LiveCollapse.decode(null))
    }
    @Test fun startsSkipCollapsedRows() {
        val st = LiveCollapse.starts(listOf("a" to 3, "b" to 2, "c" to 1), setOf("a"))
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 4), st)
    }
    @Test fun stepSections() {
        val k = listOf("a", "b", "c")
        assertEquals("b", LiveCollapse.step(k, "a", true)); assertEquals("c", LiveCollapse.step(k, "c", true))
        assertEquals("a", LiveCollapse.step(k, "a", false)); assertEquals("a", LiveCollapse.step(k, "x", true))
        assertNull(LiveCollapse.step(emptyList(), "a", true))
    }
}
