package tv.tvstreamer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One season of a series. [eps] is null until its episodes are fetched (one request); some portals list them inline. */
data class SeasonRef(val name: String, val sid: String, val cmd: String, val eps: List<Item>?, val img: String = "") {
    val id: String get() = sid.ifEmpty { "n:$name" }

    fun toJson(): JSONObject = JSONObject().put("n", name).put("sid", sid).put("cmd", cmd).put("img", img)
        .also { o -> eps?.let { o.put("eps", itemsJson(it)) } }

    companion object {
        fun from(o: JSONObject) = SeasonRef(o.optString("n"), o.optString("sid"), o.optString("cmd"),
            o.optJSONArray("eps")?.toItems(), o.optString("img"))
    }
}

/** Pure season logic (unit tested): order, which season to load first, the next season. */
object SeasonPlan {
    private val DIGITS = Regex("\\d+")
    fun number(s: SeasonRef): Int = Names.seasonNumber(s.name).takeIf { it > 0 }
        ?: DIGITS.find(s.name)?.value?.toIntOrNull()?.takeIf { it in 1..999 } ?: 0

    /** Season 1, 2 ... 10 (not 1, 10, 2); unknown names last in portal order. */
    fun order(l: List<SeasonRef>): List<SeasonRef> =
        l.withIndex().sortedWith(compareBy({ number(it.value).let { n -> if (n > 0) n else Int.MAX_VALUE } }, { it.index })).map { it.value }

    /** The season to show and fetch first: the one asked for, else the last one played, else the first. */
    fun first(l: List<SeasonRef>, wanted: String?, lastPlayed: String?): SeasonRef? =
        l.find { it.name == wanted } ?: l.find { it.name == lastPlayed } ?: l.firstOrNull()

    fun next(l: List<SeasonRef>, name: String): SeasonRef? {
        val o = order(l)
        val i = o.indexOfFirst { it.name == name }
        return if (i < 0) null else o.getOrNull(i + 1)
    }

    /** Fresh enough to use without asking the portal. */
    fun fresh(savedAt: Long, now: Long, maxAge: Long) = savedAt > 0 && now - savedAt in 0 until maxAge
}

/**
 * Seasons and episodes of series, gentle on the portal:
 *  - the season list is saved on disk per series (shown at once next time, refreshed after [LIST_AGE]);
 *  - each season's episodes are fetched only when that season is shown, saved per series / season ([EPS_AGE]);
 *  - one fetch per series / season at a time (a second caller waits for it and gets the saved answer);
 *  - at most 2 season requests queued app-wide (the portal client itself sends one request at a time).
 */
