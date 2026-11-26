package tv.tvstreamer

import org.json.JSONArray
import org.json.JSONObject

/** One watchlist. The built-in one ([WatchLists.MY]) is the old "My List" (its items stay in the synced "fav" list). */
data class WatchList(val id: String, val name: String, val items: List<Item> = emptyList())

/**
 * Several watchlists for movies and series (1.97). "My List" keeps its old storage and Google sync (pref "fav",
 * synced item by item); the lists the user creates live in one JSON pref ("watchlists", synced as a whole like any
 * plain setting). One list is the default: "Add to Watchlist" from any menu puts the title there.
 * Pure helpers, unit tested.
 */
object WatchLists {
    const val MY = "mylist"
    const val MY_NAME = "My List"
    const val PREF = "watchlists"
    const val PREF_DEFAULT = "watchlist_default"
    const val PREF_MY_NAME = "mylist_name"
    const val ADD = "Add to Watchlist"
    const val REMOVE = "Remove from Watchlist"
    const val OTHER = "Add to Other List…"
    const val NEW = "New Watchlist…"
    const val SET_DEFAULT = "Set as Default"
    private const val MAX_NAME = 40

    fun clean(name: String): String = name.replace(Regex("[\\r\\n\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_NAME)

    /** The user's lists (not "My List") from the pref; unreadable or duplicate entries are dropped. */
    fun fromJson(text: String?): List<WatchList> {
        if (text.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<WatchList>()
        val ids = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val name = clean(o.optString("name"))
            if (id.isEmpty() || id == MY || name.isEmpty() || !ids.add(id)) continue
            val items = o.optJSONArray("items")?.toItems().orEmpty().filter { it.kind == "vod" }.distinctBy { it.key }
            out += WatchList(id, name, items)
        }
        return out
    }

    fun toJson(lists: List<WatchList>): String = JSONArray().also { a ->
        lists.filter { it.id != MY }.forEach { l ->
            a.put(JSONObject().put("id", l.id).put("name", l.name).put("items", itemsJson(l.items)))
        }
    }.toString()

    /** A new id that no list uses. */
    fun newId(existing: Collection<String>, now: Long = System.currentTimeMillis()): String {
        var n = now
        while ("wl$n" in existing || "wl$n" == MY) n++
        return "wl$n"
    }

    /** Name not used by another list (case-insensitive); "Name 2", "Name 3"… otherwise. */
    fun uniqueName(name: String, all: List<WatchList>, exceptId: String? = null): String {
        val base = clean(name)
        if (base.isEmpty()) return ""
        val taken = all.filter { it.id != exceptId }.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var i = 2
        while ("$base $i".lowercase() in taken) i++
        return "$base $i"
    }

    fun create(lists: List<WatchList>, name: String, now: Long = System.currentTimeMillis()): Pair<List<WatchList>, String?> {
        val n = uniqueName(name, lists)
        if (n.isEmpty()) return lists to null
        val id = newId(lists.map { it.id }, now)
        return (lists + WatchList(id, n)) to id
    }

    fun rename(lists: List<WatchList>, id: String, name: String): List<WatchList> {
        val n = uniqueName(name, lists, exceptId = id)
        if (n.isEmpty()) return lists
        return lists.map { if (it.id == id) it.copy(name = n) else it }
    }

    /** "My List" can not be deleted. */
    fun delete(lists: List<WatchList>, id: String): List<WatchList> = if (id == MY) lists else lists.filter { it.id != id }

    fun add(lists: List<WatchList>, id: String, item: Item): List<WatchList> = lists.map { l ->
        if (l.id == id && l.items.none { it.key == item.key }) l.copy(items = l.items + item) else l
    }

    fun remove(lists: List<WatchList>, id: String, key: String): List<WatchList> = lists.map { l ->
        if (l.id == id) l.copy(items = l.items.filter { it.key != key }) else l
    }

    fun removeEverywhere(lists: List<WatchList>, key: String): List<WatchList> = lists.map { l -> l.copy(items = l.items.filter { it.key != key }) }

    fun contains(lists: List<WatchList>, key: String): Boolean = lists.any { l -> l.items.any { it.key == key } }

    fun containing(lists: List<WatchList>, key: String): List<WatchList> = lists.filter { l -> l.items.any { it.key == key } }

    /** Every key in any list. */
    fun keys(lists: List<WatchList>): Set<String> = lists.flatMap { l -> l.items.map { it.key } }.toSet()

    /** The default list's id, falling back to "My List" when the saved one no longer exists. */
    fun defaultId(saved: String, lists: List<WatchList>): String = saved.takeIf { s -> s.isNotEmpty() && lists.any { it.id == s } } ?: MY

    /** Suggestions without titles already in a list. */
    fun notListed(items: List<Item>, lists: List<WatchList>): List<Item> {
        val k = keys(lists)
        return if (k.isEmpty()) items else items.filter { it.key !in k }
    }

    /** Picker rows: one per list ("✓" = the title is in it, "Default" marks the default), then New Watchlist. */
    fun pickerLabel(l: WatchList, has: Boolean, isDefault: Boolean): String =
        (if (has) "✓  " else "") + l.name + (if (isDefault) "  ·  Default" else "")
}
