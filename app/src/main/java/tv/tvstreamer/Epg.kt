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

    /** Longest an entry with programmes is trusted (schedules do change). */
    const val MAX_TTL_MS = 12 * 60 * 60_000L
    /** Never ask again for the same channel sooner than this (a show ending right after the answer). */
    const val MIN_TTL_MS = 5 * 60_000L
    /** Entries kept on disk / in memory keep programmes that ended at most this long ago. */
    const val KEEP_ENDED_MS = 60 * 60_000L

    /** Cached guide for a channel: when it was fetched, its programmes and whether a full day was asked for. */
    data class Entry(val at: Long, val progs: List<Prog>, val day: Boolean = false)

    /**
     * Until when an entry is good (1.53): not a fixed 15 minutes but until the shows it knows run out. Now / next needs
     * two upcoming programmes, so the entry expires when the second-to-last one ends (or the only one ends). An empty
     * answer is asked again after [TTL_MS]; any entry at most after [MAX_TTL_MS].
     */
    fun expires(e: Entry): Long {
        if (e.progs.isEmpty()) return e.at + TTL_MS
        val p = e.progs.sortedBy { it.start }
        val end = if (p.size >= 2) p[p.size - 2].stop else p[0].stop
        return minOf(maxOf(end, e.at + MIN_TTL_MS), e.at + MAX_TTL_MS)
    }

    /** Still good (no refresh needed). */
    fun fresh(e: Entry?, t: Long): Boolean = e != null && t < expires(e)

    /** Shown at once from cache but refreshed in the background (stale-while-revalidate). */
    fun stale(e: Entry?, t: Long): Boolean = e != null && !fresh(e, t) && e.progs.any { it.stop > t }

    /** The day schedule (Right in the player) is good while it still lists a few hours ahead. */
    fun dayFresh(e: Entry?, t: Long): Boolean = e != null && e.day && t < e.at + MAX_TTL_MS &&
        (e.progs.isEmpty() && t < e.at + TTL_MS || e.progs.any { it.start > t + 3 * 60 * 60_000L } || e.progs.count { it.stop > t } >= 6)

    /**
     * Next channel to fetch: the first wanted (on screen, in screen order) channel without a fresh cache entry that did
     * not fail recently. Channels with nothing cached go before stale ones (they show nothing yet). null = nothing to do.
     */
    fun pick(wanted: List<String>, cache: Map<String, Entry>, failed: Map<String, Long>, t: Long): String? {
        val ok = { id: String -> failed[id]?.let { t - it >= FAIL_BACKOFF_MS } ?: true }
        return wanted.firstOrNull { id -> cache[id] == null && ok(id) }
            ?: wanted.firstOrNull { id -> !fresh(cache[id], t) && ok(id) }
    }

    /** Next full-day schedule to fetch (the playing channel first), or null. */
    fun pickDay(wanted: List<String>, cache: Map<String, Entry>, failed: Map<String, Long>, t: Long): String? =
        wanted.firstOrNull { id -> !dayFresh(cache[id], t) && (failed["day:$id"]?.let { t - it >= FAIL_BACKOFF_MS } ?: true) }

    /** New answer + what was cached: programmes merged by start time, long-ended ones dropped, day flag kept. */
    fun merge(old: Entry?, new: Entry, t: Long): Entry {
        if (old == null) return new.copy(progs = new.progs.filter { it.stop > t - KEEP_ENDED_MS })
        val byStart = LinkedHashMap<Long, Prog>()
        for (p in old.progs) byStart[p.start] = p
        for (p in new.progs) byStart[p.start] = p
        // Programmes that overlap a newer answer's programme are replaced by it (schedule changed)
        val fresh = new.progs
        val merged = byStart.values.filter { p -> p.stop > t - KEEP_ENDED_MS &&
            (p in fresh || fresh.none { f -> f.start < p.stop && p.start < f.stop && f.start != p.start }) }.sortedBy { it.start }
        return Entry(new.at, merged, old.day || new.day)
    }

    /** Disk form of the cache (only entries that still list something not over). */
    fun encode(cache: Map<String, Entry>, t: Long): String {
        val o = JSONObject()
        for ((id, e) in cache) {
            val ps = e.progs.filter { it.stop > t }
            if (ps.isEmpty()) continue
            val a = JSONArray()
            for (p in ps) a.put(JSONArray().put(p.title).put(p.start).put(p.stop).put(p.desc.take(300)))
            o.put(id, JSONObject().put("at", e.at).put("d", e.day).put("p", a))
        }
        return o.toString()
    }

    fun decode(text: String?, t: Long): Map<String, Entry> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(text)
            val out = HashMap<String, Entry>()
            for (id in o.keys()) {
                val e = o.optJSONObject(id) ?: continue
                val a = e.optJSONArray("p") ?: continue
                val ps = (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { r ->
                    Prog(r.optString(0), r.optLong(1), r.optLong(2), r.optString(3)) } }.filter { it.stop > t && it.stop > it.start }
                if (ps.isNotEmpty()) out[id] = Entry(e.optLong("at"), ps, e.optBoolean("d"))
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** Rows to ask the guide for: the visible ones plus [ahead] after them (scrolling down shows it at once). */
    fun ahead(visible: List<Int>, total: Int, ahead: Int = 6): List<Int> {
        if (visible.isEmpty()) return emptyList()
        val last = visible.max()
        return visible + ((last + 1)..minOf(total - 1, last + ahead))
    }

    /** The playing channel's neighbours to prefetch while watching (next, previous, then two away). */
    fun neighbours(idx: Int, size: Int): List<Int> =
        if (size <= 1) emptyList() else listOf(1, -1, 2, -2).map { (idx + it).mod(size) }.filter { it != idx }.distinct()
}

