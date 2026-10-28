package tv.tvstreamer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncTest {
    private fun item(k: String, i: String, n: String = "Name $i") = JSONObject().put("k", k).put("i", i).put("n", n).toString()

    @Test fun stampRecordsAddsChangesAndRemovals() {
        val j0 = Sync.stamp(emptyMap(), mapOf("a" to "1", "b" to "2"), now = 100)
        assertEquals(SyncEntry(100, "1"), j0["a"])
        val j1 = Sync.stamp(j0, mapOf("a" to "1", "b" to "3"), now = 200)
        assertEquals(100L, j1["a"]!!.t)                     // unchanged keeps its time
        assertEquals(SyncEntry(200, "3"), j1["b"])
        val j2 = Sync.stamp(j1, mapOf("b" to "3"), now = 300)
        assertEquals(SyncEntry(300, null), j2["a"])          // removal -> tombstone
        val j3 = Sync.stamp(j2, mapOf("b" to "3"), now = 400)
        assertEquals(j2, j3)                                 // nothing changed -> same journal
    }

    @Test fun firstSyncLetsCloudWin() {
        val local = Sync.stamp(emptyMap(), mapOf("pref/aspect" to "fill", "fav/itv:1" to "x"), now = 5000, first = true)
        val cloud = mapOf("pref/aspect" to SyncEntry(1000, "zoom"))
        val m = Sync.merge(local, cloud)
        assertEquals("zoom", m["pref/aspect"]!!.v)            // existing cloud value wins
        assertEquals("x", m["fav/itv:1"]!!.v)                 // local extra is kept
    }

    @Test fun mergeIsPerEntryNewestWinsAndSymmetric() {
        val a = mapOf("fav/itv:1" to SyncEntry(10, "A"), "fav/itv:2" to SyncEntry(50, null), "pref/x" to SyncEntry(30, "a"))
        val b = mapOf("fav/itv:2" to SyncEntry(40, "B"), "fav/itv:3" to SyncEntry(20, "C"), "pref/x" to SyncEntry(30, "b"))
        val m = Sync.merge(a, b)
        assertEquals("A", m["fav/itv:1"]!!.v)
        assertNull(m["fav/itv:2"]!!.v)                       // newer removal beats older add
        assertEquals("C", m["fav/itv:3"]!!.v)
        assertEquals("b", m["pref/x"]!!.v)                   // tie -> larger value, same on both devices
        assertEquals(m, Sync.merge(b, a))
        assertEquals(SyncEntry(5, null), Sync.winner(SyncEntry(5, "v"), SyncEntry(5, null)))
    }

    @Test fun twoDevicesEditingDoNotClobber() {
        // Both start from the same synced state
        val base = Sync.stamp(emptyMap(), mapOf("fav/itv:1" to "1", "hidden/itv:9" to "1"), now = 100)
        val tv = Sync.stamp(base, mapOf("fav/itv:1" to "1", "fav/itv:2" to "2", "hidden/itv:9" to "1"), now = 200)  // TV adds a fav
        val phone = Sync.stamp(base, mapOf("fav/itv:1" to "1"), now = 300)                                       // phone unhides
        val cloud = Sync.merge(tv, phone)
        assertEquals(setOf("fav/itv:1", "fav/itv:2"), Sync.values(cloud).keys)
    }

    @Test fun tombstonesExpire() {
        val m = mapOf("a" to SyncEntry(1, null), "b" to SyncEntry(1, "keep"))
        val p = Sync.prune(m, now = 1 + Sync.TOMBSTONE_TTL + 1)
        assertFalse("a" in p); assertTrue("b" in p)
    }

    @Test fun docJsonRoundTripAndSchema() {
        val d = SyncDoc(updated = 42, device = "TV", entries = mapOf("fav/itv:1" to SyncEntry(7, "{\"k\":\"itv\"}"), "x" to SyncEntry(8, null)))
        val o = d.toJson()
        assertEquals("TVstreamer", o.getString("app"))
        assertEquals(Sync.SCHEMA, o.getInt("schema"))
        assertTrue(o.getJSONObject("entries").getJSONObject("x").getBoolean("del"))
        assertFalse(o.getJSONObject("entries").getJSONObject("x").has("v"))
        assertEquals(d, SyncDoc.fromJson(o.toString()))
        assertThrows(IllegalArgumentException::class.java) { SyncDoc.fromJson("{\"app\":\"Other\",\"schema\":1}") }
        assertThrows(IllegalArgumentException::class.java) { SyncDoc.fromJson("{\"app\":\"TVstreamer\"}") }
        assertEquals(2, SyncDoc.fromJson(JSONObject(o.toString()).put("schema", 2).toString()).schema)
    }

    @Test fun codecRoundTripIsExact() {
        val l = SyncLocal(
            favs = listOf(item("itv", "2"), item("vod", "7"), item("itv", "1")),
            hidden = setOf("itv:5", "ch:itv:9"),
            custom = sortedMapOf("My Channels" to listOf(item("itv", "3"), item("itv", "4"))),
            orders = sortedMapOf("itv" to listOf("3", "1", "2")),
            langs = sortedMapOf("live" to setOf("English", "Hindi"), "movies" to emptySet()),
            prefs = sortedMapOf("aspect" to "zoom", "sort_live_mode" to "fav", "keymap" to "{\"s\":{}}"),
            recent = "[" + item("itv", "1") + "]",
            resume = listOf(JSONObject(item("vod", "7")).put("p", 1000).put("d", 5000).toString()),
            lastEp = sortedMapOf("vod:100" to "{\"e\":\"vod:101\",\"d\":true}"),
            fixes = sortedMapOf("movie|x|2001" to "movie:12"),
            secrets = sortedMapOf("portal" to "http://p")
        )
        val flat = SyncCodec.flatten(l)
        assertEquals("[\"itv:2\",\"vod:7\",\"itv:1\"]", flat["favorder"])
        assertEquals("[\"English\",\"Hindi\"]", flat["langs/live"])
        val entries = Sync.stamp(emptyMap(), flat, now = 10)
        val back = SyncCodec.unflatten(entries)
        assertEquals(l, back)
        assertEquals(flat, SyncCodec.flatten(back))
    }

    @Test fun favouritesAddedElsewhereAreAppendedAndRemovalsApplied() {
        val a = SyncCodec.flatten(SyncLocal(favs = listOf(item("itv", "1"), item("itv", "2"))))
        val base = Sync.stamp(emptyMap(), a, now = 100)
        val tv = Sync.stamp(base, SyncCodec.flatten(SyncLocal(favs = listOf(item("itv", "2"), item("itv", "1"), item("itv", "3")))), now = 200)
        val phone = Sync.stamp(base, SyncCodec.flatten(SyncLocal(favs = listOf(item("itv", "1")))), now = 300) // phone removed 2
        val m = SyncCodec.unflatten(Sync.merge(tv, phone))
        val keys = m.favs.map { JSONObject(it).getString("i") }
        assertEquals(listOf("1", "3"), keys)
    }

    @Test fun resumeIsNewestFirstAndCapped() {
        val e = (1..5).associate { "resume/vod:$it" to SyncEntry(it.toLong(), JSONObject(item("vod", "$it")).put("p", it).toString()) }
        val l = SyncCodec.unflatten(e, maxResume = 3)
        assertEquals(listOf("5", "4", "3"), l.resume.map { JSONObject(it).getString("i") })
    }

    @Test fun secretsAreSkippableAndTokensMasked() {
        val j = Sync.stamp(mapOf("secret/pw" to SyncEntry(1, "x")), emptyMap(), now = 50, skip = { Sync.isSecret(it) })
        assertEquals(SyncEntry(1, "x"), j["secret/pw"])      // not tombstoned while secrets are off
        val masked = maskGoogle("Authorization: Bearer ya29.a0AfB_abc-123 refresh_token=1//0gAbCdEfGhIjKl client_secret=ABCDEF-zzz")
        assertFalse(masked.contains("a0AfB")); assertFalse(masked.contains("0gAbCdEf")); assertFalse(masked.contains("zzz"))
        // a bare client secret in an error body, and the built-in secret passed as "known"
        val bare = maskGoogle("bad pair ABCDEF-Qw3rTy_uIoP-aSdFgHjKlZxCvBnM12 end", known = listOf("my-built-in-secret"))
        assertFalse(bare.contains("Qw3rTy")); assertTrue(bare.contains("end"))
        assertEquals("x *** y", maskGoogle("x my-built-in-secret y", known = listOf("my-built-in-secret")))
    }
}
