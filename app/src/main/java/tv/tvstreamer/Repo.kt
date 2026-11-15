package tv.tvstreamer

import kotlinx.coroutines.sync.withLock

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

private val MOVIE_W = setOf("movie", "movies", "film", "films")
private val LIVE_W = setOf("channel", "channels", "live", "tv")
private val STOP = MOVIE_W + LIVE_W + setOf("show", "shows", "watch", "play", "open", "search", "for", "the", "of")
private val LANGS = setOf("hindi", "english", "tamil", "telugu", "malayalam", "kannada", "punjabi",
    "bengali", "marathi", "gujarati", "urdu", "arabic", "spanish", "french", "korean")
private val YEAR = Regex("(19|20)\\d\\d")
private val SERIES_CAT = Regex("(?i)(series|serie|séries|tv ?shows?|\\bshows?\\b|seasons?|episod|dramas?|novelas?|sitcom|anime|docuseries|k-?drama)")

fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

class Repo(private val ctx: Context) {
    val store = Store(ctx)
    val api = Stalker(store)
    /** Movies / series of the selected languages, loaded in the background (see Library). */
    val lib = Library(this)
    /** Series seasons / episodes, saved on disk per series and season and fetched one season at a time. */
    val seasons = Seasons(api, store.cacheDir("seasons"))

    init { Tmdb.init(ctx, store.tmdbKey, store.omdbKey); Gemini.useKey(store.geminiKey); lib.preload() }

    var liveCats by mutableStateOf(emptyList<Cat>())
    var vodCats by mutableStateOf(emptyList<Cat>())
    /** Channels with the user's display names (what every screen shows); [rawChannels] has the portal's names. */
    var channels by mutableStateOf(emptyList<Item>())
    var rawChannels by mutableStateOf(emptyList<Item>())
        private set
    private var rawByKey: Map<String, Item> = emptyMap()
    var chNames by mutableStateOf(store.chNames())
        private set
    var status by mutableStateOf("")
    var hidden by mutableStateOf(store.hidden)
    /** Favourites: movies / series from the saved list, channels from the My Channels folders (see FolderSet). */
    var favs by mutableStateOf(store.list("fav"))
    var lastLiveCat by mutableStateOf("")
    var lastLiveIndex by mutableStateOf(0)
    var lastLiveCatId by mutableStateOf("")
    var folders by mutableStateOf(FolderSet())
        private set
    var vodOrder by mutableStateOf(store.catOrder("vod"))
    var liveOrder by mutableStateOf(store.catOrder("itv"))

    /** First page of each VOD category (Home rows, category samples). Loaded only when a row scrolls into view. */
    val rows = mutableStateMapOf<String, List<Item>>()
    val rowMore = mutableStateMapOf<String, Boolean>()
    val rowErrors = mutableStateMapOf<String, String>()
    private val rowGate = Semaphore(3)
    /** Scroll positions and last focused card per screen, so Back returns to the same place. */
    val listStates = mutableMapOf<String, LazyListState>()
    val gridStates = mutableMapOf<String, LazyGridState>()
    val lastFocus = mutableMapOf<String, String>()
    /** An item (poster / channel tile) got focus on [screen]: remembered separately so closing Search returns to it. */
    fun focusItem(screen: String, key: String) { lastFocus[screen] = key; lastFocus[SearchReturn.itemKey(screen)] = key }
    var catFilter by mutableStateOf("")

    private fun setLive(lc: List<Cat>, ch: List<Item>) {
        val lockedIds = lc.filter { it.locked }.map { it.id }.toSet()
        liveCats = LiveCatalog.withMissingCats(lc, ch)
        val groups = liveCats.associate { it.id to it.title }
        // Readable names (also for channels cached by older versions); number-only names become "Channel N · Group"
        rawChannels = ch.map { it.copy(locked = it.locked || it.cat in lockedIds, name = ChannelTitle.display(it.name, it.num, groups[it.cat].orEmpty())) }
        rawByKey = rawChannels.associateBy { it.key }
        applyNames()
        liveIndex.ensure(rawChannels, lc)   // sort channels by type / language in the background, ready for Live TV
        bg.launch { refreshLiveGroupKeys() }
        prewarmLive()
    }

    /** Type and language of every channel (worked out off the main thread, saved on disk). */
    val liveIndex = LiveIndex(store).also { it.onReady = { prewarmLive() } }

