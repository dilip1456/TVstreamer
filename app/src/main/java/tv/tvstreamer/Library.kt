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
import kotlinx.coroutines.coroutineScope
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
 * The movie / series catalog: pulled from the portal ONCE (every page of category "*", several requests in parallel,
 * then any category the "*" listing missed), kept on disk (catalog.jsonl) and read from there at launch.
 * Rows, language filters, sorting, grouping, See all and search all work on this local copy; the portal is asked
 * again only once a day (in the background), when stream links keep failing, or from Settings > Refresh library.
 * Stream links (create_link) and a series' episodes are still fetched when needed.
 */
class Library(private val repo: Repo) {
    companion object {
        const val MAX_AGE = 24L * 3600 * 1000          // background refresh after a day
        const val PARALLEL = 4                         // portal requests at a time
        const val MAX_PAGES = 8000                     // safety cap (8000 x 14 = 112k titles)
        const val FILE = "catalog.jsonl"
        // Link / playback failures that suggest the catalog is stale (moved / removed titles)
        const val FAIL_LIMIT = 4
        const val FAIL_WINDOW = 15L * 60 * 1000
        const val FAIL_MIN_GAP = 30L * 60 * 1000       // no more than one failure-driven refresh per 30 min
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var all: List<Item> = emptyList()               // portal order (newest added first)
    @Volatile private var byCat: Map<String, List<Item>> = emptyMap()
    @Volatile private var diskLoaded = false
    private var job: Job? = null
    private val failures = FailureWindow(FAIL_LIMIT, FAIL_WINDOW, FAIL_MIN_GAP)
    @Volatile private var owner = ""
    private fun account() = (repo.store.portal.trim().trimEnd('/') + "|" + repo.store.mac.trim().uppercase()).hashCode().toString()

    /** Bumped when the catalog changes (rows recompute). */
    var version by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    /** Progress of the running load, in portal pages. */
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var note by mutableStateOf("")
    /** When the catalog was last pulled completely from the portal (0 = never). */
    var refreshedAt by mutableStateOf(0L)
        private set
    /** True when the catalog on screen is a complete pull (search and rows need no portal requests). */
    var complete by mutableStateOf(false)
        private set

    /** Anything in the local catalog (cached or partially loaded). */
    val hasData: Boolean get() = all.isNotEmpty()
    val size: Int get() = all.size

    // ---- Languages ----

    private var catIndex: Pair<List<Cat>, Map<String, Cat>> = emptyList<Cat>() to emptyMap()
    private fun catOf(id: String): Cat? {
        val cats = repo.vodCats
        if (catIndex.first !== cats) catIndex = cats to cats.associateBy { it.id }
        return catIndex.second[id]
    }

    fun catLang(c: Cat): String = Tags.languageOrOther(c.title)
    /** The title's own tag ("[Hindi]", "|TA|", "Hindi Dubbed") wins; else its category's language; else Other. */
    fun itemLang(i: Item): String = i.parsed.lang.ifEmpty { catOf(i.cat)?.let { catLang(it) } ?: Tags.OTHER }

    /** Does a category belong on this tab? Name-based guess, used for category rows. */
    fun catForTab(c: Cat, tab: String): Boolean = when {
        tab != "series" && tab != "movies" -> true
        !repo.hasSeriesCats() -> true
        tab == "series" -> repo.isSeriesCat(c)
        else -> !repo.isSeriesCat(c)
    }

    /** Series: marked / named as a series, or listed in a series category ("TV SHOWS", "HINDI WEB SERIES"). */
    fun seriesItem(i: Item): Boolean = i.seriesLike || (i.kind == "vod" && catOf(i.cat)?.let { repo.isSeriesCat(it) } == true)

    fun wantItem(i: Item, tab: String): Boolean = when (tab) {
        "movies" -> !seriesItem(i)
        "series" -> seriesItem(i)
        else -> true
    }

    private fun visible(i: Item, unlocked: Boolean): Boolean {
        val c = catOf(i.cat)
        return "vod:${i.cat}" !in repo.hidden && (unlocked || (!i.locked && c?.locked != true))
    }

    /** Language -> count for the tab's picker: titles in the local catalog, or categories before it has loaded. */
    fun languages(tab: String): List<Pair<String, Int>> {
        val unlocked = repo.gate(true) == null
        val counts = if (hasData)
            all.filter { wantItem(it, tab) && visible(it, unlocked) }.groupingBy { itemLang(it) }.eachCount()
        else repo.vodCats.filter { (!it.locked || unlocked) && catForTab(it, tab) }.groupingBy { catLang(it) }.eachCount()
        return counts.toList().sortedWith(compareBy<Pair<String, Int>> { it.first == Tags.OTHER }.thenByDescending { it.second })
    }
    /** What [languages] counts: "title" (local catalog) or "category". */
    val countUnit: String get() = if (hasData) "title" else "category"

    fun selected(tab: String): Set<String>? = repo.store.langs(tab)

    fun cats(tab: String, langs: Set<String>?): List<Cat> {
        val unlocked = repo.gate(true) == null
        return repo.visibleVod().filter { (!it.locked || unlocked) && catForTab(it, tab) && (langs == null || catLang(it) in langs) }
    }

    /** A category's titles from the local catalog (portal order). */
    fun inCat(id: String): List<Item> = byCat[id] ?: emptyList()

    // ---- Loading ----

    /** Kept for callers of the old per-tab loader: the whole catalog is loaded once, whatever the tab. */
    fun ensure(@Suppress("UNUSED_PARAMETER") tab: String = "", @Suppress("UNUSED_PARAMETER") langs: Set<String>? = null, force: Boolean = false) = start(force)

    /** Shows the disk copy at once, then pulls the portal when there is none or it is a day old (or [force]). */
    fun start(force: Boolean = false) {
        if (repo.store.portal.isBlank()) return
        val who = account()
        if (owner != who) {            // another portal / MAC: its catalog is not this one
            job?.cancel(); all = emptyList(); byCat = emptyMap(); complete = false; refreshedAt = 0; diskLoaded = false
            owner = who; version++
        }
        if (job?.isActive == true) { if (!force) return; job?.cancel() }
        job = scope.launch {
            if (!diskLoaded) readDisk()
            val stale = !complete || System.currentTimeMillis() - refreshedAt > MAX_AGE
            if (!force && !stale) return@launch
            if (repo.vodCats.isEmpty()) return@launch       // Repo.load() calls start() again once categories are known
            pull()
        }
    }

    fun refresh(@Suppress("UNUSED_PARAMETER") tab: String = "", @Suppress("UNUSED_PARAMETER") langs: Set<String>? = null) = start(force = true)

    /** Settings: drop the local copy and pull everything again. */
    fun clear() {
        job?.cancel(); all = emptyList(); byCat = emptyMap(); complete = false; refreshedAt = 0; loading = false
        repo.store.cacheDelete(FILE); repo.store.cacheDelete("lib.json"); version++
        start(force = true)
    }

    /** A stream link / playback failure. Several in a short time usually mean the catalog is out of date: refresh it. */
    fun reportFailure() {
        if (failures.hit(System.currentTimeMillis(), refreshedAt)) { note = "Several titles failed to play; refreshing the library in the background"; repo.refreshAll() }
    }

    private fun publish(items: List<Item>) {
        val l = items.distinctBy { it.key }
        all = l
        byCat = l.groupBy { it.cat }
        version++
    }

    private suspend fun pull() = coroutineScope {
        val first = all.isEmpty()
        loading = true; done = 0; total = 0; note = ""
        val gate = Semaphore(PARALLEL)
        try {
            // 1) Every page of category "*" (one listing of the whole VOD library), PARALLEL pages at a time
            val pages = ConcurrentHashMap<Int, List<Item>>()
            val head = runCatching { repo.api.vodPage("*", 1, big = true) }.getOrNull()
            val starOk = head != null && head.items.isNotEmpty() && head.items.any { it.cat.isNotEmpty() && it.cat != "*" }
            var failed = 0
            if (starOk) {
                head!!; pages[1] = head.items
                val n = head.pages.coerceAtMost(MAX_PAGES)
                total = n; done = 1
                var p = 2
                var emptyRun = 0
                var batches = 0
                while (p <= n && emptyRun < PARALLEL * 2) {
                    val batch = (p until minOf(p + PARALLEL * 4, n + 1)).toList()
                    val got = batch.map { pg ->
                        async { gate.withPermit { pg to fetchPage("*", pg) } }
                    }.awaitAll()
                    for ((pg, items) in got) {
                        if (items == null) failed++ else pages[pg] = items
                        if (items != null && items.isEmpty()) emptyRun++ else if (items != null) emptyRun = 0
                    }
                    done += batch.size
                    p += batch.size
                    if (first && ++batches % 3 == 0) publish(pages.toSortedMap().values.flatten())   // first launch: show titles as they arrive
                }
            }
            val star = pages.toSortedMap().values.flatten()
            // 2) Categories the "*" listing did not include (some portals leave out some, or do not support "*")
            val seen = star.map { it.cat }.toHashSet()
            val missing = repo.vodCats.filter { it.id !in seen }
            val extra = ConcurrentHashMap<String, List<Item>>()
            if (missing.isNotEmpty()) {
                total += missing.size
                if (first && star.isNotEmpty()) publish(star)
                missing.chunked(PARALLEL * 4).forEach { chunk ->
                    chunk.map { c -> async { gate.withPermit { c.id to fetchCategory(c.id) } } }.awaitAll().forEach { (id, l) ->
                        if (l == null) failed++ else extra[id] = l
                    }
                    done += chunk.size
                    if (first) publish(star + extra.values.flatten())
                }
            }
            val result = star + missing.flatMap { extra[it.id] ?: emptyList() }
            if (result.isEmpty()) {
                note = if (hasData) "Could not refresh the library; showing the saved copy" else "Could not load the library from the portal. Try Refresh."
                return@coroutineScope
            }
            // A pull that lost a lot of pages keeps the old copy instead of shrinking the library
            if (!first && failed > 0 && result.size < all.size * 0.8) {
                note = "Library refresh was incomplete ($failed requests failed); kept the saved copy"
                return@coroutineScope
            }
            refreshedAt = System.currentTimeMillis()
            complete = failed == 0
            publish(result)
            writeDisk()
            note = if (failed > 0) "$failed portal requests failed; Refresh to try again" else ""
        } finally {
            loading = false
        }
    }

    /** One page with two retries; null when it kept failing. */
    private fun fetchPage(cat: String, page: Int): List<Item>? {
        repeat(3) { n ->
            try { return repo.api.vodPage(cat, page, big = true).items } catch (e: Exception) { if (n < 2) Thread.sleep(400L * (n + 1)) }
        }
        return null
    }

    /** Every page of one category. */
    private fun fetchCategory(cat: String): List<Item>? {
        val out = mutableListOf<Item>()
        val head = runCatching { repo.api.vodPage(cat, 1, big = true) }.getOrNull() ?: return null
        out += head.items
        for (p in 2..head.pages.coerceAtMost(500)) {
            val l = fetchPage(cat, p) ?: break
            if (l.isEmpty()) break
            out += l
        }
        return out
    }

    // ---- Disk: one JSON item per line, written to a temp file and swapped in ----

    private fun readDisk() {
        diskLoaded = true
        runCatching {
            val f = repo.store.cacheFile(FILE)
            if (!f.exists()) { readOldCache(); return }
            val items = ArrayList<Item>()
            var t = 0L; var ok = false
            f.bufferedReader().useLines { lines ->
                lines.forEachIndexed { n, line ->
                    if (n == 0) { val h = JSONObject(line); if (h.optString("a") != owner) return; t = h.optLong("t"); ok = h.optBoolean("complete") }
                    else if (line.isNotBlank()) runCatching { items += Item.from(JSONObject(line)) }
                }
            }
            if (items.isNotEmpty()) { refreshedAt = t; complete = ok; publish(items) }
        }
    }

    /** One-time import of the old per-category cache (lib.json), so an update does not start empty. */
    private fun readOldCache() {
        runCatching {
            val o = JSONObject(repo.store.cacheRead("lib.json") ?: return)
            val cats = o.optJSONObject("c") ?: return
            val items = ArrayList<Item>()
            cats.keys().forEach { id -> items += cats.getJSONArray(id).toItems() }
            if (items.isNotEmpty()) { complete = false; refreshedAt = 0; publish(items) }
        }
    }

    private fun writeDisk() {
        runCatching {
            val f = repo.store.cacheFile(FILE)
            val tmp = java.io.File(f.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(JSONObject().put("v", 1).put("t", refreshedAt).put("complete", complete).put("n", all.size).put("a", owner).toString()); w.newLine()
                for (i in all) { w.write(i.toJson().toString()); w.newLine() }
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            repo.store.cacheDelete("lib.json")
        }
    }

    /** Everything for the tab: not hidden / locked, language-filtered per title, classified per title (movie vs series), unique. */
    fun pool(tab: String, langs: Set<String>?): List<Item> {
        val unlocked = repo.gate(true) == null
        return all.filter { wantItem(it, tab) && visible(it, unlocked) && (langs == null || itemLang(it) in langs) }
    }

    /** All loaded items (any language), for "Versions". */
    fun everything(): List<Item> = if (hasData) all else repo.rows.values.flatten().distinctBy { it.key }

    // ---- Versions ----

    fun groupKey(i: Item): String = "${if (seriesItem(i)) "tv" else "mv"}|${Tags.norm(i.parsed.title)}"

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
            .map { e -> val (s, _) = Tags.episodeOf(e.name); e.copy(season = if (s > 0) "Season $s" else "Season 1", length = e.length) }
            .let { EpisodeOrder.sort(it) }.distinctBy { it.key }
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

/**
 * Counts stream failures in a sliding window: true (and the count starts over) when [limit] happened within [window] ms
 * and the last refresh is at least [minGap] ms old.
 */
class FailureWindow(private val limit: Int, private val window: Long, private val minGap: Long) {
    private val times = ArrayDeque<Long>()
    @Synchronized fun hit(now: Long, lastRefresh: Long): Boolean {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > window) times.removeFirst()
        if (times.size < limit || now - lastRefresh < minGap) return false
        times.clear()
        return true
    }
    @get:Synchronized val count: Int get() = times.size
}
