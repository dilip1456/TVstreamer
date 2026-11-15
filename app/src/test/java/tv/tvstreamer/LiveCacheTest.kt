package tv.tvstreamer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 1.72: the saved channel list always opens Live TV; refreshes never wipe it; nothing else starves it. */
class LiveCacheTest {
    private class FakePortal(var cats: List<Cat>, var chans: List<Item>, var fail: Boolean = false) : LivePortal {
        var calls = 0
        override fun liveCats(): List<Cat> { calls++; if (fail) throw java.io.IOException("down"); return cats }
        override fun channels(): List<Item> { calls++; if (fail) throw java.io.IOException("down"); return chans }
    }
    /** A disk backed by real files with the app's safe save (SafeFile), like Store.cacheWriteSafe. */
    private class FileDisk(val f: File) : LiveDisk {
        override fun read(): String? = f.takeIf { it.exists() }?.readText()
        override fun write(text: String) = SafeFile.write(f, text)
        override fun readBackup(): String? = SafeFile.readBackup(f)
    }

    private val day = 86_400_000L
    private fun cats(n: Int) = List(n) { Cat("c$it", listOf("News", "Sports", "Movies", "Kids", "Music")[it % 5] + " $it", false) }
    private fun chans(n: Int, cats: Int) = List(n) { Item("itv", "$it", "Channel ${it + 1} HD", "ffrt http://x/$it", "c${it % cats}", it + 1, false) }
    private fun tmp(): File = Files.createTempDirectory("live").toFile().also { it.deleteOnExit() }

    @Test fun savedListSurvivesRestartWithNoPortalCall() {
        val f = File(tmp(), "live.json")
        val p = FakePortal(cats(4), chans(300, 4))
        LiveSource(p, FileDisk(f), { day }, { 0L }).fetch()
        // App restarted / updated: a new source on the same file, the portal unreachable
        val down = FakePortal(emptyList(), emptyList(), fail = true)
        val again = LiveSource(down, FileDisk(f), { day + 1000 }, { 0L })
        assertTrue(again.hasSaved())
        assertEquals(300, again.local()!!.items.size)
        assertEquals(4, again.local()!!.cats.size)
        assertFalse(again.due())
        assertEquals(0, down.calls)
    }

    @Test fun olderSavedFormatStillReads() {
        // live.json as written by 1.70 / 1.71 (no "v" field)
        val old = org.json.JSONObject().put("t", 5L).put("cats", catsJson(cats(2))).put("items", itemsJson(chans(20, 2))).toString()
        val s = LiveSource.decode(old)
        assertEquals(20, s.items.size); assertEquals(2, s.cats.size); assertEquals(5L, s.at)
        // and the new format carries a version
        assertTrue(LiveSource.encode(s).contains("\"v\":${LiveSource.FORMAT}"))
    }

    @Test fun emptyPortalAnswerNeverWipesTheSavedList() {
        val f = File(tmp(), "live.json")
        val p = FakePortal(cats(3), chans(100, 3))
        val src = LiveSource(p, FileDisk(f), { 2 * day }, { 0L })
        src.fetch()
        p.chans = emptyList()
        assertThrows(EmptyChannelListException::class.java) { src.fetch() }
        assertEquals(100, src.local()!!.items.size)
        assertEquals(100, LiveSource(p, FileDisk(f), { 2 * day }, { 0L }).local()!!.items.size)
    }

    @Test fun failedRefreshKeepsTheList() {
        val f = File(tmp(), "live.json")
        val p = FakePortal(cats(3), chans(50, 3))
        val src = LiveSource(p, FileDisk(f), { 2 * day }, { 0L })
        src.fetch()
        p.fail = true
        assertThrows(java.io.IOException::class.java) { src.fetch() }
        assertEquals(50, src.local()!!.items.size)
    }

    @Test fun unchangedRefreshKeepsTheSameListObjects() {
        val f = File(tmp(), "live.json")
        val p = FakePortal(cats(3), chans(50, 3))
        var now = 2 * day
        val src = LiveSource(p, FileDisk(f), { now }, { 0L })
        val a = src.fetch()
        now += day
        p.chans = chans(50, 3)   // equal content, new objects
        val b = src.fetch()
        assertSame(a.items, b.items)          // nothing to lay out again
        assertEquals(now, b.at)               // but the refresh time moved on (no refresh again today)
        p.chans = chans(51, 3)
        assertNotSame(a.items, src.fetch().items)
    }

    @Test fun brokenMainFileFallsBackToTheBackup() {
        val f = File(tmp(), "live.json")
        val p = FakePortal(cats(3), chans(40, 3))
        var now = 2 * day
        LiveSource(p, FileDisk(f), { now }, { 0L }).fetch()
        now += day
        p.chans = chans(45, 3)
        LiveSource(p, FileDisk(f), { now }, { 0L }).fetch()   // previous copy kept as live.json.bak
        f.writeText("{\"t\":1,\"items\":[{\"k\":\"itv\"")     // killed mid-write (pre-1.72 style)
        val s = LiveSource(FakePortal(emptyList(), emptyList(), fail = true), FileDisk(f), { now }, { 0L }).local()
        assertNotNull(s)
        assertEquals(40, s!!.items.size)
        // main missing entirely (killed between the two renames): the backup still opens Live TV
        f.delete()
        assertEquals(40, LiveSource(FakePortal(emptyList(), emptyList()), FileDisk(f), { now }, { 0L }).local()!!.items.size)
    }

