@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package tv.tvstreamer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One horizontal row on a catalog screen. */
private data class Shelf(
    val key: String,
    val title: String,
    val items: List<Item>,
    val kind: String,          // "vod" (posters) or "itv" (channel tiles)
    val cat: Cat? = null,      // portal category (can be moved / hidden, has See all)
    val progress: Map<String, Float> = emptyMap(),
    val loading: Boolean = false,
    val error: String = "",
    val versions: Map<String, Int> = emptyMap(),   // item key -> number of versions (CAM / 4K / Dub ...)
    val pool: Screen.Pool? = null                  // language / genre row: its See all
)

/** A single episode (plays directly rather than opening details). */
fun Item.isEpisode(): Boolean = kind == "vod" && num != -1 && (series > 0 || season.isNotEmpty() || pid.isNotEmpty())

/** Episode-like entry in Continue watching (real episode or a loose "Show S01E02" title). */
fun Item.isEpisodeLike(): Boolean = isEpisode() || (kind == "vod" && !isSeries && Tags.hasEpisodeMarker(name))

private const val ROW_LIMIT = 40
private const val GENRE_ROWS = 6

/** Poster width: fixed on TV, from the screen width on phones (about 3.3 posters visible). */
@Composable
fun posterWidth(isTv: Boolean): Dp {
    val w = LocalConfiguration.current.screenWidthDp
    return if (isTv) 138.dp else ((w - 28 - 30) / 3.3f).coerceIn(92f, 150f).dp
}

/** Requests focus on this element when it was the last one focused on the screen. */
@Composable
private fun RestoreFocus(repo: Repo, screen: String, key: String, fr: FocusRequester) {
    LaunchedEffect(Unit) {
        if (repo.lastFocus[screen] == key) { delay(80); runCatching { fr.requestFocus() } }
    }
}

/** The series an episode in Continue watching belongs to (for "See all episodes"). */
fun parentSeries(repo: Repo, ep: Item): Item? {
    repo.store.parentOf(ep)?.let { return it }
    if (ep.pid.isNotEmpty()) return Item("vod", ep.pid, ep.parsed.title, ep.pcmd, ep.cat, -1, ep.locked, img = ep.img, desc = ep.desc)
    if (Tags.hasEpisodeMarker(ep.name)) return repo.lib.syntheticSeries(listOf(ep))
    return null
}

/** The hero banner: what it shows ([show], e.g. the series), what Play starts ([play], e.g. the episode) and the button text. */
class HeroPick(val show: Item, val play: Item, val pos: Long, val dur: Long, val subtitle: String = "") {
    val label: String get() = when {
        pos > 0 && play.isEpisode() -> "▶  Resume ${episodeTag(play)}· ${fmt(pos)}".replace("  · ", " · ")
        pos > 0 -> "▶  Resume · ${fmt(pos)}"
        play.isSeries -> "▶  Episodes"
        else -> ""
    }
}

private fun episodeTag(ep: Item): String {
    val m = Regex("""(?i)\bS(\d{1,2})\s*[ .:_-]?\s*E(\d{1,3})\b""").find(ep.name) ?: return ""
    return "S${m.groupValues[1].toInt()} E${m.groupValues[2].toInt()} "
}

private inline fun <T> safely(f: () -> T): T? = runCatching(f).getOrNull()

/**
 * Most recently watched title for the tab (Home: any movie or episode). In progress: Play resumes it. Watched to the end:
 * movies start again, episodes open their series (which continues with the next episode).
 */
fun heroFromHistory(repo: Repo, mode: String): HeroPick? {
    val unlocked = repo.gate(true) == null
    val last = repo.store.history().firstOrNull { it.kind == "vod" && (!it.locked || unlocked) && repo.lib.wantItem(it, mode) } ?: return null
    val (p, d) = repo.store.resumeInfo(last)
    val inProgress = p > 30000 && (d <= 0 || p < d * 0.95)
    if (last.isEpisode()) {
        val parent = parentSeries(repo, last)
        val show = parent?.takeIf { it.img.isNotEmpty() || last.img.isEmpty() } ?: last
        return if (inProgress || parent == null) HeroPick(show, last, if (inProgress) p else 0L, d) else HeroPick(show, parent, 0L, 0L)
    }
    return HeroPick(last, last, if (inProgress) p else 0L, d)
}

