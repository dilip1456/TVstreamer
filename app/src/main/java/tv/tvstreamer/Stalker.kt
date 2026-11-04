package tv.tvstreamer

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Page size asked for in full-catalog loads (most Stalker portals ignore it and use their own, usually 14). */
const val BIG_PAGE = 500

const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

class Stalker(private val store: Store) {
    private val http: OkHttpClient get() = Net.portal
    /** Every portal request (handshake, lists, links) waits its turn here: one at a time, about 2 a second at most. */
    val limiter = RateLimiter(500)
    /** When to pause: only real rate limits (429 / Retry-After) get the long backoff; see [PortalGate]. */
    private val gate = PortalGate()
    /** Only one portal request is in flight at a time, whoever asks (library, rows, details, player). */
    private val oneAtATime = Any()
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
        // The same headers a MAG250 set-top box sends, on every request
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", referer())
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=Europe/London")
        val sn = store.serial.trim()
        if (sn.isNotEmpty()) b.header("SN", sn)
        if (auth && token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        synchronized(oneAtATime) {
        limiter.acquire()
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val ra = r.header("Retry-After")
                val verdict = PortalVerdict.of(r.code, ra)
                val wait = gate.onError(r.code, ra)
                if (wait > 0) {
                    limiter.pause(wait, verdict, r.code)
                    note("portal", "HTTP ${r.code} ($verdict): pausing ${wait / 1000}s")
                    throw PortalBusyException(wait, verdict, r.code)
                }
                throw PortalHttpException(r.code, "HTTP ${r.code}: ${mask(t.take(200))}", verdict)
            }
            gate.onSuccess()
            val body = t.ifEmpty { "{}" }.trim()
            return if (body.startsWith("[")) {
                // Some portals return a bare array; wrap it
                JSONObject().put("js", JSONArray(body))
            } else {
                JSONObject(body)
            }
        }
        }
    }

    /** "http://host/c/": what the MAG portal page itself sends as Referer. */
    private fun referer(): String {
        var u = store.portal.trim().trimEnd('/')
        if (!u.startsWith("http")) u = "http://$u"
        u = u.removeSuffix("/portal.php").removeSuffix("/server/load.php").removeSuffix("/stalker_portal/server/load.php")
        return if (u.endsWith("/c")) "$u/" else "$u/c/"
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
        // The address that worked last time first, so a reconnect is one handshake, not one per possible path
        val known = api
        for (c in (listOfNotNull(known.ifEmpty { null }) + candidates()).distinct()) {
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
            } catch (e: PortalBusyException) {
                token = ""; throw e                     // the portal is there but wants us to wait: stop trying paths
            } catch (e: PortalHttpException) {
                token = ""
                // The address answered (refused / server error): trying the other paths would only add requests
                if (e.verdict == PortalVerdict.AUTH || e.verdict == PortalVerdict.SERVER) throw e
                err = e
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
        val used = token
        return try {
            fetch("$api?$q&JsHttpRequest=1-xml")
        } catch (e: Exception) {
            if (!needsNewToken(e)) throw e              // rate limit, server error, 404 ...: no retry burst
            // Expired token / session (401 / 403, "Authorization failed" text instead of JSON): one new handshake, one retry.
            // When another request already signed in again meanwhile, just retry with that token.
            synchronized(this) { if (token == used || token.isEmpty()) { token = ""; connect() } }
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

    /** Per channel: alternate cmds, use_http_tmp_link and cmds ch_id from the channel list (for [LiveLink.tries]). */
    val liveMeta = java.util.concurrent.ConcurrentHashMap<String, LiveLink.Meta>()

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
            // Best readable name of the portal's fields (many portals put only the number in "name")
            val nm = ChannelTitle.pick(o.optString("name"), o.optString("o_name"), o.optString("xmltv_id"), o.optString("cmd"), o.optInt("number"))
            val cmds = o.optJSONArray("cmds")?.let { a2 -> (0 until a2.length()).mapNotNull { a2.optJSONObject(it)?.optString("url") } }.orEmpty()
            val cmd = o.optString("cmd").ifBlank { cmds.firstOrNull { it.isNotBlank() } ?: "" }
            val chIds = o.optJSONArray("cmds")?.let { a2 -> (0 until a2.length()).mapNotNull { a2.optJSONObject(it)?.optString("ch_id")?.takeIf { x -> x.isNotBlank() } } }.orEmpty()
            liveMeta[o.optString("id")] = LiveLink.Meta(cmds.filter { it.isNotBlank() }, o.optString("use_http_tmp_link") != "0", chIds)
            Item("itv", o.optString("id"), nm.ifEmpty { o.optString("name") }, cmd,
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
            eps = eps.joinToString(","),
            added = firstOf(it, "added", "add_date", "date_add", "last_modified", "added_date"))
    }

    /** One page of get_ordered_list: items, total_items and the page size the portal uses. */
    class VodPage(val items: List<Item>, val total: Int, val per: Int) {
        val pages: Int get() = if (total <= 0) 1 else (total + per - 1) / per
    }

    /** One page; category "*" means all. Asks for big pages (portals that honour it answer with fewer requests). */
    fun vodPage(cat: String, page: Int, search: String = "", big: Boolean = false): VodPage {
        var q = "type=vod&action=get_ordered_list&category=${enc(cat)}&p=$page&sortby=added"
        if (big) q += "&per_page=$BIG_PAGE&items_per_page=$BIG_PAGE"
        if (search.isNotEmpty()) q += "&search=${enc(search)}"
        val js = call(q).optJSONObject("js") ?: return VodPage(emptyList(), 0, 14)
        val a = js.optJSONArray("data") ?: JSONArray()
        val total = js.optInt("total_items")
        val per = maxOf(js.optInt("max_page_items", 14), a.length()).coerceAtLeast(1)
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { vodItem(it, cat) }
        return VodPage(items, total, per)
    }

    /** Returns items and whether more pages exist. category "*" means all. */
    fun vod(cat: String, page: Int, search: String = ""): Pair<List<Item>, Boolean> {
        val p = vodPage(cat, page, search)
        return p.items to (page * p.per < p.total)
    }

    private fun episodesFromArray(base: Item, cmd: String, season: String, nums: List<Int>, img: String, seasonId: String = ""): List<Item> =
        nums.map { n ->
            Item("vod", "${base.id}:${season.ifEmpty { "s" }}:$n", "Episode $n", cmd, base.cat, n, base.locked,
                img = img.ifEmpty { base.img }, desc = base.desc, year = base.year, rating = base.rating,
                genre = base.genre, series = n, season = season,
                pcmd = base.cmd.takeIf { it != cmd } ?: "", pid = base.id, sid = seasonId)
        }

    /** Dig into a series and return a flat list of all episodes across seasons (one request per season: prefer [seasonList]). */
    fun seasons(seriesId: String): List<Item> = episodes(Item("vod", seriesId, "", "", "", -1, false))

    fun episodes(series: Item): List<Item> = seasonList(series).flatMap { it.eps ?: seasonEpisodes(series, it) }

    /**
     * The seasons of a series without their episodes when the portal needs one request per season ([SeasonRef.eps] null):
     * the details page shows the season chips at once and asks only for the season being looked at.
     */
    fun seasonList(series: Item): List<SeasonRef> {
        val seriesId = series.id
        // Classic Stalker: the series row carries a "series" array of episode numbers,
        // played with create_link&cmd=<series cmd>&series=<n>
        val classic = series.eps.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (classic.isNotEmpty() && series.cmd.isNotEmpty())
            return listOf(SeasonRef("", "", series.cmd, episodesFromArray(series, series.cmd, "", classic, series.img)))

        val queries = listOf(
            "type=vod&action=get_ordered_list&movie_id=${enc(seriesId)}&season_id=0&episode_id=0",
            "type=vod&action=get_ordered_list&movie_id=${enc(seriesId)}",
            "type=vod&action=get_seasons&movie_id=${enc(seriesId)}"
        )
        for (q in queries) {
            try {
                // Paginate to get all seasons
                val a = JSONArray()
                var page = 1
                while (page <= 15) {
                    val resp = call("$q&p=$page")
                    val js = resp.opt("js")
                    val pageArr = when (js) {
                        is JSONObject -> js.optJSONArray("data") ?: js.optJSONArray("seasons") ?: JSONArray()
                        is JSONArray -> js
                        else -> JSONArray()
                    }
                    if (pageArr.length() == 0) break
                    for (i in 0 until pageArr.length()) a.put(pageArr.get(i))
                    val total = when (js) { is JSONObject -> js.optInt("total_items", 0); else -> 0 }
                    if (a.length() >= total && total > 0) break
                    // A page shorter than the portal's page size is the last one: no extra empty-page request
                    val per = (js as? JSONObject)?.optInt("max_page_items", 0) ?: 0
                    if (per > 0 && pageArr.length() < per) break
                    page++
                }
                if (a.length() == 0) continue
                val refs = mutableListOf<SeasonRef>()
                val direct = mutableListOf<Item>()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val sid = o.optString("id").ifEmpty { o.optString("season_id") }
                    val sname = o.optString("name").ifEmpty { "Season ${i + 1}" }
                    val nums = intList(o.optJSONArray("series"))
                    val isSeason = o.optInt("is_season") == 1 || o.has("season_id") || sname.contains("Season", true)
                    val img = absUrl(firstOf(o, "screenshot_uri", "cover_big", "cover", "poster"))
                    if (nums.isNotEmpty() && o.optString("cmd").isNotEmpty()) {
                        // Season row with an episode-number array: its episodes are known without another request
                        val nm = if (isSeason || a.length() > 1) sname else ""
                        refs += SeasonRef(nm, sid, o.optString("cmd"), episodesFromArray(series, o.optString("cmd"), nm, nums, img, sid), img)
                    } else if (isSeason && sid.isNotEmpty()) {
                        refs += SeasonRef(sname, sid, o.optString("cmd"), null, img)
                    } else {
                        // Direct episode
                        val eid = sid.ifEmpty { seriesId }
                        direct += Item("vod", eid, sname.ifEmpty { "Episode ${i + 1}" },
                            o.optString("cmd").ifEmpty { "/media/$eid.mpg" },
                            o.optString("category_id").ifEmpty { series.cat }, direct.size + 1, series.locked,
                            img = img.ifEmpty { series.img }, desc = firstOf(o, "description").ifEmpty { series.desc },
                            year = series.year, rating = series.rating, genre = series.genre)
                    }
                }
                // Ignore a response that just echoed the series itself back
                if (refs.isEmpty() && direct.size == 1 && direct[0].id == seriesId && direct[0].series == 0) direct.clear()
                if (direct.isNotEmpty()) refs += SeasonRef("", "", "", direct)
                if (refs.isNotEmpty()) return refs
            } catch (e: Exception) {
                if (stopsLinkTries(e)) throw e          // the app must wait: do not try the other queries
            }
        }
        return emptyList()
    }

    /** One season's episodes (one request). */
    fun seasonEpisodes(series: Item, s: SeasonRef): List<Item> {
        s.eps?.let { return it }
        val seriesId = series.id
        val sid = s.sid
        val epResp = call("type=vod&action=get_ordered_list&movie_id=${enc(seriesId)}&season_id=${enc(sid)}&episode_id=0")
        val epJs = epResp.opt("js")
        val eps = when (epJs) {
            is JSONObject -> epJs.optJSONArray("data") ?: JSONArray()
            is JSONArray -> epJs
            else -> JSONArray()
        }
        val out = mutableListOf<Item>()
        for (e in 0 until eps.length()) {
            val ep = eps.optJSONObject(e) ?: continue
            val eid = ep.optString("id").ifEmpty { "$seriesId:$sid:$e" }
            val ename = ep.optString("name").ifEmpty { "Episode ${e + 1}" }
            val epNums = intList(ep.optJSONArray("series"))
            val epNum = epNums.singleOrNull()
                ?: firstOf(ep, "series_number", "episode_number", "episode", "number").toIntOrNull()
                ?: Regex("(?i)(?:episode|ep|e)\\s*(\\d+)").find(ename)?.groupValues?.get(1)?.toIntOrNull()
                ?: (e + 1)
            out += Item("vod", eid, ename,
                ep.optString("cmd").ifEmpty { "/media/$eid.mpg" },
                ep.optString("category_id").ifEmpty { series.cat }, e + 1, series.locked,
                img = absUrl(firstOf(ep, "screenshot_uri", "cover_big", "cover")).ifEmpty { s.img.ifEmpty { series.img } },
                desc = firstOf(ep, "description").ifEmpty { series.desc },
                year = series.year, rating = series.rating, genre = series.genre,
                series = epNum, season = s.name,
                pcmd = s.cmd.ifEmpty { series.cmd }, pid = seriesId, sid = sid)
        }
        return out
    }

    private fun clean(cmd: String): String = StreamLink.clean(cmd)

    // ---------- Stream links ----------

    /** Masked log of the last create_link attempts (shown on the error screen and in Settings). */
    @Volatile var lastReply: String = ""
        private set
    private val log = ArrayDeque<String>()

    /** Hide the token, MAC, serial and passwords before showing portal replies on screen. */
    fun mask(text: String): String {
        var t = text
        if (token.length > 4) t = t.replace(token, "***")
        val mac = normalizeMac(store.mac)
        if (mac.length > 4) { t = t.replace(mac, "**:**:**:**:**:**", true); t = t.replace(enc(mac), "**MAC**", true) }
        if (store.serial.length > 3) t = t.replace(store.serial, "***")
        t = t.replace(Regex("(?i)([0-9a-f]{2}(:|%3a)){5}[0-9a-f]{2}"), "**:**:**:**:**:**")
        t = t.replace(Regex("(?i)\\b((?:play_)?token|password|pass|pwd|mac|sn|auth|sig)([\"']?\\s*[=:]\\s*[\"']?)([^&\"'\\s,}]+)"), "$1$2***")
        t = maskGoogle(t)
        return t
    }

    private fun note(label: String, msg: String) {
        val line = "$label → ${mask(msg).take(300)}"
        android.util.Log.d("TVStalker", line)
        synchronized(log) { log.addLast(line); while (log.size > 12) log.removeFirst() }
        lastReply = line
    }

    private fun diag(i: Item, label: String, idx: Int, n: Int, url: String, resigned: Boolean) {
        if (i.kind != "itv") return
        LinkDiag.record(i.key, LinkDiag.Attempt(System.currentTimeMillis(), ChannelTitle.pretty(i.name), label, idx, n,
            url = LiveLink.maskUrl(url, portalOrigin()), resigned = resigned, result = "link received"))
    }

    /** Sign in again (new token) before asking for a link once more: the stream server refused the old session. */
    fun forceResign() = synchronized(this) { token = ""; runCatching { connect() } }

    /** What the stream server answers at [url] (status, type, first bytes, final URL after redirects). */
    data class Probe(val http: Int?, val contentType: String?, val head: ByteArray?, val finalUrl: String, val error: String = "")

    /** Reads the first 2 KB of a stream with the player's headers, following redirects. Never throws. */
    fun probe(url: String): Probe = try {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-2047").apply {
            streamHeaders().forEach { (k, v) -> header(k, v) }
        }.build()
        val c = http.newBuilder().followRedirects(true).followSslRedirects(true)
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS).readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
        c.newCall(req).execute().use { r ->
            val bytes = r.body?.byteStream()?.use { s -> val b = ByteArray(2048); var n = 0
                while (n < b.size) { val k = s.read(b, n, b.size - n); if (k < 0) break; n += k }
                b.copyOf(n) }
            Probe(r.code, r.header("Content-Type"), bytes, r.request.url.toString())
        }
    } catch (e: Exception) { Probe(null, null, null, url, e.javaClass.simpleName) }

    fun portalOriginPublic(): String = portalOrigin()

    fun debugLog(): String = synchronized(log) { log.joinToString("\n\n") }.ifEmpty { "No portal replies yet. Play something first." }

    /** One way of asking the portal for a stream. */
    private class LinkTry(val label: String, val type: String, val cmd: String, val series: String, val lookup: String? = null,
                          val storage: String = "undefined", val query: String? = null, val direct: Boolean = false)

    private fun portalOrigin(): String {
        val portal = store.portal.trim().let { if (it.startsWith("http")) it else "http://$it" }
        return Regex("^(https?://[^/]+)").find(portal)?.value ?: ""
    }

    /** Turns a create_link answer into a playable URL, or "" when it is not usable (see [StreamLink]). */
    private fun usable(rawCmd: String, vod: Boolean = true): String = StreamLink.usable(rawCmd, portalOrigin(), vod)

    private fun tries(i: Item): List<LinkTry> {
        val out = mutableListOf<LinkTry>()
        if (i.kind == "itv") {
            // Every create_link dialect worth asking, most likely first (see LiveLink)
            for (t in LiveLink.tries(i.cmd, i.id, liveMeta[i.id] ?: LiveLink.Meta(), ::enc))
                out += LinkTry(t.label, "itv", t.cmd, "", query = t.query, direct = t.direct)
            return out
        }
        val ep = i.series
        val isEp = ep > 0 || i.pid.isNotEmpty()
        val n = if (ep > 0) ep.toString() else ""
        if (isEp) {
            // Episode: the episode / season cmd with series=<episode number>, then the parent's cmd
            if (i.cmd.isNotEmpty()) {
                out += LinkTry("episode cmd, series=$n", "vod", i.cmd, n)
                out += LinkTry("episode cmd (type=series)", "series", i.cmd, n)
            }
            if (i.pcmd.isNotEmpty() && i.pcmd != i.cmd) {
                out += LinkTry("parent cmd, series=$n", "vod", i.pcmd, n)
                out += LinkTry("parent cmd (type=series)", "series", i.pcmd, n)
            }
            if (i.pid.isNotEmpty()) {
                val sidQ = if (i.sid.isNotEmpty()) "&season_id=${enc(i.sid)}" else ""
                out += LinkTry("lookup episode by id", "vod", "", n,
                    "type=vod&action=get_ordered_list&movie_id=${enc(i.pid)}$sidQ&episode_id=${enc(i.id)}")
                out += LinkTry("lookup episode (type=series)", "series", "", n,
                    "type=series&action=get_ordered_list&movie_id=${enc(i.pid)}$sidQ&episode_id=${enc(i.id)}")
            }
        } else {
            // Movie: the movie's own cmd exactly as the portal listed it, the way a MAG box asks for it
            if (i.cmd.isNotEmpty()) {
                out += LinkTry("movie cmd", "vod", i.cmd, "")
                out += LinkTry("movie cmd (1.35 style)", "vod", i.cmd, "", storage = "")
                out += LinkTry("movie cmd, series=0", "vod", i.cmd, "0")
            }
            if (i.id.isNotEmpty() && !i.id.contains(":")) {
                // Old behaviour (≤1.35): ask the portal for the movie by id and use the cmd it returns
                out += LinkTry("lookup movie by id", "vod", "", "", "type=vod&action=get_ordered_list&movie_id=${enc(i.id)}")
                out += LinkTry("/media/<id>.mpg", "vod", "/media/${i.id}.mpg", "")
                out += LinkTry("ffmpeg /media/<id>.mpg", "vod", "ffmpeg /media/${i.id}.mpg", "")
            }
        }
        return out
    }

    /** Find the cmd of [id] in a get_ordered_list answer (never blindly the first row of a category). */
    private fun cmdFromLookup(resp: JSONObject, id: String): String {
        val js = resp.opt("js")
        val a = when (js) {
            is JSONObject -> js.optJSONArray("data") ?: return js.optString("cmd")
            is JSONArray -> js
            else -> return ""
        }
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        rows.firstOrNull { it.optString("id") == id }?.optString("cmd")?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (rows.size == 1) rows[0].optString("cmd") else ""
    }

    /** Number of strategies for an item (the player moves on to the next one when a link does not play). */
    fun linkTries(i: Item): Int = tries(i).size +
        (if (i.kind != "itv" && clean(i.cmd).let { (it.startsWith("http://") || it.startsWith("https://")) && !it.contains("localhost") && !it.contains("127.0.0.1") }) 1 else 0)

    /**
     * Ask the portal for a playable URL. Strategies are tried in order starting at [from];
     * returns the URL and the index of the strategy that produced it.
     */
    fun link(i: Item, from: Int = 0): Pair<String, Int> {
        linkBusy.incrementAndGet()
        try { return linkNow(i, from) } finally { linkBusy.decrementAndGet() }
    }

    /** Link requests in flight; background work (EPG) waits while the user is zapping. */
    val linkBusy = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Now / next for one channel (get_short_epg, a handful of programmes). Some portals answer for several channels at
     * once; [Epg.parse] files every entry under its own ch_id.
     */
    fun shortEpg(chId: String): Map<String, List<Prog>> =
        Epg.parse(call("type=itv&action=get_short_epg&ch_id=${enc(chId)}&size=10"), chId)

    /**
     * A channel's schedule for today (the player's schedule list): the MAG guide's get_simple_data_table, at most 3
     * pages, one request at a time through the limiter. Portals without it: a longer get_short_epg.
     */
    fun dayEpg(chId: String): Map<String, List<Prog>> {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val out = ArrayList<Prog>()
        try {
            for (pg in 1..3) {
                val r = call("type=epg&action=get_simple_data_table&ch_id=${enc(chId)}&date=$day&p=$pg")
                val got = Epg.parse(r, chId)[chId].orEmpty()
                out += got
                val js = r.optJSONObject("js")
                val total = js?.optInt("total_items") ?: 0
                val per = js?.optInt("max_page_items")?.takeIf { it > 0 } ?: 10
                if (got.isEmpty() || pg * per >= total) break
            }
        } catch (e: PortalBusyException) { throw e } catch (_: Exception) { }
        if (out.isEmpty()) return Epg.parse(call("type=itv&action=get_short_epg&ch_id=${enc(chId)}&size=30"), chId)
        return mapOf(chId to out.distinctBy { it.start }.sortedBy { it.start })
    }

    private fun linkNow(i: Item, from: Int): Pair<String, Int> {
        val list = tries(i)
        val fails = mutableListOf<String>()
        var lastRaw = ""
        var resigned = false
        for ((idx, t) in list.withIndex()) {
            if (idx < from) continue
            try {
                var cmd = t.cmd
                if (t.direct) {
                    val u = usable(cmd, vod = true)
                    if (u.isNotEmpty()) { note(t.label, u); diag(i, t.label, idx, list.size, u, resigned); return u to idx }
                    fails += "${t.label}: unusable"; continue
                }
                if (t.lookup != null) {
                    val r = call(t.lookup)
                    note(t.label, r.toString())
                    cmd = cmdFromLookup(r, i.id)
                    if (cmd.isEmpty()) { fails += "${t.label}: no cmd"; continue }
                    // A lookup may already give a real stream URL (not a localhost / relative placeholder)
                    val c = clean(cmd)
                    if ((c.startsWith("http://") || c.startsWith("https://")) && !c.contains("://localhost") && !c.contains("://127.0.0.1"))
                        usable(cmd).takeIf { it.isNotEmpty() }?.let { note("${t.label} (direct)", it); return it to idx }
                }
                val q = t.query ?: ("type=${t.type}&action=create_link&cmd=${enc(cmd)}&series=${enc(t.series)}" +
                    "&forced_storage=${t.storage}&disable_ad=0&download=0")
                val resp = call(q)
                lastRaw = resp.toString()
                note(t.label, lastRaw)
                val js = resp.opt("js")
                val got = when (js) {
                    is JSONObject -> js.optString("cmd").ifEmpty { js.optString("url") }.ifEmpty { js.optString("link") }
                    is String -> js
                    else -> ""
                }
                var url = usable(got, i.kind != "itv")
                if (url.isEmpty() && StreamLink.needsFreshToken(got) && !resigned) {
                    // Empty stream id / token: the session went stale. Sign in again once and repeat this request.
                    resigned = true
                    synchronized(this) { token = ""; connect() }
                    val again = call(q)
                    note("${t.label} (after new sign-in)", again.toString())
                    val j2 = again.opt("js")
                    val got2 = when (j2) {
                        is JSONObject -> j2.optString("cmd").ifEmpty { j2.optString("url") }.ifEmpty { j2.optString("link") }
                        is String -> j2
                        else -> ""
                    }
                    url = usable(got2, i.kind != "itv")
                }
                if (url.isNotEmpty()) { diag(i, t.label, idx, list.size, url, resigned); return url to idx }
                val err = (js as? JSONObject)?.let { o -> o.optString("error").ifEmpty { o.optString("msg") } }.orEmpty()
                fails += "${t.label}: " + when {
                    err.isNotEmpty() -> err
                    got.isEmpty() -> "empty cmd"
                    else -> "unusable \"${mask(got).take(60)}\""
                }
            } catch (e: Exception) {
                note(t.label, "request failed: ${e.message}")
                // Rate limit pause or a cancelled request (channel changed): stop here, do not fire the other strategies
                if (stopsLinkTries(e)) throw e
                fails += "${t.label}: ${e.message?.take(90)}"
            }
        }
        // Some items carry a direct stream URL; use it when the portal gave nothing usable
        if (from <= list.size && i.kind != "itv") {
            val c = clean(i.cmd)
            val direct = if (c.startsWith("http://") || c.startsWith("https://")) usable(i.cmd, i.kind != "itv") else ""
            if (direct.isNotEmpty() && !c.contains("localhost") && !c.contains("127.0.0.1")) {
                note("direct cmd", direct)
                return direct to list.size
            }
        }
        if (i.kind == "itv") LinkDiag.record(i.key, LinkDiag.Attempt(System.currentTimeMillis(), ChannelTitle.pretty(i.name),
            list.getOrNull(list.size - 1)?.label ?: "none", (list.size - 1).coerceAtLeast(0), list.size, resigned = resigned,
            result = "no playable link: " + fails.distinct().takeLast(2).joinToString("; ") { mask(it).take(80) }))
        throw NoLinkException(
            "The portal gave no playable link (${fails.size} tries).\n" +
            fails.distinct().takeLast(4).joinToString("\n") +
            (if (lastRaw.isNotEmpty()) "\nLast reply: ${mask(lastRaw).take(300)}" else "")
        )
    }
}

class NoLinkException(msg: String) : Exception(msg)

/** Hides Google OAuth tokens, codes and secrets (access "ya29.", refresh "1//", client secrets, and key=value forms). */
fun maskGoogle(text: String, known: List<String> = runCatching { listOf(GoogleClient.builtIn.secret) }.getOrDefault(emptyList())): String = known
    .filter { it.length >= 8 }.fold(text) { t, k -> t.replace(k, "***") }
    .replace(Regex("ya29\\.[A-Za-z0-9_\\-.]+"), "ya29.***")
    .replace(Regex("\\b1//[A-Za-z0-9_\\-]{10,}"), "1//***")
    // Google client secrets: six capital letters, a dash, then about 28 letters/digits
    .replace(Regex("\\b([A-Z]{6})-[A-Za-z0-9_\\-]{20,}"), "$1-***")
    .replace(Regex("(?i)\\b(access_token|refresh_token|id_token|client_secret|device_code|Bearer)([\"']?\\s*[=: ]\\s*[\"']?)([^&\"'\\s,}]+)"), "$1$2***")
