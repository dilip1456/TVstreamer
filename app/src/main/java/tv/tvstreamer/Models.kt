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
    val num: Int,
    val locked: Boolean
) {
    val key: String get() = "$kind:$id"
    fun toJson(): JSONObject = JSONObject()
        .put("k", kind).put("i", id).put("n", name).put("c", cmd)
        .put("g", cat).put("u", num).put("l", locked)

    companion object {
        fun from(o: JSONObject) = Item(
            o.getString("k"), o.getString("i"), o.getString("n"), o.optString("c"),
            o.optString("g"), o.optInt("u"), o.optBoolean("l")
        )
    }
}

fun itemsJson(l: List<Item>): JSONArray = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }
fun JSONArray.toItems(): List<Item> = (0 until length()).map { Item.from(getJSONObject(it)) }

fun catsJson(l: List<Cat>): JSONArray = JSONArray().also { a ->
    l.forEach { a.put(JSONObject().put("i", it.id).put("t", it.title).put("l", it.locked)) }
}
fun JSONArray.toCats(): List<Cat> = (0 until length()).map {
    val o = getJSONObject(it)
    Cat(o.getString("i"), o.getString("t"), o.optBoolean("l"))
}
