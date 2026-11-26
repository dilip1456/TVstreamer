package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class LiveCollapseGroupTest {
    private fun section(type: String, label: String, n: Int) = LiveModes.Section(
        type, label, (1..n).map { i ->
            LiveModes.Row(Item("itv", "$type-$label-$i", "$label $i", "", type.lowercase(), i, false), true, type, label, "")
        }
    )

    private val sections = listOf(
        section(LiveKind.SPORTS, "Cricket", 40), section(LiveKind.SPORTS, "Football", 30), section(LiveKind.NEWS, LiveKind.NEWS, 50)
    )

    @Test fun channelFoldsItsSubCategory() {
        val k = sections[1].key
        assertEquals(k, LiveCollapse.headerOf(sections, k))
        assertEquals(setOf(k), LiveCollapse.foldGroup(sections, emptySet(), k))
    }

    @Test fun loneBlockFoldsUnderTypeHeader() {
        val k = sections[2].key
        assertEquals(LiveCollapse.typeKey(LiveKind.NEWS), LiveCollapse.headerOf(sections, k))
        assertEquals(setOf(LiveCollapse.typeKey(LiveKind.NEWS)), LiveCollapse.foldGroup(sections, emptySet(), k))
    }

    @Test fun foldNeverUnfolds() {
        val k = sections[0].key
        assertEquals(setOf(k), LiveCollapse.foldGroup(sections, setOf(k), k))
        assertEquals(emptySet<String>(), LiveCollapse.foldGroup(sections, emptySet(), "missing"))
    }

    @Test fun headerIndexAfterFoldIsInView() {
        val k = sections[1].key
        val next = LiveCollapse.foldGroup(sections, emptySet(), k)
        val l = LiveCollapse.layout(sections, next)
        // type header 0, Cricket header 1, 40 channels, Football header 42
        assertEquals(42, LiveCollapse.headerIndex(l, k))
        assertEquals(0, LiveCollapse.headerIndex(l, LiveCollapse.typeKey(LiveKind.SPORTS)))
        // News header right after the folded Football header
        assertEquals(43, LiveCollapse.headerIndex(l, LiveCollapse.typeKey(LiveKind.NEWS)))
    }

    @Test fun upFromChannelLandsOnOwnHeaderFirst() {
        val hs = LiveCollapse.layout(sections, emptySet()).visibleHeaders
        val football = sections[1].key
        assertEquals(football, LiveCollapse.jump(hs, football, onHeader = false, down = false))
        assertEquals(sections[0].key, LiveCollapse.jump(hs, football, onHeader = true, down = false))
        assertEquals(LiveCollapse.typeKey(LiveKind.NEWS), LiveCollapse.jump(hs, football, onHeader = false, down = true))
    }

    @Test fun jumpDoesNotWrap() {
        val hs = LiveCollapse.layout(sections, emptySet()).visibleHeaders
        assertEquals(hs.first(), LiveCollapse.jump(hs, hs.first(), onHeader = true, down = false))
        assertEquals(hs.last(), LiveCollapse.jump(hs, hs.last(), onHeader = true, down = true))
    }

    @Test fun channelMenuOffersCollapse() {
        val items = ItemContext.channelItems(on = true, inList = false, renamed = false, inGroup = true)
        assertEquals(ItemContext.COLLAPSE_GROUP, items.first())
        assertTrue(ItemContext.COLLAPSE_ALL in items)
        assertFalse(ItemContext.COLLAPSE_GROUP in ItemContext.channelItems(true, false, false))
        assertTrue(items.none { it.contains('(') || it.contains('[') })
    }
}
