package tv.tvstreamer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EpgTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")

    @Test fun parsesTimestampsArray() {
        val r = JSONObject("""{"js":[{"name":"News at 9","start_timestamp":"1700000000","stop_timestamp":"1700003600"},
            {"name":"Weather","start_timestamp":1700003600,"stop_timestamp":1700005400}]}""")
        val m = Epg.parse(r, "7", utc)
        assertEquals(listOf("News at 9", "Weather"), m["7"]!!.map { it.title })
        assertEquals(1_700_000_000_000L, m["7"]!![0].start)
    }

    @Test fun parsesDataAndTimeStringsAndDuration() {
        val r = JSONObject("""{"js":{"data":[{"name":"Show","time":"2024-05-01 20:00:00","duration":1800}]}}""")
        val p = Epg.parse(r, "1", utc)["1"]!!.single()
        assertEquals(30 * 60_000L, p.stop - p.start)
    }

    @Test fun batchAnswerIsFiledPerChannel() {
        val r = JSONObject("""{"js":{"5":[{"name":"A","start_timestamp":100,"stop_timestamp":200}],"6":[{"name":"B","start_timestamp":100,"stop_timestamp":200}]}}""")
        val m = Epg.parse(r, "5", utc)
        assertEquals("A", m["5"]!!.single().title)
        assertEquals("B", m["6"]!!.single().title)
    }

    @Test fun emptyAnswerIsCachedAsEmpty() {
        val m = Epg.parse(JSONObject("""{"js":[]}"""), "3", utc)
        assertTrue(m.containsKey("3")); assertTrue(m["3"]!!.isEmpty())
    }

    @Test fun nowNextAndProgress() {
        val l = listOf(Prog("A", 0, 100), Prog("B", 100, 200), Prog("C", 200, 300))
        assertEquals("B", Epg.now(l, 150)?.title)
        assertEquals("C", Epg.next(l, 150)?.title)
        assertEquals(0.5f, Prog("B", 100, 200).progress(150), 0.001f)
        assertEquals(listOf("B", "C"), Epg.upcoming(l, 150).map { it.title })
    }

    @Test fun freshAndPickRespectTtlAndBackoff() {
        val t = 10 * Epg.TTL_MS
        val cache = mapOf("1" to Epg.Entry(t - 1000, listOf(Prog("x", t - 10, t + 10_000))), "2" to Epg.Entry(t - Epg.TTL_MS - 1, emptyList()))
        assertTrue(Epg.fresh(cache["1"], t))
        assertFalse(Epg.fresh(cache["2"], t))
        // nothing cached ("3") goes before stale ("2")
        assertEquals("3", Epg.pick(listOf("1", "2", "3"), cache, emptyMap(), t))
        assertEquals("2", Epg.pick(listOf("1", "2", "3"), cache, mapOf("3" to t - 1000), t))
        assertEquals("2", Epg.pick(listOf("1", "2"), cache, emptyMap(), t))
        assertNull(Epg.pick(listOf("1"), cache, emptyMap(), t))
    }
}