    /** When channels and categories were last read from the portal (from live.json). */
    var listsAt by mutableStateOf(0L)
        private set

    /**
     * Shows the saved channel list, categories and movie / series catalog at once; asks the portal again only when
     * they are a day old, there is nothing saved, or [force] (Settings > Refresh library, repeated link failures).
     */
    suspend fun load(force: Boolean = false) = withContext(Dispatchers.IO) { loadLock.withLock { loadNow(force) } }

    /** One list refresh at a time: start-up, sign-in sync, Settings and the daily refresh used to overlap (3 requests each). */
    private val loadLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var fetchedAt = 0L

    private suspend fun loadNow(force: Boolean) = withContext(Dispatchers.IO) {
        if (store.portal.isBlank() || store.mac.isBlank()) {
            status = "Open Settings and enter the portal URL and MAC"
            return@withContext
        }
        openLocal()
        lib.start()       // the saved catalog appears now; a stale one refreshes in the background
        // 1.70: channels / categories are asked again only once a day (or first run / forced), silently in the background
        val fresh = channels.isNotEmpty() && vodCats.isNotEmpty() && !live.due()
        if (fresh && !force) return@withContext
        // A second forced refresh right after one finished (e.g. start-up then sign-in sync) would just repeat it
        if (fresh && api.token.isNotEmpty() && System.currentTimeMillis() - fetchedAt < 60_000) return@withContext   // api.reset() (new login) clears the token
        if (channels.isEmpty()) status = "Loading channels and categories…"
        try {
            val s = live.fetch()
            val vc = api.vodCats()
            setLive(s.cats, s.items)
            if (vc != vodCats) { clearRows(); lib.version++ }
            vodCats = vc
            listsAt = s.at
            fetchedAt = listsAt
            store.cacheWrite("vod.json", catsJson(vc).toString())
            status = ""
            lib.start()
        } catch (e: Exception) {
            status = if (channels.isEmpty()) "Portal error: ${e.message}" else ""
            // Portal refresh failed but we have cached data; stay quiet instead of saying Offline
        }
    }

    /** The channel list on the device (live.json): read once, kept in memory; get_genres / get_all_channels only via [fetch]. */
    val live = LiveSource(
        object : LivePortal {
            override fun liveCats() = api.liveCats()
            override fun channels() = api.channels()
        },
        object : LiveDisk {
            override fun read() = store.cacheRead("live.json")
            override fun write(text: String) = store.cacheWrite("live.json", text)
        })
    private val localLock = Any()
    @Volatile private var localShown = false

    /** Shows the saved channels, categories and movie / series categories (no portal). Runs once, off the main thread. */
    fun openLocal() {
        if (localShown) return
        synchronized(localLock) {
            if (localShown) return
            val t0 = System.nanoTime()
            runCatching {
                live.local()?.let { setLive(it.cats, it.items); listsAt = it.at }
                if (vodCats.isEmpty()) store.cacheRead("vod.json")?.let { vodCats = org.json.JSONArray(it).toCats() }
            }
            localShown = true
            LiveTiming.loaded((System.nanoTime() - t0) / 1_000_000, channels.size)
        }
    }

    // ---- Live TV list model (1.70): built off the main thread, last one kept ----
    @Volatile var liveModelLast: LiveModel = LiveModel.EMPTY
        private set
    fun liveInputs(view: LiveModes.View) = LiveInputs(channels, liveCats, liveOrder, hidden, showAdult, liveLangs,
        liveIndex.info, mode(), folders, view)
    fun liveModelCached(i: LiveInputs): LiveModel? = liveModelLast.takeIf { i.same(it.inputs) }
    suspend fun liveModel(i: LiveInputs): LiveModel = withContext(Dispatchers.Default) {
        liveModelCached(i) ?: LiveModel.build(i, liveModelLast).also { liveModelLast = it }
    }
    /** Lays the Live TV list out in the background (start-up, refresh, sorting done), so opening it is instant. */
    fun prewarmLive() { if (channels.isNotEmpty()) bg.launch { runCatching { liveModel(liveInputs(liveView.view)) } } }

    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Pull channels, categories and the whole movie / series catalog again (in the background). */
    fun refreshAll() { bg.launch { load(force = true) } }

    /** Re-read channels and categories (one request each), then run [then] (the library's gentle refresh). */
    fun refreshLists(then: () -> Unit) { bg.launch { runCatching { load(force = true) }; then() } }

