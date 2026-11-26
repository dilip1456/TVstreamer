package tv.tvstreamer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * When the movie / series catalog may ask the portal again:
 *  - no previous pull, or
 *  - at least [intervalMs] have passed (default 6 hours), or
 *  - this is the first launch on a new local calendar day.
 * Otherwise the saved list is used and the portal is not contacted.
 */
object CatalogStale {
    const val DEFAULT_HOURS = 6
    val HOURS = listOf(3, 6, 12, 24)
    const val DAY_MS = 86_400_000L

    fun intervalMs(hours: Int): Long = hours.coerceIn(1, 168) * 3_600_000L

    fun localDay(epochMs: Long, offsetMs: Long): Long = Math.floorDiv(epochMs + offsetMs, DAY_MS)

    /** True when a portal pull is allowed. [offsetMs] is the local timezone offset from UTC. */
    fun due(now: Long, lastRefresh: Long, intervalMs: Long, offsetMs: Long = 0L): Boolean {
        if (lastRefresh <= 0L) return true
        if (now - lastRefresh >= intervalMs) return true
        return localDay(now, offsetMs) > localDay(lastRefresh, offsetMs)
    }
}

/** Header stored beside the append-only item file. */
data class CatalogMeta(
    val version: Int = 3,
    val account: String = "",
    val refreshedAt: Long = 0L,
    val size: Int = 0,
    val catNext: Map<String, Int> = emptyMap(),
    val catEnd: Set<String> = emptySet()
) {
    fun toJson(): JSONObject {
        val cn = JSONObject(); catNext.forEach { (k, v) -> cn.put(k, v) }
        return JSONObject()
            .put("v", version).put("a", account).put("t", refreshedAt).put("n", size)
            .put("cn", cn).put("ce", JSONArray(catEnd.toList()))
    }

    companion object {
        fun from(o: JSONObject): CatalogMeta {
            val cn = mutableMapOf<String, Int>()
            o.optJSONObject("cn")?.let { j -> j.keys().forEach { k -> cn[k] = j.getInt(k) } }
            val ce = mutableSetOf<String>()
            o.optJSONArray("ce")?.let { a -> for (i in 0 until a.length()) ce += a.getString(i) }
            return CatalogMeta(o.optInt("v", 3), o.optString("a"), o.optLong("t"), o.optInt("n"), cn, ce)
        }
    }
}

/**
 * Append-only catalog on disk: [metaFile] is rewritten, [bodyFile] only grows with new titles
 * (deduped by item key). A compact rewrite can be used later to drop leftovers.
 */
object CatalogDisk {
    /** Items already on disk plus [add], unique by key, [add] first when [front]. */
    fun merge(existing: List<Item>, add: List<Item>, front: Boolean = false): Pair<List<Item>, List<Item>> {
        val known = existing.mapTo(HashSet()) { it.key }
        val fresh = add.filter { known.add(it.key) }.distinctBy { it.key }
        val all = if (front) fresh + existing else existing + fresh
        return all to fresh
    }

    fun writeMeta(file: File, meta: CatalogMeta) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(meta.toJson().toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun readMeta(file: File): CatalogMeta? =
        runCatching { if (file.exists()) CatalogMeta.from(JSONObject(file.readText())) else null }.getOrNull()

    /** Append [items] as one JSON object per line. */
    fun append(file: File, items: List<Item>) {
        if (items.isEmpty()) return
        file.parentFile?.mkdirs()
        file.appendText(buildString {
            for (i in items) { append(i.toJson().toString()); append('\n') }
        })
    }

    /** Rewrite the whole body (used to migrate the v2 single-file format, or to compact). */
    fun rewriteBody(file: File, items: List<Item>) {
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for (i in items) { w.write(i.toJson().toString()); w.newLine() }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Read items, last occurrence of a key wins, order of first occurrence kept. */
    fun readBody(file: File): List<Item> {
        if (!file.exists()) return emptyList()
        val first = LinkedHashMap<String, Item>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching {
                    val i = Item.from(JSONObject(line))
                    if (i.key !in first) first[i.key] = i
                }
            }
        }
        return first.values.toList()
    }

