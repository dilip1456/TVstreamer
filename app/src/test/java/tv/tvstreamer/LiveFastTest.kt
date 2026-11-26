package tv.tvstreamer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 1.73: Live TV start-up and mode switches. The list layout used to re-read the custom mode's whole item list for every
 * channel (quadratic), so each layout took seconds on a Fire TV and start-up ran several in a row (~30 s).
 */
class LiveFastTest {
    private val catNames = listOf("UK | ENTERTAINMENT", "INDIA HINDI", "USA LOCALS", "ARABIC MBC", "FRANCE", "GERMANY SPORT",
        "US SPORTS", "PAKISTAN NEWS", "CANADA", "SPAIN FOOTBALL", "KIDS", "MOVIES 24/7", "MUSIC", "INDIA MALAYALAM")
    private val names = listOf("BBC One", "Star Plus", "NBC 4 New York", "MBC Drama", "TF1", "Sky Sport 1", "ESPN 2", "Geo News",
        "CBC Toronto", "Movistar LaLiga", "Sony Max", "Zee Cinema", "Cartoon Network", "HBO Family", "Al Jazeera")
    private fun cats(n: Int = 200) = List(n) { Cat("$it", catNames[it % catNames.size] + " $it", false) }
    private fun chans(n: Int, cats: Int = 200) = List(n) {
        Item("itv", "$it", listOf("UK: ", "|IN| ", "US: ", "")[it % 4] + names[(it / 3) % names.size] + listOf(" HD", "", " FHD")[it % 3],
            "ffrt http://x/$it", "${it % cats}", it + 1, false)
    }
    private fun info(ch: List<Item>, cats: List<Cat>) = runBlocking { LiveIndex.build(ch, cats.associate { it.id to it.title }) }

    /** The 1.72 rule, kept here to prove the fast one gives the same answers. */
    private fun oldRowsFrom(cl: List<LiveModes.Classified>, mode: String, f: FolderSet, h: Set<String>, v: LiveModes.View) =
        cl.map { c -> LiveModes.Row(c.ch, LiveModes.rowOn(c, mode, f, h), c.type, c.sub, c.region) }.filter { v.shows(it.on) }

    @Test fun fastRuleMatchesTheOldRuleEverywhere() {
        val cats = cats(); val ch = chans(3000); val inf = info(ch, cats)
        val cl = LiveModes.classify(ch, null, inf, cats.associate { it.id to it.title })
        val types = cl.map { it.type }.distinct(); val subs = cl.map { it.type to it.sub }.distinct()
        val regions = cl.filter { it.region.isNotEmpty() }.map { it.type to it.region }.distinct()
        val rnd = Random(7)
        repeat(40) { round ->
            // Hidden channels, hidden groups, forced-shown channels
            val h = HashSet<String>()
            ch.forEach { if (rnd.nextInt(5) == 0) h += "ch:${it.key}"; if (rnd.nextInt(40) == 0) h += LiveModes.SHOW + it.key }
            types.forEach { if (rnd.nextInt(6) == 0) h += Taxonomy.typeKey(it) }
            subs.forEach { (t, s) -> if (rnd.nextInt(4) == 0) h += Taxonomy.subKey(t, s) }
            regions.forEach { (t, r) -> if (rnd.nextInt(3) == 0) h += Taxonomy.regionKey(t, r) }
            // Modes: channels only, group stubs only, both, empty
            var f = FolderSet().create("Chans").add("Chans", ch.shuffled(rnd).take(130))
                .create("Groups").create("Both").add("Both", ch.shuffled(rnd).take(23)).create("Empty")
            val stubs = types.filter { rnd.nextBoolean() }.map { CatalogGroups.stub(Taxonomy.typeKey(it), it) } +
                subs.filter { rnd.nextInt(3) == 0 }.map { (t, s) -> CatalogGroups.stub(Taxonomy.subKey(t, s), s) } +
                regions.filter { rnd.nextBoolean() }.map { (t, r) -> CatalogGroups.stub(Taxonomy.regionKey(t, r), r) }
            f = f.add("Groups", stubs).add("Both", stubs)
            for (mode in listOf(LiveModes.DEFAULT, "Chans", "Groups", "Both", "Empty", "Missing")) for (v in LiveModes.View.values())
                assertEquals("round $round mode '$mode' $v", oldRowsFrom(cl, mode, f, h, v), LiveModes.rowsFrom(cl, mode, f, h, v))
        }
    }

