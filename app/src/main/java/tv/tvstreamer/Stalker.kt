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

    private fun ensureConnected() = synchronized(this) { if (token.isEmpty()) connect() }

    private fun call(q: String): JSONObject {
        ensureConnected()
        return try {
            fetch("$api?$q&JsHttpRequest=1-xml")
        } catch (e: Exception) {
            synchronized(this) { token = ""; connect() }
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

    /** Turns a portal image path (absolute, protocol-relative or relative) into a full URL. */
    fun absUrl(path: String?, logoDir: Boolean = false): String {
        val p = path?.trim().orEmpty()
        if (p.isEmpty() || p == "null" || p == "0") return ""
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        val portal = store.portal.trim().let { if (it.startsWith("http")) it else "http://$it" }
        val origin = Regex("^(https?://[^/]+)").find(portal)?.value ?: return ""
        if (p.startsWith("//")) return "http:$p"
        if (p.startsWith("/")) return origin + p
        // Relative path: resolve against the portal folder that holds load.php / portal.php
        val dir = api.ifEmpty { candidates().first() }
            .substringBeforeLast("/").removeSuffix("/server").removeSuffix("/c") + "/"
        return if (logoDir && !p.contains("/")) dir + "misc/logos/320/" + p else dir + p
    }

    /** Headers the stream servers expect (same identity as the portal calls). */
    fun streamHeaders(): Map<String, String> {
        val h = mutableMapOf("Cookie" to cookie(), "X-User-Agent" to "Model: MAG250; Link: WiFi")
        if (token.isNotEmpty()) h["Authorization"] = "Bearer $token"
        return h
    }

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
                o.optString("tv_genre_id"), o.optInt("number"), o.optInt("censored") == 1,
                img = absUrl(o.optString("logo"), logoDir = true))
        }
    }

    private fun intList(a: JSONArray?): List<Int> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it)?.toString()?.toIntOrNull() }

    private fun firstOf(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k).trim()
            if (v.isNotEmpty() && v != "null" && v != "0" && v != "N/A") return v
        }
        return ""
    }

    private fun vodItem(it: JSONObject, catFallback: String = ""): Item {
        val id = it.optString("id")
        val isSeries = it.optInt("is_series") == 1
        val eps = intList(it.optJSONArray("series"))
        val time = firstOf(it, "time", "length")
        return Item("vod", id, it.optString("name").ifEmpty { it.optString("o_name") },
            it.optString("cmd").ifEmpty { "/media/$id.mpg" },
            it.optString("category_id").ifEmpty { catFallback },
            if (isSeries || eps.isNotEmpty()) -1 else 0, it.optInt("censored") == 1,
            img = absUrl(firstOf(it, "screenshot_uri", "cover_big", "cover", "poster", "pic", "logo")),
            desc = firstOf(it, "description", "plot"),
            year = firstOf(it, "year", "release_date").take(4).filter { c -> c.isDigit() },
            rating = firstOf(it, "rating_imdb", "rating_kinopoisk", "rating"),
            genre = firstOf(it, "genres_str", "genre"),
            length = time.toIntOrNull()?.let { m -> if (m > 0) "${m / 60}h ${m % 60}m".removePrefix("0h ") else "" } ?: time,
            eps = eps.joinToString(","))
    }

    /** Returns items and whether more pages exist. category "*" means all. */
    fun vod(cat: String, page: Int, search: String = ""): Pair<List<Item>, Boolean> {
        var q = "type=vod&action=get_ordered_list&category=${enc(cat)}&p=$page&sortby=added"
        if (search.isNotEmpty()) q += "&search=${enc(search)}"
        val js = call(q).optJSONObject("js") ?: return emptyList<Item>() to false
        val a = js.optJSONArray("data") ?: JSONArray()
        val total = js.optInt("total_items")
        val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { vodItem(it, cat) }
        return items to (page * per < total)
    }

    private fun episodesFromArray(base: Item, cmd: String, season: String, nums: List<Int>, img: String): List<Item> =
        nums.map { n ->
            Item("vod", "${base.id}:${season.ifEmpty { "s" }}:$n", "Episode $n", cmd, base.cat, n, base.locked,
                img = img.ifEmpty { base.img }, desc = base.desc, year = base.year, rating = base.rating,
                genre = base.genre, series = n, season = season)
        }

    /** Dig into a series and return a flat list of all episodes across seasons. */
    fun seasons(seriesId: String): List<Item> = episodes(Item("vod", seriesId, "", "", "", -1, false))

    fun episodes(series: Item): List<Item> {
        val seriesId = series.id
        // Classic Stalker: the series row carries a "series" array of episode numbers,
        // played with create_link&cmd=<series cmd>&series=<n>
        val classic = series.eps.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (classic.isNotEmpty() && series.cmd.isNotEmpty()) return episodesFromArray(series, series.cmd, "", classic, series.img)

        val out = mutableListOf<Item>()
        val queries = listOf(
            "type=vod&action=get_ordered_list&movie_id=${enc(seriesId)}&season_id=0&episode_id=0",
            "type=vod&action=get_ordered_list&movie_id=${enc(seriesId)}",
            "type=vod&action=get_seasons&movie_id=${enc(seriesId)}"
        )
        for (q in queries) {
            try {
                val resp = call(q)
                val js = resp.opt("js")
                val a = when (js) {
                    is JSONObject -> js.optJSONArray("data") ?: js.optJSONArray("seasons") ?: JSONArray()
                    is JSONArray -> js
                    else -> JSONArray()
                }
                if (a.length() == 0) continue
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val sid = o.optString("id").ifEmpty { o.optString("season_id") }
                    val sname = o.optString("name").ifEmpty { "Season ${i + 1}" }
                    val nums = intList(o.optJSONArray("series"))
                    val isSeason = o.optInt("is_season") == 1 || o.has("season_id") || sname.contains("Season", true)
                    val img = absUrl(firstOf(o, "screenshot_uri", "cover_big", "cover", "poster"))
                    if (nums.isNotEmpty() && o.optString("cmd").isNotEmpty()) {
                        // Season row with an episode-number array
                        out += episodesFromArray(series, o.optString("cmd"), if (isSeason || a.length() > 1) sname else "", nums, img)
                    } else if (isSeason && sid.isNotEmpty()) {
                        try {
                            val epResp = call("type=vod&action=get_ordered_list&movie_id=${enc(seriesId)}&season_id=${enc(sid)}&episode_id=0")
                            val epJs = epResp.opt("js")
                            val eps = when (epJs) {
                                is JSONObject -> epJs.optJSONArray("data") ?: JSONArray()
                                is JSONArray -> epJs
                                else -> JSONArray()
                            }
                            for (e in 0 until eps.length()) {
                                val ep = eps.optJSONObject(e) ?: continue
                                val eid = ep.optString("id").ifEmpty { "$seriesId:$sid:$e" }
                                val ename = ep.optString("name").ifEmpty { "Episode ${e + 1}" }
                                val epNums = intList(ep.optJSONArray("series"))
                                out += Item("vod", eid, ename,
                                    ep.optString("cmd").ifEmpty { "/media/$eid.mpg" },
                                    ep.optString("category_id").ifEmpty { series.cat }, e + 1, series.locked,
                                    img = absUrl(firstOf(ep, "screenshot_uri", "cover_big", "cover")).ifEmpty { img.ifEmpty { series.img } },
                                    desc = firstOf(ep, "description").ifEmpty { series.desc },
                                    year = series.year, rating = series.rating, genre = series.genre,
                                    series = if (epNums.size == 1) epNums[0] else 0, season = sname)
                            }
                        } catch (_: Exception) {}
                    } else {
                        // Direct episode
                        val eid = sid.ifEmpty { seriesId }
                        out += Item("vod", eid, sname.ifEmpty { "Episode ${i + 1}" },
                            o.optString("cmd").ifEmpty { "/media/$eid.mpg" },
                            o.optString("category_id").ifEmpty { series.cat }, out.size + 1, series.locked,
                            img = img.ifEmpty { series.img }, desc = firstOf(o, "description").ifEmpty { series.desc },
                            year = series.year, rating = series.rating, genre = series.genre)
                    }
                }
                // Ignore a response that just echoed the series itself back
                if (out.size == 1 && out[0].id == seriesId && out[0].series == 0) out.clear()
                if (out.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        return out
    }

    private fun clean(cmd: String): String {
        var raw = cmd.trim()
        for (p in listOf("ffmpeg ", "ffrt ", "ffrt2 ", "ffrt3 ", "ffrt4 ", "auto ")) raw = raw.removePrefix(p).trim()
        if (raw.contains(" ")) raw = raw.substringAfterLast(" ").trim()
        return raw
    }

    fun link(i: Item): String {
        // Always go through create_link so the portal returns an authorized stream URL.
        // Use the item's own cmd first: a movie_id lookup on many portals returns the whole
        // category (so its first cmd is a *different* film), so it is only a last resort.
        val candidates = mutableListOf<String>()
        if (i.cmd.isNotEmpty()) candidates += i.cmd
        if (i.kind == "vod" && i.id.isNotEmpty() && !i.id.contains(":")) {
            candidates += "/media/${i.id}.mpg"
            candidates += "ffmpeg /media/${i.id}.mpg"
        }
        val ser = if (i.series > 0) i.series.toString() else ""

        var lastErr = ""
        for (c in candidates.distinct()) {
            val variants = listOf(
                "type=${i.kind}&action=create_link&cmd=${enc(c)}&series=$ser&forced_storage=&disable_ad=0&download=0",
                "type=${i.kind}&action=create_link&cmd=${enc(c)}&series=${ser.ifEmpty { "0" }}&forced_storage=0&disable_ad=0&download=0",
                "type=${i.kind}&action=create_link&cmd=${enc(c)}"
            )
            for (q in variants.distinct()) {
                try {
                    val resp = call(q)
                    lastErr = resp.toString().take(200)
                    val js = resp.opt("js")
                    val raw = clean(when (js) {
                        is JSONObject -> js.optString("cmd").ifEmpty { js.optString("url") }.ifEmpty { js.optString("link") }
                        is String -> js
                        else -> ""
                    })
                    if (raw.startsWith("http") || raw.startsWith("rtsp") || raw.startsWith("rtmp") || raw.startsWith("udp")) return raw
                    if (js is JSONObject) {
                        val err = js.optString("error").ifEmpty { js.optString("msg") }
                        if (err.isNotEmpty()) lastErr = err
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: "request failed"
                }
            }
        }
        // Some channels carry a direct stream URL; use it if create_link gave nothing usable
        val direct = clean(i.cmd)
        if (direct.startsWith("http") && !direct.contains("localhost") && !direct.contains("127.0.0.1")) return direct
        throw IllegalStateException("No stream link. Portal: $lastErr")
    }
}