    /** Categories in the user's order; categories never arranged keep the portal order after them. */
    fun ordered(kind: String): List<Cat> {
        val cats = (if (kind == "itv") liveCats else vodCats).distinctBy { it.id }
        val order = if (kind == "itv") liveOrder else vodOrder
        if (order.isEmpty()) return cats
        val pos = order.withIndex().associate { it.value to it.index }
        return cats.filter { it.id in pos }.sortedBy { pos[it.id] } + cats.filter { it.id !in pos }
    }

    fun visibleLive() = ordered("itv").filter { "itv:${it.id}" !in hidden }
    fun visibleVod() = ordered("vod").filter { groupOn("vod:${it.id}") }

    private fun saveOrder(kind: String, ids: List<String>) {
        if (kind == "itv") liveOrder = ids else vodOrder = ids
        store.saveCatOrder(kind, ids)
    }

    /** Move a category one visible step up (-1) or down (+1); hidden categories are jumped over. */
    fun moveCat(kind: String, id: String, delta: Int) {
        val ids = ordered(kind).map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        if (i < 0 || delta == 0) return
        val step = if (delta < 0) -1 else 1
        var n = i + step
        if ("$kind:$id" !in hidden) while (n in ids.indices && "$kind:${ids[n]}" in hidden) n += step
        if (n !in ids.indices) return
        ids.removeAt(i)
        ids.add(n, id)
        saveOrder(kind, ids)
    }

    fun moveCatTo(kind: String, id: String, top: Boolean) {
        val ids = ordered(kind).map { it.id }.toMutableList()
        if (!ids.remove(id)) return
        if (top) ids.add(0, id) else ids.add(id)
        saveOrder(kind, ids)
    }

    fun resetOrder(kind: String) = saveOrder(kind, emptyList())

    fun isHidden(kind: String, id: String) = if (kind == "vod") !groupOn("vod:$id") else "$kind:$id" in hidden

    /** Best guess from the category name: does this VOD category hold series? */
    private val seriesCatMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var seriesCatsAny: Pair<List<Cat>, Boolean> = emptyList<Cat>() to false
    fun isSeriesCat(c: Cat) = seriesCatMemo.getOrPut(c.title) { SERIES_CAT.containsMatchIn(c.title) }
    fun hasSeriesCats(): Boolean {
        val cats = vodCats
        seriesCatsAny.let { (l, v) -> if (l === cats) return v }
        return cats.any { isSeriesCat(it) }.also { seriesCatsAny = cats to it }
    }

    /** Load the first page of a VOD category (at most 3 requests at a time). */
    suspend fun loadRow(cat: Cat) {
        if (rows.containsKey(cat.id)) return
        // Rows come from the saved catalog; a category with nothing saved yet asks for its newest page (gently, queued)
        if (lib.inCat(cat.id).isEmpty()) lib.moreInCat(cat.id)
        return
        @Suppress("UNREACHABLE_CODE")
        withContext(Dispatchers.IO) {
            rowGate.withPermit {
                if (rows.containsKey(cat.id)) return@withPermit
                try {
                    val r = api.vod(cat.id, 1)
                    rows[cat.id] = r.first.distinctBy { it.key }
                    rowMore[cat.id] = r.second
                    rowErrors.remove(cat.id)
                } catch (e: Exception) {
                    rowErrors[cat.id] = e.message ?: "Load failed"
                }
            }
        }
    }

    fun retryRow(cat: Cat) { rowErrors.remove(cat.id); rows.remove(cat.id) }
    fun clearRows() { rows.clear(); rowMore.clear(); rowErrors.clear() }

