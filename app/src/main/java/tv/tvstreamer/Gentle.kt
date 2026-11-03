package tv.tvstreamer

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/*
 * Being gentle with the IPTV portal, so the provider does not see the app as a scraper:
 * one shared HTTP client with a small connection pool, one request at a time, at most ~2 requests a second,
 * a pause (growing each time) when the portal says "too many requests" / "forbidden" / server error, and
 * library refreshes limited by [RefreshPolicy].
 */

/** What an HTTP error from the portal means. Only a real rate limit makes the app wait; see [PortalGate]. */
enum class PortalVerdict {
    /** 429, or a 503 with Retry-After: the portal asked us to slow down. */
    RATE_LIMIT,
    /** 401 / 403: usually an expired token / session; a fresh handshake fixes it (or the account / MAC is refused). */
    AUTH,
    /** Other 5xx: the portal (or that channel's server) had a problem; not a reason to stop everything. */
    SERVER,
    /** Anything else (404 ...): a plain error for that one request. */
    OTHER;

    companion object {
        fun of(code: Int, retryAfter: String? = null): PortalVerdict = when {
            code == 429 -> RATE_LIMIT
            code == 503 && !retryAfter.isNullOrBlank() -> RATE_LIMIT
            code == 401 || code == 403 -> AUTH
            code in 500..599 -> SERVER
            else -> OTHER
        }
    }
}

/** HTTP error from the portal, with what it means ([verdict]). */
class PortalHttpException(val code: Int, msg: String, val verdict: PortalVerdict = PortalVerdict.of(code)) : Exception(msg) {
    /** True only for a real rate limit. */
    val slowDown: Boolean get() = verdict == PortalVerdict.RATE_LIMIT
}

/** The app is waiting before it asks the portal again. [waitMs] = time left; [reason] says why (shown as is). */
class PortalBusyException(val waitMs: Long, val reason: PortalVerdict = PortalVerdict.RATE_LIMIT, code: Int = 0) :
    Exception(busyText(waitMs, reason, code))

fun busyText(waitMs: Long, reason: PortalVerdict = PortalVerdict.RATE_LIMIT, code: Int = 0): String {
    val t = if (waitMs < 60_000) "${((waitMs + 999) / 1000).coerceAtLeast(1)} s" else "about ${(waitMs + 59_999) / 60_000} min"
    return when (reason) {
        PortalVerdict.RATE_LIMIT -> "The portal asked the app to slow down (HTTP ${if (code > 0) code else 429}), so it waits $t before asking again. Saved titles still work."
        PortalVerdict.AUTH -> "The portal keeps refusing this device (HTTP ${if (code > 0) code else 403}) even after signing in again. " +
            "Check the MAC / account in Settings (it may be expired or in use elsewhere). Trying again in $t."
        PortalVerdict.SERVER -> "The portal is having server errors (HTTP ${if (code > 0) code else 500}). Trying again in $t."
        PortalVerdict.OTHER -> "The portal is not answering properly. Trying again in $t."
    }
}

/**
 * Decides when the app must pause portal requests (pure logic, unit tested):
 *  - rate limit: pause for Retry-After, else a growing backoff (1, 2, 4 ... 30 min);
 *  - auth (401 / 403): no pause; the caller signs in again once. Only [authLimit] refusals in a row (with fresh tokens)
 *    pause briefly ([shortMs]) so a refused account is not hammered;
 *  - server errors: no pause for one bad reply (a dead channel's 500 must not block the rest); [serverLimit] in a row
 *    pause briefly;
 *  - a good reply resets everything.
 */
class PortalGate(private val authLimit: Int = 3, private val serverLimit: Int = 3, private val shortMs: Long = 30_000,
                 private val backoff: Backoff = Backoff(60_000, 30 * 60_000)) {
    private var auth = 0
    private var server = 0

    /** Pause in ms for this error (0 = none). */
    @Synchronized fun onError(code: Int, retryAfter: String?): Long {
        val ra = retryAfter?.trim()?.toLongOrNull()?.coerceIn(0, 3600)?.times(1000)
        return when (PortalVerdict.of(code, retryAfter)) {
            PortalVerdict.RATE_LIMIT -> maxOf(ra ?: 0L, backoff.next())
            PortalVerdict.AUTH -> { auth++; if (auth >= authLimit) shortMs * (auth - authLimit + 1).coerceAtMost(10) else 0 }
            PortalVerdict.SERVER -> { server++; if (server >= serverLimit) shortMs else 0 }
            PortalVerdict.OTHER -> 0
        }
    }

    @Synchronized fun onSuccess() { auth = 0; server = 0; backoff.reset() }
}

/**
 * Spaces requests at least [minIntervalMs] apart (all portal calls share one), and holds them during a [pause].
 * Clock and sleep are injectable for tests.
 */
