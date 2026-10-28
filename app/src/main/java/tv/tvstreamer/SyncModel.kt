package tv.tvstreamer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud sync data model (pure Kotlin + org.json, unit tested).
 *
 * Everything that syncs is flattened into small entries ("fav/itv:12" -> the item's JSON).
 * Each entry has the time it last changed; removals are kept as tombstones (v = null) so a removal on one
 * device is not undone by the other. Two copies merge entry by entry: the newer change wins.
 */
data class SyncEntry(val t: Long, val v: String?) {
    val deleted: Boolean get() = v == null
}

data class SyncDoc(
    val schema: Int = Sync.SCHEMA,
    val updated: Long = 0L,
    val device: String = "",
    val entries: Map<String, SyncEntry> = emptyMap()
) {
    fun toJson(): JSONObject {
        val e = JSONObject()
        for (k in entries.keys.sorted()) {
            val x = entries.getValue(k)
            e.put(k, if (x.v == null) JSONObject().put("t", x.t).put("del", true) else JSONObject().put("t", x.t).put("v", x.v))
        }
        return JSONObject().put("app", "TVstreamer").put("schema", schema).put("updated", updated).put("device", device).put("entries", e)
    }

    companion object {
        /** Throws on anything that is not a TVstreamer sync file. */
        fun fromJson(text: String): SyncDoc {
            val o = JSONObject(text)
            require(o.optString("app") == "TVstreamer") { "Not a TVstreamer sync file" }
            val schema = o.optInt("schema", 0)
            require(schema >= 1) { "Unknown sync file version" }
            val e = o.optJSONObject("entries") ?: JSONObject()
            val m = LinkedHashMap<String, SyncEntry>()
            for (k in e.keys()) {
                val x = e.optJSONObject(k) ?: continue
                val t = x.optLong("t", 0L)
                m[k] = if (x.optBoolean("del") || !x.has("v")) SyncEntry(t, null) else SyncEntry(t, x.getString("v"))
            }
            return SyncDoc(schema, o.optLong("updated"), o.optString("device"), m)
        }
    }
}

object Sync {
    const val SCHEMA = 1
    /** Tombstones older than this are dropped (a device offline for longer may bring an item back). */
    const val TOMBSTONE_TTL = 120L * 24 * 3600 * 1000
    /** Time given to this device's existing data on its very first sync, so the cloud copy wins any conflict. */
    const val FIRST_SYNC_T = 1L

    /**
     * Records local changes: [current] is the device's data right now (entry key -> value).
     * New or changed values get time [now]; values that disappeared become tombstones.
     * Keys for which [skip] is true are left exactly as they are in the journal.
     * On the first sync ([first]) existing data gets [FIRST_SYNC_T] so the cloud copy wins on conflicts.
     */
    fun stamp(
        journal: Map<String, SyncEntry>, current: Map<String, String>, now: Long,
        first: Boolean = false, skip: (String) -> Boolean = { false }
    ): Map<String, SyncEntry> {
        val out = LinkedHashMap<String, SyncEntry>()
        for ((k, j) in journal) out[k] = j
        for ((k, v) in current) {
            if (skip(k)) continue
            val j = journal[k]
            if (j == null) out[k] = SyncEntry(if (first) FIRST_SYNC_T else now, v)
            else if (j.v != v) out[k] = SyncEntry(maxOf(now, j.t + 1), v)
        }
        for ((k, j) in journal) if (!skip(k) && j.v != null && k !in current) out[k] = SyncEntry(maxOf(now, j.t + 1), null)
        return prune(out, now)
    }

    /** Entry-by-entry merge: newer time wins; on a tie a removal wins, then the larger value (same result on every device). */
    fun merge(a: Map<String, SyncEntry>, b: Map<String, SyncEntry>): Map<String, SyncEntry> {
        val out = LinkedHashMap<String, SyncEntry>()
        for (k in (a.keys + b.keys)) {
            val x = a[k]; val y = b[k]
            out[k] = when {
                x == null -> y!!
                y == null -> x
                else -> winner(x, y)
            }
        }
        return out
    }

    fun winner(x: SyncEntry, y: SyncEntry): SyncEntry = when {
        x.t != y.t -> if (x.t > y.t) x else y
        x.v == y.v -> x
        x.v == null -> x
        y.v == null -> y
        else -> if (x.v >= y.v) x else y
    }

    fun prune(m: Map<String, SyncEntry>, now: Long): Map<String, SyncEntry> =
        m.filter { (_, e) -> e.v != null || e.t > now - TOMBSTONE_TTL }

    /** Live values only (no tombstones). */
    fun values(m: Map<String, SyncEntry>): Map<String, String> =
        m.filterValues { it.v != null }.mapValues { it.value.v!! }

    fun isSecret(key: String) = key.startsWith("secret/")
}

/**
 * The device's syncable data as plain values (raw JSON strings as they are stored), so a value written from the
 * cloud reads back as exactly the same string.
 */
data class SyncLocal(
    /** Favourites in order: raw item JSON (key "k:i"). */
    val favs: List<String> = emptyList(),
    /** Hidden categories ("itv:5", "vod:9") and channels ("ch:itv:12"). */
    val hidden: Set<String> = emptySet(),
    /** "My Channels" style user categories: name -> raw item JSON list. */
    val custom: Map<String, List<String>> = emptyMap(),
    /** Category order per kind ("itv" / "vod"). */
    val orders: Map<String, List<String>> = emptyMap(),
    /** Selected languages per tab; a tab that was never chosen is absent. */
    val langs: Map<String, Set<String>> = emptyMap(),
    /** Plain string settings (sort choices, live mode settings, player options, remote keys). */
    val prefs: Map<String, String> = emptyMap(),
    /** Recently played channels (raw JSON array). Empty = none. */
    val recent: String = "",
    /** Continue watching records (raw JSON, newest first). */
    val resume: List<String> = emptyList(),
    /** Last played episode per series: series key -> raw JSON. */
    val lastEp: Map<String, String> = emptyMap(),
    /** TMDB "Fix match" choices: cache key -> "movie:123" / "tv:45" / "none". */
    val fixes: Map<String, String> = emptyMap(),
    /** Portal login and API keys; only filled when the user turned that on. */
    val secrets: Map<String, String> = emptyMap()
)

object SyncCodec {
    private const val SEP = "\t"

    private fun keyOf(json: String): String? = runCatching {
        val o = JSONObject(json); "${o.getString("k")}:${o.getString("i")}"
    }.getOrNull()

    fun flatten(l: SyncLocal): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val favKeys = mutableListOf<String>()
        for (f in l.favs) keyOf(f)?.let { k -> if (k !in favKeys) { favKeys += k; m["fav/$k"] = f } }
        if (favKeys.isNotEmpty()) m["favorder"] = JSONArray(favKeys).toString()
        for (h in l.hidden) m["hidden/$h"] = "1"
        for ((name, items) in l.custom) {
            val keys = mutableListOf<String>()
            for (f in items) keyOf(f)?.let { k -> keys += k; m["custom/$name$SEP$k"] = f }
            m["customorder/$name"] = JSONArray(keys).toString()
        }
        for ((kind, ids) in l.orders) if (ids.isNotEmpty()) m["order/$kind"] = JSONArray(ids).toString()
        for ((tab, set) in l.langs) m["langs/$tab"] = JSONArray(set.sorted()).toString()
        for ((k, v) in l.prefs) m["pref/$k"] = v
        if (l.recent.isNotEmpty() && l.recent != "[]") m["recent"] = l.recent
        for (r in l.resume) keyOf(r)?.let { k -> m["resume/$k"] = r }
        for ((k, v) in l.lastEp) m["lastep/$k"] = v
        for ((k, v) in l.fixes) m["fix/$k"] = v
        for ((k, v) in l.secrets) if (v.isNotEmpty()) m["secret/$k"] = v
        return m
    }

    private fun arr(s: String?): List<String> = runCatching {
        val a = JSONArray(s ?: "[]"); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** Rebuilds the device data from merged entries (tombstones ignored). Entry times order lists without an explicit order. */
    fun unflatten(entries: Map<String, SyncEntry>, maxResume: Int = 40): SyncLocal {
        val live = entries.filterValues { it.v != null }
        fun under(prefix: String) = live.filterKeys { it.startsWith(prefix) }
        fun newestFirst(m: Map<String, SyncEntry>) = m.entries.sortedWith(compareByDescending<Map.Entry<String, SyncEntry>> { it.value.t }.thenBy { it.key })
        fun oldestFirst(m: Map<String, SyncEntry>) = m.entries.sortedWith(compareBy<Map.Entry<String, SyncEntry>> { it.value.t }.thenBy { it.key })

        // Favourites: saved order first, then any added elsewhere (oldest first)
        val favMap = under("fav/").mapKeys { it.key.removePrefix("fav/") }
        val order = arr(live["favorder"]?.v).filter { it in favMap }
        val favKeys = order + oldestFirst(favMap).map { it.key }.filter { it !in order }
        val favs = favKeys.map { favMap.getValue(it).v!! }

        val hidden = under("hidden/").keys.map { it.removePrefix("hidden/") }.toSet()

        val customRaw = under("custom/").entries.groupBy { it.key.removePrefix("custom/").substringBefore(SEP) }
        val custom = customRaw.mapValues { (name, list) ->
            val byKey = list.associate { it.key.substringAfter(SEP) to it.value }
            val o = arr(live["customorder/$name"]?.v).filter { it in byKey }
            val keys = o + oldestFirst(byKey).map { it.key }.filter { it !in o }
            keys.map { byKey.getValue(it).v!! }
        }.toSortedMap()

        val orders = under("order/").mapKeys { it.key.removePrefix("order/") }.mapValues { arr(it.value.v) }.toSortedMap()
        val langs = under("langs/").mapKeys { it.key.removePrefix("langs/") }.mapValues { arr(it.value.v).toSet() }.toSortedMap()
        val prefs = under("pref/").mapKeys { it.key.removePrefix("pref/") }.mapValues { it.value.v!! }.toSortedMap()
        val recent = live["recent"]?.v ?: ""
        val resume = newestFirst(under("resume/")).take(maxResume).map { it.value.v!! }
        val lastEp = under("lastep/").mapKeys { it.key.removePrefix("lastep/") }.mapValues { it.value.v!! }.toSortedMap()
        val fixes = under("fix/").mapKeys { it.key.removePrefix("fix/") }.mapValues { it.value.v!! }.toSortedMap()
        val secrets = under("secret/").mapKeys { it.key.removePrefix("secret/") }.mapValues { it.value.v!! }.toSortedMap()
        return SyncLocal(favs, hidden, custom, orders, langs, prefs, recent, resume, lastEp, fixes, secrets)
    }
}
