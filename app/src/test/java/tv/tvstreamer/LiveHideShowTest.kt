package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test
import tv.tvstreamer.LiveModes.View

/**
 * Hide / Show on a single channel and in bulk, from Only hidden and Only active views.
 * Covers the 1.58 bug: a channel hidden only by its group was skipped by bulk Unhide
 * (LiveModes.isOn was still true) and toggleOn added ch:key instead of showing it.
 */
class LiveHideShowTest {
    private val cats = mapOf("s" to "UK | SPORTS", "k" to "KIDS")
    private fun ch(id: String, name: String, cat: String) = Item("itv", id, name, "", cat, id.toInt(), false)
    private val cricket = ch("1", "Sky Sports Cricket", "s")
    private val golf = ch("2", "Sky Sports Golf", "s")
    private val toon = ch("3", "Cartoon Network", "k")
    private val all = listOf(cricket, golf, toon)
    private val info = mapOf(
        cricket.key to ChInfo(LiveKind.SPORTS, "English"),
        golf.key to ChInfo(LiveKind.SPORTS, "English"),
        toon.key to ChInfo(LiveKind.KIDS, "English")
    )
    private val folders = FolderSet()
    private fun rows(hidden: Set<String>, v: View, mode: String = LiveModes.DEFAULT, f: FolderSet = folders) =
        LiveModes.rows(all, mode, f, hidden, v, null, info, cats)

    private fun visible(ch: Item, hidden: Set<String>, f: FolderSet = folders, mode: String = LiveModes.DEFAULT): Boolean {
        val title = cats[ch.cat].orEmpty()
        val (type, sub, region) = LiveModes.groups(ch, title, info)
        return LiveModes.isVisible(ch, mode, f, hidden, type, sub, region)
    }

    @Test fun singleHideFromActiveAndShowFromHidden() {
        var h = emptySet<String>()
        val (f1, h1) = LiveModes.setOn(cricket, false, LiveModes.DEFAULT, folders, h, LiveKind.SPORTS, "Cricket", "")
        assertFalse(visible(cricket, h1, f1))
        assertTrue(visible(golf, h1, f1))
        assertEquals(listOf(cricket), rows(h1, View.HIDDEN).map { it.ch })
        assertEquals(listOf(golf, toon), rows(h1, View.ACTIVE).map { it.ch })

        val (_, h2) = LiveModes.setOn(cricket, true, LiveModes.DEFAULT, f1, h1, LiveKind.SPORTS, "Cricket", "")
        assertTrue(visible(cricket, h2, f1))
        assertTrue(rows(h2, View.HIDDEN).none { it.ch == cricket })
        assertTrue(rows(h2, View.ACTIVE).any { it.ch == cricket && it.on })
    }

    @Test fun showWorksWhenChannelIsOnlyHiddenByGroup() {
        val h = setOf(Taxonomy.subKey(LiveKind.SPORTS, "Golf"))
        assertFalse(visible(golf, h))
        assertTrue(visible(cricket, h))
        // The old toggle would ADD ch:key (isOn was still true) and leave the channel hidden
        val (_, h2) = LiveModes.setOn(golf, true, LiveModes.DEFAULT, folders, h, LiveKind.SPORTS, "Golf", "")
        assertTrue(visible(golf, h2))
        assertTrue(LiveModes.isForced(golf, h2))
        // The group stays hidden: cricket is unaffected, other golf would stay hidden unless also forced
        assertTrue(Taxonomy.groupHidden(LiveKind.SPORTS, "Golf", "", h2))
        assertTrue(rows(h2, View.ACTIVE).any { it.ch == golf && it.on })
    }

    @Test fun hideOfForcedShowDropsExceptionAndHidesChannel() {
        val h0 = setOf(Taxonomy.typeKey(LiveKind.SPORTS))
        val (_, h1) = LiveModes.setOn(cricket, true, LiveModes.DEFAULT, folders, h0, LiveKind.SPORTS, "Cricket", "")
        assertTrue(visible(cricket, h1))
        val (_, h2) = LiveModes.setOn(cricket, false, LiveModes.DEFAULT, folders, h1, LiveKind.SPORTS, "Cricket", "")
        assertFalse(visible(cricket, h2))
        assertFalse(LiveModes.isForced(cricket, h2))
        assertTrue("ch:${cricket.key}" in h2)
    }

    @Test fun bulkHideAndUnhideFromActiveAndHiddenViews() {
        var h = emptySet<String>()
        var rowsActive = rows(h, View.ACTIVE)
        val sel = setOf(cricket.key, golf.key)
        val (_, h1) = LiveSelect.applyRows(rowsActive, sel, false, LiveModes.DEFAULT, folders, h)
        assertFalse(visible(cricket, h1))
        assertFalse(visible(golf, h1))
        assertTrue(visible(toon, h1))
        assertEquals(setOf(cricket, golf), rows(h1, View.HIDDEN).map { it.ch }.toSet())

        val hiddenRows = rows(h1, View.HIDDEN)
        val (_, h2) = LiveSelect.applyRows(hiddenRows, sel, true, LiveModes.DEFAULT, folders, h1)
        assertTrue(visible(cricket, h2))
        assertTrue(visible(golf, h2))
        assertTrue(rows(h2, View.HIDDEN).isEmpty())
    }