    fun toggleHideChannel(i: Item) {
        val k = "ch:${i.key}"
        hidden = if (k in hidden) hidden - k else hidden + k
        store.hidden = hidden
    }
    fun isChannelHidden(i: Item) = "ch:${i.key}" in hidden
    /** A single movie / series hidden from every row and grid (synced with the hidden set). */
    fun isTitleHidden(i: Item) = "title:${i.key}" in hidden
    fun toggleHideTitle(i: Item) {
        val k = "title:${i.key}"
        hidden = if (k in hidden) hidden - k else hidden + k
        store.hidden = hidden; lib.version++
    }
    fun hiddenTitleCount() = hidden.count { it.startsWith("title:") }
    fun unhideAllTitles() { hidden = hidden.filterNot { it.startsWith("title:") }.toSet(); store.hidden = hidden; lib.version++ }
    /** Watched = played to the end (kept in the resume list at 100 %, so it leaves Continue watching). */
    fun isWatched(i: Item): Boolean { val (p, d) = store.resumeInfo(i); return d > 0 && p >= d * 0.95 }
    fun setWatched(i: Item, on: Boolean) {
        if (on) { val d = store.resumeInfo(i).second.takeIf { it > 0 } ?: 1L; store.saveResume(i, d, d) } else store.removeResume(i.key)
        lib.version++
    }
    fun hiddenChannelCount() = hidden.count { it.startsWith("ch:") }
    fun unhideAllChannels() { hidden = hidden.filterNot { it.startsWith("ch:") }.toSet(); store.hidden = hidden }
    fun toggleHide(kind: String, id: String) {
        if (kind == "vod") {
            val c = ordered(kind).find { it.id == id }
            toggleGroupKey("vod:$id", c?.let { Names.cat(it.title) } ?: id)
            return
        }
        val k = "$kind:$id"
        hidden = if (k in hidden) hidden - k else hidden + k
        store.hidden = hidden
    }

    /** Hide a Home / Movies / Series row (synced with the hidden set, like hidden categories). */
    fun hideRow(tab: String, rowKey: String, title: String) {
        if (HomeHide.isHidden(hidden, tab, rowKey)) return
        hidden = hidden + HomeHide.entry(tab, rowKey, title); store.hidden = hidden
    }

    fun unhideRow(entry: String) { hidden = hidden.filterNot { HomeHide.id(it) == HomeHide.id(entry) }.toSet(); store.hidden = hidden }

    /** Every hidden Home row and hidden Movies / Series category shown again (hidden channels stay hidden). */
    fun unhideAllRows() { hidden = hidden.filterNot { it.startsWith(HomeHide.PREFIX) || it.startsWith("vod:") }.toSet(); store.hidden = hidden }

    /** Re-reads everything cloud sync may have changed. */
    fun reloadSynced() {
        hidden = store.hidden
        chNames = store.chNames()
        loadFavs()
        applyNames()
        vodOrder = store.catOrder("vod")
        liveOrder = store.catOrder("itv")
        Theme.mode = store.theme
        liveLangs = store.langs("live")?.takeIf { it.isNotEmpty() }
        showAdult = store.showAdult
        syncTick++
    }
    /** Bumped after a cloud sync changed local data. */
    var syncTick by mutableStateOf(0)

    init { loadFavs() }

    /** Reads folders + the saved list; old-style favourite channels move into a "Favorites" folder. */
    private fun loadFavs() {
        var f = store.folders()
        val saved = store.list("fav")
        // Also catches channel favourites synced from an older app version
        if (!store.favChannelsMigrated || saved.any { it.kind == "itv" }) {
            f = FolderSet.migrate(f, saved.filter { it.kind == "itv" })
            store.saveFolders(f)
            store.saveList("fav", saved.filter { it.kind != "itv" })
            store.favChannelsMigrated = true
        }
        folders = f
        favs = saved.filter { it.kind != "itv" } + f.channels()
    }

    private fun applyFolders(f: FolderSet) {
        val named = f.mapItems(::named)
        folders = named
        favs = favs.filter { it.kind != "itv" } + named.channels()
        persistFolders(named)
    }

    private fun persistFolders(f: FolderSet) { bg.launch { store.saveFolders(f) } }
    private fun persistFavs(list: List<Item>) { bg.launch { store.saveList("fav", list) } }
    private fun persistHidden(h: Set<String>) { bg.launch { store.hidden = h } }

    fun isFav(i: Item) = if (i.kind == "itv") folders.contains(i.key) else favs.any { it.key == i.key }
    /** Quick toggle (remote Favourite key, search): a channel goes into the last used folder, or out of every folder. */
    fun toggleFav(i: Item) {
        if (i.kind == "itv") {
            if (isFav(i)) removeFromFavorites(listOf(i))
            else addToFolder(store.sort("last_folder", "").takeIf { it in folders.folders } ?: folders.names.firstOrNull() ?: FolderSet.DEFAULT, listOf(i))
            return
        }
        favs = if (isFav(i)) favs.filter { it.key != i.key } else favs + i
        persistFavs(favs.filter { it.kind != "itv" })
    }

