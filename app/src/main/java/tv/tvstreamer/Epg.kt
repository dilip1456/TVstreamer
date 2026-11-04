package tv.tvstreamer

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One programme in the guide. Times in epoch milliseconds. */
data class Prog(val title: String, val start: Long, val stop: Long, val desc: String = "") {
    fun isOn(now: Long) = now in start until stop
    /** 0..1 how far the programme is at [now]. */
    fun progress(now: Long): Float = if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/**
 * Electronic programme guide (now / next), loaded lazily: only for the channels on screen, one gentle request at a
 * time through the portal's global limiter, never while a stream link is being fetched, cached for [TTL_MS] (or until
 * the cached programmes run out). Parsing and the "what to fetch next" choice are pure and unit tested.
 */
object Epg {
    const val TTL_MS = 15 * 60_000L
    const val GAP_MS = 1_200L
    const val FAIL_BACKOFF_MS = 10 * 60_000L
    const val DEBOUNCE_MS = 600L

    private val TIME = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?")

    private fun seconds(o: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            val n = v.toString().trim().toLongOrNull() ?: continue
            if (n > 0) return if (n > 100_000_000_000L) n else n * 1000   // already ms, or seconds
        }
        return 0
    }

    private fun dateTime(s: String, tz: java.util.TimeZone): Long {
        val m = TIME.find(s.trim()) ?: return 0
        val c = java.util.Calendar.getInstance(tz)
        val g = m.groupValues
        c.clear()
        c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        return c.timeInMillis
    }

    /** One programme from a portal row; null when it has no title or times. */
    fun prog(o: JSONObject, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Prog? {
        val title = o.optString("name").ifBlank { o.optString("title") }.trim()
        if (title.isEmpty()) return null
        var start = seconds(o, "start_timestamp", "start_ts", "start")
        var stop = seconds(o, "stop_timestamp", "stop_ts", "end_timestamp", "stop")
        if (start == 0L) start = dateTime(o.optString("time").ifEmpty { o.optString("start") }, tz)
        if (stop == 0L) stop = dateTime(o.optString("time_to").ifEmpty { o.optString("end") }, tz)
        if (stop == 0L && start > 0) o.optLong("duration").takeIf { it > 0 }?.let { stop = start + it * 1000 }
        if (start <= 0 || stop <= start) return null
        return Prog(title, start, stop, o.optString("descr").ifBlank { o.optString("description") }.trim())
    }

    /**
     * get_short_epg answer -> programmes per channel id. Rows carrying their own ch_id are filed under it (portals that
     * answer for several channels at once); other rows belong to [chId]. Sorted by start, duplicates dropped.
     */
    fun parse(resp: JSONObject, chId: String, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Map<String, List<Prog>> {
        val out = LinkedHashMap<String, MutableList<Prog>>()
        fun add(o: JSONObject, fallback: String) {
            val p = prog(o, tz) ?: return
            val id = o.optString("ch_id").ifBlank { fallback }
            out.getOrPut(id) { ArrayList() }.add(p)
        }
        fun addArray(a: JSONArray, fallback: String) {
            for (i in 0 until a.length()) when (val v = a.opt(i)) {
                is JSONObject -> add(v, fallback)
                is JSONArray -> addArray(v, fallback)
            }
        }
        when (val js = resp.opt("js")) {
            is JSONArray -> addArray(js, chId)
            is JSONObject -> {
                val data = js.optJSONArray("data")
                if (data != null) addArray(data, chId)
                else for (k in js.keys()) when (val v = js.opt(k)) {   // { "<ch_id>": [ … ] }
                    is JSONArray -> addArray(v, k)
                    is JSONObject -> add(v, k)
                }
            }
        }
        out.getOrPut(chId) { ArrayList() }   // an empty answer is cached too (no repeated asking)
        return out.mapValues { (_, l) -> l.distinctBy { it.start }.sortedBy { it.start } }
    }

    /** The programme on now, or null. */
    fun now(l: List<Prog>?, t: Long): Prog? = l?.firstOrNull { it.isOn(t) }
    /** The next programme after now (or the first one still to come). */
    fun next(l: List<Prog>?, t: Long): Prog? = l?.firstOrNull { it.start >= t && !it.isOn(t) }
    /** Programmes that have not ended yet, now first. */
    fun upcoming(l: List<Prog>?, t: Long): List<Prog> = l.orEmpty().filter { it.stop > t }

    /** Cached guide for a channel: when it was fetched and its programmes. */
    data class Entry(val at: Long, val progs: List<Prog>)

    /** Still good: fetched less than [TTL_MS] ago and (when it had programmes) not all of them over. */
    fun fresh(e: Entry?, t: Long): Boolean {
        if (e == null || t - e.at >= TTL_MS) return false
        return e.progs.isEmpty() || e.progs.any { it.stop > t }
    }

    /**
     * Next channel to fetch: the first wanted (on screen, in screen order) channel without a fresh cache entry that did
     * not fail recently. null = nothing to do.
     */
    fun pick(wanted: List<String>, cache: Map<String, Entry>, failed: Map<String, Long>, t: Long): String? =
        wanted.firstOrNull { id -> !fresh(cache[id], t) && (failed[id]?.let { t - it >= FAIL_BACKOFF_MS } ?: true) }
}

/**
 * The live guide loader. Screens call [want] with the channel ids on screen (debounced); a single worker fetches them
 * one at a time, at least [Epg.GAP_MS] apart, pausing while a stream link is requested or the portal asked us to wait.
 */
class EpgLoader(private val fetch: (String) -> Map<String, List<Prog>>, private val linkBusy: () -> Boolean = { false },
                private val clock: () -> Long = System::currentTimeMillis) {
    val guide = mutableStateMapOf<String, Epg.Entry>()
    @Volatile private var wanted: List<String> = emptyList()
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var debounce: Job? = null
    @Volatile private var pausedUntil = 0L

    /** Programmes of a channel id (may be stale or empty). */
    fun of(id: String): List<Prog> = guide[id]?.progs.orEmpty()

    /** The channels on screen now (in order). Earlier wishes are dropped. */
    fun want(ids: List<String>, immediate: Boolean = false) {
        debounce?.cancel()
        debounce = scope.launch {
            if (!immediate) delay(Epg.DEBOUNCE_MS)
            wanted = ids.distinct().take(MAX_WANTED)
            start()
        }
    }

    /** Fetch one channel soon (the player's channel panel), ahead of the list. */
    fun wantFirst(id: String) { wanted = (listOf(id) + wanted).distinct().take(MAX_WANTED); start() }

    @Synchronized private fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            var last = 0L
            while (isActive) {
                val t = clock()
                val id = Epg.pick(wanted, guide, failed, t) ?: break
                val wait = maxOf(Epg.GAP_MS - (t - last), pausedUntil - t)
                if (wait > 0) { delay(wait); continue }
                if (linkBusy()) { delay(400); continue }
                last = clock()
                try {
                    val got = fetch(id)
                    val now = clock()
                    for ((k, v) in got) guide[k] = Epg.Entry(now, v)
                    if (id !in got) guide[id] = Epg.Entry(now, emptyList())
                    failed.remove(id)
                } catch (e: PortalBusyException) {
                    pausedUntil = clock() + maxOf(e.waitMs, 30_000L)
                } catch (e: Exception) {
                    failed[id] = clock()
                }
            }
        }
    }

    companion object { const val MAX_WANTED = 40 }
}