class RateLimiter(
    private val minIntervalMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    private var next = 0L
    @Volatile var pausedUntil = 0L
        private set
    @Volatile private var reason = PortalVerdict.RATE_LIMIT
    @Volatile private var code = 0

    /** Waits for a free slot. Throws [PortalBusyException] instead of waiting through a slow-down pause. */
    fun acquire() {
        val wait: Long
        synchronized(this) {
            val t = now()
            if (pausedUntil > t) throw PortalBusyException(pausedUntil - t, reason, code)
            val at = maxOf(t, next)
            next = at + minIntervalMs
            wait = at - t
        }
        if (wait > 0) sleep(wait)
    }

    @Synchronized fun pause(ms: Long, why: PortalVerdict = PortalVerdict.RATE_LIMIT, http: Int = 0) {
        pausedUntil = maxOf(pausedUntil, now() + ms); reason = why; code = http
    }
    /** Why the app is waiting (for messages). */
    fun busy(): PortalBusyException = PortalBusyException(pauseLeft(), reason, code)
    @Synchronized fun resume() { pausedUntil = 0 }
    val paused: Boolean get() = pausedUntil > now()
    fun pauseLeft(): Long = (pausedUntil - now()).coerceAtLeast(0)
}

/** Exponential backoff: base, 2x base, 4x base ... capped at [maxMs]. [reset] after a good reply. */
class Backoff(private val baseMs: Long, private val maxMs: Long) {
    private var n = 0
    @Synchronized fun next(): Long {
        val d = (baseMs shl n.coerceAtMost(20)).coerceAtMost(maxMs)
        n++
        return d
    }
    @Synchronized fun reset() { n = 0 }
    val failures: Int @Synchronized get() = n
}

/** A short random wait between catalog pages (looks like a person scrolling, not a burst). */
fun pageGap(rnd: Random = Random.Default): Long = 300L + rnd.nextLong(401)

/**
 * When the library may ask the portal again (all times in ms):
 *  - automatic refresh of the newest pages: at most once per [autoEvery] (24 h),
 *  - early refresh after repeated stream failures: at most [earlyPerDay] (2) per day, [earlyGap] (4 h) apart,
 *  - manual Refresh: not within [manualCooldown] (10 min) of the previous one.
 * Kept as JSON in the settings so the limits survive restarts.
 */
class RefreshPolicy(
    val autoEvery: Long = 24 * HOUR,
    val earlyPerDay: Int = 2,
    val earlyGap: Long = 4 * HOUR,
    val manualCooldown: Long = 10 * MINUTE
) {
    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        fun from(json: String?, p: RefreshPolicy = RefreshPolicy()): RefreshPolicy {
            runCatching {
                val o = JSONObject(json ?: return p)
                p.lastAuto = o.optLong("a"); p.lastManual = o.optLong("m")
                o.optJSONArray("e")?.let { a -> for (i in 0 until a.length()) p.early += a.getLong(i) }
            }
            return p
        }
    }

    var lastAuto = 0L
    var lastManual = 0L
    val early = mutableListOf<Long>()

    /** Any refresh (automatic, early or manual) counts as the latest refresh. */
    private fun last() = maxOf(lastAuto, lastManual, early.maxOrNull() ?: 0L)

    fun autoDue(now: Long) = now - last() >= autoEvery

    fun earlyAllowed(now: Long): Boolean {
        val today = early.filter { now - it < 24 * HOUR }
        return today.size < earlyPerDay && now - last() >= earlyGap
    }

    /** 0 when a manual refresh may start now, else ms to wait. */
    fun manualWait(now: Long): Long = (lastManual + manualCooldown - now).coerceAtLeast(0)

    fun recordAuto(now: Long) { lastAuto = now }
    fun recordEarly(now: Long) { early += now; early.removeAll { now - it >= 24 * HOUR } }
    fun recordManual(now: Long) { lastManual = now }

    fun toJson(): String = JSONObject().put("a", lastAuto).put("m", lastManual).put("e", JSONArray(early)).toString()
}

/** One HTTP client for the portal (and the player, which talks to the same provider): 2 idle connections. */
object Net {
    val portal: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)    // our own code decides about retries
            .build()
    }
}

/**
 * Whether a failed portal call should sign in again and retry once: only for an expired token / session (401 / 403),
 * a non-JSON reply ("Authorization failed" text or an HTML page) or a dropped connection. Never after a rate limit,
 * a server error or a timeout, so one failure is never turned into a burst of requests.
 */
fun needsNewToken(e: Throwable): Boolean = when (e) {
    is PortalBusyException -> false
    is PortalHttpException -> e.verdict == PortalVerdict.AUTH
    is org.json.JSONException -> true
    is java.io.InterruptedIOException -> false          // timeouts and cancelled requests
    is java.io.IOException -> true                      // a kept-alive connection the portal closed
    else -> false
}

/** Stop trying other link strategies: the app must wait, or the request was cancelled (channel zapped on). */
fun stopsLinkTries(e: Throwable): Boolean =
    e is PortalBusyException || e is InterruptedException || (e is java.io.InterruptedIOException && e !is java.net.SocketTimeoutException)

/** Small in-memory cache with an age limit (portal search results, ...): the same query twice asks the portal once. */
class TtlCache<K, V>(private val max: Int, private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val map = object : LinkedHashMap<K, Pair<Long, V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Long, V>>?) = size > max
    }

    @Synchronized operator fun get(k: K): V? {
        val e = map[k] ?: return null
        if (now() - e.first > ttlMs) { map.remove(k); return null }
        return e.second
    }

    @Synchronized operator fun set(k: K, v: V) { map[k] = now() to v }
    @Synchronized fun clear() = map.clear()
}
