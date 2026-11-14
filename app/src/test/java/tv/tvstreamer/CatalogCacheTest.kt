package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CatalogCacheTest {
    private fun item(id: String, cat: String = "c1", name: String = "Title $id", added: String = "2024-01-0$id 00:00:00") =
        Item("vod", id, name, "cmd$id", cat, 0, false, added = added)

    @Test fun mergeAppendsAndDedupesById() {
        val a = listOf(item("1"), item("2"))
        val page = listOf(item("2", name = "dup"), item("3"))
        val (all, fresh) = CatalogDisk.merge(a, page)
        assertEquals(listOf("vod:1", "vod:2", "vod:3"), all.map { it.key })
        assertEquals(listOf("vod:3"), fresh.map { it.key })
        assertEquals("Title 2", all[1].name) // existing wins
    }

    @Test fun mergeFrontPutsNewFirst() {
        val (all, fresh) = CatalogDisk.merge(listOf(item("1")), listOf(item("9")), front = true)
        assertEquals(listOf("vod:9", "vod:1"), all.map { it.key })
        assertEquals(listOf("vod:9"), fresh.map { it.key })
    }

    @Test fun mergeEmptyPageChangesNothing() {
        val a = listOf(item("1"))
        val (all, fresh) = CatalogDisk.merge(a, emptyList())
        assertEquals(a, all)
        assertTrue(fresh.isEmpty())
    }

    @Test fun appendOnlyRoundTripDedupes() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cat-cache-${System.nanoTime()}").apply { mkdirs() }
        try {
            val body = File(dir, "body.jsonl")
            val meta = File(dir, "meta.json")
            CatalogDisk.append(body, listOf(item("1"), item("2")))
            CatalogDisk.append(body, listOf(item("2", name = "again"), item("3")))
            CatalogDisk.writeMeta(meta, CatalogMeta(account = "acc", refreshedAt = 10, size = 3,
                catNext = mapOf("c1" to 3), catEnd = setOf("c1")))
            val items = CatalogDisk.readBody(body)
            assertEquals(listOf("vod:1", "vod:2", "vod:3"), items.map { it.key })
            assertEquals("Title 2", items[1].name) // first occurrence kept
            val m = CatalogDisk.readMeta(meta)!!
            assertEquals("acc", m.account)
            assertEquals(10L, m.refreshedAt)
            assertEquals(3, m.catNext["c1"])
            assertEquals(setOf("c1"), m.catEnd)
        } finally { dir.deleteRecursively() }
    }

    @Test fun staleWhenNeverPulledOrIntervalPassedOrNewLocalDay() {
        val h = CatalogStale.intervalMs(6)
        // Mid-day UTC so a 5-hour window does not cross a day boundary
        val t0 = 2 * CatalogStale.DAY_MS + 12 * 3_600_000L
        assertTrue(CatalogStale.due(t0, 0, h))
        assertFalse(CatalogStale.due(t0 + 5 * 3_600_000L, t0, h, offsetMs = 0))
        assertTrue(CatalogStale.due(t0 + 6 * 3_600_000L, t0, h, offsetMs = 0))
        // First launch of the next UTC day, even when the interval has not passed
        val justBeforeMidnight = CatalogStale.DAY_MS - 60_000L
        val afterMidnight = CatalogStale.DAY_MS + 60_000L
        assertTrue(CatalogStale.due(afterMidnight, justBeforeMidnight, h, offsetMs = 0))
        // Same local day, interval not passed
        assertFalse(CatalogStale.due(justBeforeMidnight, justBeforeMidnight - 60_000L, h, offsetMs = 0))
        // A positive offset moves the day boundary
        val offset = -5 * 3_600_000L // UTC-5
        val now = 10 * CatalogStale.DAY_MS + 2 * 3_600_000L // 02:00 UTC = 21:00 previous local day
        val last = 10 * CatalogStale.DAY_MS + 1 * 3_600_000L
        assertFalse(CatalogStale.due(now, last, h, offsetMs = offset))
    }

    @Test fun indexBuildsAndAppendsIncrementally() {
        val a = listOf(item("1", "movies"), item("2", "shows"))
        fun tab(i: Item) = if (i.cat == "shows") "series" else "movies"
        fun lang(i: Item) = if (i.id == "1") "English" else "Hindi"
        val ix = CatalogIndexes.build(a, ::tab, ::lang)
        assertEquals(listOf("vod:1"), ix.byCat["movies"])
        assertEquals(listOf("vod:2"), ix.byCat["shows"])
        assertEquals(listOf("vod:1"), ix.pools[CatalogIndexes.poolKey("movies", setOf("English"))])
        val ix2 = CatalogIndexes.append(ix, listOf(item("3", "movies")), ::tab, ::lang)
        assertEquals(listOf("vod:1", "vod:3"), ix2.byCat["movies"])
        assertEquals(ix.byCat["shows"], ix2.byCat["shows"])
        // Previous language pools stay; new item goes to its language + wildcard
        assertEquals(listOf("vod:1"), ix2.pools[CatalogIndexes.poolKey("movies", setOf("English"))])
        assertTrue("vod:3" in (ix2.pools["movies|*"] ?: emptyList()))
    }

    @Test fun indexRoundTripJson() {
        val ix = CatalogIndexes(mapOf("c" to listOf("vod:1", "vod:2")), mapOf("movies|*" to listOf("vod:1")))
        val back = CatalogIndexes.from(ix.toJson())
        assertEquals(ix.byCat, back.byCat)
        assertEquals(ix.pools, back.pools)
    }

    @Test fun measuredMergeAndIndexStayCheap() {
        val existing = (1..3000).map { item(it.toString(), if (it % 2 == 0) "a" else "b") }
        val page = (2900..3200).map { item(it.toString(), "a") }
        val t0 = System.nanoTime()
        val (all, fresh) = CatalogDisk.merge(existing, page)
        val mergeMs = (System.nanoTime() - t0) / 1_000_000.0
        assertEquals(3200, all.size)
        assertEquals(200, fresh.size)
        val t1 = System.nanoTime()
        val ix = CatalogIndexes.build(existing, { "movies" }, { "English" })
        val buildMs = (System.nanoTime() - t1) / 1_000_000.0
        val t2 = System.nanoTime()
        CatalogIndexes.append(ix, fresh, { "movies" }, { "English" })
        val incMs = (System.nanoTime() - t2) / 1_000_000.0
        // Loose ceilings so a slow CI box still passes; the report quotes the measured values.
        assertTrue("merge ${mergeMs}ms", mergeMs < 250)
        assertTrue("full index ${buildMs}ms", buildMs < 250)
        assertTrue("incremental ${incMs}ms", incMs < 50)
        println("CatalogCache timings: merge=${"%.2f".format(mergeMs)}ms build=${"%.2f".format(buildMs)}ms incremental=${"%.2f".format(incMs)}ms")
    }
}
