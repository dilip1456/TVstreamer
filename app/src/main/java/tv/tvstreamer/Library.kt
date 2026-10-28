package tv.tvstreamer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Sort orders for rows and See-all grids. */
object Sorts {
    const val ADDED = "added"
    const val YEAR = "year"
    const val RATING = "rating"
    const val AZ = "az"
    val all = listOf(ADDED, YEAR, RATING, AZ)
    fun label(s: String) = when (s) {
        ADDED -> "Recently added"
        YEAR -> "Release year"
        RATING -> "Rating"
        AZ -> "A–Z"
        else -> s
    }
}

/** Same title (normalized) + year from different categories (CAM / 4K / Dub ...). items[0] is the best version. */
data class Group(val key: String, val items: List<Item>) {
    val best: Item get() = items.first()
}

/**
 * The movie / series library: the first [PAGES] pages (newest first) of every category in the
 * selected languages, loaded in the background (2 requests at a time), kept in a disk cache (lib.json).
 */
class Library(private val repo: Repo) {
    companion object {
        const val PAGES = 4
        const val MAX_AGE = 12L * 3600 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val data = ConcurrentHashMap<String, List<Item>>()   // category id -> items
    private val stamp = ConcurrentHashMap<String, Long>()
    private var diskLoaded = false
    private var job: Job? = null

    /** Bumped when items arrive (rows recompute). */
    var version by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var note by mutableStateOf("")

    fun catLang(c: Cat): String = Tags.language(c.title).ifEmpty { Tags.OTHER }
    fun itemLang(i: Item): String = i.parsed.lang.ifEmpty { repo.vodCats.firstOrNull { it.id == i.cat }?.let { catLang(it) } ?: Tags.OTHER }

    /** Does a category belong on this tab? Name-based guess only to skip pointless loads; items are classified one by one. */
    fun catForTab(c: Cat, tab: String): Boolean = when {
        tab != "series" && tab != "movies" -> true
        !repo.hasSeriesCats() -> true
        tab == "series" -> repo.isSeriesCat(c)
        else -> !repo.isSeriesCat(c)
    }

    fun wantItem(i: Item, tab: String): Boolean = when (tab) {
        "movies" -> !i.seriesLike
        "series" -> i.seriesLike
        else -> true
    }

    /** Language -> number of categories, for the tab's picker. */
    fun languages(tab: String): List<Pair<String, Int>> {
        val unlocked = repo.gate(true) == null
        return repo.vodCats.filter { (!it.locked || unlocked) && catForTab(it, tab) }
            .groupingBy { catLang(it) }.eachCount().toList()
            .sortedWith(compareBy<Pair<String, Int>> { it.first == Tags.OTHER }.thenByDescending { it.second })
    }

    fun selected(tab: String): Set<String>? = repo.store.langs(tab)

    fun cats(tab: String, langs: Set<String>?): List<Cat> {
        val unlocked = repo.gate(true) == null
        return repo.visibleVod().filter { (!it.locked || unlocked) && catForTab(it, tab) && (langs == null || catLang(it) in langs) }
    }

    fun loadedCount(tab: String, langs: Set<String>?) = cats(tab, langs).count { data.containsKey(it.id) }

    /** Start (or continue) loading the tab's categories in the background. */
    fun ensure(tab: String, langs: Set<String>?, force: Boolean = false) {
        if (langs != null && langs.isEmpty()) return
        val list = cats(tab, langs)
        if (list.isEmpty()) return
        if (job?.isActive == true && !force) return
        job = scope.launch {
            if (!diskLoaded) readDisk()
            val now = System.currentTimeMillis()
            val todo = list.filter { force || !data.containsKey(it.id) || now - (stamp[it.id] ?: 0) > MAX_AGE }
            if (todo.isEmpty()) { loading = false; return@launch }
            loading = true; done = 0; total = todo.size; note = ""
            val failed = java.util.concurrent.atomic.AtomicInteger(0)
            todo.chunked(8).forEach { chunk ->
                chunk.map { c ->
                    async {
                        gate.withPermit {
                            val got = mutableListOf<Item>()
                            try {
                                for (p in 1..PAGES) {
                                    val (items, more) = repo.api.vod(c.id, p)
                                    got += items
                                    if (!more || items.isEmpty()) break
                                }
                                data[c.id] = got.distinctBy { it.key }
                                stamp[c.id] = System.currentTimeMillis()
                                if (!repo.rows.containsKey(c.id)) repo.rows[c.id] = got.take(14)
                            } catch (e: Exception) {
                                failed.incrementAndGet()
                                if (got.isNotEmpty()) data[c.id] = got.distinctBy { it.key }
                            }
                            done++
                        }
                    }
                }.awaitAll()
                version++
                writeDisk()
            }
            note = if (failed.get() > 0) "${failed.get()} categories could not be loaded" else ""
            loading = false
            version++
        }
    }

    fun refresh(tab: String, langs: Set<String>?) {
        job?.cancel()
        ensure(tab, langs, force = true)
    }

    fun clear() { job?.cancel(); data.clear(); stamp.clear(); loading = false; version++ ; repo.store.cacheWrite("lib.json", "{}") }

    private fun readDisk() {
        diskLoaded = true
        runCatching {
            val o = JSONObject(repo.store.cacheRead("lib.json") ?: return)
            val cats = o.optJSONObject("c") ?: return
            val st = o.optJSONObject("t")
            val valid = repo.vodCats.map { it.id }.toSet()
            cats.keys().forEach { id ->
                if (id in valid && !data.containsKey(id)) {
                    data[id] = cats.getJSONArray(id).toItems()
                    stamp[id] = st?.optLong(id) ?: 0L
                }
            }
            version++
        }
    }

    private fun writeDisk() {
        runCatching {
            val c = JSONObject(); val t = JSONObject()
            data.forEach { (k, v) -> c.put(k, itemsJson(v)); t.put(k, stamp[k] ?: 0L) }
            repo.store.cacheWrite("lib.json", JSONObject().put("c", c).put("t", t).toString())
        }
    }

    /** Everything loaded for the tab: language-filtered, classified per item (movie vs series), unique. */
    fun pool(tab: String, langs: Set<String>?): List<Item> {
        val ids = cats(tab, langs).map { it.id }
        return ids.flatMap { data[it] ?: repo.rows[it] ?: emptyList() }
            .filter { wantItem(it, tab) && (langs == null || itemLang(it) in langs) }
            .distinctBy { it.key }
    }

    /** All loaded items (any language), for "Versions". */
    fun everything(): List<Item> = (data.values.flatten() + repo.rows.values.flatten()).distinctBy { it.key }

    // ---- Versions ----

    fun groupKey(i: Item): String = "${if (i.seriesLike) "tv" else "mv"}|${Tags.norm(i.parsed.title)}"

    /** Best version first: quality rank, then not dubbed, then has poster. CAM is rank 0 so it is last. */
    private val bestFirst = compareByDescending<Item> { it.parsed.rank }.thenBy { it.parsed.dub }.thenByDescending { it.img.isNotEmpty() }

    /**
     * Group same title + year. Versions without a year join the biggest group with that title;
     * different years (remakes) stay apart. Standalone episode entries are grouped into their show.
     */
    fun groups(items: List<Item>): List<Group> {
        val out = mutableListOf<Group>()
        val order = LinkedHashMap<String, MutableList<Item>>()
        items.forEach { order.getOrPut(groupKey(it)) { mutableListOf() } += it }
        for ((k, list) in order) {
            val byYear = list.groupBy { it.yearNum }
            val years = byYear.keys.filter { it > 0 }
            if (years.size <= 1) out += Group("$k|${years.firstOrNull() ?: ""}", list.sortedWith(bestFirst))
            else {
                val main = years.maxBy { byYear[it]!!.size }
                years.forEach { y ->
                    val l = byYear[y]!! + (if (y == main) byYear[0] ?: emptyList() else emptyList())
                    out += Group("$k|$y", l.sortedWith(bestFirst))
                }
            }
        }
        // Keep the incoming (portal / sorted) order of the first item of each group
        val pos = items.withIndex().associate { it.value.key to it.index }
        return out.sortedBy { g -> g.items.minOf { pos[it.key] ?: Int.MAX_VALUE } }
    }

    fun versionsOf(item: Item): List<Item> {
        val k = groupKey(item)
        val y = item.yearNum
        val same = everything().filter { groupKey(it) == k && (y == 0 || it.yearNum == 0 || it.yearNum == y) && !it.isEpisode() }
        return (same + item).distinctBy { it.key }.sortedWith(bestFirst)
    }

    /** A show made of loose "Show S01E02" entries: one card that opens a season / episode list. */
    fun syntheticSeries(eps: List<Item>): Item {
        val first = eps.first()
        val t = first.parsed.title
        return Item("vod", "grp:" + Tags.norm(t).replace(' ', '_'), t, "", first.cat, -1, first.locked,
            img = eps.firstOrNull { it.img.isNotEmpty() }?.img ?: "", desc = first.desc, year = first.year,
            rating = first.rating, genre = first.genre, added = eps.maxByOrNull { it.addedMs }?.added ?: "")
    }

    /** Episodes of a synthetic show, numbered from their names. */
    fun syntheticEpisodes(series: Item): List<Item> {
        val norm = series.id.removePrefix("grp:").replace('_', ' ')
        return everything().filter { !it.isSeries && it.kind == "vod" && Tags.hasEpisodeMarker(it.name) && Tags.norm(it.parsed.title) == norm }
            .map { e -> val (s, n) = Tags.episodeOf(e.name); e.copy(season = if (s > 0) "Season $s" else "Season 1", length = e.length) to (s * 1000 + n) }
            .sortedBy { it.second }.map { it.first }.distinctBy { it.key }
    }

    /** Groups for display: loose episode entries collapse into one synthetic show per title. */
    fun cards(items: List<Item>): List<Group> {
        val (loose, rest) = items.partition { !it.isSeries && !it.isEpisode() && Tags.hasEpisodeMarker(it.name) }
        val shows = loose.groupBy { Tags.norm(it.parsed.title) }.values.map { syntheticSeries(it) }
        return groups(rest + shows)
    }

    // ---- Sorting (missing values always sort last) ----

    private fun metaOf(i: Item) = Tmdb.cached(i)?.takeIf { it.found }

    /** IMDb (OMDb) rating, else the portal's rating (rating_imdb first), else TMDB's vote. 0 = missing. */
    fun ratingOf(i: Item): Double {
        val m = metaOf(i)
        return m?.imdbRating?.toDoubleOrNull() ?: i.ratingNum.takeIf { it > 0 } ?: m?.rating?.toDoubleOrNull() ?: 0.0
    }

    /** Release as yyyymmdd: TMDB release date, else the year from the portal or the title. 0 = missing. */
    fun releaseOf(i: Item): Int {
        metaOf(i)?.released?.takeIf { it.length >= 10 }?.let { d -> d.replace("-", "").take(8).toIntOrNull()?.let { return it } }
        val y = i.yearNum.takeIf { it > 0 } ?: metaOf(i)?.year?.toIntOrNull() ?: 0
        return if (y > 0) y * 10000 + 1231 else 0
    }

    /**
     * Sort groups. Items with the field missing (no "added" date, no year, no rating) go LAST,
     * in their original order; the others are ordered newest / highest first (A–Z ascending).
     */
    fun sort(groups: List<Group>, mode: String): List<Group> = when (mode) {
        Sorts.ADDED -> groups.sortedWith(compareBy<Group> { if (it.items.maxOf { x -> x.addedMs } > 0) 0 else 1 }.thenByDescending { it.items.maxOf { x -> x.addedMs } })
        Sorts.YEAR -> groups.sortedWith(compareBy<Group> { if (releaseOf(it.best) > 0) 0 else 1 }.thenByDescending { releaseOf(it.best) })
        Sorts.RATING -> groups.sortedWith(compareBy<Group> { if (ratingOf(it.best) > 0) 0 else 1 }.thenByDescending { ratingOf(it.best) })
        Sorts.AZ -> groups.sortedBy { Tags.norm(it.best.parsed.title).ifEmpty { "\uFFFF" } }
        else -> groups
    }

    fun genresOf(i: Item): List<String> =
        (metaOf(i)?.genres?.takeIf { it.isNotEmpty() } ?: i.genre).split(',', '/', '|').map { it.trim() }.filter { it.length > 1 }
}