    // ---- My Channels folders ----
    fun createFolder(name: String): String? {
        val n = FolderSet.clean(name)
        if (n.isEmpty()) return null
        applyFolders(folders.create(n)); return n
    }
    fun renameFolder(old: String, new: String) = applyFolders(folders.rename(old, new))
    fun deleteFolder(name: String) = applyFolders(folders.delete(name))
    fun moveFolder(name: String, step: Int) = applyFolders(folders.move(name, step))
    fun addToFolder(name: String, chans: List<Item>) {
        applyFolders(folders.add(name, chans.map { c -> named(rawByKey[c.key] ?: c) }))
        store.saveSort("last_folder", FolderSet.clean(name))
    }
    fun removeFromFolder(name: String, chans: List<Item>) = applyFolders(folders.remove(name, chans.map { it.key }.toSet()))
    fun removeFromFavorites(chans: List<Item>) = applyFolders(folders.removeEverywhere(chans.map { it.key }.toSet()))
    fun moveInFolder(name: String, ch: Item, step: Int) = applyFolders(folders.moveItem(name, ch.key, step))

    // ---- Channel names ----
    /** [i] with the user's name for it (channels only). */
    fun named(i: Item): Item = ChannelNames.apply(i, chNames, rawByKey)
    fun isRenamed(i: Item) = i.key in chNames
    /**
     * A channel as the portal lists it now (current cmd / stream id) with its custom name, for playing. Folders,
     * Recently watched and synced favourites keep copies that can be old; a stale cmd makes create_link fail.
     */
    fun fresh(i: Item): Item = if (i.kind != "itv") i else rawByKey[i.key]?.let { named(it) } ?: i

    fun portalName(i: Item): String = rawByKey[i.key]?.name ?: i.name
    /** Sets (or with null / blank, resets) a channel's display name; used in lists, folders, the player and search. */
    fun renameChannel(i: Item, name: String?) {
        chNames = ChannelNames.set(chNames, i.key, name)
        store.saveChNames(chNames)
        applyNames()
        store.saveList("recent", store.list("recent").map(::named))
    }
    private fun applyNames() {
        channels = rawChannels.map(::named)
        val f = folders.mapItems(::named)
        if (f != folders) { folders = f; store.saveFolders(f) }
        favs = favs.filter { it.kind != "itv" } + folders.channels()
    }

    /** A video is on screen (1.71: the title indexer pauses meanwhile). */
    @Volatile var playing = false

    /** Key of the channel playing now (Live TV marks it). */
    var nowPlaying by mutableStateOf("")
    /** Sections of the Live TV list last handed to the player (Left panel mirrors them). */
    var liveSpans: List<LiveOverlay.Span> = emptyList()
    var liveSpanKeys = 0
    fun markPlayed(i: Item) { if (i.kind == "itv") { store.addRecent(i); nowPlaying = i.key } }

    /** Returns an error message, or null when access is allowed. Playing locked items still requires this. */
    fun gate(locked: Boolean): String? {
        if (!locked) return null
        val pw = store.password
        if (pw.isEmpty()) return "Locked. Save the password in Settings first"
        val pp = api.parentPassword
        if (pp != null && pp != pw) return "Saved password was rejected. Update it in Settings"
        return null
    }

    /** Show locked / adult portal groups in lists. Default on; the user can turn it off. */
    private var showAdultState by mutableStateOf(store.showAdult)
    var showAdult: Boolean
        get() = showAdultState
        set(v) { showAdultState = v; store.showAdult = v; lib.version++ }
    /** Whether a locked title or category is listed (play still goes through [gate]). */
    fun listOk(locked: Boolean): Boolean = AdultContent.listOk(locked, showAdult)

    private val searchCache = TtlCache<String, List<Item>>(40, 10 * 60_000)

    /** Live channels matching the query, from the saved channel list (offline, instant). A number finds that channel. */
    fun searchLive(q: String): List<Item> {
        val toks = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val core = toks.filter { it !in STOP }.ifEmpty { toks.filter { it !in LIVE_W } }
        if (core.isEmpty()) return emptyList()
        val hid = hidden
        if (core.size == 1 && core[0].all { it.isDigit() })
            return channels.filter { "itv:${it.cat}" !in hid && listOk(it.locked) && it.num.toString() == core[0] }
        val query = SearchIndex.query(core.joinToString(" "))
        val titles = liveCats.associate { it.id to it.title }
        return channels.asSequence()
            .filter { "itv:${it.cat}" !in hid && listOk(it.locked) }
            .mapNotNull { c ->
                val pretty = ChannelTitle.pretty(c.name)
                val extra = listOf(c.name, if (c.key in chNames) portalName(c) else "", titles[c.cat] ?: "")
                val sc = SearchIndex.score(query, pretty, extra)
                if (sc > 0) c to sc else null
            }
            .sortedByDescending { it.second }
            .map { it.first }.take(100).toList()
    }