    @Test fun bulkUnhideFromHiddenViewIncludesGroupHiddenChannels() {
        val h = setOf("ch:${toon.key}", Taxonomy.typeKey(LiveKind.SPORTS))
        val hiddenRows = rows(h, View.HIDDEN)
        assertEquals(setOf(cricket, golf, toon), hiddenRows.map { it.ch }.toSet())
        assertTrue(hiddenRows.all { !it.on })
        // Previously LiveSelect.apply used isOn, skipped cricket/golf (still "on" at channel level), and only unhid toon
        val sel = hiddenRows.map { it.ch.key }.toSet()
        val (_, h2) = LiveSelect.applyRows(hiddenRows, sel, true, LiveModes.DEFAULT, folders, h)
        assertTrue(visible(cricket, h2))
        assertTrue(visible(golf, h2))
        assertTrue(visible(toon, h2))
        assertTrue(rows(h2, View.ACTIVE).all { it.on })
        assertTrue(rows(h2, View.HIDDEN).isEmpty())
    }

    @Test fun bulkInCustomModeShowAndHide() {
        val f = FolderSet().create("Kids")
        // "Only hidden" in a new empty mode: every channel is off
        val hiddenRows = rows(emptySet(), View.HIDDEN, "Kids", f)
        assertEquals(3, hiddenRows.size)
        val sel = setOf(toon.key, cricket.key)
        val (f2, h) = LiveSelect.applyRows(hiddenRows, sel, true, "Kids", f, emptySet())
        assertTrue(h.isEmpty())
        assertEquals(setOf(toon.key, cricket.key), f2.items("Kids").map { it.key }.toSet())
        val active = rows(h, View.ACTIVE, "Kids", f2)
        assertEquals(setOf(toon, cricket), active.map { it.ch }.toSet())
        val (f3, _) = LiveSelect.applyRows(active, setOf(cricket.key), false, "Kids", f2, h)
        assertEquals(listOf(toon.key), f3.items("Kids").map { it.key })
    }

    @Test fun treeKeepsHiddenGroupWhenAChannelIsForcedShown() {
        val h0 = setOf(Taxonomy.typeKey(LiveKind.SPORTS))
        val (_, h) = LiveModes.setOn(golf, true, LiveModes.DEFAULT, folders, h0, LiveKind.SPORTS, "Golf", "")
        val active = rows(h, View.ACTIVE)
        assertEquals(listOf(golf, toon), active.map { it.ch })
        val tree = LiveModes.tree(active, h, View.ACTIVE)
        assertTrue(tree.any { it.type == LiveKind.SPORTS && it.hidden })
        assertTrue(tree.any { it.type == LiveKind.KIDS })
    }

    @Test fun toggleGroupHidesAndUnhides() {
        val key = Taxonomy.subKey(LiveKind.SPORTS, "Golf")
        val h1 = LiveModes.toggleGroup(key, emptySet())
        assertEquals(setOf(key), h1)
        assertFalse(visible(golf, h1))
        val h2 = LiveModes.toggleGroup(key, h1)
        assertTrue(h2.isEmpty())
        assertTrue(visible(golf, h2))
    }

    @Test fun nodeForSectionPicksSubNotType() {
        val rs = rows(emptySet(), View.ACTIVE)
        val tree = LiveModes.tree(rs, emptySet(), View.ACTIVE)
        val cricketSec = LiveModes.Section(LiveKind.SPORTS, "Cricket", listOf(LiveModes.Row(cricket, true, LiveKind.SPORTS, "Cricket", "")))
        val node = LiveModes.nodeForSection(tree, cricketSec)
        assertEquals(Taxonomy.subKey(LiveKind.SPORTS, "Cricket"), node?.key)
        val kidsSec = LiveModes.Section(LiveKind.KIDS, "Cartoons", listOf(LiveModes.Row(toon, true, LiveKind.KIDS, "Cartoons", "")))
        // Kids has a single sub in this set, so the type node has no children and the header maps to the type
        val kids = LiveModes.nodeForSection(tree, kidsSec)
        assertEquals(Taxonomy.typeKey(LiveKind.KIDS), kids?.key)
    }

    @Test fun swipeLeftPastThresholdCommitsHideOrShow() {
        val reveal = 84f
        assertFalse(LiveSwipe.shouldCommit(0f, reveal))
        assertFalse(LiveSwipe.shouldCommit(-20f, reveal))
        assertTrue(LiveSwipe.shouldCommit(-reveal * LiveSwipe.COMMIT_FRAC, reveal))
        assertTrue(LiveSwipe.shouldCommit(-reveal, reveal))
        assertEquals(-reveal, LiveSwipe.clamp(-200f, reveal), 0.01f)
        assertEquals(0f, LiveSwipe.clamp(40f, reveal), 0.01f)
    }
}
