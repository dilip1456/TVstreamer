package tv.tvstreamer

import org.json.JSONArray
import org.json.JSONObject

data class Cat(val id: String, val title: String, val locked: Boolean)

data class Item(
    val kind: String, // "itv" or "vod"
    val id: String,
    val name: String,
    val cmd: String,
    val cat: String,
    val num: Int,      // channel number, -1 for a series, episode number for episodes
    val locked: Boolean,
    val img: String = "",     // poster / screenshot / channel logo (absolute URL)
    val desc: String = "",
    val year: String = "",
    val rating: String = "",
    val genre: String = "",
    val series: Int = 0,      // episode number passed to create_link as series=N
    val season: String = "",  // season label for episodes
    val length: String = "",  // running time text
    val eps: String = "",     // comma separated episode numbers (classic Stalker "series" array)
    val pcmd: String = "",    // episodes: cmd of the parent series / season (fallback for create_link)
    val pid: String = "",     // episodes: id of the parent series (movie_id)
    val sid: String = ""      // episodes: season id
) {
    val key: String get() = "$kind:$id"
    val isSeries: Boolean get() = kind == "vod" && num == -1
    fun toJson(): JSONObject = JSONObject()
        .put("k", kind).put("i", id).put("n", name).put("c", cmd)
        .put("g", cat).put("u", num).put("l", locked)
        .put("m", img).put("d", desc).put("y", year).put("r", rating)
        .put("ge", genre).put("s", series).put("se", season).put("t", length).put("ep", eps)
        .put("pc", pcmd).put("pi", pid).put("si", sid)

    companion object {
        fun from(o: JSONObject) = Item(
            o.getString("k"), o.getString("i"), o.getString("n"), o.optString("c"),
            o.optString("g"), o.optInt("u"), o.optBoolean("l"),
            o.optString("m"), o.optString("d"), o.optString("y"), o.optString("r"),
            o.optString("ge"), o.optInt("s"), o.optString("se"), o.optString("t"), o.optString("ep"),
            o.optString("pc"), o.optString("pi"), o.optString("si")
        )
    }
}

fun itemsJson(l: List<Item>): JSONArray = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }
fun JSONArray.toItems(): List<Item> = (0 until length()).mapNotNull { runCatching { Item.from(getJSONObject(it)) }.getOrNull() }

fun catsJson(l: List<Cat>): JSONArray = JSONArray().also { a ->
    l.forEach { a.put(JSONObject().put("i", it.id).put("t", it.title).put("l", it.locked)) }
}
fun JSONArray.toCats(): List<Cat> = (0 until length()).map {
    val o = getJSONObject(it)
    Cat(o.getString("i"), o.getString("t"), o.optBoolean("l"))
}