/**
 * The live guide loader. Screens call [want] with the channel ids on screen (debounced); a single worker fetches them
 * one at a time, at least [Epg.GAP_MS] apart, pausing while a stream link is requested or the portal asked us to wait.
 */
class EpgLoader(private val fetch: (String) -> Map<String, List<Prog>>, private val linkBusy: () -> Boolean = { false },
                private val clock: () -> Long = System::currentTimeMillis,
                private val fetchDay: ((String) -> Map<String, List<Prog>>)? = null,
                private val load: () -> String? = { null }, private val save: (String) -> Unit = {}) {
    val guide = mutableStateMapOf<String, Epg.Entry>()
    @Volatile private var wanted: List<String> = emptyList()
    @Volatile private var dayWanted: List<String> = emptyList()
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var debounce: Job? = null
    private var saver: Job? = null
    @Volatile private var pausedUntil = 0L
    @Volatile private var restored = false

    /** The guide saved on disk, shown at once (stale-while-revalidate); loaded on first use. */
    private fun restore() {
        if (restored) return
        restored = true
        val got = Epg.decode(runCatching { load() }.getOrNull(), clock())
        for ((k, v) in got) if (guide[k] == null) guide[k] = v
    }

    private fun scheduleSave() {
        if (saver?.isActive == true) return
        saver = scope.launch { delay(15_000); runCatching { save(Epg.encode(HashMap(guide), clock())) } }
    }

    /** Programmes of a channel id (may be stale or empty). */
    fun of(id: String): List<Prog> = guide[id]?.progs.orEmpty()

    /** The channels on screen now (in order), plus a few ahead. Earlier wishes are dropped. */
    fun want(ids: List<String>, immediate: Boolean = false) {
        debounce?.cancel()
        debounce = scope.launch {
            restore()
            if (!immediate) delay(Epg.DEBOUNCE_MS)
            wanted = ids.distinct().take(MAX_WANTED)
            start()
        }
    }

    /** Fetch one channel soon (the player's channel), ahead of the list. */
    fun wantFirst(id: String) { restore(); wanted = (listOf(id) + wanted).distinct().take(MAX_WANTED); start() }

    /** While watching: the playing channel first, then its neighbours (zapping shows now / next at once). */
    fun watching(id: String, neighbours: List<String>) {
        restore()
        wanted = (listOf(id) + neighbours + wanted).distinct().take(MAX_WANTED)
        dayWanted = listOf(id)
        start()
    }

    /** The full-day schedule of a channel (the player's schedule list). */
    fun wantDay(id: String) { restore(); dayWanted = (listOf(id) + dayWanted).distinct().take(3); start() }

    @Synchronized private fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            restore()
            var last = 0L
            while (isActive) {
                val t = clock()
                val dayId = if (fetchDay != null) Epg.pickDay(dayWanted, guide, failed, t) else null
                val id = dayId ?: Epg.pick(wanted, guide, failed, t) ?: break
                val wait = maxOf(Epg.GAP_MS - (t - last), pausedUntil - t)
                if (wait > 0) { delay(wait); continue }
                if (linkBusy()) { delay(400); continue }
                last = clock()
                try {
                    val got = if (dayId != null) fetchDay!!(id) else fetch(id)
                    val now = clock()
                    for ((k, v) in got) guide[k] = Epg.merge(guide[k], Epg.Entry(now, v, day = dayId != null && k == id), now)
                    if (id !in got) guide[id] = Epg.merge(guide[id], Epg.Entry(now, emptyList(), day = dayId != null), now)
                    failed.remove(if (dayId != null) "day:$id" else id)
                    scheduleSave()
                } catch (e: PortalBusyException) {
                    pausedUntil = clock() + maxOf(e.waitMs, 30_000L)
                } catch (e: Exception) {
                    failed[if (dayId != null) "day:$id" else id] = clock()
                }
            }
        }
    }

    companion object { const val MAX_WANTED = 40 }
}