    /**
     * The portal's own search, used only when the saved catalog has few matches (1.69). Gentle: at most [pages] pages
     * (one request at a time through the portal limiter), cached 10 min per query, nothing while the portal asked us
     * to slow down. Results are kept in the saved catalog, so the next search finds them offline.
     */
    fun portalSearch(q: String, pages: Int = 3): List<Item> {
        val toks = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val core = toks.filter { it !in STOP }
        if (core.isEmpty()) return emptyList()
        // Language / year words narrow the local match; the portal is asked for the title words
        val hard = core.filter { it !in LANGS && !it.matches(YEAR) }
        val sq = (hard.ifEmpty { core }).joinToString(" ")
        val ck = sq.lowercase()
        searchCache[ck]?.let { return it }
        if (api.limiter.paused) return emptyList()
        val out = mutableListOf<Item>()
        for (p in 1..pages) {
            val pg = runCatching { api.vodPage("*", p, sq) }.getOrNull() ?: break
            out += pg.items
            if (pg.items.isEmpty() || p >= pg.pages) break
        }
        val res = out.distinctBy { it.key }
        searchCache[ck] = res
        if (res.isNotEmpty()) lib.merge(res)
        return res
    }

    // ---- Live TV guide, modes, languages, reminders (v1.52) ----

    /** Now / next, loaded only for the channels on screen (see EpgLoader). */
    // Guide: cached on disk and shown at once, refreshed in the background (1.53)
    val epg = EpgLoader({ id -> api.shortEpg(id) }, { api.linkBusy.get() > 0 },
        fetchDay = { id -> api.dayEpg(id) }, load = { store.cacheRead("epg.json") }, save = { store.cacheWrite("epg.json", it) })

    /** Current Live TV mode: "" = Default (every channel not hidden), else a custom mode's name. */
    var liveMode by mutableStateOf(store.sort("live_mode", LiveModes.DEFAULT))
        private set
    fun selectMode(name: String) {
        liveMode = if (name in folders.folders) name else LiveModes.DEFAULT
        store.saveSort("live_mode", liveMode)
    }
    /** Live TV list view (All channels, scroll, focused channel), kept across the player and restarts (1.53). */
    var liveView by mutableStateOf(LiveView.decode(store.sort("live_view", "")))
        private set
    fun saveLiveView(v: LiveView) { if (v != liveView) { liveView = v; store.saveSort("live_view", v.encode()) } }
    /** The mode actually used ("" when the saved one was deleted, e.g. on another device). */
    fun mode(): String = liveMode.takeIf { it in folders.folders } ?: LiveModes.DEFAULT
    val modes: List<String> get() = folders.names

    fun createMode(name: String, current: List<Item>?): String? {
        val n = FolderSet.clean(name)
        if (n.isEmpty()) return null
        val chans = current?.map { named(rawByKey[it.key] ?: it) }?.filter { it.kind == "itv" }
        var f = LiveModes.create(folders, n, chans)
        // "What's visible now" also copies currently shown movie / series / Live groups so one mode
        // drives every tab. "Everything hidden" (current == null) starts empty.
        if (current != null) f = f.add(n, groupStubs())
        applyFolders(f)
        selectMode(n); return n
    }
    fun renameMode(old: String, new: String) {
        val n = FolderSet.clean(new); if (n.isEmpty()) return
        renameFolder(old, n); if (liveMode == old) selectMode(n)
    }
    fun deleteMode(name: String) { deleteFolder(name); if (liveMode == name) selectMode(LiveModes.DEFAULT) }

    /** Category title used to resolve a live channel's type / sub / region. */
    private fun catTitleOf(ch: Item): String = liveCats.firstOrNull { it.id == ch.cat }?.title.orEmpty()

