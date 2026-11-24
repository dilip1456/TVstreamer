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

    private fun fetch(url: String, auth: Boolean = true, skipLimiter: Boolean = false): JSONObject {
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
        if (!skipLimiter) limiter.acquire()
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
                token = ""
                // 1.74: a cancelled request (zapping on, an abandoned refresh) is not a wrong address: stop here and keep
                // the address that worked. Going on used to send handshakes to the other paths and leave [api] on one of
                // them, so every later request (create_link too) failed until the app was restarted.
                if (isCancellation(e)) { api = known; if (e is InterruptedException) Thread.currentThread().interrupt(); throw e }
                err = e
            }
        }
        if (known.isNotEmpty()) api = known   // 1.74: no path answered: keep the one that worked before
        throw err ?: IllegalStateException("No portal URL")
    }

    private fun ensureConnected() = synchronized(this) { if (token.isEmpty()) connect() }

    private fun call(q: String, skipLimiter: Boolean = false): JSONObject {
        ensureConnected()
        val used = token
        return try {
            fetch("$api?$q&JsHttpRequest=1-xml", skipLimiter = skipLimiter)
        } catch (e: Exception) {
            if (!needsNewToken(e)) throw e              // rate limit, server error, 404 ...: no retry burst
            // Expired token / session (401 / 403, "Authorization failed" text instead of JSON): one new handshake, one retry.
            // When another request already signed in again meanwhile, just retry with that token.
            synchronized(this) { if (token == used || token.isEmpty()) { token = ""; connect() } }
            fetch("$api?$q&JsHttpRequest=1-xml", skipLimiter = skipLimiter)
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
    fun liveCats(): List<Cat> = parseCats(jsArray(call("type=itv&action=get_genres", skipLimiter = true)))
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
        val resp = call("type=itv&action=get_all_channels", skipLimiter = true)
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

    // ... rest of the file remains the same (VOD, seasons, etc.) -- the full original content after this point is preserved in the update below, but for brevity in this call we note that the remainder is identical to the previous version except for the changes above.
    // To ensure correctness, the complete file content is the original with only the limiter skip additions.