/** Language rows for one tab, computed off the main thread. */
private suspend fun languageShelves(repo: Repo, tab: String, langs: Set<String>, sort: String): List<Shelf> = withContext(Dispatchers.Default) {
    val lib = repo.lib
    val pool = lib.pool(tab, langs)
    val byLang = pool.groupBy { lib.itemLang(it) }
    val order = byLang.keys.sortedWith(compareBy<String> { it == Tags.OTHER }.thenByDescending { byLang[it]?.size ?: 0 })
    val out = mutableListOf<Shelf>()
    for (lang in order) {
        val groups = lib.cards(byLang[lang] ?: continue)
        if (groups.isEmpty()) continue
        val vcount = groups.associate { it.best.key to it.items.size }
        fun shelf(id: String, title: String, gs: List<Group>, genre: String, s: String) {
            if (gs.isEmpty()) return
            out += Shelf("lang:$tab:$lang:$id", title, gs.take(ROW_LIMIT).map { it.best }, "vod", versions = vcount,
                pool = Screen.Pool(tab, lang, genre, title, s, Screen.Home))
        }
        val p = if (order.size > 1 || langs.size > 1) "$lang · " else ""
        shelf("added", "${p}Latest added", lib.sort(groups, Sorts.ADDED), "", Sorts.ADDED)
        shelf("rated", "${p}Top rated", lib.sort(groups, Sorts.RATING).filter { lib.ratingOf(it.best) > 0 }, "", Sorts.RATING)
        shelf("new", "${p}Newest releases", lib.sort(groups, Sorts.YEAR).filter { lib.releaseOf(it.best) > 0 }, "", Sorts.YEAR)
        val genres = groups.flatMap { lib.genresOf(it.best) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.filter { it.value >= 3 }.take(GENRE_ROWS).map { it.key }
        for (g in genres) shelf("g:$g", "$p$g", lib.sort(groups.filter { g in lib.genresOf(it.best) }, sort), g, sort)
    }
    out
}

/** Home / Movies / Series: hero banner, Continue watching, My List, then language rows (Movies / Series) or category rows. */
@Composable
fun Catalog(repo: Repo, mode: String, go: (Screen) -> Unit, open: (Item) -> Unit, play: (List<Item>, Int) -> Unit, tick: Int) {
    val ctx = LocalContext.current
    val isTv = rememberIsTv()
    val scope = rememberCoroutineScope()
    val unlocked = repo.gate(true) == null
    val lib = repo.lib
    var contVer by remember { mutableIntStateOf(0) }
    val cont = remember(tick, contVer) { repo.store.continueList() }
    val recent = remember(tick) { repo.store.list("recent").filter { !it.locked } }
    var menu by remember { mutableStateOf<Pair<Shelf, Item?>?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf("") }
    val tabbed = mode == "movies" || mode == "series"
    var langVer by remember { mutableIntStateOf(0) }
    val langs = remember(mode, tick, langVer) { if (tabbed) lib.selected(mode) else null }
    // Home: hide categories of languages that were not picked on either tab
    val homeLangs = remember(tick) {
        val a = lib.selected("movies"); val b = lib.selected("series")
        if (a == null && b == null) null else (a ?: emptySet()) + (b ?: emptySet())
    }
    var sort by remember(mode) { mutableStateOf(repo.store.sort("tab:$mode", Sorts.ADDED)) }

    // Movies / Series with languages picked: load those categories in the background
    LaunchedEffect(mode, langs, repo.vodCats.size) { if (tabbed && !langs.isNullOrEmpty()) lib.ensure(mode, langs) }
    val langShelves by produceState(emptyList<Shelf>(), mode, langs, sort, lib.version, Tmdb.version, repo.vodCats.size) {
        value = if (tabbed && !langs.isNullOrEmpty()) languageShelves(repo, mode, langs, sort) else emptyList()
    }

    fun wantItem(i: Item): Boolean = lib.wantItem(i, mode)

    // Gemini "Suggested for you": cached for 12 h; refreshed (debounced) when the key, history or library changes. Quiet without a key.
    LaunchedEffect(Gemini.key) { Ai.loadCache(repo) }
    LaunchedEffect(Gemini.key, tick, lib.version) {
        if (!Gemini.enabled) return@LaunchedEffect
        delay(4000)
        Ai.refreshSuggested(repo)
    }

    lib.version   // recompose when the local catalog changes
    val shelves = buildList {
        val c = cont.filter { wantItem(it.first) }
        if (c.isNotEmpty()) {
            val prog = c.associate { (i, p) ->
                val d = repo.store.resumeInfo(i).second
                i.key to (if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f)
            }
            add(Shelf("cont", "Continue watching", c.map { it.first }, "vod", progress = prog))
        }
        repo.favs.filter { !it.locked && it.kind == "vod" && wantItem(it) }.takeIf { it.isNotEmpty() }
            ?.let { add(Shelf("fav", "My List", it, "vod")) }
        if (Gemini.enabled) Ai.suggested.filter { (!it.locked || unlocked) && wantItem(it) }.takeIf { it.isNotEmpty() }
            ?.let { add(Shelf("ai", "✨ Suggested for you", it, "vod")) }
        if (mode == "home") {
            repo.favs.filter { !it.locked && it.kind == "itv" }.takeIf { it.isNotEmpty() }?.let { add(Shelf("favc", "Favorite channels", it, "itv")) }
            if (recent.isNotEmpty()) add(Shelf("recent", "Recent channels", recent, "itv"))
        }
        if (tabbed && !langs.isNullOrEmpty()) {
            addAll(langShelves)
        } else {
            val catLangs = if (tabbed) null else homeLangs
            repo.visibleVod().filter { (!it.locked || unlocked) && lib.catForTab(it, mode) && (catLangs == null || lib.catLang(it) in catLangs) }.forEach { cat ->
                val raw = if (lib.hasData) lib.inCat(cat.id).takeIf { it.isNotEmpty() || !lib.loading }?.take(ROW_LIMIT * 2) else repo.rows[cat.id]
                // Each item is classified on its own, so categories that mix movies and series work
                val items = raw?.filter { wantItem(it) }
                if ((items == null && !(lib.hasData && lib.complete)) || items?.isNotEmpty() == true || repo.rowErrors[cat.id] != null) {
                    add(Shelf("vod:${cat.id}", Names.cat(cat.title), items ?: emptyList(), "vod", cat,
                        loading = raw == null && repo.rowErrors[cat.id] == null, error = repo.rowErrors[cat.id] ?: ""))
                }
            }
        }
    }
    // Hero: the last thing watched on this tab (resume it); a featured pick only when there is no watch history
    val lastWatched = remember(tick, contVer, mode) { safely { heroFromHistory(repo, mode) } }
    val featured = remember(shelves.map { it.key to it.items.size }) {
        shelves.asSequence().filter { it.cat != null || it.pool != null }.flatMap { it.items.asSequence() }.firstOrNull { it.img.isNotEmpty() && !it.locked }
            ?: shelves.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.kind == "vod" && it.img.isNotEmpty() }
    }
    val heroPick = lastWatched ?: featured?.let { HeroPick(it, it, 0L, 0L) }
    val hero = heroPick?.show

    val screenKey = "cat:$mode"
    val heroFr = remember { FocusRequester() }
    val navFr = remember { FocusRequester() }
    LaunchedEffect(hero?.key) {
        val lf = repo.lastFocus[screenKey]
        if (isTv && (lf == null || lf == "hero")) {
            delay(150)
            if (hero != null) runCatching { heroFr.requestFocus() }.onFailure { runCatching { navFr.requestFocus() } }
            else runCatching { navFr.requestFocus() }
        }
    }

    fun playOrGate(i: Item) { val e = repo.gate(i.locked); if (e == null) play(listOf(i), 0) else repo.status = e }

    val hPad = if (isTv) 48.dp else 14.dp
    val state = remember(mode) { repo.listStates.getOrPut(screenKey) { LazyListState() } }
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "nav") {
                val tabs = listOf<Triple<String, String, () -> Unit>>(
                    Triple("Home", "home") { go(Screen.Home) },
                    Triple("Movies", "movies") { go(Screen.MoviesTab) },
                    Triple("Series", "series") { go(Screen.SeriesTab) },
                    Triple("Live TV", "live") { go(Screen.Live) },
                    Triple("Categories", "cats") { go(Screen.Categories) },
                    Triple("Search", "search") { go(Screen.Search) },
                    Triple("Settings", "settings") { go(Screen.Settings) },
                    Triple("Update", "update") {
                        updateMsg = "Checking for updates..."
                        scope.launch {
                            val code = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode } catch (e: Exception) { 1 }
                            updateMsg = Updater.checkAndUpdate(ctx, code)
                        }
                    }
                )
                Column(Modifier.padding(horizontal = hPad, vertical = if (isTv) 18.dp else 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TVSTREAMER", color = Pal.GoldText, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(14.dp))
                        val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" } }
                        Text("v$ver", color = Pal.Dim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (!AppUi.phoneNav) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.focusRestorer()) {
                        items(tabs, key = { it.second }) { (t, k, a) ->
                            PillButton(t, a, if (k == mode) Modifier.focusRequester(navFr).onFocusChanged { if (it.isFocused) repo.lastFocus[screenKey] = "nav" } else Modifier,
                                selected = k == mode, size = 15)
                        }
                        item(key = "avatar") { AvatarButton { AppUi.accountMenu = true } }
                    }
                    if (tabbed) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.focusRestorer()) {
                            item(key = "langs") {
                                val label = when {
                                    langs.isNullOrEmpty() -> "🌐  Pick languages"
                                    langs.size <= 3 -> "🌐  " + langs.sorted().joinToString(", ")
                                    else -> "🌐  ${langs.size} languages"
                                }
                                PillButton(label, { go(Screen.Languages(mode, Screen.Home)) }, size = 14, primary = langs.isNullOrEmpty())
                            }
                            if (!langs.isNullOrEmpty()) {
                                item(key = "sort") { PillButton("⇅  " + Sorts.label(sort), { sortMenu = true }, size = 14) }
                                item(key = "refresh") { PillButton(if (lib.loading) "↻  Refreshing…" else "↻  Refresh", { if (!lib.loading) repo.refreshAll() }, size = 14) }
                            }
                        }
                        if (lib.loading && (!lib.hasData || !lib.complete)) {
                            // First load only; later daily refreshes run quietly with the saved copy on screen
                            Spacer(Modifier.height(4.dp))
                            Text("Loading the library… " + (if (lib.total > 0) "${lib.done} of ${lib.total} pages · " else "") + "%,d titles so far".format(lib.size),
                                color = Pal.Dim, fontSize = 13.sp)
                            if (lib.total > 0) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(progress = { (lib.done.toFloat() / lib.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(if (isTv) 0.4f else 1f).height(3.dp), color = Pal.Gold, trackColor = Pal.Dim.copy(alpha = 0.3f))
                            }
                        } else if (lib.note.isNotEmpty() && !langs.isNullOrEmpty()) {
                            Spacer(Modifier.height(4.dp)); Text(lib.note, color = Pal.Warn, fontSize = 13.sp)
                        }
                        if (langs.isNullOrEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Pick the languages you watch: their CAM / HD / 4K / Dub categories are merged into one set of rows.",
                                color = Pal.Dim, fontSize = 13.sp)
                        }
                    }
                    if (repo.status.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(repo.status, color = Pal.Warn, fontSize = 15.sp, maxLines = 5) }
                    if (updateMsg.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(updateMsg, color = Pal.Warn, fontSize = 14.sp, maxLines = 4) }
                }
            }
            if (heroPick != null && hero != null) item(key = "hero") {
                val h = heroPick
                Hero(repo, hero, isTv, hPad, h.label,
                    onPlay = { if (h.play.isSeries) open(h.play) else playOrGate(h.play) },
                    onInfo = { open(hero) },
                    playModifier = Modifier.focusRequester(heroFr).onFocusChanged { if (it.isFocused) repo.lastFocus[screenKey] = "hero" })
            }
            if (shelves.isEmpty() && repo.status.isEmpty()) item(key = "empty") {
                Text(when {
                    repo.vodCats.isEmpty() -> "Loading your portal…"
                    tabbed && !langs.isNullOrEmpty() && lib.loading -> "Loading…"
                    tabbed && !langs.isNullOrEmpty() -> "Nothing found for these languages yet. Try Refresh or pick more languages."
                    else -> "Nothing here yet. Open Categories to show hidden ones."
                }, color = Pal.Dim, fontSize = 16.sp, modifier = Modifier.padding(hPad))
            }
            items(shelves.distinctBy { it.key }, key = { it.key }) { shelf ->
                // Lazy: a category's items are requested only when its row is composed (scrolled into view)
                if (shelf.cat != null && shelf.loading) LaunchedEffect(shelf.key) { repo.loadRow(shelf.cat) }
                ShelfRow(repo, screenKey, shelf, isTv, hPad,
                    onOpen = { it ->
                        when {
                            it.kind == "itv" -> play(shelf.items, shelf.items.indexOfFirst { x -> x.key == it.key }.coerceAtLeast(0))
                            shelf.key == "cont" || it.isEpisode() -> playOrGate(it)
                            else -> open(it)
                        }
                    },
                    onMenu = { menu = shelf to it },
                    onSeeAll = shelf.cat?.let { c -> { go(Screen.Grid(c, Screen.Home)) } } ?: shelf.pool?.let { p -> { go(p) } }
                )
            }
        }

        menu?.let { (shelf, item) ->
            val opts = buildList<Pair<String, () -> Unit>> {
                if (item != null) {
                    if (shelf.key == "cont") {
                        val (p, _) = repo.store.resumeInfo(item)
                        add("▶  Resume from ${fmt(p)}" to { menu = null; playOrGate(item) })
                        add("↺  Start over" to { menu = null; repo.store.removeResume(item.key); contVer++; playOrGate(item) })
                        if (item.isEpisodeLike()) parentSeries(repo, item)?.let { ps ->
                            add("☰  See all episodes" to { menu = null; go(Screen.Detail(ps, Screen.Home, item.key)) })
                        }
                    }
                    if (item.kind == "vod" && !item.isEpisode() && shelf.key != "cont") add("Details" to { menu = null; open(item) })
                    if (!item.isEpisode()) add((if (repo.isFav(item)) "★  Remove from My List" else "☆  Add to My List") to { repo.toggleFav(item); menu = null })
                    if (shelf.key == "cont") add("✕  Remove from Continue watching" to { repo.store.removeResume(item.key); menu = null; contVer++ })
                    if (item.kind == "itv") add("Hide channel" to { repo.toggleHideChannel(item); menu = null })
                }
                shelf.pool?.let { p -> add("See all: ${shelf.title}" to { menu = null; go(p) }) }
                shelf.cat?.let { c ->
                    add("See all in ${shelf.title}" to { menu = null; go(Screen.Grid(c, Screen.Home)) })
                    add("▲  Move row up" to { repo.moveCat("vod", c.id, -1) })
                    add("▼  Move row down" to { repo.moveCat("vod", c.id, 1) })
                    add("⤒  Move row to top" to { repo.moveCatTo("vod", c.id, true); menu = null })
                    add("Hide this category" to { repo.toggleHide("vod", c.id); menu = null })
                    if (shelf.error.isNotEmpty()) add("Retry loading" to { repo.retryRow(c); menu = null })
                }
                if (tabbed) add("🌐  Languages…" to { menu = null; go(Screen.Languages(mode, Screen.Home)) })
                add("Browse / arrange all categories…" to { menu = null; go(Screen.Categories) })
            }
            val title = item?.let { (if (it.season.isNotEmpty()) "${it.season} · " else "") + Names.title(it.name) } ?: shelf.cat?.let { c ->
                val pos = repo.visibleVod().indexOfFirst { it.id == c.id }
                "${shelf.title}  (row ${pos + 1})"
            } ?: shelf.title
            ChoiceDialog(title, opts, focusKey = shelf.key + (item?.key ?: "")) { menu = null }
        }
        if (sortMenu) {
            ChoiceDialog("Sort genre rows", Sorts.all.map { s ->
                ((if (s == sort) "✓  " else "") + Sorts.label(s)) to { sort = s; repo.store.saveSort("tab:$mode", s); sortMenu = false }
            }, focusKey = "sort") { sortMenu = false }
        }
    }
}

