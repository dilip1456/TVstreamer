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

    // ... (rest of the full file with skipLimiter on liveCats and channels)
    fun liveCats(): List<Cat> = parseCats(jsArray(call("type=itv&action=get_genres", skipLimiter = true)))
    fun channels(): List<Item> {
        val resp = call("type=itv&action=get_all_channels", skipLimiter = true)
        // ... full implementation
    }
}
