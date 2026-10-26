package tv.tvstreamer

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val MOVIE_W = setOf("movie", "movies", "film", "films")
private val LIVE_W = setOf("channel", "channels", "live", "tv")
private val STOP = MOVIE_W + LIVE_W + setOf("show", "shows", "watch", "play", "open", "search", "for", "the", "of")
private val LANGS = setOf("hindi", "english", "tamil", "telugu", "malayalam", "kannada", "punjabi",
    "bengali", "marathi", "gujarati", "urdu", "arabic", "spanish", "french", "korean")
private val YEAR = Regex("(19|20)\\d\\d")

fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

class Repo(ctx: Context) {
    val store = Store(ctx)
    val api = Stalker(store)

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

    private fun setLive(lc: List<Cat>, ch: List<Item>) {
        val lockedIds = lc.filter { it.locked }.map { it.id }.toSet()
        liveCats = lc
        channels = ch.map { it.copy(locked = it.locked || it.cat in lockedIds) }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        if (store.portal.isBlank() || store.mac.isBlank()) {
            status = "Open Settings and enter the portal URL and MAC"
            return@withContext
        }
        if (channels.isEmpty()) {
            runCatching {
                store.cacheRead("live.json")?.let {
                    val o = JSONObject(it)
                    setLive(o.getJSONArray("cats").toCats(), o.getJSONArray("items").toItems())
                }
                store.cacheRead("vod.json")?.let { vodCats = org.json.JSONArray(it).toCats() }
            }
        }
        status = "Updating..."
        try {
            val lc = api.liveCats()
            val ch = api.channels()
            val vc = api.vodCats()
            setLive(lc, ch)
            vodCats = vc
            store.cacheWrite("live.json", JSONObject().put("cats", catsJson(lc)).put("items", itemsJson(ch)).toString())
            store.cacheWrite("vod.json", catsJson(vc).toString())
            status = ""
        } catch (e: Exception) {
            status = if (channels.isEmpty()) "Portal error: ${e.message}" else "Offline, showing saved list"
        }
    }

    fun visibleLive() = liveCats.filter { "itv:${it.id}" !in hidden }
    fun visibleVod() = vodCats.filter { "vod:${it.id}" !in hidden }

    fun toggleHide(kind: String, id: String) {
        val k = "$kind:$id"
        hidden = if (k in hidden) hidden - k else hidden + k
        store.hidden = hidden
    }

    fun isFav(i: Item) = favs.any { it.key == i.key }
    fun moveToCustom(i: Item, catName: String) {
        store.addToCustom(catName, i)
        customCats = store.customCats()
    }
    fun toggleFav(i: Item) {
        favs = if (isFav(i)) favs.filter { it.key != i.key } else favs + i
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
            val res = runCatching { api.vod("*", 1, sq).first }.getOrDefault(emptyList())
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