@Composable
private fun Hero(repo: Repo, raw: Item, isTv: Boolean, hPad: Dp, playLabel: String, onPlay: () -> Unit, onInfo: () -> Unit, playModifier: Modifier) {
    val meta = rememberMeta(raw)
    val item = raw.withMeta(meta)
    var fav by remember(item.key) { mutableStateOf(repo.isFav(item)) }
    val name = meta?.title?.ifEmpty { null } ?: Names.title(item.name)
    Box(Modifier.fillMaxWidth().height(if (isTv) 320.dp else 260.dp)) {
        Poster(meta?.backdrop?.ifEmpty { null } ?: item.img, name, Modifier.fillMaxSize(), ContentScale.Crop, initialsSize = 1)
        Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Pal.Bg, Pal.Bg.copy(alpha = 0.55f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Pal.Bg)))
        Row(Modifier.fillMaxSize().padding(horizontal = hPad, vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, color = Pal.Text, fontSize = if (isTv) 34.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetaLine(item)
                if (item.desc.isNotEmpty()) Text(item.desc, color = Pal.Desc, fontSize = if (isTv) 15.sp else 13.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(if (isTv) 0.8f else 1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(playLabel.ifEmpty { if (item.isSeries) "▶  Episodes" else "▶  Play" }, onPlay, playModifier.onFocusChanged { if (it.isFocused) Keys.focused = raw }, primary = true)
                    PillButton(if (fav) "✓  My List" else "+  My List", { repo.toggleFav(raw); fav = repo.isFav(raw) })
                    PillButton("ⓘ  Info", onInfo)
                }
            }
            if (item.img.isNotEmpty()) {
                Spacer(Modifier.width(20.dp))
                Poster(item.img, name, Modifier.width(if (isTv) 160.dp else 100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
            }
        }
    }
}

@Composable
fun MetaLine(item: Item, meta: Meta? = null) {
    val parts = listOfNotNull(
        item.year.ifEmpty { null },
        meta?.imdbRating?.ifEmpty { null }?.let { "IMDb $it" }
            ?: item.rating.ifEmpty { null }?.takeIf { it != "0" && it != "0.0" }?.let { "★ $it" },
        meta?.rating?.ifEmpty { null }?.takeIf { meta.imdbRating.isEmpty() && it != item.rating }?.let { "TMDB $it" },
        item.length.ifEmpty { null },
        if (item.isSeries) "Series" else null,
        item.genre.ifEmpty { null }
    )
    if (parts.isNotEmpty()) Text(parts.joinToString("   ·   "), color = Pal.Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ShelfRow(repo: Repo, screen: String, shelf: Shelf, isTv: Boolean, hPad: Dp, onOpen: (Item) -> Unit, onMenu: (Item?) -> Unit, onSeeAll: (() -> Unit)?) {
    val state = remember(shelf.key) { repo.listStates.getOrPut("$screen|${shelf.key}") { LazyListState() } }
    val posterW = posterWidth(isTv)
    val tileW = if (isTv) 200.dp else 150.dp
    Column(Modifier.padding(top = if (isTv) 16.dp else 12.dp)) {
        Row(Modifier.padding(horizontal = hPad), verticalAlignment = Alignment.CenterVertically) {
            Text(shelf.title, color = Pal.Text, fontSize = if (isTv) 20.sp else 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (shelf.cat?.locked == true) Text("  🔒", fontSize = 14.sp)
            if (!isTv && (shelf.cat != null || shelf.pool != null)) {
                // Phones: "⋮" next to the title opens the row menu (move / hide)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.focusCard({ onMenu(null) }, shape = RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("⋮", color = Pal.Dim, fontSize = 18.sp)
                }
            }
        }
        if (shelf.error.isNotEmpty() && shelf.items.isEmpty()) {
            Row(Modifier.padding(horizontal = hPad, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load: ${shelf.error}", color = Pal.Warn, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                PillButton("Retry", { shelf.cat?.let { repo.retryRow(it) } }, size = 13)
            }
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
            contentPadding = PaddingValues(horizontal = hPad, vertical = 8.dp),
            modifier = Modifier.focusRestorer()
        ) {
            if (shelf.loading && shelf.items.isEmpty()) {
                items(7) {
                    Column(Modifier.width(posterW)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).shimmer())
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                    }
                }
            }
            val shown = shelf.items.distinctBy { it.key }.take(ROW_LIMIT)
            itemsIndexed(shown, key = { _, it -> it.key }) { i, it ->
                val k = "${shelf.key}|${it.key}"
                val fr = remember { FocusRequester() }
                RestoreFocus(repo, screen, k, fr)
                if (shelf.kind == "itv") {
                    ChannelTile(it, tileW, repo.isFav(it), Modifier.focusRequester(fr), { onOpen(it) }, { onMenu(it) }) { repo.lastFocus[screen] = k; Keys.focused = it }
                } else {
                    PosterCard(it, posterW, shelf.progress[it.key], Modifier.focusRequester(fr), { onOpen(it) }, { onMenu(it) },
                        fav = repo.isFav(it), versions = shelf.versions[it.key] ?: 0) { repo.lastFocus[screen] = k; Keys.focused = it }
                }
            }
            if (onSeeAll != null && shelf.items.isNotEmpty()) {
                item(key = "seeall") {
                    Box(
                        Modifier.width(posterW).aspectRatio(2f / 3f).focusCard(onSeeAll).background(Pal.Card),
                        contentAlignment = Alignment.Center
                    ) { Text("See all  ›", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun PosterCard(raw: Item, width: Dp, progress: Float?, modifier: Modifier, onClick: () -> Unit, onMenu: () -> Unit,
               fav: Boolean = false, versions: Int = 0, onFocused: () -> Unit = {}) {
    // TMDB / OMDb details are looked up only for cards that are on screen
    val meta = rememberMeta(raw)
    val item = raw.withMeta(meta)
    val name = meta?.title?.ifEmpty { null } ?: Names.title(item.name)
    val q = raw.parsed.quality
    Column(Modifier.width(width)) {
        Box(modifier.fillMaxWidth().aspectRatio(2f / 3f).focusCard(onClick, onMenu, onFocused)) {
            Poster(item.img, name, Modifier.fillMaxSize())
            val tag = when {
                item.isSeries -> "SERIES"
                versions > 1 -> "$versions VERSIONS"
                q == "CAM" || q == "TS" -> q
                else -> ""
            }
            if (tag.isNotEmpty()) {
                Text(tag, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(if (tag == "CAM" || tag == "TS") Color(0xFF8D6E00) else Pal.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
            if (fav) Text("★", color = Color(0xFFFFD54F), fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 5.dp))
            if (item.locked) Text("🔒", fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x88FFFFFF)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(Pal.Accent))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(if (item.season.isNotEmpty()) "${item.season} · $name" else name, color = Pal.Text2, fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Year and rating (IMDb when known)
        val r = meta?.imdbRating?.ifEmpty { null } ?: item.rating.takeIf { item.ratingNum > 0 }
        val y = item.year.ifEmpty { raw.parsed.year }
        val sub = listOfNotNull(y.ifEmpty { null }, r?.let { "★ $it" }).joinToString("  ·  ")
        if (sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun ChannelTile(item: Item, width: Dp, fav: Boolean, modifier: Modifier, onClick: () -> Unit, onMenu: () -> Unit, onFocused: () -> Unit = {}) {
    Column(Modifier.width(width)) {
        Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).focusCard(onClick, onMenu, onFocused).background(Pal.Card)) {
            Poster(item.img, item.name, Modifier.fillMaxSize().padding(if (item.img.isNotEmpty()) 10.dp else 0.dp), ContentScale.Fit)
            if (item.num > 0) Text("${item.num}", color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            if (fav) Text("★", color = Color(0xFFFFD54F), fontSize = 15.sp, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, color = Pal.Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun seasonNo(e: Item): Int = EpisodeOrder.seasonNo(e)
private fun episodeNo(e: Item): Int = EpisodeOrder.episodeNo(e)

/** "4K · Hindi" / "CAM · Hindi · Dub" for the Versions selector. */
private fun versionLabel(repo: Repo, v: Item): String {
    val p = v.parsed
    val cat = repo.vodCats.firstOrNull { it.id == v.cat }
    val q = p.quality.ifEmpty { cat?.let { Tags.quality(it.title).first } ?: "" }.ifEmpty { "Standard" }
    val dub = p.dub || (cat != null && Tags.isDub(cat.title))
    val season = Tags.episodeOf(v.name).first.takeIf { it > 0 && v.isSeries }?.let { "Season $it" }
    return listOfNotNull(q, repo.lib.itemLang(v).takeIf { it != Tags.OTHER }, if (dub) "Dub" else null, season).joinToString(" · ")
}

/** Movie / series page: backdrop, poster, info (TMDB / IMDb when keys are set), Versions, Play / Resume, seasons and episodes. */
@Composable
fun DetailScreen(repo: Repo, item: Item, play: (List<Item>, Int) -> Unit, onBack: () -> Unit, tick: Int,
                 focusEp: String? = null, go: (Screen) -> Unit = {}) {
    val isTv = rememberIsTv()
    val scope = rememberCoroutineScope()
    val versions = remember(item.key, repo.lib.version) { if (item.id.startsWith("grp:")) listOf(item) else repo.lib.versionsOf(item) }
    var cur by remember(item.key) { mutableStateOf(item) }
    val meta = rememberMeta(cur)
    val shownItem = cur.withMeta(meta)
    val name = meta?.title?.ifEmpty { null } ?: Names.title(cur.name)
    var eps by remember(cur.key) { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember(cur.key) { mutableStateOf(cur.isSeries) }
    var msg by remember(cur.key) { mutableStateOf("") }
    var fav by remember(cur.key) { mutableStateOf(repo.isFav(cur)) }
    var reload by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf(false) }
    LaunchedEffect(cur.key) { Keys.focused = cur }
    LaunchedEffect(cur.key, reload) {
        if (cur.isSeries) {
            loading = true
            msg = ""
            val r = withContext(Dispatchers.IO) {
                runCatching { if (cur.id.startsWith("grp:")) repo.lib.syntheticEpisodes(cur) else repo.api.episodes(cur) }
            }
            // Always seasons ascending, episodes 1..N (the portal's order varies); next-episode logic uses this order too
            eps = EpisodeOrder.sort(r.getOrDefault(emptyList()))
            msg = r.exceptionOrNull()?.message ?: if (eps.isEmpty()) "No episodes found for this series" else ""
            loading = false
        }
    }
    // TMDB names / stills / overviews mapped onto the portal's episodes by season + episode number
    val epMeta by produceState(emptyMap<String, EpMeta>(), eps, meta?.tmdbId) {
        val m = meta
        if (m == null || m.type != "tv" || m.tmdbId == 0 || eps.isEmpty()) { value = emptyMap(); return@produceState }
        val bySeason = eps.groupBy { seasonNo(it).coerceAtLeast(1) }
        val out = mutableMapOf<String, EpMeta>()
        for ((sn, list) in bySeason) {
            val sm = Tmdb.season(m.tmdbId, sn)
            list.forEach { e -> sm[episodeNo(e)]?.let { out[e.key] = it } }
            value = out.toMap()
        }
    }
    val seasons = remember(eps) { EpisodeOrder.seasons(eps) }
    // Display names without the show's name ("Koffee With Karan Season 1" -> "Season 1"); raw names stay for requests
    val shows = listOfNotNull(cur.name, item.name, meta?.title?.ifEmpty { null })
    fun seasonName(s: String) = if (s.isEmpty()) "Episodes" else Names.seasonLabel(s, shows)
    var season by remember(cur.key) { mutableStateOf<String?>(null) }
    val cont = remember(tick) { repo.store.continueList().associate { it.first.key to it.second } }
    val target = focusEp?.let { k -> eps.indexOfFirst { it.key == k } } ?: -1
    // Where to open: the episode asked for, else the last one played (the next one if it was finished),
    // else one in Continue watching, else the first episode of the first season
    val lastIdx = remember(eps, tick) {
        val l = repo.store.lastEp(cur.key) ?: return@remember -1
        val i = eps.indexOfFirst { it.key == l.first }
        when {
            i < 0 -> -1
            l.second && eps[i].key !in cont && i + 1 < eps.size -> i + 1
            else -> i
        }
    }
    val resumeEp = when {
        target >= 0 -> target
        lastIdx >= 0 -> lastIdx
        else -> eps.indexOfFirst { it.key in cont }
    }
    val focusKey = focusEp ?: eps.getOrNull(resumeEp)?.key
    val curSeason = season ?: (if (resumeEp >= 0) eps[resumeEp].season else null) ?: seasons.firstOrNull() ?: ""
    val shown = if (seasons.size > 1) eps.filter { it.season == curSeason } else eps
    val (rp, rd) = remember(tick, cur.key) { repo.store.resumeInfo(cur) }
    val canResume = !cur.isSeries && rp > 30_000 && (rd <= 0 || rp < rd * 0.95)

    fun start(list: List<Item>, i: Int) {
        val e = repo.gate(cur.locked)
        if (e != null) { msg = e; return }
        if (cur.isSeries) repo.store.saveParent(cur, eps)
        play(list, i)
    }

    val playFr = remember { FocusRequester() }
    val epFr = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(cur.key, loading) {
        if (loading) return@LaunchedEffect
        delay(100)
        val i = shown.indexOfFirst { it.key == focusKey }
        if (focusKey != null && i >= 0) {
            // header (+ versions) (+ seasons row) + count line come before the episodes
            listState.scrollToItem(1 + (if (versions.size > 1) 1 else 0) + (if (seasons.size > 1) 1 else 0) + 1 + i)
            delay(80)
            runCatching { epFr.requestFocus() }
        } else if (isTv) runCatching { playFr.requestFocus() }
    }
    val hPad = if (isTv) 48.dp else 16.dp
    val epW = if (isTv) 180.dp else 124.dp

    Box(Modifier.fillMaxSize().background(Pal.Bg).onKeyEvent { e ->
        // Menu on the details page (nothing else used it): more options incl. Fix match
        if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { more = true; true } else false
    }) {
        Poster(meta?.backdrop?.ifEmpty { null } ?: shownItem.img, name, Modifier.fillMaxWidth().fillMaxHeight(0.7f), ContentScale.Crop, initialsSize = 1)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Pal.Bg.copy(alpha = 0.6f), 0.55f to Pal.Bg)))
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = hPad, vertical = if (isTv) 32.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 14.dp)) {
                    Poster(shownItem.img, name, Modifier.width(if (isTv) 200.dp else 116.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, color = Pal.Text, fontSize = if (isTv) 34.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        MetaLine(shownItem, meta)
                        meta?.released?.takeIf { it.length >= 10 }?.let { Text("Released $it", color = Pal.Dim, fontSize = 13.sp) }
                        if (shownItem.desc.isNotEmpty()) Text(shownItem.desc, color = Pal.Desc, fontSize = if (isTv) 16.sp else 14.sp,
                            maxLines = if (isTv) 6 else 8, overflow = TextOverflow.Ellipsis)
                        meta?.cast?.takeIf { it.isNotEmpty() }?.let { Text("Cast: $it", color = Pal.Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (msg.isNotEmpty()) Text(msg, color = Pal.Warn, fontSize = 14.sp, maxLines = 4)
                        Spacer(Modifier.height(4.dp))
                        FlowRowCompat {
                            val pm = Modifier.focusRequester(playFr)
                            when {
                                cur.isSeries && eps.isNotEmpty() -> {
                                    val i = if (resumeEp >= 0) resumeEp else 0
                                    val e = eps[i]
                                    val label = listOf(if (seasons.size > 1) seasonName(e.season) else "", epMeta[e.key]?.name?.ifEmpty { null } ?: Names.episodeLabel(e.name, shows, episodeNo(e)))
                                        .filter { it.isNotEmpty() }.joinToString(" · ")
                                    PillButton((if (eps[i].key in cont) "▶  Resume " else "▶  Play ") + label, { start(eps, i) }, pm, primary = true, onMenu = { more = true })
                                }
                                cur.isSeries && loading -> PillButton("Loading episodes…", {}, pm)
                                cur.isSeries -> PillButton("↻  Retry", { reload++ }, pm)
                                canResume -> {
                                    PillButton("▶  Resume from ${fmt(rp)}", { start(listOf(cur), 0) }, pm, primary = true, onMenu = { more = true })
                                    PillButton("↺  Start over", { repo.store.removeResume(cur.key); start(listOf(cur), 0) })
                                }
                                else -> PillButton("▶  Play", { start(listOf(cur), 0) }, pm, primary = true, onMenu = { more = true })
                            }
                            PillButton(if (fav) "★  In My List" else "☆  Add to My List", { repo.toggleFav(cur); fav = repo.isFav(cur) }, onMenu = { more = true })
                            if (Tmdb.key.isNotBlank()) PillButton("✎  Fix match", { fix = true })
                        }
                    }
                }
            }
            if (versions.size > 1) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Versions", color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.focusRestorer()) {
                        items(versions, key = { it.key }) { v ->
                            PillButton(versionLabel(repo, v), { cur = v }, selected = v.key == cur.key, size = 14)
                        }
                    }
                }
            }
            if (cur.isSeries && loading) item { CircularProgressIndicator(color = Pal.Accent, modifier = Modifier.padding(16.dp)) }
            if (seasons.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp), modifier = Modifier.focusRestorer()) {
                    items(seasons) { s -> PillButton(seasonName(s), { season = s }, selected = s == curSeason, size = 15) }
                }
            }
            if (shown.isNotEmpty()) item {
                Text(if (seasons.size > 1) "${seasonName(curSeason)} · ${shown.size} episodes" else "${shown.size} episodes",
                    color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
            itemsIndexed(shown, key = { i, e -> "$i|${e.key}" }) { _, e ->
                val all = eps.indexOf(e)
                val p = cont[e.key]
                val d = if (p != null) repo.store.resumeInfo(e).second else 0L
                val em = epMeta[e.key]
                val title = em?.name?.takeIf { it.isNotEmpty() }?.let { n -> episodeNo(e).takeIf { it > 0 }?.let { "$it. $n" } ?: n } ?: Names.episodeLabel(e.name, shows, episodeNo(e))
                Row(
                    Modifier.fillMaxWidth().then(if (e.key == focusKey) Modifier.focusRequester(epFr) else Modifier)
                        .focusCard({ start(eps, all) }, scale = 1.02f).background(Pal.Soft).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(epW).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                        Poster(em?.still?.ifEmpty { null } ?: e.img.ifEmpty { shownItem.img }, title, Modifier.fillMaxSize(), initialsSize = 18)
                        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                        Text("▶", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
                        if (p != null && d > 0) {
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x88FFFFFF)))
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((p.toFloat() / d).coerceIn(0f, 1f)).height(3.dp).background(Pal.Accent))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(e.length.ifEmpty { null }, em?.airDate?.ifEmpty { null }).joinToString("  ·  ")
                        if (sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = 12.sp)
                        val ov = em?.overview?.ifEmpty { null } ?: e.desc.takeIf { it.isNotEmpty() && it != cur.desc }
                        if (ov != null) Text(ov, color = Pal.Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (more) {
            ChoiceDialog(name, focusKey = "more", opts = buildList {
                if (Tmdb.key.isNotBlank()) add("✎  Fix match (choose the right TMDB title)" to { more = false; fix = true })
                if (Tmdb.enabled) add("↻  Look up details again" to { more = false; scope.launch { Tmdb.clearFor(cur) } })
                add((if (repo.isFav(cur)) "★  Remove from My List" else "☆  Add to My List") to { repo.toggleFav(cur); fav = repo.isFav(cur); more = false })
                if (canResume) add("Remove from Continue watching" to { repo.store.removeResume(cur.key); more = false })
            }) { more = false }
        }
        if (fix) FixMatchDialog(cur) { fix = false }
    }
}

/** Search TMDB and pick the right title; the choice is saved and used from then on. */
@Composable
private fun FixMatchDialog(item: Item, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val p = item.parsed
    var q by remember { mutableStateOf(p.title + (if (p.year.isNotEmpty()) " (${p.year})" else "")) }
    var type by remember { mutableStateOf(Tmdb.typeOf(item)) }
    var results by remember { mutableStateOf<List<Cand>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    fun run() { busy = true; scope.launch { results = Tmdb.search(q, type); busy = false } }
    LaunchedEffect(Unit) { run(); delay(150); runCatching { fr.requestFocus() } }
    // System / remote Back closes the dialog (not the details page behind it)
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(0.9f).heightIn(max = 560.dp).background(Pal.Surface, RoundedCornerShape(14.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fix match: ${Names.title(item.name)}", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            TextField(value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { kb?.hide(); run(); runCatching { fr.requestFocus() } }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Pal.FieldFocus, unfocusedContainerColor = Pal.Field,
                    focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Search", { run() }, Modifier.focusRequester(fr), primary = true, size = 14)
                PillButton("Movie", { type = "movie"; run() }, selected = type == "movie", size = 14)
                PillButton("Series", { type = "tv"; run() }, selected = type == "tv", size = 14)
                PillButton("Close", onClose, size = 14)
            }
            if (busy) CircularProgressIndicator(color = Pal.Accent)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val r = results
                if (r != null && r.isEmpty() && !busy) item { Text("No TMDB results. Edit the title and search again.", color = Pal.Dim, fontSize = 14.sp) }
                items(r ?: emptyList(), key = { "${it.type}${it.id}" }) { c ->
                    Row(Modifier.fillMaxWidth().focusCard({ scope.launch { Tmdb.setOverride(item, c); onClose() } }, scale = 1.02f)
                        .background(Pal.Row).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Poster(c.poster, c.title, Modifier.width(46.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)), initialsSize = 12)
                        Column(Modifier.weight(1f)) {
                            Text(c.title + (if (c.year.isNotEmpty()) " (${c.year})" else ""), color = Pal.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.overview.isNotEmpty()) Text(c.overview, color = Pal.Dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item { PillButton("No match: use the portal's info", { scope.launch { Tmdb.setOverride(item, null); onClose() } }, Modifier.fillMaxWidth(), size = 14) }
            }
        }
    }
}

/** Simple wrapping row for buttons. */
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        var x = 0; var y = 0; var rowH = 0
        val pos = placeables.map { p ->
            if (x > 0 && x + p.width > constraints.maxWidth) { x = 0; y += rowH + gap; rowH = 0 }
            val at = x to y
            x += p.width + gap
            rowH = maxOf(rowH, p.height)
            at
        }
        layout(constraints.maxWidth, y + rowH) { placeables.forEachIndexed { i, p -> p.place(pos[i].first, pos[i].second) } }
    }
}

/** "See all": every title in one category as a poster grid, loading more pages as you scroll. */
@Composable
fun GridScreen(repo: Repo, cat: Cat, open: (Item) -> Unit, play: (List<Item>, Int) -> Unit) {
    val isTv = rememberIsTv()
    val scope = rememberCoroutineScope()
    var items by remember(cat.id) { mutableStateOf(repo.rows[cat.id] ?: emptyList()) }
    var page by remember(cat.id) { mutableIntStateOf(if (repo.rows.containsKey(cat.id)) 1 else 0) }
    var more by remember(cat.id) { mutableStateOf(repo.rowMore[cat.id] ?: true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<Item?>(null) }
    var sort by remember { mutableStateOf(repo.store.sort("grid", Sorts.ADDED)) }
    var sortMenu by remember { mutableStateOf(false) }
    val screenKey = "grid:${cat.id}"
    // The local catalog has every title of the category: no paging through the portal
    val local = remember(cat.id, repo.lib.version) { repo.lib.inCat(cat.id).takeIf { it.isNotEmpty() || (repo.lib.hasData && repo.lib.complete) } }
    LaunchedEffect(local) { if (local != null) { items = local; more = false; page = 1; msg = "" } }
    // Same title in several qualities shows once (best version first); sorted on what is loaded so far
    val cards = remember(items, sort, Tmdb.version) { repo.lib.sort(repo.lib.cards(items), sort) }
    val vcount = remember(cards) { cards.associate { it.best.key to it.items.size } }
    val shownItems = remember(cards) { cards.map { it.best } }

    fun next() {
        if (busy || !more || local != null) return
        busy = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { repo.api.vod(cat.id, page + 1) }
                val seen = items.map { it.key }.toHashSet()
                items = items + r.first.filter { it.key !in seen }
                page += 1
                more = r.second && r.first.isNotEmpty()
                msg = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { msg = e.message ?: "Load failed"; more = false }
            busy = false
        }
    }
    LaunchedEffect(cat.id) { if (page == 0 && local == null) next() }

    val posterW = posterWidth(isTv)
    val hPad = if (isTv) 48.dp else 14.dp
    val state = remember(cat.id) { repo.gridStates.getOrPut(screenKey) { LazyGridState() } }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(items.isNotEmpty()) { if (isTv && items.isNotEmpty() && repo.lastFocus[screenKey] == null) { delay(100); runCatching { firstFr.requestFocus() } } }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(posterW),
            state = state,
            contentPadding = PaddingValues(horizontal = hPad, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(Names.cat(cat.title), color = Pal.Text, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (more) "${items.size}+ titles" else "${items.size} titles", color = Pal.Dim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    PillButton("⇅  Sort: " + Sorts.label(sort), { sortMenu = true }, size = 14)
                    if (msg.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = Pal.Warn, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        PillButton("Retry", { more = true; next() }, size = 13)
                    }
                }
            }
            gridItemsIndexed(shownItems, key = { i, it -> "$i|${it.key}" }) { i, it ->
                val k = it.key
                val fr = remember { FocusRequester() }
                RestoreFocus(repo, screenKey, k, fr)
                PosterCard(it, posterW, null, Modifier.then(if (i == 0) Modifier.focusRequester(firstFr) else Modifier).focusRequester(fr),
                    { if (it.isEpisode()) play(listOf(it), 0) else open(it) }, { menu = it }, fav = repo.isFav(it), versions = vcount[it.key] ?: 0) { repo.lastFocus[screenKey] = k; Keys.focused = it }
                // Paging: ask for the next page when the last few posters come into view
                if (i >= shownItems.size - 6 && more && !busy) LaunchedEffect(items.size) { next() }
            }
            if (busy || (page == 0 && more)) items(if (items.isEmpty()) 12 else 6) { _ ->
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).shimmer())
            }
        }
        menu?.let { m ->
            ChoiceDialog(Names.title(m.name), listOf(
                "Details" to { menu = null; open(m) },
                (if (repo.isFav(m)) "★  Remove from My List" else "☆  Add to My List") to { repo.toggleFav(m); menu = null }
            ), onClose = { menu = null })
        }
        if (sortMenu) SortDialog(sort, { sort = it; repo.store.saveSort("grid", it); sortMenu = false }) { sortMenu = false }
    }
}

@Composable
fun SortDialog(cur: String, pick: (String) -> Unit, onClose: () -> Unit) {
    ChoiceDialog("Sort by", Sorts.all.map { s -> ((if (s == cur) "✓  " else "") + Sorts.label(s)) to { pick(s) } } +
        listOf("(Titles without that information are listed last)" to onClose), focusKey = "sort", onClose = onClose)
}

/** See all for a language / genre row: every loaded title, versions merged, with a sort choice. */
@Composable
fun PoolScreen(repo: Repo, tab: String, lang: String, genre: String, title: String, sort0: String, open: (Item) -> Unit) {
    val isTv = rememberIsTv()
    val lib = repo.lib
    var sort by remember { mutableStateOf(repo.store.sort("pool:$title", sort0)) }
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Item?>(null) }
    val cards by produceState(emptyList<Group>(), lang, genre, sort, lib.version, Tmdb.version) {
        value = withContext(Dispatchers.Default) {
            val g = lib.cards(lib.pool(tab, setOf(lang)))
            lib.sort(if (genre.isEmpty()) g else g.filter { genre in lib.genresOf(it.best) }, sort)
        }
    }
    val screenKey = "pool:$tab:$title"
    val posterW = posterWidth(isTv)
    val hPad = if (isTv) 48.dp else 14.dp
    val state = remember(screenKey) { repo.gridStates.getOrPut(screenKey) { LazyGridState() } }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(cards.isNotEmpty()) { if (isTv && cards.isNotEmpty() && repo.lastFocus[screenKey] == null) { delay(100); runCatching { firstFr.requestFocus() } } }
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyVerticalGrid(columns = GridCells.Adaptive(posterW), state = state,
            contentPadding = PaddingValues(horizontal = hPad, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 12.dp), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, color = Pal.Text, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
                    Text("${cards.size} titles" + (if (lib.loading) " (still loading…)" else ""), color = Pal.Dim, fontSize = 13.sp)
                    PillButton("⇅  Sort: " + Sorts.label(sort), { sortMenu = true }, Modifier.focusRequester(firstFr), size = 14)
                }
            }
            gridItemsIndexed(cards, key = { i, g -> "$i|${g.key}" }) { _, g ->
                val it = g.best
                val k = g.key
                val fr = remember { FocusRequester() }
                RestoreFocus(repo, screenKey, k, fr)
                PosterCard(it, posterW, null, Modifier.focusRequester(fr), { open(it) }, { menu = it }, fav = repo.isFav(it),
                    versions = g.items.size) { repo.lastFocus[screenKey] = k; Keys.focused = it }
            }
        }
        menu?.let { m ->
            ChoiceDialog(Names.title(m.name), listOf(
                "Details" to { menu = null; open(m) },
                (if (repo.isFav(m)) "★  Remove from My List" else "☆  Add to My List") to { repo.toggleFav(m); menu = null }
            ), onClose = { menu = null })
        }
        if (sortMenu) SortDialog(sort, { sort = it; repo.store.saveSort("pool:$title", it); sortMenu = false }) { sortMenu = false }
    }
}

/** Languages checklist for a tab, with category counts. Nothing ticked = no language filter (old category rows). */
@Composable
fun LanguagesScreen(repo: Repo, tab: String, onBack: () -> Unit) {
    val isTv = rememberIsTv()
    val lib = repo.lib
    val langs by produceState(lib.languages(tab), repo.vodCats.size, lib.version) { value = withContext(Dispatchers.Default) { lib.languages(tab) } }
    val unit = lib.countUnit
    var sel by remember { mutableStateOf(lib.selected(tab) ?: emptySet()) }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { firstFr.requestFocus() } }
    fun save(v: Set<String>) {
        sel = v
        repo.store.saveLangs(tab, v.ifEmpty { null })
        if (v.isNotEmpty()) lib.start()
    }
    val hPad = if (isTv) 48.dp else 16.dp
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = hPad, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Languages · ${if (tab == "series") "Series" else "Movies"}", color = Pal.Text, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
                    Text("Tick the languages to show. The language comes from the category and title names (e.g. \"HINDI 4K\", \"|EN|\", \"[Tamil]\"); " +
                        "Dub / CAM / 4K are treated as versions, not languages; a dubbed title counts as the language it is dubbed in. " +
                        "Titles with no recognisable language are listed under \"Other\". Untick everything to go back to plain category rows. " +
                        "Other languages stay reachable through Search and Categories.", color = Pal.Dim, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Done", onBack, Modifier.focusRequester(firstFr), primary = true, size = 14)
                        PillButton("Select all", { save(langs.map { it.first }.toSet()) }, size = 14)
                        PillButton("Select none", { save(emptySet()) }, size = 14)
                    }
                }
            }
            items(langs, key = { it.first }) { (l, n) ->
                val on = l in sel
                Row(Modifier.fillMaxWidth().focusCard({ save(if (on) sel - l else sel + l) }, scale = 1.02f)
                    .background(if (on) Color(0x33E50914) else Pal.Soft).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "☑" else "☐", color = Pal.Text, fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(l, color = Pal.Text, fontSize = 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text("%,d ".format(n) + if (unit == "title") (if (n == 1) "title" else "titles") else (if (n == 1) "category" else "categories"), color = Pal.Dim, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Browse all categories: grid with a sample poster each, filter box, open / move / hide / unhide. */
@Composable
fun CategoriesScreen(repo: Repo, go: (Screen) -> Unit) {
    val isTv = rememberIsTv()
    var kind by remember { mutableStateOf("vod") }
    var showHidden by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Cat?>(null) }
    val unlocked = repo.gate(true) == null
    val all = repo.ordered(kind)
    val q = repo.catFilter.trim()
    // Languages picked on the Movies / Series tabs; "Show all" lists every language
    val picked = remember { (repo.lib.selected("movies") ?: emptySet()) + (repo.lib.selected("series") ?: emptySet()) }
    val cats = all.filter { c ->
        (showHidden || !repo.isHidden(kind, c.id)) &&
            (kind != "vod" || showAll || picked.isEmpty() || repo.lib.catLang(c) in picked) &&
            (q.isEmpty() || Names.cat(c.title).contains(q, true) || c.title.contains(q, true))
    }
    val screenKey = "cats:$kind"
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv && repo.lastFocus[screenKey] == null) { delay(120); runCatching { firstFr.requestFocus() } } }
    val hPad = if (isTv) 48.dp else 14.dp
    val cardW = if (isTv) 220.dp else ((LocalConfiguration.current.screenWidthDp - 28 - 10) / 2f).coerceIn(140f, 220f).dp
    val state = remember(kind) { repo.gridStates.getOrPut(screenKey) { LazyGridState() } }

    fun openCat(c: Cat) {
        val e = repo.gate(c.locked)
        if (e != null) { repo.status = e; return }
        if (kind == "vod") go(Screen.Grid(c, Screen.Categories))
        else { repo.lastLiveCatId = c.id; repo.lastLiveCat = liveGroupOf(c); go(Screen.Live) }
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cardW),
            state = state,
            contentPadding = PaddingValues(horizontal = hPad, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("All categories", color = Pal.Text, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (isTv) "OK opens a category. Menu (or long-press OK) moves it up / down or hides it. The order is used on Home, Movies, Series and Live TV."
                        else "Tap to open. Long-press to move up / down or hide. The order is used on Home, Movies, Series and Live TV.",
                        color = Pal.Dim, fontSize = 13.sp)
                    FlowRowCompat {
                        PillButton("Movies & Series", { kind = "vod" }, Modifier.focusRequester(firstFr), selected = kind == "vod", size = 14)
                        PillButton("Live TV", { kind = "itv" }, selected = kind == "itv", size = 14)
                        PillButton(if (showHidden) "Showing hidden" else "Show hidden", { showHidden = !showHidden }, selected = showHidden, size = 14)
                        if (kind == "vod" && picked.isNotEmpty()) PillButton(if (showAll) "All languages" else "Show all languages", { showAll = !showAll }, selected = showAll, size = 14)
                        PillButton("Reset order", { repo.resetOrder(kind) }, size = 14)
                    }
                    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    val fm = androidx.compose.ui.platform.LocalFocusManager.current
                    TextField(
                        value = repo.catFilter, onValueChange = { repo.catFilter = it }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { kb?.hide(); fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                        placeholder = { Text("Filter categories…", color = Pal.Dim) },
                        modifier = Modifier.fillMaxWidth(if (isTv) 0.5f else 1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Pal.Surface, unfocusedContainerColor = Pal.Surface,
                            focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text
                        )
                    )
                    Text("${cats.size} of ${all.size}" + (if (q.isNotEmpty()) " match \"$q\"" else ""), color = Pal.Dim, fontSize = 12.sp)
                }
            }
            gridItems(cats, key = { "$kind:${it.id}" }) { c ->
                val hidden = repo.isHidden(kind, c.id)
                val locked = c.locked && !unlocked
                if (kind == "vod" && !locked && !repo.lib.hasData) LaunchedEffect(c.id) { repo.loadRow(c) }
                val sample = if (kind == "vod") (repo.lib.inCat(c.id).takeIf { it.isNotEmpty() } ?: repo.rows[c.id])?.firstOrNull { it.img.isNotEmpty() }?.img ?: ""
                    else repo.channels.firstOrNull { it.cat == c.id && it.img.isNotEmpty() }?.img ?: ""
                val label = Names.cat(c.title)
                val fr = remember { FocusRequester() }
                val k = "$kind:${c.id}"
                RestoreFocus(repo, screenKey, k, fr)
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRequester(fr)
                    .focusCard({ openCat(c) }, { menu = c }, { repo.lastFocus[screenKey] = k }, scale = 1.05f)) {
                    Poster(sample, label, Modifier.fillMaxSize(), if (kind == "vod") ContentScale.Crop else ContentScale.Fit, initialsSize = 30)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xEE000000))))
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text((if (c.locked) "🔒 " else "") + label, color = if (hidden) Color(0xFF999999) else Pal.Text, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val n = if (kind == "itv") repo.channels.count { it.cat == c.id } else -1
                        Text(listOfNotNull("#${all.indexOf(c) + 1}", if (kind == "vod") repo.lib.catLang(c) else null, if (n >= 0) "$n channels" else null, if (hidden) "hidden" else null).joinToString("  ·  "),
                            color = Pal.Dim, fontSize = 11.sp)
                    }
                }
            }
        }
        menu?.let { c ->
            val pos = repo.ordered(kind).indexOfFirst { it.id == c.id } + 1
            ChoiceDialog("${Names.cat(c.title)}  (#$pos)", focusKey = c.id, opts = listOf(
                "Open" to { menu = null; openCat(c) },
                "▲  Move up" to { repo.moveCat(kind, c.id, -1) },
                "▼  Move down" to { repo.moveCat(kind, c.id, 1) },
                "⤒  Move to top" to { repo.moveCatTo(kind, c.id, true) },
                "⤓  Move to bottom" to { repo.moveCatTo(kind, c.id, false) },
                (if (repo.isHidden(kind, c.id)) "Unhide (show again)" else "Hide") to { repo.toggleHide(kind, c.id); menu = null }
            ), onClose = { menu = null })
        }
    }
}