    /** Effective visibility in the current mode (group hides included, unless this channel was shown over them). */
    fun isOn(ch: Item): Boolean {
        val raw = named(rawByKey[ch.key] ?: ch)
        val (type, sub, region) = LiveModes.groups(raw, catTitleOf(raw), liveIndex.info)
        return LiveModes.isVisible(raw, mode(), folders, hidden, type, sub, region)
    }
    /** Hide / show [ch] in the current mode (Default: hide / unhide). Works even when a category group is what hid it. */
    fun toggleOn(ch: Item) {
        val raw = named(rawByKey[ch.key] ?: ch)
        val (type, sub, region) = LiveModes.groups(raw, catTitleOf(raw), liveIndex.info)
        val want = !LiveModes.isVisible(raw, mode(), folders, hidden, type, sub, region)
        val (f, h) = LiveModes.setOn(raw, want, mode(), folders, hidden, type, sub, region)
        if (f != folders) applyFolders(f)
        if (h != hidden) { hidden = h; persistHidden(h) }
    }
    /** Select mode: show (true) or hide every channel in [chs] in the current mode, in one save. */
    fun setOn(chs: List<Item>, on: Boolean) {
        val namedChs = chs.map { named(rawByKey[it.key] ?: it) }
        val (f, h) = LiveSelect.apply(namedChs, on, mode(), folders, hidden)
        if (f != folders) applyFolders(f)
        if (h != hidden) { hidden = h; persistHidden(h) }
    }
    /** Select mode using list rows, so Show also works for channels hidden only by a group. */
    fun setOnRows(rows: List<LiveModes.Row>, on: Boolean) {
        val sel = rows.map { it.ch.key }.toSet()
        val namedRows = rows.map { r ->
            val raw = named(rawByKey[r.ch.key] ?: r.ch)
            if (raw === r.ch) r else r.copy(ch = raw)
        }
        val (f, h) = LiveSelect.applyRows(namedRows, sel, on, mode(), folders, hidden)
        if (f != folders) applyFolders(f)
        if (h != hidden) { hidden = h; persistHidden(h) }
    }
    /** Hold on a left-nav category / sub-category / region: hide it, or show it again (synced like channel hides). */
    fun toggleGroup(key: String) = toggleGroupKey(key, Taxonomy.label(key))

    /** Whether a movie / series / Live group is shown in the current catalog mode. */
    fun groupOn(key: String): Boolean = CatalogGroups.groupOn(key, mode(), folders, hidden)

    @Volatile private var cachedLiveGroupKeys: List<String> = emptyList()

    fun groupStubs(family: String? = null): List<Item> {
        val wantVod = family == null || family == CatalogGroups.VOD
        val wantLive = family == null || family == CatalogGroups.GRP
        val vod = if (wantVod) ordered("vod").filter { groupOn("vod:${it.id}") }.map { CatalogGroups.stub("vod:${it.id}", Names.cat(it.title)) } else emptyList()
        val live = if (wantLive) cachedLiveGroupKeys.ifEmpty { computeLiveGroupKeys() }.mapNotNull { k -> k.takeIf { groupOn(it) }?.let { CatalogGroups.stub(it, Taxonomy.label(it)) } } else emptyList()
        return vod + live
    }

    private fun computeLiveGroupKeys(): List<String> {
        val catTitle = liveCats.associate { it.id to it.title }
        val rows = LiveModes.rows(channels, LiveModes.DEFAULT, FolderSet(), emptySet(), LiveModes.View.ALL, null, liveIndex.info, catTitle)
        return CatalogGroups.liveGroups(LiveModes.tree(rows, emptySet(), LiveModes.View.ALL)).map { it.key }
    }

    private fun refreshLiveGroupKeys() { cachedLiveGroupKeys = computeLiveGroupKeys() }

    private fun needsGroupSeed(): Boolean {
        val m = mode()
        return m != LiveModes.DEFAULT && m in folders.folders && folders.items(m).isNotEmpty()
    }

    private fun applyGroupChange(family: String?, block: (List<Item>) -> Pair<FolderSet, Set<String>>) {
        fun apply(seed: List<Item>) {
            val (f, h) = block(seed)
            if (f != folders) applyFolders(f)
            if (h != hidden) { hidden = h; persistHidden(h) }
        }
        if (!needsGroupSeed()) { apply(emptyList()); return }
        val needLive = family == null || family == CatalogGroups.GRP
        if (needLive && cachedLiveGroupKeys.isEmpty()) {
            bg.launch {
                refreshLiveGroupKeys()
                val seed = groupStubs(family)
                withContext(Dispatchers.Main) { apply(seed) }
            }
        } else apply(groupStubs(family))
    }

