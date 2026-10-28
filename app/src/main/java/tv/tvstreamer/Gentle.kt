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

/** HTTP error from the portal. 429 / 403 / 5xx mean "slow down" (or a block): stop and wait, never retry at once. */
class PortalHttpException(val code: Int, msg: String) : Exception(msg) {
    val slowDown: Boolean get() = code == 429 || code == 403 || code in 500..599
}

/** The portal asked to slow down (or the app is waiting after that). [waitMs] = time left before the app asks again. */
class PortalBusyException(val waitMs: Long) : Exception(busyText(waitMs))

fun busyText(waitMs: Long): String {
    val min = ((waitMs + 59_999) / 60_000).coerceAtLeast(1)
    return "The portal asked the app to slow down, so it is pausing for about $min min to keep your account safe. " +
        "Saved titles still work."
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

    /** Waits for a free slot. Throws [PortalBusyException] instead of waiting through a slow-down pause. */
    fun acquire() {
        val wait: Long
        synchronized(this) {
            val t = now()
            if (pausedUntil > t) throw PortalBusyException(pausedUntil - t)
            val at = maxOf(t, next)
            next = at + minIntervalMs
            wait = at - t
        }
        if (wait > 0) sleep(wait)
    }

    @Synchronized fun pause(ms: Long) { pausedUntil = maxOf(pausedUntil, now() + ms) }
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
