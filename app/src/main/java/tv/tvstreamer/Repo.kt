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

    init { Tmdb.init(ctx, store.tmdbKey, store.omdbKey); Gemini.useKey(store.geminiKey) }

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
    var catFilter by mutableStateOf("")

    private fun setLive(lc: List<Cat>, ch: List<Item>) {
        val lockedIds = lc.filter { it.locked }.map { it.id }.toSet()
        liveCats = lc
        val groups = lc.associate { it.id to it.title }
        // Readable names (also for channels cached by older versions); number-only names become "Channel N · Group"
        rawChannels = ch.map { it.copy(locked = it.locked || it.cat in lockedIds, name = ChannelTitle.display(it.name, it.num, groups[it.cat].orEmpty())) }
        rawByKey = rawChannels.associateBy { it.key }
        applyNames()
        liveIndex.ensure(rawChannels, lc)   // sort channels by type / language in the background, ready for Live TV
    }

    /** Type and language of every channel (worked out off the main thread, saved on disk). */
    val liveIndex = LiveIndex(store)

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
        if (channels.isEmpty()) {
            runCatching {
                store.cacheRead("live.json")?.let {
                    val o = JSONObject(it)
                    setLive(o.getJSONArray("cats").toCats(), o.getJSONArray("items").toItems())
                    listsAt = o.optLong("t")
                }
                store.cacheRead("vod.json")?.let { vodCats = org.json.JSONArray(it).toCats() }
            }
        }
        lib.start()       // the saved catalog appears now; a stale one refreshes in the background
        val fresh = channels.isNotEmpty() && vodCats.isNotEmpty() && System.currentTimeMillis() - listsAt < Library.MAX_AGE
        if (fresh && !force) return@withContext
        // A second forced refresh right after one finished (e.g. start-up then sign-in sync) would just repeat it
        if (fresh && api.token.isNotEmpty() && System.currentTimeMillis() - fetchedAt < 60_000) return@withContext   // api.reset() (new login) clears the token
        if (channels.isEmpty()) status = "Loading channels and categories…"
        try {
            val lc = api.liveCats()
            val ch = api.channels()
            val vc = api.vodCats()
            setLive(lc, ch)
            if (vc != vodCats) { clearRows(); lib.version++ }
            vodCats = vc
            listsAt = System.currentTimeMillis()
            fetchedAt = listsAt
            store.cacheWrite("live.json", JSONObject().put("t", listsAt).put("cats", catsJson(lc)).put("items", itemsJson(ch)).toString())
            store.cacheWrite("vod.json", catsJson(vc).toString())
            status = ""
            lib.start()
        } catch (e: Exception) {
            status = if (channels.isEmpty()) "Portal error: ${e.message}" else ""
            // Portal refresh failed but we have cached data; stay quiet instead of saying Offline
        }
    }

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
    fun visibleVod() = ordered("vod").filter { "vod:${it.id}" !in hidden }

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

    fun isHidden(kind: String, id: String) = "$kind:$id" in hidden

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
        favs = saved.filter { it.kind != "itv" } + f.all()
    }

    private fun applyFolders(f: FolderSet) {
        val named = f.mapItems(::named)
        folders = named
        store.saveFolders(named)
        favs = favs.filter { it.kind != "itv" } + named.all()
    }

    fun isFav(i: Item) = if (i.kind == "itv") folders.contains(i.key) else favs.any { it.key == i.key }
    /** Quick toggle (remote Favourite key, search): a channel goes into the last used folder, or out of every folder. */
    fun toggleFav(i: Item) {
        if (i.kind == "itv") {
            if (isFav(i)) removeFromFavorites(listOf(i))
            else addToFolder(store.sort("last_folder", "").takeIf { it in folders.folders } ?: folders.names.firstOrNull() ?: FolderSet.DEFAULT, listOf(i))
            return
        }
        favs = if (isFav(i)) favs.filter { it.key != i.key } else favs + i
        store.saveList("fav", favs.filter { it.kind != "itv" })
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
        favs = favs.filter { it.kind != "itv" } + folders.all()
    }

    /** Key of the channel playing now (Live TV marks it). */
    var nowPlaying by mutableStateOf("")
    fun markPlayed(i: Item) { if (i.kind == "itv") { store.addRecent(i); nowPlaying = i.key } }

    /** Returns an error message, or null when access is allowed. */
    fun gate(locked: Boolean): String? {
        if (!locked) return null
        val pw = store.password
        if (pw.isEmpty()) return "Locked. Save the password in Settings first"
        val pp = api.parentPassword
        if (pp != null && pp != pw) return "Saved password was rejected. Update it in Settings"
        return null
    }

    private val searchCache = TtlCache<String, List<Item>>(40, 10 * 60_000)

    fun search(q: String): Pair<List<Item>, List<Item>> {
        val toks = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val wantMovie = toks.any { it in MOVIE_W }
        val wantLive = toks.any { it in LIVE_W }
        val core = toks.filter { it !in STOP }
        if (core.isEmpty()) return emptyList<Item>() to emptyList()
        val hid = hidden
        val unlocked = gate(true) == null

        var live = emptyList<Item>()
        if (!wantMovie || wantLive) {
            val titles = liveCats.associate { it.id to it.title }
            val first = squash(core[0])
            live = channels.filter { "itv:${it.cat}" !in hid && (!it.locked || unlocked) }
                .filter { c ->
                    if (core.size == 1 && core[0].all { ch -> ch.isDigit() }) c.num.toString() == core[0]
                    else {
                        val h = squash(c.name + " " + (if (c.key in chNames) portalName(c) + " " else "") + (titles[c.cat] ?: ""))
                        core.all { t -> h.contains(squash(t)) }
                    }
                }
                .sortedBy { if (squash(it.name).startsWith(first)) 0 else 1 }
                .take(100)
        }

        var vod = emptyList<Item>()
        if (!wantLive || wantMovie) {
            val soft = core.filter { it in LANGS || it.matches(YEAR) }
            val hard = core.filter { it !in soft }
            val sq = (if (hard.isEmpty()) core else hard).joinToString(" ")
            // With the whole catalog saved locally the search is local only (searchAll adds the local matches)
            // One portal search request (only when the user searches); results are kept in the saved catalog
            // Cached 10 min: re-typing, switching the scope chip or reopening search does not ask the portal again
            val ck = sq.lowercase()
            val res = searchCache[ck] ?: runCatching { api.vod("*", 1, sq).first }.getOrNull()
                ?.also { searchCache[ck] = it; lib.merge(it) }.orEmpty()
            val titles = vodCats.associate { it.id to it.title }
            val f = res.filter { m ->
                val h = squash(m.name + " " + (titles[m.cat] ?: ""))
                soft.all { s -> h.contains(squash(s)) }
            }
            vod = (if (f.isEmpty()) res else f)
                .filter { "vod:${it.cat}" !in hid && (!it.locked || unlocked) }
        }
        return live to vod
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
        applyFolders(LiveModes.create(folders, n, current?.map { named(rawByKey[it.key] ?: it) }))
        selectMode(n); return n
    }
    fun renameMode(old: String, new: String) {
        val n = FolderSet.clean(new); if (n.isEmpty()) return
        renameFolder(old, n); if (liveMode == old) selectMode(n)
    }
    fun deleteMode(name: String) { deleteFolder(name); if (liveMode == name) selectMode(LiveModes.DEFAULT) }

    /** Is [ch] listed in the current mode? */
    fun isOn(ch: Item): Boolean = LiveModes.isOn(ch, mode(), folders, hidden)
    /** Long-press / "Hide channel": enable or disable in the current mode (Default: unhide / hide). */
    fun toggleOn(ch: Item) {
        val (f, h) = LiveModes.toggle(named(rawByKey[ch.key] ?: ch), mode(), folders, hidden)
        if (f != folders) applyFolders(f)
        if (h != hidden) { hidden = h; store.hidden = h }
    }
    /** Select mode: show (true) or hide every channel in [chs] in the current mode, in one save. */
    fun setOn(chs: List<Item>, on: Boolean) {
        val (f, h) = LiveSelect.apply(chs.map { named(rawByKey[it.key] ?: it) }, on, mode(), folders, hidden)
        if (f != folders) applyFolders(f)
        if (h != hidden) { hidden = h; store.hidden = h }
    }
    fun toTopOfMode(ch: Item) { val m = mode(); if (m != LiveModes.DEFAULT) applyFolders(LiveModes.toTop(ch, m, folders)) }

    /** Live TV languages: null = all; else only these are listed (synced with the other language choices). */
    var liveLangs by mutableStateOf(store.langs("live"))
        private set
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
}
