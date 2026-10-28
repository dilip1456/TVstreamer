package tv.tvstreamer

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

class Repo(ctx: Context) {
    val store = Store(ctx)
    val api = Stalker(store)
    /** Movies / series of the selected languages, loaded in the background (see Library). */
    val lib = Library(this)

    init { Tmdb.init(ctx, store.tmdbKey, store.omdbKey); Gemini.useKey(store.geminiKey) }

    var liveCats by mutableStateOf(emptyList<Cat>())
    var vodCats by mutableStateOf(emptyList<Cat>())
    var channels by mutableStateOf(emptyList<Item>())
    var status by mutableStateOf("")
    var hidden by mutableStateOf(store.hidden)
    var favs by mutableStateOf(store.list("fav"))
    var lastLiveCat by mutableStateOf("")
    var lastLiveIndex by mutableStateOf(0)
    var lastLiveCatId by mutableStateOf("")
    var customCats by mutableStateOf(store.customCats())
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
        channels = ch.map { it.copy(locked = it.locked || it.cat in lockedIds) }
    }

    /** When channels and categories were last read from the portal (from live.json). */
    var listsAt by mutableStateOf(0L)
        private set

    /**
     * Shows the saved channel list, categories and movie / series catalog at once; asks the portal again only when
     * they are a day old, there is nothing saved, or [force] (Settings > Refresh library, repeated link failures).
     */
    suspend fun load(force: Boolean = false) = withContext(Dispatchers.IO) {
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
        if (channels.isEmpty()) status = "Loading channels and categories…"
        try {
            val lc = api.liveCats()
            val ch = api.channels()
            val vc = api.vodCats()
            setLive(lc, ch)
            if (vc != vodCats) { clearRows(); lib.version++ }
            vodCats = vc
            listsAt = System.currentTimeMillis()
            store.cacheWrite("live.json", JSONObject().put("t", listsAt).put("cats", catsJson(lc)).put("items", itemsJson(ch)).toString())
            store.cacheWrite("vod.json", catsJson(vc).toString())
            status = ""
            lib.start(force)
        } catch (e: Exception) {
            status = if (channels.isEmpty()) "Portal error: ${e.message}" else ""
            // Portal refresh failed but we have cached data; stay quiet instead of saying Offline
        }
    }

    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Pull channels, categories and the whole movie / series catalog again (in the background). */
    fun refreshAll() { bg.launch { load(force = true) } }

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
    fun isSeriesCat(c: Cat) = SERIES_CAT.containsMatchIn(c.title)
    fun hasSeriesCats() = vodCats.any { isSeriesCat(it) }

    /** Load the first page of a VOD category (at most 3 requests at a time). */
    suspend fun loadRow(cat: Cat) {
        if (rows.containsKey(cat.id)) return
        // The local catalog has (or is loading) every category: no portal request per row
        if (lib.hasData || lib.loading) return
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
    fun hiddenChannelCount() = hidden.count { it.startsWith("ch:") }
    fun unhideAllChannels() { hidden = hidden.filterNot { it.startsWith("ch:") }.toSet(); store.hidden = hidden }
    fun toggleHide(kind: String, id: String) {
        val k = "$kind:$id"
        hidden = if (k in hidden) hidden - k else hidden + k
        store.hidden = hidden
    }

    /** Re-reads everything cloud sync may have changed. */
    fun reloadSynced() {
        hidden = store.hidden
        favs = store.list("fav")
        customCats = store.customCats()
        vodOrder = store.catOrder("vod")
        liveOrder = store.catOrder("itv")
        Theme.mode = store.theme
        syncTick++
    }
    /** Bumped after a cloud sync changed local data. */
    var syncTick by mutableStateOf(0)

    fun isFav(i: Item) = favs.any { it.key == i.key }
    fun moveToCustom(i: Item, catName: String) {
        store.addToCustom(catName, i)
        customCats = store.customCats()
    }
    fun toggleFav(i: Item) {
        favs = if (isFav(i)) favs.filter { it.key != i.key } else favs + i
        store.saveList("fav", favs)
    }

    /** Moves a favourite channel one place up (-1) or down (+1) among the favourite channels (My order). */
    fun moveFav(i: Item, step: Int) {
        val l = favs.toMutableList()
        val idx = l.indexOfFirst { it.key == i.key }
        if (idx < 0) return
        var j = idx + step
        while (j in l.indices && l[j].kind != i.kind) j += step
        if (j !in l.indices) return
        l[idx] = l[j]; l[j] = i
        favs = l
        store.saveList("fav", favs)
    }

    fun markPlayed(i: Item) { if (i.kind == "itv") store.addRecent(i) }

    /** Returns an error message, or null when access is allowed. */
    fun gate(locked: Boolean): String? {
        if (!locked) return null
        val pw = store.password
        if (pw.isEmpty()) return "Locked. Save the password in Settings first"
        val pp = api.parentPassword
        if (pp != null && pp != pw) return "Saved password was rejected. Update it in Settings"
        return null
    }

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
                        val h = squash(c.name + " " + (titles[c.cat] ?: ""))
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
            val res = if (lib.complete) emptyList() else runCatching { api.vod("*", 1, sq).first }.getOrDefault(emptyList())
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
}
