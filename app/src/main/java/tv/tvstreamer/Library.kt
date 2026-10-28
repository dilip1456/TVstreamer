package tv.tvstreamer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * The movie / series catalog, fetched gently and kept on the device (catalog.jsonl):
 *  - only categories whose language (from the category name, refined by the titles found in it) is selected on
 *    the Movies / Series tabs; unselected languages are never fetched ("Other" only when selected);
 *  - first the newest page of each such category, then more pages only on demand (end of a row / grid,
 *    few titles for the picked languages, a sort that needs older titles, a category grid);
 *  - pages already fetched are kept and never fetched again; the daily refresh re-reads only page 1;
 *  - one request at a time with a short random gap, at most [SESSION_PAGES] pages per app session, a Stop button,
 *    and a pause when the portal says "slow down" (see Gentle.kt).
 * Stream links are fetched on Play only.
 */
class Library(private val repo: Repo) {
    companion object {
        const val MORE_PAGES = 3             // each "load more"
        const val FEW = 40                   // fewer cards than this for the picked languages: fetch a little more
        const val FEW_BUDGET = 9             // ... at most this many extra pages per language choice, per session
        const val SESSION_PAGES = 150        // hard cap of catalog pages per app session
        const val MAX_AGE = 24L * 3600 * 1000
        const val FILE = "catalog.jsonl"
        const val FAIL_LIMIT = 4
        const val FAIL_WINDOW = 15L * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = Mutex()
    @Volatile private var all: List<Item> = emptyList()
    @Volatile private var byCat: Map<String, List<Item>> = emptyMap()
    /** Languages actually seen in each category's titles (refines the name-based guess). */
    @Volatile private var catMix: Map<String, Set<String>> = emptyMap()
    @Volatile private var diskLoaded = false
    @Volatile private var owner = ""
    private fun account() = (repo.store.portal.trim().trimEnd('/') + "|" + repo.store.mac.trim().uppercase()).hashCode().toString()
    private val failures = FailureWindow(FAIL_LIMIT, FAIL_WINDOW, 0)
    private val policy: RefreshPolicy by lazy { RefreshPolicy.from(repo.store.refreshPolicy) }
    private fun savePolicy() { repo.store.refreshPolicy = policy.toJson() }

    private val catNext = ConcurrentHashMap<String, Int>()      // next page to fetch per category
    private val catEnd = ConcurrentHashMap.newKeySet<String>()  // every page fetched
    private val fewSpent = ConcurrentHashMap<String, Int>()
    private val queued = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var sessionPages = 0
    @Volatile private var current: Job? = null

    var version by mutableIntStateOf(0)
    /** Fetching in the background ("Loading more…"); never blocks the screen. */
    var loading by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var note by mutableStateOf("")
    /** Stop pressed: nothing is fetched in the background until Resume / Refresh. */
    var stopped by mutableStateOf(false)
        private set
    var refreshedAt by mutableStateOf(0L)
        private set
    /** Every page of every selected category is saved. */
    var complete by mutableStateOf(false)
        private set

    val hasData: Boolean get() = all.isNotEmpty()
    val size: Int get() = all.size

    // ---- Languages ----

    private var catIndex: Pair<List<Cat>, Map<String, Cat>> = emptyList<Cat>() to emptyMap()
    private fun catOf(id: String): Cat? {
        val cats = repo.vodCats
        if (catIndex.first !== cats) catIndex = cats to cats.associateBy { it.id }
        return catIndex.second[id]
    }

    private val catLangMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Language of a category name (memoized: it is asked for every title when grouping). */
    fun catLang(c: Cat): String = catLangMemo.getOrPut(c.title) { Tags.languageOrOther(c.title) }
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

    // ---- Loading (gentle, selected languages only) ----

    private fun langsAll(): Set<String> = (selected("movies") ?: emptySet()) + (selected("series") ?: emptySet())

    /** Categories worth fetching for [langs]: name language selected, or titles of a selected language found in it. */
    fun wantedCats(langs: Set<String>, tab: String = ""): List<Cat> {
        if (langs.isEmpty()) return emptyList()
        val unlocked = repo.gate(true) == null
        return repo.visibleVod().filter { c ->
            (!c.locked || unlocked) && (tab.isEmpty() || catForTab(c, tab)) &&
                (catLang(c) in langs || catMix[c.id]?.any { it in langs } == true)
        }
    }

    /** A language choice: fetch the newest page of the newly selected languages' categories (cached data is kept). */
    fun ensure(tab: String = "", langs: Set<String>? = null, @Suppress("UNUSED_PARAMETER") force: Boolean = false) {
        start(); if (!langs.isNullOrEmpty()) job("first") { firstPages(langsAll()) }
    }

    /** Shows the saved copy at once; fetches first pages of selected languages; re-reads page 1 once a day. */
    fun start() {
        if (repo.store.portal.isBlank()) return
        val who = account()
        if (owner != who) {
            all = emptyList(); byCat = emptyMap(); catMix = emptyMap(); complete = false; refreshedAt = 0; diskLoaded = false
            catNext.clear(); catEnd.clear(); fewSpent.clear(); owner = who; version++
        }
        job("start") {
            if (!diskLoaded) readDisk()
            if (repo.vodCats.isEmpty()) return@job
            val now = System.currentTimeMillis()
            if (hasData && policy.autoDue(now) && now - refreshedAt >= MAX_AGE) { policy.recordAuto(now); savePolicy(); newest() }
            firstPages(langsAll())
        }
    }

    /** Older titles for the tab's languages: next pages of the categories whose fetched titles are the newest. */
    fun more(tab: String = "", langs: Set<String>? = null, pages: Int = MORE_PAGES) {
        val l = langs ?: langsAll()
        if (l.isEmpty()) return
        job("more") { morePages(l, tab, pages) }
    }

    fun fewResults(tab: String, langs: Set<String>, cards: Int) {
        if (cards >= FEW || stopped) return
        val k = tab + "|" + langs.sorted().joinToString(",")
        val spent = fewSpent[k] ?: 0
        if (spent >= FEW_BUDGET) return
        fewSpent[k] = spent + MORE_PAGES
        job("few") { if (wantedCats(langs, tab).any { !catNext.containsKey(it.id) }) firstPages(langs) else morePages(langs, tab, MORE_PAGES) }
    }

    /** A category grid / row ran out of saved titles: its next page. */
    fun moreInCat(id: String) { if (id !in catEnd) job("cat:$id") { fetchCat(id, front = false) } }
    fun catHasMore(id: String): Boolean = id !in catEnd

    /** Titles found by a portal search: kept with the rest. */
    fun merge(items: List<Item>) {
        val known = all.map { it.key }.toHashSet()
        val add = items.filter { it.kind == "vod" && it.key !in known }
        if (add.isEmpty()) return
        publish(all + add); scope.launch { work.withLock { writeDisk() } }
    }

    /** Stop button: cancels the running fetch; nothing more is fetched in the background until [resume]. */
    fun stop() {
        stopped = true; current?.cancel(); loading = false
        note = "Loading stopped. Saved titles still work; Settings > Refresh library or Resume continues."
    }
    fun resume() { stopped = false; note = ""; sessionPages = 0; start() }

    /** Settings > Refresh library: re-reads page 1 of the selected categories, at most once every 10 minutes. */
    fun requestManual(): String {
        val now = System.currentTimeMillis()
        val wait = policy.manualWait(now)
        if (wait > 0) return "Refreshed a moment ago. To keep your provider happy, the next refresh is possible in ${(wait + 59_999) / 60_000} min."
        if (repo.api.limiter.paused) return busyText(repo.api.limiter.pauseLeft())
        policy.recordManual(now); savePolicy()
        stopped = false; sessionPages = 0
        repo.refreshLists { job("manual") { newest(); firstPages(langsAll()) } }
        return "Checking the portal for new titles in the background"
    }
    fun manualWait(): Long = policy.manualWait(System.currentTimeMillis())

    /** Several stream failures in a short time: re-read page 1, at most twice a day and 4 h apart. */
    fun reportFailure() {
        val now = System.currentTimeMillis()
        if (!failures.hit(now, 0) || stopped || !policy.earlyAllowed(now)) return
        policy.recordEarly(now); savePolicy()
        repo.refreshLists { job("early") { newest() } }
    }

    /** Runs [f] after the current library job (one at a time); the same request is not queued twice. */
    private fun job(key: String, f: suspend () -> Unit) {
        if (stopped || !queued.add(key)) return
        scope.launch {
            try {
                work.withLock {
                    queued.remove(key)
                    if (stopped) return@withLock
                    coroutineScope { current = launch { f() }; current?.join() }
                }
            } finally { queued.remove(key); loading = false }
        }
    }

    private fun publish(items: List<Item>) {
        val l = items.distinctBy { it.key }
        all = l
        val bc = l.groupBy { it.cat }
        byCat = bc
        catMix = bc.mapValues { (_, v) -> v.asSequence().filter { it.parsed.lang.isNotEmpty() }.map { it.parsed.lang }.toSet() }
        version++
    }

    /** One page, gently: a random gap first, at most one calm retry; null = stop (busy, not answering, cap, stopped). */
    private suspend fun page(cat: String, p: Int): Stalker.VodPage? {
        if (stopped) return null
        if (sessionPages >= SESSION_PAGES) { note = "Loaded enough for now ($SESSION_PAGES pages this session). More loads next time you open the app."; return null }
        if (repo.api.limiter.paused) { note = busyText(repo.api.limiter.pauseLeft()); return null }
        delay(pageGap())
        repeat(2) { attempt ->
            try {
                sessionPages++
                return repo.api.vodPage(cat, p).also { if (note.startsWith("The portal")) note = "" }
            } catch (e: CancellationException) { throw e
            } catch (e: PortalBusyException) { note = e.message ?: ""; return null
            } catch (e: Exception) {
                if (attempt == 0) delay(5_000L + pageGap())
                else note = "The portal is not answering right now; saved titles still work. It will try again later."
            }
        }
        return null
    }

    /** Returns false when fetching should stop. */
    private suspend fun fetchCat(id: String, front: Boolean): Boolean {
        if (id in catEnd) return true
        loading = true
        val p = catNext[id] ?: 1
        val pg = page(id, p) ?: return false
        val known = all.map { it.key }.toHashSet()
        val add = pg.items.filter { it.key !in known }
        publish(if (front) add + all else all + add)
        catNext[id] = p + 1
        if (pg.items.isEmpty() || p >= pg.pages) catEnd += id
        return true
    }

    /** Page 1 of each selected-language category not fetched yet. */
    private suspend fun firstPages(langs: Set<String>) {
        val todo = wantedCats(langs).filter { !catNext.containsKey(it.id) }
        if (todo.isEmpty()) return
        done = 0; total = todo.size
        try {
            for (c in todo) { if (!fetchCat(c.id, front = false)) break; done++ }
        } finally { finishBatch(langs) }
    }

    /** Next pages, newest first across categories: the category whose oldest fetched title is the most recent goes next. */
    private suspend fun morePages(langs: Set<String>, tab: String, n: Int) {
        done = 0; total = n
        try {
            repeat(n) {
                val c = wantedCats(langs, tab).filter { it.id !in catEnd }
                    .maxByOrNull { c -> byCat[c.id]?.minOfOrNull { it.addedMs } ?: Long.MAX_VALUE } ?: return
                if (!fetchCat(c.id, front = false)) return
                done++
            }
        } finally { finishBatch(langs) }
    }

    /** Daily / manual refresh: page 1 of the selected categories already fetched; new titles go in front. */
    private suspend fun newest() {
        val cats = wantedCats(langsAll()).filter { catNext.containsKey(it.id) }
        if (cats.isEmpty()) return
        done = 0; total = cats.size
        var ok = false
        try {
            for (c in cats) {
                val pg = page(c.id, 1) ?: break
                ok = true; done++
                val known = all.map { it.key }.toHashSet()
                val add = pg.items.filter { it.key !in known }
                if (add.isNotEmpty()) publish(add + all)
                if (pg.pages > 1 && c.id in catEnd && add.isNotEmpty()) catEnd -= c.id
            }
            if (ok) refreshedAt = System.currentTimeMillis()
        } finally { finishBatch(langsAll()) }
    }

    private fun finishBatch(langs: Set<String>) {
        loading = false
        complete = wantedCats(langs).all { it.id in catEnd }
        if (refreshedAt == 0L && hasData) refreshedAt = System.currentTimeMillis()
        writeDisk()
    }

    // ---- Disk: a header line, then one JSON item per line; written to a temp file and swapped in ----

    private fun readDisk() {
        diskLoaded = true
        runCatching {
            val f = repo.store.cacheFile(FILE)
            if (!f.exists()) { readOldCache(); return }
            val items = ArrayList<Item>()
            var h = JSONObject()
            f.bufferedReader().useLines { lines ->
                lines.forEachIndexed { n, line ->
                    if (n == 0) { h = JSONObject(line); if (h.optString("a") != owner) return }
                    else if (line.isNotBlank()) runCatching { items += Item.from(JSONObject(line)) }
                }
            }
            if (items.isEmpty()) return
            refreshedAt = h.optLong("t")
            h.optJSONObject("cn")?.let { o -> o.keys().forEach { k -> catNext[k] = o.getInt(k) } }
            h.optJSONArray("ce")?.let { a -> for (i in 0 until a.length()) catEnd += a.getString(i) }
            if (h.optInt("v") < 2) {
                // Saved by 1.44 (whole list): treat every category it has as fully fetched
                items.map { it.cat }.distinct().forEach { catNext[it] = 2; catEnd += it }
            }
            publish(items)
        }
    }

    private fun readOldCache() {
        runCatching {
            val o = JSONObject(repo.store.cacheRead("lib.json") ?: return)
            val cats = o.optJSONObject("c") ?: return
            val items = ArrayList<Item>()
            cats.keys().forEach { id -> items += cats.getJSONArray(id).toItems() }
            if (items.isNotEmpty()) publish(items)
        }
    }

    private fun writeDisk() {
        runCatching {
            val f = repo.store.cacheFile(FILE)
            val tmp = java.io.File(f.path + ".tmp")
            val snap = all
            tmp.bufferedWriter().use { w ->
                val cn = JSONObject(); catNext.forEach { (k, v) -> cn.put(k, v) }
                w.write(JSONObject().put("v", 2).put("a", owner).put("t", refreshedAt).put("n", snap.size)
                    .put("cn", cn).put("ce", org.json.JSONArray(catEnd.toList())).toString()); w.newLine()
                for (i in snap) { w.write(i.toJson().toString()); w.newLine() }
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