    /**
     * Legacy v2: first line is a header JSON, the rest are items. Returns null when the file is absent
     * or is not that format (so the caller can try the split files).
     */
    fun readLegacy(file: File, account: String): Pair<CatalogMeta, List<Item>>? {
        if (!file.exists()) return null
        var header: CatalogMeta? = null
        val items = ArrayList<Item>()
        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { n, line ->
                if (n == 0) {
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
                    if (!o.has("v") || !o.has("a")) return null
                    if (o.optString("a") != account) return CatalogMeta.from(o) to emptyList()
                    header = CatalogMeta.from(o)
                } else if (line.isNotBlank()) runCatching { items += Item.from(JSONObject(line)) }
            }
        }
        val h = header ?: return null
        return if (h.version < 2) {
            val cats = items.map { it.cat }.distinct()
            h.copy(catNext = cats.associateWith { 2 }, catEnd = cats.toSet()) to items
        } else h to items
    }
}

/** Per-category and per-tab/language key lists, cheap to persist and to update as pages arrive. */
data class CatalogIndexes(
    val byCat: Map<String, List<String>> = emptyMap(),
    val pools: Map<String, List<String>> = emptyMap()
) {
    fun toJson(): JSONObject {
        fun map(m: Map<String, List<String>>): JSONObject {
            val o = JSONObject()
            m.forEach { (k, v) -> o.put(k, JSONArray(v)) }
            return o
        }
        return JSONObject().put("v", 1).put("byCat", map(byCat)).put("pools", map(pools))
    }

    companion object {
        fun from(o: JSONObject): CatalogIndexes {
            fun map(name: String): Map<String, List<String>> {
                val src = o.optJSONObject(name) ?: return emptyMap()
                val out = LinkedHashMap<String, List<String>>()
                src.keys().forEach { k ->
                    val a = src.optJSONArray(k) ?: return@forEach
                    out[k] = (0 until a.length()).map { a.getString(it) }
                }
                return out
            }
            return CatalogIndexes(map("byCat"), map("pools"))
        }

        fun poolKey(tab: String, langs: Set<String>?): String =
            tab + "|" + (langs?.sorted()?.joinToString(",") ?: "*")

        /** Full rebuild from items already classified with [tabOf] and [langOf]. */
        fun build(
            items: List<Item>,
            tabOf: (Item) -> String,
            langOf: (Item) -> String
        ): CatalogIndexes {
            val byCat = LinkedHashMap<String, MutableList<String>>()
            val pools = LinkedHashMap<String, MutableList<String>>()
            for (i in items) {
                byCat.getOrPut(i.cat) { mutableListOf() }.add(i.key)
                val tab = tabOf(i)
                val lang = langOf(i)
                pools.getOrPut("$tab|*") { mutableListOf() }.add(i.key)
                pools.getOrPut(poolKey(tab, setOf(lang))) { mutableListOf() }.add(i.key)
            }
            return CatalogIndexes(byCat.mapValues { it.value.toList() }, pools.mapValues { it.value.toList() })
        }

        /** Append [add] (already deduped) onto an existing index. */
        fun append(
            prev: CatalogIndexes,
            add: List<Item>,
            tabOf: (Item) -> String,
            langOf: (Item) -> String
        ): CatalogIndexes {
            if (add.isEmpty()) return prev
            val byCat = prev.byCat.mapValues { it.value.toMutableList() }.toMutableMap()
            val pools = prev.pools.mapValues { it.value.toMutableList() }.toMutableMap()
            for (i in add) {
                byCat.getOrPut(i.cat) { mutableListOf() }.add(i.key)
                val tab = tabOf(i)
                val lang = langOf(i)
                pools.getOrPut("$tab|*") { mutableListOf() }.add(i.key)
                pools.getOrPut(poolKey(tab, setOf(lang))) { mutableListOf() }.add(i.key)
            }
            return CatalogIndexes(byCat, pools)
        }
    }
}

/** Stable sort of groups already computed, by added date / year / name only (no network). */
object CatalogSort {
    fun added(groups: List<Group>): List<Group> =
        groups.sortedWith(compareBy<Group> { if (it.items.maxOf { x -> x.addedMs } > 0) 0 else 1 }.thenByDescending { it.items.maxOf { x -> x.addedMs } })

    fun year(groups: List<Group>, yearOf: (Item) -> Int): List<Group> =
        groups.sortedWith(compareBy<Group> { if (yearOf(it.best) > 0) 0 else 1 }.thenByDescending { yearOf(it.best) })

    fun az(groups: List<Group>, nameOf: (Item) -> String): List<Group> =
        groups.sortedBy { nameOf(it.best).ifEmpty { "\uFFFF" } }
}