class Seasons(private val api: Stalker, private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val LIST_AGE = 24 * 3600_000L
        const val EPS_AGE = 12 * 3600_000L
        private const val KEEP_FILES = 150
        val gate = Semaphore(2)
    }

    private class Saved(val t: Long, val seasons: List<SeasonRef>, val eps: MutableMap<String, Pair<Long, List<Item>>>)

    private val mem = ConcurrentHashMap<String, Saved>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(k: String) = locks.getOrPut(k) { Mutex() }

    private fun file(series: Item): File {
        val safe = series.key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        return File(dir, "s_${safe}_${series.key.hashCode().toUInt()}.json")
    }

    private fun read(series: Item): Saved? = mem[series.key] ?: runCatching {
        val f = file(series)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val ss = o.optJSONArray("s") ?: JSONArray()
        val eps = mutableMapOf<String, Pair<Long, List<Item>>>()
        o.optJSONObject("e")?.let { e -> e.keys().forEach { k -> e.optJSONObject(k)?.let { eps[k] = it.optLong("t") to (it.optJSONArray("l")?.toItems().orEmpty()) } } }
        Saved(o.optLong("t"), (0 until ss.length()).map { SeasonRef.from(ss.getJSONObject(it)) }, eps)
    }.getOrNull()?.also { mem[series.key] = it }

    private fun write(series: Item, s: Saved) {
        mem[series.key] = s
        runCatching {
            dir.mkdirs()
            val e = JSONObject()
            synchronized(s.eps) { s.eps.forEach { (k, v) -> e.put(k, JSONObject().put("t", v.first).put("l", itemsJson(v.second))) } }
            file(series).writeText(JSONObject().put("t", s.t).put("s", JSONArray().also { a -> s.seasons.forEach { a.put(it.toJson()) } }).put("e", e).toString())
            dir.listFiles()?.takeIf { it.size > KEEP_FILES }?.let { fs -> fs.sortedBy { it.lastModified() }.take(fs.size - KEEP_FILES).forEach { it.delete() } }
        }
    }

    /** Saved season list (any age), for showing the chips instantly. */
    fun cached(series: Item): List<SeasonRef>? = read(series)?.seasons?.let(SeasonPlan::order)

    /** Saved episodes of a season (any age); inline seasons always have theirs. */
    fun cachedEpisodes(series: Item, s: SeasonRef): List<Item>? = s.eps ?: read(series)?.eps?.get(s.id)?.second

    /** Season list, asking the portal only when nothing fresh is saved (or [force]). */
    suspend fun list(series: Item, force: Boolean = false): List<SeasonRef> = withContext(Dispatchers.IO) {
        lock(series.key).withLock {
            val s = read(series)
            if (!force && s != null && s.seasons.isNotEmpty() && SeasonPlan.fresh(s.t, now(), LIST_AGE)) return@withLock SeasonPlan.order(s.seasons)
            val l = kotlinx.coroutines.runInterruptible { api.seasonList(series) }
            if (l.isEmpty() && s != null && s.seasons.isNotEmpty()) return@withLock SeasonPlan.order(s.seasons)
            // Keep the saved episodes of seasons that are still there
            val ids = l.map { it.id }.toSet()
            val keep = s?.eps?.filterKeys { it in ids }.orEmpty().toMutableMap()
            if (l.isNotEmpty()) write(series, Saved(now(), l, keep))
            SeasonPlan.order(l)
        }
    }

    /** One season's episodes: saved if fresh, else one request (single flight, max 2 queued app-wide). */
    suspend fun episodes(series: Item, season: SeasonRef, force: Boolean = false): List<Item> = withContext(Dispatchers.IO) {
        season.eps?.let { return@withContext it }
        lock(series.key + "|" + season.id).withLock {
            val s = read(series)
            val have = s?.eps?.get(season.id)
            if (!force && have != null && have.second.isNotEmpty() && SeasonPlan.fresh(have.first, now(), EPS_AGE)) return@withLock have.second
            val l = try {
                gate.withPermit { kotlinx.coroutines.runInterruptible { api.seasonEpisodes(series, season) } }
            } catch (e: Exception) {
                if (have != null && have.second.isNotEmpty() && e !is kotlinx.coroutines.CancellationException) return@withLock have.second   // old copy beats an error
                throw e
            }
            val base = s ?: Saved(now(), listOf(season), mutableMapOf())
            synchronized(base.eps) { base.eps[season.id] = now() to l }
            write(series, base)
            l
        }
    }

    /**
     * The episode after [ep]: the next one in its season, else the first of the next season. Uses saved seasons; asks the
     * portal for at most the season list and one season (the old way re-read every season on each episode).
     */
    suspend fun nextEpisode(series: Item, ep: Item): Item? {
        val l = list(series)
        val ref = l.find { ep.sid.isNotEmpty() && it.sid == ep.sid } ?: l.find { it.name == ep.season }
            ?: return NextEpisode.after(savedEpisodes(series), ep)
        val cur = episodes(series, ref)
        NextEpisode.after(cur, ep)?.let { return it }
        if (cur.none { it.key == ep.key }) return null
        val n = SeasonPlan.next(l, ref.name) ?: return null
        return EpisodeOrder.sort(episodes(series, n)).firstOrNull()
    }

    /** All saved episodes (seasons fetched so far), sorted. */
    fun savedEpisodes(series: Item): List<Item> {
        val s = read(series) ?: return emptyList()
        return EpisodeOrder.sort(s.seasons.flatMap { it.eps ?: s.eps[it.id]?.second.orEmpty() })
    }
}