    private fun ms(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    @Test fun modeSwitchIsALookUpUnder50ms() {
        val cats = cats(); val ch = chans(20_000); val inf = info(ch, cats)
        val folders = FolderSet().create("Sports").add("Sports", ch.filterIndexed { i, _ -> i % 870 == 0 })   // 23
            .create("Mine").add("Mine", ch.filterIndexed { i, _ -> i % 153 == 0 })                            // 131
        fun inputs(mode: String, v: LiveModes.View) =
            LiveInputs(ch, cats, emptyList(), setOf("ch:itv:5"), false, null, inf, mode, folders, v)
        val cache = LiveModelCache()
        var last = LiveModel.build(inputs(LiveModes.DEFAULT, LiveModes.View.ACTIVE)).also { cache.put(it) }
        // Pre-building every mode / view (what Repo.prewarmLive does in the background): each is a cheap pass now
        for (m in listOf(LiveModes.DEFAULT, "Sports", "Mine")) for (v in LiveModes.View.values()) {
            val i = inputs(m, v)
            if (cache.get(i) == null) {
                val t = ms { last = LiveModel.build(i, cache.base(i) ?: last).also { cache.put(it) } }
                assertTrue("pre-build $m/$v took $t ms", t < 400)
            }
        }
        assertEquals(9, cache.size())
        // The switches the user makes: 23 -> 131 channels and back, views; each must be instant
        var rows = 0
        for ((m, v) in listOf("Sports" to LiveModes.View.ACTIVE, "Mine" to LiveModes.View.ACTIVE, "Mine" to LiveModes.View.ALL,
                "Sports" to LiveModes.View.HIDDEN, LiveModes.DEFAULT to LiveModes.View.ACTIVE)) {
            var got: LiveModel? = null
            val t = ms { got = cache.get(inputs(m, v)) }
            assertNotNull("$m/$v not pre-built", got)
            assertTrue("switch to $m/$v took $t ms", t < 50)
            rows = got!!.flat.size
        }
        assertEquals(19_999, rows)
        assertEquals(23, cache.get(inputs("Sports", LiveModes.View.ACTIVE))!!.flat.size)
        assertEquals(131, cache.get(inputs("Mine", LiveModes.View.ACTIVE))!!.flat.size)
    }

    @Test fun switchingToACustomModeIsNoLongerQuadratic() {
        val cats = cats(); val ch = chans(20_000); val inf = info(ch, cats)
        val folders = FolderSet().create("Mine").add("Mine", ch.filterIndexed { i, _ -> i % 153 == 0 })
        val base = LiveModel.build(LiveInputs(ch, cats, emptyList(), emptySet(), false, null, inf, LiveModes.DEFAULT, folders, LiveModes.View.ACTIVE))
        repeat(3) { LiveModel.build(LiveInputs(ch, cats, emptyList(), emptySet(), false, null, inf, "Mine", folders, LiveModes.View.ALL), base) }   // warm the JIT
        val t = ms { LiveModel.build(LiveInputs(ch, cats, emptyList(), emptySet(), false, null, inf, "Mine", folders, LiveModes.View.ACTIVE), base) }
        // 1.72 took ~200 ms here on a desktop (seconds on a Fire TV); now a set look-up per channel
        assertTrue("custom mode layout took $t ms", t < 50)
    }

    private class Hang : LivePortal {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var calls = 0
        override fun liveCats(): List<Cat> { calls++; entered.countDown(); release.await(); return emptyList() }
        override fun channels(): List<Item> { calls++; release.await(); return emptyList() }
    }
    private class FileDisk(val f: File) : LiveDisk {
        override fun read(): String? = f.takeIf { it.exists() }?.readText()
        override fun write(text: String) = SafeFile.write(f, text)
        override fun readBackup(): String? = SafeFile.readBackup(f)
    }

    @Test fun savedListReachesTheScreenWithin200msWhileThePortalHangs() {
        val f = File(Files.createTempDirectory("live").toFile(), "live.json")
        val cats = cats(); val ch = chans(5000)
        f.writeText(LiveSource.encode(LiveSource.Snapshot(cats, ch, 1L)))
        val hang = Hang()
        val src = LiveSource(hang, FileDisk(f))
        // A refresh is stuck inside the portal (login / categories)...
        val refresh = Thread { runCatching { src.fetch() } }.apply { isDaemon = true; start() }
        assertTrue(hang.entered.await(5, TimeUnit.SECONDS))
        // ...and the saved list is still published at once: read, parse, names, first layout
        val t0 = System.nanoTime()
        val snap = src.local()!!
        val lc = LiveCatalog.withMissingCats(snap.cats, snap.items); val groups = lc.associate { it.id to it.title }
        val raw = snap.items.map { it.copy(name = ChannelTitle.display(it.name, it.num, groups[it.cat].orEmpty())) }
        val firstEmit = (System.nanoTime() - t0) / 1_000_000
        val model = LiveModel.build(LiveInputs(raw, lc, emptyList(), emptySet(), false, null, emptyMap(), LiveModes.DEFAULT, FolderSet(), LiveModes.View.ACTIVE))
        val onScreen = (System.nanoTime() - t0) / 1_000_000
        println("saved list: first emit $firstEmit ms, laid out $onScreen ms, ${model.flat.size} rows (portal hanging)")
        assertEquals(5000, model.flat.size)
        assertTrue("first emit took $firstEmit ms", firstEmit < 200)
        assertTrue(refresh.isAlive)   // the portal never answered
        hang.release.countDown()
    }

    @Test fun downloadGivesUpAfterTheLimitInsteadOfWaitingForever() = runBlocking {
        val hang = Hang()
        val src = LiveSource(hang, FileDisk(File(Files.createTempDirectory("live").toFile(), "live.json")))
        val steps = ArrayList<LiveSource.Step>()
        val t0 = System.nanoTime()
        val e = runCatching { LiveFetch.run(src, stepMs = 300, listMs = 600) { steps += it } }.exceptionOrNull()
        val took = (System.nanoTime() - t0) / 1_000_000
        assertTrue("got $e", e is LiveTimeoutException)
        assertEquals(LiveSource.Step.CATEGORIES, (e as LiveTimeoutException).step)
        assertEquals(listOf(LiveSource.Step.CATEGORIES), steps)
        assertTrue("gave up after $took ms", took in 300..2000)
        hang.release.countDown()
    }

    /** 1.74: giving up on a slow refresh must not interrupt the portal request (a sign-in cut in half broke playback). */
    @Test fun abandonedDownloadIsNotInterruptedAndStillSaves() = runBlocking {
        val release = CountDownLatch(1); val done = CountDownLatch(1)
        var interrupted: Boolean? = null
        val portal = object : LivePortal {
            override fun liveCats(): List<Cat> {
                val ok = runCatching { release.await(5, TimeUnit.SECONDS) }.getOrDefault(false)
                interrupted = !ok || Thread.currentThread().isInterrupted
                return cats(3)
            }
            override fun channels(): List<Item> = chans(30, 3).also { done.countDown() }
        }
        val f = File(Files.createTempDirectory("live").toFile(), "live.json")
        val e = runCatching { LiveFetch.run(LiveSource(portal, FileDisk(f)), stepMs = 200, listMs = 400) }.exceptionOrNull()
        assertTrue("got $e", e is LiveTimeoutException)
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(false, interrupted)
        Thread.sleep(200)
        assertEquals(30, LiveSource(portal, FileDisk(f)).local()!!.items.size)   // the late answer is kept for next time
    }

    @Test fun cancelledRequestsAreToldApartFromTimeouts() {
        assertTrue(isCancellation(InterruptedException()))
        assertTrue(isCancellation(java.io.InterruptedIOException("interrupted")))
        assertFalse(isCancellation(java.net.SocketTimeoutException("timeout")))
        assertFalse(isCancellation(java.io.IOException("reset")))
        assertFalse(isCancellation(PortalHttpException(404, "x")))
    }

    @Test fun downloadReturnsTheListWhenThePortalAnswers() = runBlocking {
        val portal = object : LivePortal {
            override fun liveCats() = cats(3)
            override fun channels() = chans(30, 3)
        }
        val steps = ArrayList<LiveSource.Step>()
        val s = LiveFetch.run(LiveSource(portal, FileDisk(File(Files.createTempDirectory("live").toFile(), "live.json")))) { steps += it }
        assertEquals(30, s.items.size)
        assertEquals(listOf(LiveSource.Step.CATEGORIES, LiveSource.Step.CHANNELS), steps)
    }

    @Test fun cacheDropsLayoutsOfAnOldChannelList() {
        val cats = cats(4); val a = chans(50, 4); val b = chans(60, 4)
        val cache = LiveModelCache()
        fun i(ch: List<Item>, v: LiveModes.View) = LiveInputs(ch, cats, emptyList(), emptySet(), false, null, emptyMap(), LiveModes.DEFAULT, FolderSet(), v)
        cache.put(LiveModel.build(i(a, LiveModes.View.ACTIVE))); cache.put(LiveModel.build(i(a, LiveModes.View.ALL)))
        assertEquals(2, cache.size())
        cache.put(LiveModel.build(i(b, LiveModes.View.ACTIVE)))
        assertEquals(1, cache.size())
        assertNull(cache.get(i(a, LiveModes.View.ALL)))
        // A hide / show changes the inputs: the old layout is not served
        val hid = LiveInputs(b, cats, emptyList(), setOf("ch:itv:1"), false, null, emptyMap(), LiveModes.DEFAULT, FolderSet(), LiveModes.View.ACTIVE)
        assertNull(cache.get(hid))
    }
}