    @Test fun safeSaveLeavesNoTempFile() {
        val d = tmp(); val f = File(d, "live.json")
        SafeFile.write(f, "one"); SafeFile.write(f, "two")
        assertEquals("two", f.readText())
        assertEquals("one", SafeFile.readBackup(f))
        assertFalse(File(d, "live.json.tmp").exists())
    }

    @Test fun indexerWaitsForLiveTv() {
        assertTrue(IndexGate.canRun(stopped = false, playing = false, portalPaused = false, channelsSaved = true, channelsFetching = false, liveOpen = false))
        assertFalse(IndexGate.canRun(false, false, false, channelsSaved = false, channelsFetching = false, liveOpen = false))   // first run: channels first
        assertFalse(IndexGate.canRun(false, false, false, true, channelsFetching = true, liveOpen = false))                   // channel list being fetched
        assertFalse(IndexGate.canRun(false, false, false, true, false, liveOpen = true))                                     // Live TV on screen
        assertFalse(IndexGate.canRun(false, playing = true, portalPaused = false, channelsSaved = true, channelsFetching = false, liveOpen = false))
        assertFalse(IndexGate.canRun(true, false, false, true, false, false))
    }

    @Test fun indexerFetchesNothingWhileLiveTvComesFirst() = runBlocking {
        var saved = false
        var fetches = 0
        val ix = CatalogIndexer(HashMap(), HashSet(), HashMap(), clock = { 10 * day }, tzOffset = { 0L })
        val stop = ix.run(
            wanted = { listOf("a", "b") },
            canRun = { IndexGate.canRun(false, false, false, saved, false, false) },
            fetch = { _, _ -> fetches++; Stalker.VodPage(emptyList(), 0, 14) },
            save = { _, _ -> 0 })
        assertEquals(CatalogIndexer.Stop.PAUSED, stop)
        assertEquals(0, fetches)
        saved = true
        assertEquals(CatalogIndexer.Stop.DONE, ix.run({ listOf("a", "b") }, { IndexGate.canRun(false, false, false, saved, false, false) },
            { _, _ -> fetches++; Stalker.VodPage(emptyList(), 0, 14) }, { _, _ -> 0 }))
        assertEquals(2, fetches)
    }

    @Test fun catalogChangesAreCoalescedWhileIndexing() {
        var now = 0L
        val t = BumpThrottle(60_000, { now })
        assertTrue(t.changed())                       // the first page shows at once
        var told = 0
        repeat(30) { now += 2_500; if (t.changed()) told++ }   // a page every 2.5 s for 75 s
        assertEquals(1, told)                         // one more update, not 30 shelf rebuilds
        assertTrue(t.flush())                         // the end of the run tells the held-back pages
        assertFalse(t.flush())
    }

    @Test fun endOfRunFlushesAHeldBackChange() {
        var now = 0L
        val t = BumpThrottle(60_000, { now })
        t.changed(); now += 1_000
        assertFalse(t.changed())
        assertTrue(t.flush())
        assertFalse(t.flush())
    }

    @Test fun emptyLiveTvSaysWhatIsHappening() {
        assertEquals("Opening saved channels…", LiveStatus.empty(true, ""))
        assertEquals("Loading channels and categories…", LiveStatus.empty(false, "Loading channels and categories…"))
        assertEquals("No channels loaded yet", LiveStatus.empty(false, ""))
    }

    /**
     * The 1.71 regression: Live TV's layout queued on the shared pool behind catalog work. Fill every Default thread
     * with long CPU jobs (like shelf rebuilds after each indexed page), then lay out 20 000 channels on Live TV's own
     * thread: it must not wait for them.
     */
    @Test fun liveLayoutDoesNotQueueBehindCatalogWork() = runBlocking {
        val s = LiveSource.Snapshot(cats(120), chans(20_000, 120), 1L)
        val inputs = LiveInputs(s.items, s.cats, emptyList(), emptySet(), true, null, emptyMap(), LiveModes.DEFAULT, FolderSet(), LiveModes.View.ACTIVE)
        withContext(LiveWork.dispatcher) { LiveModel.build(inputs) }   // warm up (JIT)
        val busyMs = 4_000L
        val cores = Runtime.getRuntime().availableProcessors()
        val hogs = List(cores * 3) { async(Dispatchers.Default) { val end = System.currentTimeMillis() + busyMs; var x = 0L; while (System.currentTimeMillis() < end) x++; x } }
        Thread.sleep(100)   // the pool is now full
        val t0 = System.currentTimeMillis()
        val m = withContext(LiveWork.dispatcher) { LiveModel.build(inputs) }
        val took = System.currentTimeMillis() - t0
        hogs.awaitAll()
        assertEquals(20_000, m.flat.size)
        assertTrue("Live TV layout waited $took ms behind catalog work", took < busyMs)
        // The same layout on the shared pool (1.70 / 1.71) waits for the catalog jobs ahead of it
        val hogs2 = List(cores * 3) { async(Dispatchers.Default) { val end = System.currentTimeMillis() + busyMs; var x = 0L; while (System.currentTimeMillis() < end) x++; x } }
        Thread.sleep(100)
        val t1 = System.currentTimeMillis()
        withContext(Dispatchers.Default) { LiveModel.build(inputs) }
        val shared = System.currentTimeMillis() - t1
        hogs2.awaitAll()
        println("LIVE-STARVE own thread $took ms, shared pool $shared ms")
    }
}
