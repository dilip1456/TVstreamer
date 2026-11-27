package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class LivePaneTest {
    private fun ch(id: String, cat: String = "1", num: Int = id.toIntOrNull() ?: 0, name: String = "Ch $id") =
        Item("itv", id, name, "cmd$id", cat, num, false)

    private val sports = ch("1", "s", 101, "Sky Sports")
    private val news = ch("2", "n", 201, "BBC News")
    private val foot = ch("3", "s", 102, "Football One")
    private val all = listOf(sports, news, foot)
    private val info = mapOf(
        sports.key to ChInfo(LiveKind.SPORTS, "English"),
        news.key to ChInfo(LiveKind.NEWS, "English"),
        foot.key to ChInfo(LiveKind.SPORTS, "English"),
    )
    private val titles = mapOf("s" to "UK | SPORTS Football", "n" to "UK | NEWS")
    private val rows = LiveModes.rows(all, LiveModes.DEFAULT, FolderSet(), emptySet(), LiveModes.View.ACTIVE, null, info, titles)
    private val tree = LiveModes.tree(rows, emptySet(), LiveModes.View.ACTIVE)

    @Test fun opensOnFavouritesWhenThereAreAnyOtherwiseAll() {
        assertEquals(LivePane.FAVOURITES, LivePane.initial(3))
        assertEquals(LivePane.ALL, LivePane.initial(0))
    }

    @Test fun emptyFavouritesFallBackToAllAndNothingElseIsRemembered() {
        val pins = LivePane.pins(0, 2, 3)
        val vis = LivePane.visible(pins, tree, emptySet())
        assertEquals(LivePane.ALL, LivePane.settle(LivePane.FAVOURITES, vis, favCount = 0))
        assertEquals(LivePane.FAVOURITES, LivePane.settle(LivePane.FAVOURITES, vis, favCount = 2))
        assertEquals(LivePane.SEARCH, LivePane.settle(LivePane.SEARCH, vis, favCount = 0))
        // A vanished group does not restore a previous category — it falls back to All (or Favourites if any)
        assertEquals(LivePane.ALL, LivePane.settle("grp:Missing", vis, favCount = 0))
        assertEquals(LivePane.FAVOURITES, LivePane.settle("grp:Missing", vis, favCount = 2))
    }

    @Test fun pinsComeFirstWithCountsThenTypes() {
        val pins = LivePane.pins(2, 1, 3)
        val vis = LivePane.visible(pins, tree, emptySet())
        assertEquals(listOf("search", "favourites", "recent", "all"), vis.take(4).map { it.key })
        assertEquals(2, vis.first { it.key == LivePane.FAVOURITES }.count)
        assertEquals(3, vis.first { it.key == LivePane.ALL }.count)
        assertTrue(vis.any { it.label == LiveKind.SPORTS })
        assertTrue(vis.any { it.label == LiveKind.NEWS })
        assertTrue(vis.none { it.depth == 1 })
    }

    @Test fun expandingATypeShowsItsGroupsAndKeepsCounts() {
        val sportsNode = tree.first { it.type == LiveKind.SPORTS }
        val pins = LivePane.pins(0, 0, 3)
        val vis = LivePane.visible(pins, tree, setOf(sportsNode.key))
        assertTrue(vis.any { it.depth == 1 && it.parent == sportsNode.key })
        val collapsed = LivePane.visible(pins, tree, emptySet())
        assertTrue(collapsed.none { it.depth == 1 })
        val toggled = LivePane.toggleExpand(emptySet(), sportsNode.key)
        assertEquals(setOf(sportsNode.key), toggled)
        assertEquals(emptySet<String>(), LivePane.toggleExpand(toggled, sportsNode.key))
    }

    @Test fun rowsForAllFavouritesRecentAndAGroup() {
        val fav = listOf(news.key, sports.key)
        val recent = listOf(foot.key, news.key)
        assertEquals(listOf("1", "2", "3"), LivePane.rowsOf(LivePane.ALL, rows, fav, recent, tree).map { it.ch.id })
        assertEquals(listOf("2", "1"), LivePane.rowsOf(LivePane.FAVOURITES, rows, fav, recent, tree).map { it.ch.id })
        assertEquals(listOf("3", "2"), LivePane.rowsOf(LivePane.RECENT, rows, fav, recent, tree).map { it.ch.id })
        assertTrue(LivePane.rowsOf(LivePane.SEARCH, rows, fav, recent, tree).isEmpty())
        val sportsNode = tree.first { it.type == LiveKind.SPORTS }
        val sportsRows = LivePane.rowsOf(sportsNode.key, rows, fav, recent, tree)
        assertEquals(setOf("1", "3"), sportsRows.map { it.ch.id }.toSet())
        assertTrue(news.key !in sportsRows.map { it.ch.key })
    }

    @Test fun titleJoinsTypeAndGroupWithoutBrackets() {
        val pins = LivePane.pins(0, 0, 3)
        val sportsNode = tree.first { it.type == LiveKind.SPORTS }
        val vis = LivePane.visible(pins, tree, setOf(sportsNode.key))
        assertEquals("Favourites", LivePane.titleOf(LivePane.FAVOURITES, vis))
        val child = vis.firstOrNull { it.parent == sportsNode.key }
        if (child != null) {
            val title = LivePane.titleOf(child.key, vis)
            assertTrue(title.startsWith("Sports · "))
            assertFalse(title.contains("(") || title.contains(")"))
        }
    }

    @Test fun pagingMovesAScreenfulAndStopsAtTheEnds() {
        assertEquals(8, LivePane.pageIndex(0, down = true, size = 20))
        assertEquals(0, LivePane.pageIndex(3, down = false, size = 20))
        assertEquals(19, LivePane.pageIndex(18, down = true, size = 20))
        assertEquals(0, LivePane.pageIndex(0, down = false, size = 20))
        assertEquals(0, LivePane.pageIndex(0, down = true, size = 0))
    }

    @Test fun unfavouriteMovesFocusToTheNextRowInFavourites() {
        val favRows = LivePane.rowsOf(LivePane.FAVOURITES, rows, listOf(news.key, sports.key, foot.key), emptyList(), tree)
        val keys = favRows.map { it.ch.key }
        assertEquals(sports.key, FocusStay.afterRemove(keys, news.key))
    }

    @Test fun phoneChipsExpandTheSelectedType() {
        val pins = LivePane.pins(0, 0, 3)
        val sportsNode = tree.first { it.type == LiveKind.SPORTS }
        val chips = LivePane.chips(pins, tree, sportsNode.key)
        if (sportsNode.children.isNotEmpty()) {
            assertTrue(chips.any { it.depth == 1 && it.parent == sportsNode.key })
        }
        assertTrue(LivePane.chips(pins, tree, LivePane.ALL).none { it.depth == 1 })
    }
}
