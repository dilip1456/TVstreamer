package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemContextTest {
    @Test fun channelMenuIsCompactAndHasRenameListAndSelect() {
        val on = ItemContext.channelItems(on = true, inList = false, renamed = false)
        assertEquals(listOf("Hide", "Rename", "Add to My List", "Select"), on)
        val off = ItemContext.channelItems(on = false, inList = true, renamed = true)
        assertEquals(listOf("Show", "Rename", "Reset to original", "Remove from My List", "Select"), off)
        assertFalse(on.contains("Languages…"))
    }

    @Test fun liveGroupMenuHasShowHideAllAndCollapse() {
        val items = ItemContext.liveGroupItems(groupOn = true, folded = false)
        assertEquals(
            listOf("Hide", "Show all channels in group", "Hide all channels in group",
                "Collapse", "Expand all", "Collapse all", "Select channels"),
            items
        )
        val shown = ItemContext.liveGroupItems(groupOn = false, folded = true)
        assertEquals("Show", shown[0])
        assertEquals("Expand", shown[3])
    }

    @Test fun catalogMovieGroupHasShowHideAll() {
        val items = ItemContext.catalogGroupItems(groupOn = true, liveType = false, folded = null)
        assertEquals(listOf("Hide", "Show all", "Hide all", "Select"), items)
    }

    @Test fun catalogLiveTypeAlsoHasCollapse() {
        val items = ItemContext.catalogGroupItems(groupOn = false, liveType = true, folded = true)
        assertTrue(items.contains("Show"))
        assertTrue(items.contains("Show all"))
        assertTrue(items.contains("Expand"))
        assertTrue(items.contains("Collapse all"))
        assertTrue(items.contains("Select"))
    }

    @Test fun catalogCollapseAllCoversSectionsAndLiveTypes() {
        val keys = ItemContext.catalogCollapseAll(listOf("grp:Sports", "grp:News"))
        assertEquals(setOf("Movies", "Series", "live:grp:Sports", "live:grp:News"), keys)
    }

    @Test fun withDescendantsIncludesNestedLiveGroups() {
        val sports = CatalogGroups.Group("grp:Sports", "Sports", "Live TV", 10)
        val cricket = CatalogGroups.Group("grp:Sports/Cricket", "Cricket", "Live TV", 4, parent = "grp:Sports", depth = 1)
        val au = CatalogGroups.Group("grp:Sports/@Australia", "Australia", "Live TV", 2, parent = "grp:Sports", depth = 1)
        val news = CatalogGroups.Group("grp:News", "News", "Live TV", 3)
        val all = listOf(sports, cricket, au, news)
        assertEquals(listOf("grp:Sports", "grp:Sports/Cricket", "grp:Sports/@Australia"),
            CatalogGroups.withDescendants(sports, all).map { it.key })
        assertEquals(listOf("grp:News"), CatalogGroups.withDescendants(news, all).map { it.key })
    }
}
