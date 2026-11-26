package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

/** 1.70: Live TV opens from the saved list; only first run / daily / forced refresh ask the portal. */
class LiveSourceTest {
    private class FakePortal(val cats: List<Cat>, val chans: List<Item>) : LivePortal {
        var calls = 0
        override fun liveCats(): List<Cat> { calls++; return cats }
        override fun channels(): List<Item> { calls++; return chans }
    }
    private class FakeDisk(var text: String? = null) : LiveDisk {
        var reads = 0; var writes = 0
        override fun read(): String? { reads++; return text }
        override fun write(text: String) { writes++; this.text = text }
    }

    private val day = 86_400_000L
    private fun cats(n: Int) = List(n) { Cat("c$it", listOf("News", "Sports", "Movies", "Kids", "Music")[it % 5] + " $it", false) }
    private fun chans(n: Int, cats: Int) = List(n) { Item("itv", "$it", "Channel ${it + 1} HD", "ffrt http://x/$it", "c${it % cats}", it + 1, false) }

    private fun inputs(s: LiveSource.Snapshot, hidden: Set<String> = emptySet(), mode: String = LiveModes.DEFAULT,
                       folders: FolderSet = FolderSet(), view: LiveModes.View = LiveModes.View.ACTIVE, info: Map<String, ChInfo> = emptyMap()) =
        LiveInputs(s.items, s.cats, emptyList(), hidden, false, null, info, mode, folders, view)

    @Test fun firstRunFetchesOnceThenEverythingIsLocal() {
        val portal = FakePortal(cats(5), chans(50, 5))
        val disk = FakeDisk()
        var now = 10 * day + 3_600_000L
        val src = LiveSource(portal, disk, { now }, { 0L })
        assertNull(src.local())
        assertTrue(src.due())
        src.fetch()
        assertEquals(2, portal.calls)          // get_genres + get_all_channels, once
        assertEquals(1, disk.writes)

        // Next app start: a new source on the same disk shows the saved list without the portal
        val portal2 = FakePortal(emptyList(), emptyList())
        val src2 = LiveSource(portal2, disk, { now }, { 0L })
        val s = src2.local()!!
        assertEquals(50, s.items.size)
        assertFalse(src2.due())
        now += 10 * 3_600_000L                 // later the same day: still not due
        assertFalse(src2.due())
        now = 11 * day + 60_000L               // first launch of the next day: the daily refresh may run
        assertTrue(src2.due())
        assertEquals(0, portal2.calls)
    }

    @Test fun savedListIsReadFromDiskOnceThenKeptInMemory() {
        val disk = FakeDisk(LiveSource.encode(LiveSource.Snapshot(cats(3), chans(30, 3), 1L)))
        val src = LiveSource(FakePortal(emptyList(), emptyList()), disk, { 2L }, { 0L })
        repeat(5) { assertEquals(30, src.local()!!.items.size) }
        assertEquals(1, disk.reads)
    }

    @Test fun openModeHideRenameAndViewNeverCallThePortal() {
        val portal = FakePortal(cats(5), chans(200, 5))
        val disk = FakeDisk(LiveSource.encode(LiveSource.Snapshot(cats(5), chans(200, 5), 10 * day)))
        val src = LiveSource(portal, disk, { 10 * day + 1000 }, { 0L })
        val s = src.local()!!
        // Open
        val open = LiveModel.build(inputs(s))
        assertEquals(200, open.flat.size)
        // Hide two channels: gone from the active view, the rest keep their order
        val hidden = setOf("ch:itv:3", "ch:itv:7")
        val afterHide = LiveModel.build(inputs(s, hidden), open)
        assertEquals(open.flat.map { it.key }.filter { "ch:$it" !in hidden }, afterHide.flat.map { it.key })
        // "Only hidden" and "All channels" views: hidden ones listed, order stable
        assertEquals(listOf("itv:3", "itv:7"), LiveModel.build(inputs(s, hidden, view = LiveModes.View.HIDDEN)).flat.map { it.key })
        assertEquals(open.flat.map { it.key }, LiveModel.build(inputs(s, hidden, view = LiveModes.View.ALL)).flat.map { it.key })
        // A custom mode: only its channels, in the mode's order
        val picks = listOf(s.items[9], s.items[2], s.items[40])
        val folders = FolderSet().create("Kids").add("Kids", picks)
        val kids = LiveModel.build(inputs(s, mode = "Kids", folders = folders))
        assertEquals(picks.map { it.key }.toSet(), kids.flat.map { it.key }.toSet())
        // Hiding a whole category is a local filter too
        val noCat = LiveModel.build(inputs(s, setOf("itv:c0")))
        assertTrue(noCat.flat.none { it.cat == "c0" })
        // Rename = a new channel list with the same keys: same order
        val renamed = s.items.map { if (it.id == "5") it.copy(name = "My channel") else it }
        val r = LiveModel.build(LiveInputs(renamed, s.cats, emptyList(), emptySet(), false, null, emptyMap(), LiveModes.DEFAULT, FolderSet(), LiveModes.View.ACTIVE))
        assertEquals(open.flat.map { it.key }, r.flat.map { it.key })
        assertEquals(0, portal.calls)
    }

    @Test fun inputsCompareByIdentity() {
        val s = LiveSource.Snapshot(cats(2), chans(10, 2), 1L)
        val a = inputs(s)
        assertTrue(a.same(inputs(s)))
        assertFalse(a.same(inputs(s, hidden = setOf("ch:itv:1"))))
        assertFalse(a.same(inputs(s, view = LiveModes.View.ALL)))
    }

    /** Timing on the build machine (no device here): read + parse the saved list, then lay the Live TV list out. */
    @Test fun timing20kChannels() {
        val text = LiveSource.encode(LiveSource.Snapshot(cats(120), chans(20_000, 120), 1L))
        val out = StringBuilder()
        repeat(3) { round ->
            val t0 = System.nanoTime()
            val src = LiveSource(FakePortal(emptyList(), emptyList()), FakeDisk(text), { 2L }, { 0L })
            val s = src.local()!!
            val t1 = System.nanoTime()
            val info = kotlinx.coroutines.runBlocking { LiveIndex.build(s.items, s.cats.associate { it.id to it.title }) }
            val t2 = System.nanoTime()
            val m = LiveModel.build(inputs(s, info = info))
            val t3 = System.nanoTime()
            val hide = LiveModel.build(inputs(s, hidden = setOf("ch:itv:5"), info = info), m)
            val t4 = System.nanoTime()
            out.append("round $round: parse ${(t1 - t0) / 1_000_000} ms, sort (first run only) ${(t2 - t1) / 1_000_000} ms, " +
                "layout ${(t3 - t2) / 1_000_000} ms, hide re-layout ${(t4 - t3) / 1_000_000} ms, rows ${hide.flat.size}\n")
        }
        println("LIVE-TIMING\n$out")
    }
}