    /** Hide / show a group in the current mode (Default: hidden set; custom: mode folder). Syncs with existing modes. */
    fun toggleGroupKey(key: String, title: String) {
        applyGroupChange(CatalogGroups.family(key)) { seed ->
            CatalogGroups.toggle(key, title, mode(), folders, hidden, seed)
        }
    }

    fun setGroupsOn(keys: List<Pair<String, String>>, on: Boolean) {
        if (keys.isEmpty()) return
        val fams = keys.map { CatalogGroups.family(it.first) }.toSet()
        val family = fams.singleOrNull()
        applyGroupChange(family) { seed ->
            val s = if (family != null) seed else fams.flatMap { groupStubs(it) }
            CatalogGroups.setOn(keys, on, mode(), folders, hidden, s)
        }
    }
    fun toTopOfMode(ch: Item) { val m = mode(); if (m != LiveModes.DEFAULT) applyFolders(LiveModes.toTop(ch, m, folders)) }

    /** Live TV languages: null = all; else only these are listed (synced with the other language choices). */
    var liveLangs by mutableStateOf(store.langs("live"))
        private set
    var liveCollapsed by mutableStateOf(LiveCollapse.decode(store.liveCollapsed))
        private set
    fun toggleCollapsed(keys: Collection<String>) { setCollapsed(LiveCollapse.toggle(liveCollapsed, keys)) }
    fun setCollapsed(keys: Set<String>) { liveCollapsed = keys; store.liveCollapsed = LiveCollapse.encode(keys) }
    var catalogFolded by mutableStateOf(LiveCollapse.decode(store.catalogFolded))
        private set
    fun foldCatalog(keys: Set<String>) { catalogFolded = keys; store.catalogFolded = LiveCollapse.encode(keys) }
    var liveNavType by mutableStateOf(store.liveNavType)
        private set
    fun rememberNavType(type: String) { if (type != liveNavType) { liveNavType = type; store.liveNavType = type } }
    fun chooseLiveLangs(v: Set<String>?) { liveLangs = v?.takeIf { it.isNotEmpty() }; store.saveLangs("live", liveLangs) }

    /** Reminders for upcoming programmes (on this device): a toast at the start time. */
    data class Reminder(val chKey: String, val chName: String, val title: String, val start: Long)
    val reminders = mutableStateListOf<Reminder>().apply {
        runCatching {
            val a = org.json.JSONArray(store.cacheRead("reminders.json") ?: "[]")
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                add(Reminder(o.getString("k"), o.getString("c"), o.getString("t"), o.getLong("s")))
            }
        }
    }
    private fun saveReminders() {
        val a = org.json.JSONArray()
        reminders.forEach { a.put(JSONObject().put("k", it.chKey).put("c", it.chName).put("t", it.title).put("s", it.start)) }
        store.cacheWrite("reminders.json", a.toString())
    }
    fun hasReminder(ch: Item, p: Prog) = reminders.any { it.chKey == ch.key && it.start == p.start }
    /** Sets (or, when set already, removes) a reminder; returns true when one is set now. */
    fun toggleReminder(ch: Item, p: Prog): Boolean {
        val r = reminders.firstOrNull { it.chKey == ch.key && it.start == p.start }
        if (r != null) { reminders.remove(r); saveReminders(); return false }
        reminders.add(Reminder(ch.key, ChannelTitle.pretty(ch.name), p.title, p.start)); saveReminders()
        startReminderTicker(); return true
    }
    private var ticker: kotlinx.coroutines.Job? = null
    @Synchronized fun startReminderTicker() {
        if (ticker?.isActive == true || reminders.isEmpty()) return
        ticker = bg.launch {
            while (reminders.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val due = reminders.filter { it.start <= now + 15_000 }
                if (due.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        reminders.removeAll(due); saveReminders()
                        due.filter { now - it.start < 30 * 60_000 }.forEach {
                            android.widget.Toast.makeText(ctx, "⏰ Starting now on ${it.chName}: ${it.title}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    // 1.70: read the saved channel list as soon as the app starts (off the main thread), before any screen asks
    init { bg.launch { if (store.portal.isNotBlank() && store.mac.isNotBlank()) openLocal() } }

}
