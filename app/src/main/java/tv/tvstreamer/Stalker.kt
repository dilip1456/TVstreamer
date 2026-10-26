package tv.tvstreamer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

class Stalker(private val store: Store) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private var api = ""
    var token = ""
    fun cookie(): String {
        val mac = normalizeMac(store.mac)
        return "mac=$mac; stb_lang=en; timezone=Europe/London"
    }
    var parentPassword: String? = null

    fun reset() { token = ""; api = ""; parentPassword = null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun normalizeMac(m: String): String {
        val clean = m.replace(":", "").replace("-", "").uppercase()
        if (clean.length != 12) return m.uppercase()
        return clean.chunked(2).joinToString(":")
    }

    private fun fetch(url: String, auth: Boolean = true): JSONObject {
        val mac = normalizeMac(store.mac)
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=Europe/London")
        val sn = store.serial.trim()
        if (sn.isNotEmpty()) b.header("SN", sn)
        if (auth && token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}: $t")
            val body = t.ifEmpty { "{}" }.trim()
            return if (body.startsWith("[")) {
                // Some portals return a bare array; wrap it
                JSONObject().put("js", JSONArray(body))
            } else {
                JSONObject(body)
            }
        }
    }

    private fun candidates(): List<String> {
        var u = store.portal.trim().trimEnd('/')
        if (!u.startsWith("http")) u = "http://$u"
        if (u.endsWith(".php")) return listOf(u)
        val paths = mutableListOf<String>()
        val bases = mutableListOf(u)
        if (u.endsWith("/c")) bases.add(u.removeSuffix("/c"))
        else bases.add("$u/c")
        for (b in bases.distinct()) {
            paths.add("$b/portal.php")
            paths.add("$b/server/load.php")
            paths.add("$b/stalker_portal/server/load.php")
        }
        return paths.distinct()
    }

    fun connect() {
        var err: Exception? = null
        for (c in candidates()) {
            try {
                api = c
                token = ""
                val h = fetch("$c?type=stb&action=handshake&token=&JsHttpRequest=1-xml", false)
                token = h.getJSONObject("js").getString("token")
                runCatching {
                    val sn = store.serial.trim()
                    val snParam = if (sn.isNotEmpty()) "&sn=$sn" else ""
                    val prof = fetch("$c?type=stb&action=get_profile&hd=1&stb_type=MAG250&client_type=STB&auth_second_step=1$snParam&JsHttpRequest=1-xml")
                    parentPassword = prof.optJSONObject("js")?.optString("parent_password")?.ifEmpty { null }
                }
                return
            } catch (e: Exception) {
                err = e
                token = ""
            }
        }
        throw err ?: IllegalStateException("No portal URL")
    }

    private fun call(q: String): JSONObject {
        if (token.isEmpty()) connect()
        return try {
            fetch("$api?$q&JsHttpRequest=1-xml")
        } catch (e: Exception) {
            token = ""
            connect()
            fetch("$api?$q&JsHttpRequest=1-xml")
        }
    }

    private fun parseCats(a: JSONArray): List<Cat> =
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("id").let { id -> id.isNotEmpty() && id != "*" } }
            .map { Cat(it.optString("id"), it.optString("title"), it.optInt("censored") == 1) }

    private fun jsArray(resp: JSONObject): JSONArray {
        val js = resp.opt("js")
        return when (js) {
            is JSONArray -> js
            is JSONObject -> js.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
    }
    fun liveCats(): List<Cat> = parseCats(jsArray(call("type=itv&action=get_genres")))
    fun vodCats(): List<Cat> = parseCats(jsArray(call("type=vod&action=get_categories")))

    fun channels(): List<Item> {
        val resp = call("type=itv&action=get_all_channels")
        val js = resp.opt("js")
        val a = when (js) {
            is JSONObject -> js.optJSONArray("data") ?: JSONArray()
            is JSONArray -> js
            else -> JSONArray()
        }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Item("itv", o.optString("id"), o.optString("name"), o.optString("cmd"),
                o.optString("tv_genre_id"), o.optInt("number"), o.optInt("censored") == 1)
        }
    }

    /** Returns items and whether more pages exist. category "*" means all. */
    fun vod(cat: String, page: Int, search: String = ""): Pair<List<Item>, Boolean> {
        var q = "type=vod&action=get_ordered_list&category=${enc(cat)}&p=$page&sortby=added"
        if (search.isNotEmpty()) q += "&search=${enc(search)}"
        val js = call(q).getJSONObject("js")
        val a = js.optJSONArray("data") ?: JSONArray()
        val total = js.optInt("total_items")
        val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
        val items = (0 until a.length()).map { a.getJSONObject(it) }
            .map {
                val id = it.optString("id")
                Item("vod", id, it.optString("name"),
                    it.optString("cmd").ifEmpty { "/media/$id.mpg" },
                    it.optString("category_id"), if (it.optInt("is_series") == 1) -1 else 0, it.optInt("censored") == 1)
            }
        return items to (page * per < total)
    }

    /** For a series (num == -1), return its episodes as playable items. */
    fun seasons(seriesId: String): List<Item> {
        val resp = call("type=vod&action=get_ordered_list&movie_id=$seriesId&season_id=0&episode_id=0")
        val js = resp.opt("js")
        val a = when (js) {
            is JSONObject -> js.optJSONArray("data") ?: JSONArray()
            is JSONArray -> js
            else -> JSONArray()
        }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { seriesId }
            val name = o.optString("name").ifEmpty { "Episode ${i + 1}" }
            Item("vod", id, name, o.optString("cmd").ifEmpty { "/media/$id.mpg" },
                o.optString("category_id"), i + 1, false)
        }
    }

    fun link(i: Item): String {
        val candidates = mutableListOf<String>()
        if (i.cmd.isNotEmpty()) candidates += i.cmd
        if (i.kind == "vod") {
            candidates += "/media/${i.id}.mpg"
            candidates += "ffmpeg /media/${i.id}.mpg"
        }
        var lastResp = ""
        for (cmd in candidates.distinct()) {
            try {
                val q = "type=${i.kind}&action=create_link&cmd=${enc(cmd)}&series=0&forced_storage=&disable_ad=0&download=0&force_ch_link_check=0"
                val resp = call(q)
                lastResp = resp.toString().take(180)
                val js = resp.opt("js")
                var raw = when (js) {
                    is JSONObject -> js.optString("cmd").ifEmpty { js.optString("url") }.ifEmpty { js.optString("link") }
                    is String -> js
                    else -> ""
                }
                raw = raw.trim()
                    .removePrefix("ffmpeg ").removePrefix("ffrt ").removePrefix("ffrt2 ")
                    .removePrefix("ffrt3 ").removePrefix("ffrt4 ").trim()
                if (raw.contains(" ")) raw = raw.substringAfterLast(" ").trim()
                if (raw.startsWith("http")) return raw
            } catch (_: Exception) {}
        }
        throw IllegalStateException("No stream link. Portal said: $lastResp")
    }
}
