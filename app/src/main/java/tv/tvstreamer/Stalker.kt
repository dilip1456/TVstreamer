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
    private var token = ""
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
            return JSONObject(t.ifEmpty { "{}" })
        }
    }

    private fun candidates(): List<String> {
        var u = store.portal.trim().trimEnd('/')
        if (!u.startsWith("http")) u = "http://$u"
        val root = if (u.endsWith("/c")) u.removeSuffix("/c") else u
        return if (root.endsWith(".php")) listOf(root)
        else listOf("$root/portal.php", "$root/server/load.php")
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
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("id").let { id -> id.isNotEmpty() && id != "*" } }
            .map { Cat(it.optString("id"), it.optString("title"), it.optInt("censored") == 1) }

    fun liveCats(): List<Cat> = parseCats(call("type=itv&action=get_genres").getJSONArray("js"))
    fun vodCats(): List<Cat> = parseCats(call("type=vod&action=get_categories").getJSONArray("js"))

    fun channels(): List<Item> {
        val a = call("type=itv&action=get_all_channels").getJSONObject("js").getJSONArray("data")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
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
            .filter { it.optInt("is_series") != 1 }
            .map {
                val id = it.optString("id")
                Item("vod", id, it.optString("name"),
                    it.optString("cmd").ifEmpty { "/media/$id.mpg" },
                    it.optString("category_id"), 0, it.optInt("censored") == 1)
            }
        return items to (page * per < total)
    }

    fun link(i: Item): String {
        val q = "type=${i.kind}&action=create_link&cmd=${enc(i.cmd)}&series=&forced_storage=&disable_ad=0&download=0&force_ch_link_check=0"
        val u = call(q).getJSONObject("js").optString("cmd").removePrefix("ffmpeg ").trim()
        if (u.isEmpty()) throw IllegalStateException("Portal returned no stream link")
        return u
    }
}
