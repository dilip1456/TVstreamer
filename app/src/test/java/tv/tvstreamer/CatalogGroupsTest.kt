package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class CatalogGroupsTest {
    private fun cat(id: String, title: String) = Cat(id, title, false)
    private val movies = listOf(cat("m1", "Action"), cat("m2", "Comedy"))
    private val series = listOf(cat("s1", "Drama"))
    private val all = movies + series
    private val hiddenAction = setOf("vod:m1")
    private val kids = FolderSet().create("Kids")

    @Test fun defaultShowsEverythingNotHidden() {
        assertTrue(CatalogGroups.groupOn("vod:m1", LiveModes.DEFAULT, FolderSet(), emptySet()))
        assertFalse(CatalogGroups.groupOn("vod:m1", LiveModes.DEFAULT, FolderSet(), hiddenAction))
        assertTrue(CatalogGroups.groupOn("vod:m2", LiveModes.DEFAULT, FolderSet(), hiddenAction))
        assertTrue(CatalogGroups.groupOn(Taxonomy.typeKey("Sports"), LiveModes.DEFAULT, FolderSet(), emptySet()))
        assertFalse(CatalogGroups.groupOn(Taxonomy.typeKey("Sports"), LiveModes.DEFAULT, FolderSet(), setOf(Taxonomy.typeKey("Sports"))))
    }

    @Test fun emptyCustomModeHidesEveryGroup() {
        assertFalse(CatalogGroups.groupOn("vod:m1", "Kids", kids, emptySet()))
        assertFalse(CatalogGroups.groupOn(Taxonomy.typeKey("Sports"), "Kids", kids, emptySet()))
    }

    @Test fun legacyChannelModeFallsBackToDefaultHiddenForVod() {
        val f = FolderSet().create("Kids").add("Kids", listOf(Item("itv", "1", "Ch 1", "", "1", 1, false)))
        assertTrue(CatalogGroups.groupOn("vod:m1", "Kids", f, emptySet()))
        assertFalse(CatalogGroups.groupOn("vod:m1", "Kids", f, hiddenAction))
    }

    @Test fun customWhitelistUsesFolderKeys() {
        val f = kids.add("Kids", listOf(CatalogGroups.stub("vod:m2", "Comedy")))
        assertFalse(CatalogGroups.groupOn("vod:m1", "Kids", f, emptySet()))
        assertTrue(CatalogGroups.groupOn("vod:m2", "Kids", f, hiddenAction)) // whitelist wins over hidden
    }

    @Test fun toggleDefaultWritesHiddenSet() {
        val (f, h) = CatalogGroups.toggle("vod:m1", "Action", LiveModes.DEFAULT, FolderSet(), emptySet())
        assertTrue("vod:m1" in h)
        assertEquals(FolderSet(), f)
        val (_, h2) = CatalogGroups.toggle("vod:m1", "Action", LiveModes.DEFAULT, f, h)
        assertFalse("vod:m1" in h2)
    }

    @Test fun toggleEmptyModeAddsOnlyThatGroup() {
        val (f, h) = CatalogGroups.toggle("vod:m1", "Action", "Kids", kids, emptySet(),
            seed = listOf(CatalogGroups.stub("vod:m2", "Comedy")))
        assertTrue(CatalogGroups.groupOn("vod:m1", "Kids", f, h))
        assertFalse(CatalogGroups.groupOn("vod:m2", "Kids", f, h)) // empty mode is not seeded
        assertTrue(h.isEmpty())
    }

    @Test fun toggleLegacyModeSeedsThenHidesOne() {
        val f0 = FolderSet().create("Kids").add("Kids", listOf(Item("itv", "1", "Ch 1", "", "1", 1, false)))
        val seed = all.map { CatalogGroups.stub("vod:${it.id}", it.title) }
        val (f, h) = CatalogGroups.toggle("vod:m1", "Action", "Kids", f0, emptySet(), seed)
        assertTrue(h.isEmpty())
        assertFalse(CatalogGroups.groupOn("vod:m1", "Kids", f, h))
        assertTrue(CatalogGroups.groupOn("vod:m2", "Kids", f, h))
        assertTrue(CatalogGroups.groupOn("vod:s1", "Kids", f, h))
        assertEquals(1, f.channelItems("Kids").size)
    }

    @Test fun showingASubAlsoEnablesTheType() {
        val (f, _) = CatalogGroups.toggle(Taxonomy.subKey("Sports", "Football"), "Football", "Kids", kids, emptySet())
        assertTrue(CatalogGroups.groupOn(Taxonomy.typeKey("Sports"), "Kids", f, emptySet()))
        assertTrue(CatalogGroups.groupOn(Taxonomy.subKey("Sports", "Football"), "Kids", f, emptySet()))
    }

    @Test fun liveChannelRespectsModeGroups() {
        val sports = Taxonomy.typeKey("Sports")
        val foot = Taxonomy.subKey("Sports", "Football")
        assertTrue(CatalogGroups.liveChannelOn("Sports", "Football", "", LiveModes.DEFAULT, FolderSet(), emptySet()))
        assertFalse(CatalogGroups.liveChannelOn("Sports", "Football", "", LiveModes.DEFAULT, FolderSet(), setOf(sports)))
        assertFalse(CatalogGroups.liveChannelOn("Sports", "Football", "", "Kids", kids, emptySet()))
        val f = kids.add("Kids", listOf(CatalogGroups.stub(sports, "Sports"), CatalogGroups.stub(foot, "Football")))
        assertTrue(CatalogGroups.liveChannelOn("Sports", "Football", "", "Kids", f, emptySet()))
        assertFalse(CatalogGroups.liveChannelOn("News", "", "", "Kids", f, emptySet()))
        val onlyType = kids.add("Kids", listOf(CatalogGroups.stub(sports, "Sports")))
        assertTrue(CatalogGroups.liveChannelOn("Sports", "Football", "", "Kids", onlyType, emptySet()))
    }

    @Test fun vodGroupsSplitMoviesAndSeries() {
        val g = CatalogGroups.vodGroups(all, series = { it.id.startsWith("s") }, count = { 3 })
        assertEquals(listOf("Movies", "Movies", "Series"), g.map { it.section })
        assertEquals(listOf("vod:m1", "vod:m2", "vod:s1"), g.map { it.key })
        assertEquals("Action", g[0].title)
    }

    @Test fun setOnShowsSeveralInEmptyMode() {
        val keys = listOf("vod:m1" to "Action", "vod:m2" to "Comedy")
        val (f, h) = CatalogGroups.setOn(keys, true, "Kids", kids, emptySet())
        assertTrue(CatalogGroups.groupOn("vod:m1", "Kids", f, h))
        assertTrue(CatalogGroups.groupOn("vod:m2", "Kids", f, h))
        assertFalse(CatalogGroups.groupOn("vod:s1", "Kids", f, h))
    }

    @Test fun stubKeyMatchesHiddenAndFolderKeys() {
        assertEquals("vod:m1", CatalogGroups.stub("vod:m1", "Action").key)
        assertEquals("grp:Sports/Football", CatalogGroups.stub(Taxonomy.subKey("Sports", "Football"), "Football").key)
        assertFalse(CatalogGroups.isGroupItem(Item("itv", "1", "Ch", "", "", 1, false)))
        assertTrue(CatalogGroups.isGroupItem(CatalogGroups.stub("vod:m1", "Action")))
    }


    @Test fun groupOnlyCustomModeListsChannelsInEnabledGroups() {
        val ch = Item("itv", "1", "Sky Sports Cricket", "", "s", 1, false)
        val f = FolderSet().create("Kids").add("Kids", listOf(
            CatalogGroups.stub(Taxonomy.typeKey("Sports"), "Sports"),
            CatalogGroups.stub(Taxonomy.subKey("Sports", "Cricket"), "Cricket")
        ))
        assertFalse(LiveModes.isOn(ch, "Kids", f, emptySet())) // not a channel member
        assertTrue(LiveModes.channelEnabled(ch, "Kids", f, emptySet()))
        assertTrue(CatalogGroups.liveChannelOn("Sports", "Cricket", "", "Kids", f, emptySet()))
        assertTrue(LiveModes.isVisible(ch, "Kids", f, emptySet(), "Sports", "Cricket", ""))
        assertFalse(LiveModes.isVisible(ch, "Kids", f, emptySet(), "News", "", ""))
        // empty mode: not enabled as a member, hidden by groups
        val empty = FolderSet().create("Kids")
        assertFalse(LiveModes.isOn(ch, "Kids", empty, emptySet()))
        assertTrue(LiveModes.channelEnabled(ch, "Kids", empty, emptySet()))
        assertFalse(LiveModes.isVisible(ch, "Kids", empty, emptySet(), "Sports", "Cricket", ""))
    }
}
