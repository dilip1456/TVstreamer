@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package tv.tvstreamer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
/** Last rows / hero slides per tab, shown at once when the tab opens again while fresh ones are worked out. */
private val shelfMemo = java.util.concurrent.ConcurrentHashMap<String, List<Shelf>>()
private val heroMemo = java.util.concurrent.ConcurrentHashMap<String, List<HeroPick>>()
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
    // Search closed (Back): back to the poster / channel focused before Search opened (1.55)
    LaunchedEffect(AppUi.searchClosed) {
        if (AppUi.searchClosed > 0 && SearchReturn.target(repo.lastFocus, screen) == key) { delay(80); runCatching { fr.requestFocus() } }
    }
}

/** The series an episode in Continue watching belongs to (for "See All Episodes"). */
fun parentSeries(repo: Repo, ep: Item): Item? {
    repo.store.parentOf(ep)?.let { return it }
    if (ep.pid.isNotEmpty()) return Item("vod", ep.pid, ep.parsed.title, ep.pcmd, ep.cat, -1, ep.locked, img = ep.img, desc = ep.desc)
    if (Tags.hasEpisodeMarker(ep.name)) return repo.lib.syntheticSeries(listOf(ep))
    return null
}

/** The hero banner: what it shows ([show], e.g. the series), what Play starts ([play], e.g. the episode) and the button text. */
class HeroPick(val show: Item, val play: Item, val pos: Long, val dur: Long, val subtitle: String = "") {
    val label: String get() = when {
        pos > 0 && play.isEpisode() -> "Resume ${episodeTag(play)}· ${fmt(pos)}".replace("  · ", " · ")
        pos > 0 -> "Resume · ${fmt(pos)}"
        play.isSeries -> "Episodes"
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
    val last = repo.store.history().firstOrNull { it.kind == "vod" && repo.listOk(it.locked) && repo.lib.wantItem(it, mode) } ?: return null
    return heroPickFor(repo, last)
}

/** Hero for one movie / episode / series: resume it when in progress (episodes show their series). */
fun heroPickFor(repo: Repo, last: Item): HeroPick {
    val (p, d) = repo.store.resumeInfo(last)
    val inProgress = p > 30000 && (d <= 0 || p < d * 0.95)
    if (last.isEpisode()) {
        val parent = parentSeries(repo, last)
        val show = parent?.takeIf { it.img.isNotEmpty() || last.img.isEmpty() } ?: last
        return if (inProgress || parent == null) HeroPick(show, last, if (inProgress) p else 0L, d) else HeroPick(show, parent, 0L, 0L)
    }
    return HeroPick(last, last, if (inProgress) p else 0L, d)
}

/**
 * Hero slides for a tab: new / recently added movies and series (portal added dates), mixed on Home,
 * skipping hidden / adult-locked titles and anything in watch history. Empty when none match
 * (the caller falls back to a featured title that is not Continue watching). Runs off the main thread.
 */
fun heroPicks(repo: Repo, mode: String): List<HeroPick> {
    // No history → no hero recommendations
    if (repo.store.history().isEmpty() && repo.store.continueList().isEmpty()) return emptyList()
    fun ok(i: Item) = i.kind == "vod" && !i.isEpisode() && !i.isEpisodeLike() &&
        repo.listOk(i.locked) && repo.lib.wantItem(i, mode) &&
        "vod:${i.cat}" !in repo.hidden && "title:${i.key}" !in repo.hidden
    val exclude = HeroOrder.historyKeys(repo.store.history() + repo.store.continueList().map { it.first })
    return HeroOrder.newest(repo.lib.everything(), { it.addedMs }, { HeroOrder.showKey(it) }, exclude, ::ok)
        .map { HeroPick(it, it, 0L, 0L) }
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

/** Sentinel for the Home menu's "Manage Watchlists…" (WatchlistPicker without a title). */
private val WATCHLIST_MANAGER = Item("vod", "__watchlists__", "", "", "", 0, false)

/** Home / Movies / Series: hero banner, Continue watching, My List, then language rows (Movies / Series) or category rows. */
@Composable
fun Catalog(repo: Repo, mode: String, go: (Screen) -> Unit, open: (Item) -> Unit, play: (List<Item>, Int) -> Unit, tick: Int) {
    val ctx = LocalContext.current
    val isTv = rememberIsTv()
    val scope = rememberCoroutineScope()
    val showAdult = repo.showAdult
    val lib = repo.lib
    var contVer by remember { mutableIntStateOf(0) }
    val cont = remember(tick, contVer) { repo.store.continueList() }
    val recent = remember(tick) { repo.store.list("recent").filter { repo.listOk(it.locked) }.map(repo::named) }
    var menu by remember { mutableStateOf<Pair<Shelf, Item?>?>(null) }
    var listPick by remember { mutableStateOf<Item?>(null) }
    var listPickBefore by remember { mutableStateOf<List<String>?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var clearHist by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
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
    LaunchedEffect(mode, langs, repo.vodCats.size, repo.showAdult) { if (tabbed) lib.ensure(mode, if (repo.showAdult) (langs ?: emptySet()) else (langs ?: emptySet())) }
    val langShelves by produceState(emptyList<Shelf>(), mode, langs, sort, lib.version, Tmdb.version, repo.vodCats.size) {
        value = if (tabbed && !langs.isNullOrEmpty()) languageShelves(repo, mode, langs, sort) else emptyList()
    }
    // Few titles for the picked languages: fetch a few more pages (limited; never for unselected languages)
    LaunchedEffect(mode, langs, lib.version, lib.loading) {
        if (!tabbed || langs.isNullOrEmpty() || lib.loading) return@LaunchedEffect
        delay(1500)
        val n = withContext(Dispatchers.Default) { lib.cards(lib.pool(mode, langs)).size }
        lib.fewResults(mode, langs, n)
    }

    fun wantItem(i: Item): Boolean = lib.wantItem(i, mode)
    fun buildShelves(): List<Shelf> = buildList {
        val sensitive = repo.showAdult
        // Always show category rows so series/movies can be clicked and loaded on demand
        val cats = repo.visibleVod().filter { 
            repo.listOk(it.locked) && lib.catForTab(it, mode) &&
            (langs.isNullOrEmpty() || lib.catLang(it) in langs || sensitive)
        }
        if (cats.isEmpty() && tabbed) {
            add(Shelf("empty", if (sensitive) "No password-controlled categories" else "No categories for this tab", emptyList(), "vod"))
        }
        val c = cont.filter { !sensitive && wantItem(it.first) }
        if (c.isNotEmpty()) {
            val prog = c.associate { (i, p) ->
                val d = repo.store.resumeInfo(i).second
                i.key to (if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f)
            }
            add(Shelf("cont", "Continue watching", c.map { it.first }, "vod", progress = prog))
        }
        // Suggested for you is spliced on in composition (like My List) so a Like / Already Watched
        // does not rebuild every Home row.
        if (mode == "home") {
            if (recent.isNotEmpty()) add(Shelf("recent", "Recent channels", recent, "itv"))
        }
        cats.forEach { cat ->
            val raw = lib.inCat(cat.id).takeIf { it.isNotEmpty() }?.take(ROW_LIMIT * 2)
            val items = raw?.filter { wantItem(it) }
            add(Shelf("vod:${cat.id}", GroupTitle.display(cat.title, if (mode == "series") "Series" else "Movies"), items ?: emptyList(), "vod", cat,
                loading = raw == null, error = repo.rowErrors[cat.id] ?: ""))
        }
    }


    // Gemini "Suggested for you": load the cache at once; auto-refresh only when the cache is older than 12 h
    // or missing (not on each Like / Already Watched / library page). Quiet without a key.
    LaunchedEffect(Gemini.key) { Ai.loadCache(repo) }
    LaunchedEffect(Gemini.key, lib.hasData) {
        if (!Gemini.enabled) return@LaunchedEffect
        delay(1500)
        Ai.refreshSuggested(repo)
    }

    // Rows are worked out off the main thread (category filters, language checks) and remembered per tab, so
    // returning to a tab shows its rows at once and D-pad moves never wait for this
    val rowErrs = repo.rowErrors.toMap()
    val memoKey = "${System.identityHashCode(repo)}|$mode"
    var shelvesReady by remember(mode) { mutableStateOf(shelfMemo[memoKey] != null) }
    // Channel hide keys (ch:) must not rebuild movie/series rows; only group / VOD hides change the catalog.
    val catalogHideSig = remember(repo.hidden) { repo.hidden.filter { it.startsWith("vod:") || it.startsWith("grp:") }.toSet() }
    val coreShelves by produceState(shelfMemo[memoKey] ?: emptyList(), mode, cont, recent, langShelves, lib.version, catalogHideSig,
        repo.vodCats, repo.vodOrder, repo.favs, repo.userLists, Gemini.enabled, showAdult, homeLangs, langs, rowErrs, repo.liveMode, repo.folders) {
        val built = withContext(Dispatchers.Default) { runCatching { buildShelves() }.getOrNull() }
        if (built != null) { value = built; shelfMemo[memoKey] = built }
        shelvesReady = true
    }
    // Splice Suggested for you here so feedback only updates that row (stable key "ai"), not every shelf.
    val aiShelf = remember(Ai.suggested, Ai.feedback, Ai.hadAny, repo.favs, repo.userLists, showAdult, mode, Gemini.enabled) {
        if (!Gemini.enabled) null
        else {
            val left = Ai.visibleFor(repo).filter { repo.listOk(it.locked) && wantItem(it) }
            if (left.isNotEmpty() || Ai.hadAny) Shelf("ai", "Suggested for you", left, "vod") else null
        }
    }
    val shelves = remember(coreShelves, repo.favs, repo.userLists, repo.myListName, showAdult, mode, aiShelf) {
        val favVod = repo.favs.filter { repo.listOk(it.locked) && it.kind == "vod" && lib.wantItem(it, mode) }
        // 1.97: each watchlist the user made is its own row, right after My List
        val userRows = repo.userLists.mapNotNull { l ->
            l.items.filter { repo.listOk(it.locked) && lib.wantItem(it, mode) }.takeIf { it.isNotEmpty() }?.let { Shelf("wl:${l.id}", l.name, it, "vod") }
        }
        val favCh = if (mode == "home") repo.favs.filter { repo.listOk(it.locked) && it.kind == "itv" } else emptyList()
        val skip = setOf("fav", "favc")
        val headIds = setOf("cont", "ai", "recent")
        buildList {
            coreShelves.filter { it.key == "cont" }.forEach { add(it) }
            if (favVod.isNotEmpty()) add(Shelf("fav", repo.myListName, favVod, "vod"))
            addAll(userRows)
            aiShelf?.let { add(it) }
            if (favCh.isNotEmpty()) add(Shelf("favc", "Favorite channels", favCh, "itv"))
            coreShelves.filter { it.key == "recent" }.forEach { add(it) }
            addAll(coreShelves.filter { it.key !in skip && it.key !in headIds })
        }
    }
    // Hero: newest / recently added titles (not last watched). Featured (non-history rows) only when none match.
    val picks by produceState(heroMemo[memoKey] ?: emptyList(), tick, contVer, mode, showAdult, lib.version, catalogHideSig) {
        value = withContext(Dispatchers.Default) { safely { heroPicks(repo, mode) } ?: emptyList() }
        heroMemo[memoKey] = value
    }
    val featured = remember(shelves) {
        val skip = setOf("cont", "fav", "favc", "recent", "ai")
        shelves.asSequence().filter { it.key !in skip && !it.key.startsWith("wl:") && (it.cat != null || it.pool != null) }.flatMap { it.items.asSequence() }.firstOrNull { it.img.isNotEmpty() && repo.listOk(it.locked) }
            ?: shelves.asSequence().filter { it.key !in skip && !it.key.startsWith("wl:") }.flatMap { it.items.asSequence() }.firstOrNull { it.kind == "vod" && it.img.isNotEmpty() }
    }
    val slides = picks  // no featured fallback after history is cleared
    var heroIx by remember(mode) { mutableIntStateOf(0) }
    LaunchedEffect(slides.firstOrNull()?.show?.key, slides.size) { heroIx = 0 }
    var heroTouched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(slides.size) {
        while (true) {
            delay(HeroOrder.ROTATE_MS)
            if (HeroOrder.shouldRotate(slides.size, System.currentTimeMillis(), heroTouched)) heroIx = HeroOrder.next(heroIx, slides.size)
        }
    }
    val heroPick = slides.getOrNull(heroIx.coerceIn(0, (slides.size - 1).coerceAtLeast(0)))
    val hero = heroPick?.show

    // Rows hidden with the row menu stay out; restore in Catalog Selector or Settings > Hidden
    val shelvesShown = remember(shelves, repo.hidden) {
        val off = HomeHide.ids(repo.hidden)
        shelves.distinctBy { it.key }.filter { "${HomeHide.PREFIX}$mode|${it.key}" !in off }
    }
    val screenKey = "cat:$mode"
    val heroFr = remember { FocusRequester() }
    val navFr = remember { FocusRequester() }
    // TV side menu: Left on a row's first card (or the hero's Play button) opens the overlay menu
    val railOn = !AppUi.phoneNav
    val heroPlayFr = remember { FocusRequester() }
    SideEffect { SideMenu.fallback = if (hero != null) heroPlayFr else navFr }
    val searchFr = remember { FocusRequester() }
    // Search closed (Back): focus returns to the Search tab it was opened from
    LaunchedEffect(AppUi.searchClosed) {
        // The item focused before Search takes focus itself (RestoreFocus); the Search tab only when there is none
        // or it is scrolled away (then nothing has claimed focus after a moment)
        if (isTv && AppUi.searchClosed > 0 && repo.lastFocus[screenKey] == "search") {
            delay(250); if (repo.lastFocus[screenKey] == "search") runCatching { searchFr.requestFocus() }
        }
    }
    // Focus the banner once when it first appears (not on every slide change)
    LaunchedEffect(hero != null) {
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
    var topBarFocused by remember { mutableStateOf(false) }
    val topBarVisible by remember { derivedStateOf { TvChrome.topBarVisible(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, topBarFocused) } }
    val railOnTv = isTv && railOn
    val firstLoad = FirstLoad.of(
        FirstLoad.Kind.HOME,
        hasPortal = repo.store.portal.isNotBlank() && repo.store.mac.isNotBlank(),
        hasChannels = repo.channels.isNotEmpty(),
        hasCatalog = lib.hasData,
        localReading = repo.localReading,
        liveFetching = repo.liveFetching,
        liveStatus = repo.status,
        channelCount = repo.channels.size,
        catalogLoading = lib.loading,
        catalogDone = lib.done,
        catalogTotal = lib.total,
        catalogSize = lib.size,
        catalogStopped = lib.stopped,
    )
    // Back: close the rail if it has focus, else scroll to the top and focus this tab's menu item, then ask before exit.
    var pendingTop by remember { mutableStateOf(false) }
    val focusTop: () -> Unit = {
        pendingTop = true
        scope.launch { runCatching { state.animateScrollToItem(0) } }
    }
    LaunchedEffect(pendingTop, topBarVisible) {
        if (!pendingTop) return@LaunchedEffect
        if (isTv && !topBarVisible) return@LaunchedEffect
        delay(80)
        if (isTv && railOn) runCatching { navFr.requestFocus() }
        pendingTop = false
    }
    LaunchedEffect(AppChrome.topFocusTick) {
        if (AppChrome.topFocusTick == 0) return@LaunchedEffect
        focusTop()
    }
    MainBackSync(
        isTv = railOnTv,
        railFocused = SideMenu.open,
        topMenuFocused = topBarFocused,
        listState = state,
        closeRail = { SideMenu.close() },
        focusTop = focusTop,
    )
    Box(Modifier.fillMaxSize().background(Pal.Bg).onKeyEvent { e ->
        // Options / Menu on the remote: page context menu (languages, sort). Cards that handle Menu keep their own.
        if (isTv && e.type == KeyEventType.KeyUp && e.key == Key.Menu && menu == null && !pageMenu && !sortMenu) {
            pageMenu = true; true
        } else false
    }) {
        // Soft cinematic wash at the top (static gradient, no blur — cheap on Fire TV). Not a second copy of the hero art.
        CinematicWash()
        Column(Modifier.fillMaxSize()) {
            if (AppUi.phoneNav) Column(Modifier.fillMaxWidth().padding(start = hPad, end = hPad, top = Layout.PHONE_TOP, bottom = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BlueyMark(size = Layout.LOGO_PHONE)
                    Spacer(Modifier.weight(1f))
                    AvatarButton { AppUi.accountMenu = true }
                }
            }
            AnimatedVisibility(
                visible = !isTv || topBarVisible,
                enter = slideInVertically(tween(Motion.SCROLL_MS)) { -it } + fadeIn(tween(Motion.SCROLL_MS)),
                exit = slideOutVertically(tween(Motion.SCROLL_MS)) { -it } + fadeOut(tween(Motion.SCROLL_MS))
            ) {
                if (!AppUi.phoneNav) Column(Modifier.fillMaxWidth().padding(start = hPad, end = hPad, top = Layout.TV_TOP, bottom = Layout.S)
                    .onFocusChanged { topBarFocused = it.hasFocus }) {
                    // Left on the first tab always opens the side menu on TV, including while the top bar is showing.
                    TopNavBar(mode, topTabs(go) { go(Screen.Search) }, if (railOnTv) ({ SideMenu.open() }) else null, { AppUi.accountMenu = true }) { k ->
                        when (k) {
                            mode -> Modifier.focusRequester(navFr).onFocusChanged { if (it.isFocused) repo.lastFocus[screenKey] = "nav" }
                            "search" -> Modifier.focusRequester(searchFr).onFocusChanged { if (it.isFocused) repo.lastFocus[screenKey] = "search" }
                            else -> Modifier
                        }
                    }
                }
            }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "nav") {
                Column(Modifier.padding(horizontal = hPad)) {
                    if (tabbed) {
                        Spacer(Modifier.height(8.dp))
                        // TV: languages live in the Options / Menu context menu, not a second control on the page
                        if (AppUi.phoneNav || !isTv) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.focusRestorer()) {
                                item(key = "langs") {
                                    val label = when {
                                        langs.isNullOrEmpty() -> "Pick languages"
                                        langs.size <= 3 -> langs.sorted().joinToString(", ")
                                        else -> "${langs.size} languages"
                                    }
                                    PillButton(label, { go(Screen.Languages(mode, Screen.Home)) }, size = Ts.HEAD, primary = langs.isNullOrEmpty())
                                }
                                if (!langs.isNullOrEmpty()) item(key = "sort") { PillButton("Sort: " + Sorts.label(sort), { sortMenu = true }, size = Ts.HEAD) }
                            }
                        }
                        if (lib.loading) {
                            // Small and non-blocking: saved titles stay usable while pages arrive one by one
                            Spacer(Modifier.height(4.dp))
                            Text("Loading more… " + (if (lib.total > 0) "${lib.done} of ${lib.total} pages · " else "") + "%,d titles saved".format(lib.size),
                                color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                            if (lib.total > 0) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(progress = { (lib.done.toFloat() / lib.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(if (isTv) 0.4f else 1f).height(3.dp), color = Pal.Primary, trackColor = Pal.Dim.copy(alpha = 0.3f))
                            }
                        } else if (lib.note.isNotEmpty() && !langs.isNullOrEmpty()) {
                            Spacer(Modifier.height(4.dp)); Text(lib.note, color = Pal.Warn, fontSize = Ts.SECONDARY.sp)
                        }
                        if (langs.isNullOrEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(if (isTv && !AppUi.phoneNav)
                                "Press Options on the remote to choose the languages you watch. Their CAM / HD / 4K / Dub categories are merged into one set of rows."
                                else "Pick the languages you watch: their CAM / HD / 4K / Dub categories are merged into one set of rows.",
                                color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                        }
                    }
                    if (repo.status.isNotEmpty() && !firstLoad.visible) { Spacer(Modifier.height(8.dp)); Text(repo.status, color = Pal.Warn, fontSize = Ts.ROW.sp, maxLines = 5) }
                    if (updateMsg.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(updateMsg, color = Pal.Warn, fontSize = Ts.HEAD.sp, maxLines = 4) }
                }
            }
            if (heroPick != null && hero != null) item(key = "hero") {
                val h = heroPick
                Hero(repo, hero, isTv, hPad, "Play",
                    onPlay = { if (h.play.isSeries) open(h.play) else playOrGate(h.play) },
                    onInfo = { open(hero) },
                    playModifier = Modifier.focusRequester(heroPlayFr).onFocusChanged {
                        if (it.isFocused) { repo.lastFocus[screenKey] = "hero"; SideMenu.rememberContent(heroPlayFr) }
                    },
                    heroModifier = Modifier.focusRequester(heroFr).onFocusChanged { if (it.isFocused) repo.lastFocus[screenKey] = "hero" }
                        .focusProperties { up = navFr; down = heroPlayFr },
                    slide = heroIx, slides = slides.size, onTouch = { heroTouched = System.currentTimeMillis() },
                    onStep = { d -> heroTouched = System.currentTimeMillis(); heroIx = (heroIx + d).mod(slides.size.coerceAtLeast(1)) },
                    onUp = { runCatching { navFr.requestFocus() }.onFailure { focusTop() } },
                    onOpenMenu = if (railOnTv) ({ SideMenu.open(heroPlayFr) }) else null)
            }
            // Cached titles must never be hidden behind a spinner; the first-load panel only when nothing is saved yet
            if (firstLoad.visible && shelves.isEmpty()) item(key = "empty") {
                FirstLoadPanel(firstLoad, onStop = { lib.stop() }, Modifier.padding(hPad), onResume = { lib.resume() })
            } else if (shelves.isEmpty() && repo.status.isEmpty() && !(lib.hasData && !shelvesReady)) item(key = "empty") {
                val emptyMsg = when {
                    tabbed && !langs.isNullOrEmpty() -> "Nothing found for these languages yet. Try Refresh or pick more languages."
                    else -> "Open Catalog Selector from Options or Settings to choose what to show."
                }
                EmptyState("Nothing here yet", emptyMsg, Modifier.padding(horizontal = hPad))
            }
            items(shelvesShown, key = { it.key }) { shelf ->
                // Lazy: a category's items are requested only when its row is composed (scrolled into view)
                if (shelf.cat != null && shelf.loading) LaunchedEffect(shelf.key) { repo.loadRow(shelf.cat) }
                ShelfRow(repo, screenKey, shelf, isTv, hPad,
                    onLeftEdge = if (railOnTv) ({ SideMenu.open() }) else null,
                    onCardFocus = { SideMenu.rememberContent(it) },
                    listViewport = { state.layoutInfo.viewportSize.height },
                    listState = state,
                    onOpen = { it ->
                        when {
                            it.kind == "itv" -> play(shelf.items, shelf.items.indexOfFirst { x -> x.key == it.key }.coerceAtLeast(0))
                            shelf.key == "cont" || it.isEpisode() -> playOrGate(it)
                            else -> open(it)
                        }
                    },
                    onMenu = { menu = shelf to it },
                    onRefresh = if (shelf.key == "ai") ({ scope.launch { Ai.refreshSuggested(repo, force = true) } }) else null,
                    onSeeAll = shelf.cat?.let { c -> { go(Screen.Grid(c, Screen.Home)) } } ?: shelf.pool?.let { p -> { go(p) } }
                )
            }
        }
        }

        menu?.let { (shelf, item) ->
            val opts = buildList<Pair<String, () -> Unit>> {
                add(AppChrome.GO_HOME to { menu = null; AppChrome.goHome() })
                if (item != null) {
                    if (shelf.key == "cont") {
                        val (p, _) = repo.store.resumeInfo(item)
                        add("Resume from ${fmt(p)}" to { menu = null; playOrGate(item) })
                        add("Start Over" to { menu = null; repo.store.removeResume(item.key); contVer++; playOrGate(item) })
                        if (item.isEpisodeLike()) parentSeries(repo, item)?.let { ps ->
                            add("See All Episodes" to { menu = null; go(Screen.Detail(ps, Screen.Home, item.key)) })
                        }
                    }
                    if (item.kind == "vod" && !item.isEpisode() && shelf.key != "cont") add("Details" to { menu = null; open(item) })
                    if (shelf.key == "ai") {
                        // Suggestions: Already Watched, Like, Dislike, Add to Watchlist. Each removes this one card
                        // in place and moves focus; none of them asks Gemini or rebuilds Home.
                        add(AiSuggest.WATCHED to { Ai.markWatched(repo, item, moveFocus = true); contVer++; menu = null; toast(ctx, "Marked as watched") })
                        AiFeedback.menuItems(Ai.voteOf(item)).forEach { (label, vote) ->
                            add(label to { Ai.toggleVote(repo, item, vote, moveFocus = true); menu = null })
                        }
                        val aiBefore = Ai.visibleFor(repo)
                        addAll(watchlistOptions(repo, item, ctx, { Ai.noteRemoval(aiBefore, item); Ai.drop(repo, item); menu = null }) { menu = null; listPickBefore = aiBefore.map { it.key }; listPick = item })
                    } else {
                        if (item.kind == "itv") add((if (repo.isFav(item)) "Remove from My Channels" else "Add to My Channels") to { repo.toggleFav(item); menu = null })
                        else addAll(watchlistOptions(repo, item, ctx, { menu = null }) { menu = null; listPick = item })
                        if (item.kind == "vod" && !item.isEpisode()) {
                            AiFeedback.menuItems(Ai.voteOf(item)).forEach { (label, vote) ->
                                add(label to { Ai.toggleVote(repo, item, vote); menu = null })
                            }
                        }
                    }
                    if (item.kind == "vod" && !item.isSeries && shelf.key != "ai") add((if (repo.isWatched(item)) "Mark as Unwatched" else "Mark as Watched") to { repo.setWatched(item, !repo.isWatched(item)); contVer++; menu = null })
                    if (item.kind == "vod" && (shelf.versions[item.key] ?: 0) > 1) add("Versions: ${shelf.versions[item.key]}" to { menu = null; open(item) })
                    if (item.kind == "vod" && !item.isEpisode() && shelf.key != "cont") add("Hide This Title" to { repo.toggleHideTitle(item); menu = null })
                    if (shelf.key == "cont") add("Remove from History" to { repo.store.removeResume(item.key); menu = null; contVer++ })
                    if (item.kind == "itv") add("Hide Channel" to { repo.toggleHideChannel(item); menu = null })
                }
                if (shelf.key == "cont") add("Clear All History" to { menu = null; clearHist = true })
                if (shelf.key == "ai") add(AiSuggest.REFRESH to { menu = null; scope.launch { Ai.refreshSuggested(repo, force = true) } })
                if (shelf.key == "fav" || shelf.key.startsWith("wl:")) add("Manage Watchlists" to { menu = null; listPick = WATCHLIST_MANAGER })
                shelf.pool?.let { p -> add("See All: ${shelf.title}" to { menu = null; go(p) }) }
                shelf.cat?.let { c ->
                    add("See All in ${shelf.title}" to { menu = null; go(Screen.Grid(c, Screen.Home)) })
                    add("Move Row Up" to { repo.moveCat("vod", c.id, -1) })
                    add("Move Row Down" to { repo.moveCat("vod", c.id, 1) })
                    add("Move Row to Top" to { repo.moveCatTo("vod", c.id, true); menu = null })
                    val gKey = "vod:${c.id}"
                    val gOn = repo.groupOn(gKey)
                    add((if (gOn) "Hide" else "Show") to {
                        repo.setGroupsOn(listOf(gKey to shelf.title), !gOn); menu = null
                        repo.status = "\"${shelf.title}\" " + (if (gOn) "hidden" else "shown") + ". Change this in Catalog Selector."
                    })
                    add("Show All" to { repo.setGroupsOn(listOf(gKey to shelf.title), true); menu = null })
                    add("Hide All" to {
                        repo.setGroupsOn(listOf(gKey to shelf.title), false); menu = null
                        repo.status = "\"${shelf.title}\" hidden. Show it again in Catalog Selector."
                    })
                    if (shelf.error.isNotEmpty()) add("Retry Loading" to { repo.retryRow(c); menu = null })
                }
                if (shelf.cat == null) add("Hide This Category" to {
                    repo.hideRow(mode, shelf.key, shelf.title); menu = null
                    repo.status = "\"${shelf.title}\" hidden. Show it again in Catalog Selector."
                })
                if (tabbed) add("Languages" to { menu = null; go(Screen.Languages(mode, Screen.Home)) })
                add("Catalog Selector" to { menu = null; go(Screen.Categories) })
            }
            val title = item?.let { (if (it.season.isNotEmpty()) "${it.season} · " else "") + Names.title(it.name) } ?: shelf.cat?.let { c ->
                val pos = repo.visibleVod().indexOfFirst { it.id == c.id }
                "${shelf.title}  ·  Row ${pos + 1}"
            } ?: shelf.title
            ChoiceDialog(title, opts, focusKey = shelf.key + (item?.key ?: "")) { menu = null }
        }
        listPick?.let { li -> WatchlistPicker(repo, li.takeIf { it !== WATCHLIST_MANAGER }) {
            val rec = listPickBefore
            listPick = null; listPickBefore = null
            if (li !== WATCHLIST_MANAGER && rec != null && repo.listedKeys().contains(li.key)) {
                Ai.pendingFocus = AiSuggest.focusAfterRemoval(rec, li.key); Ai.drop(repo, li)
            }
        } }
        if (clearHist) {
            ChoiceDialog("Clear All Watch History?", listOf(
                "Clear All History" to { repo.store.clearHistory(); heroMemo.clear(); clearHist = false; contVer++; toast(ctx, "Watch history cleared") },
                "Cancel" to { clearHist = false }
            ), focusKey = "clear-hist") { clearHist = false }
        }
        if (pageMenu) {
            ChoiceDialog("Options", buildList {
                add(AppChrome.GO_HOME to { pageMenu = false; AppChrome.goHome() })
                if (tabbed) add("Languages" to { pageMenu = false; go(Screen.Languages(mode, Screen.Home)) })
                if (tabbed && !langs.isNullOrEmpty()) add("Sort: " + Sorts.label(sort) to { pageMenu = false; sortMenu = true })
                add("Catalog Selector" to { pageMenu = false; go(Screen.Categories) })
                add("Exit ${ctx.getString(R.string.app_name)}" to { pageMenu = false; exitApp(ctx) })
            }, focusKey = "page-menu") { pageMenu = false }
        }
        if (sortMenu) {
            ChoiceDialog("Sort genre rows", Sorts.all.map { s ->
                ((if (s == sort) "✓  " else "") + Sorts.label(s)) to { sort = s; repo.store.saveSort("tab:$mode", s); sortMenu = false; if (s != Sorts.ADDED) lib.more(mode, langs) }
            }, focusKey = "sort") { sortMenu = false }
        }
    }
}

@Composable
private fun Hero(repo: Repo, raw: Item, isTv: Boolean, hPad: Dp, playLabel: String, onPlay: () -> Unit, onInfo: () -> Unit, playModifier: Modifier,
                 heroModifier: Modifier = Modifier, slide: Int = 0, slides: Int = 1, onTouch: () -> Unit = {}, onStep: (Int) -> Unit = {},
                 onUp: (() -> Unit)? = null, onOpenMenu: (() -> Unit)? = null) {
    val meta = rememberMeta(raw)
    val item = raw.withMeta(meta)
    var fav by remember(item.key) { mutableStateOf(repo.isFav(item)) }
    val name = HeroTitle.display(item.name, meta?.title?.ifEmpty { null }, episode = item.isEpisode() || item.isEpisodeLike())
    val screenH = LocalConfiguration.current.screenHeightDp
    val heroH = if (isTv) (screenH * 0.72f).dp.coerceIn(420.dp, 720.dp) else 280.dp
    val backdrop = BackdropArt.distinct(meta?.backdrop, item.img)
    // Full-bleed billboard on TV. Left opens the side menu, Up goes to the top menu, Down reaches Play.
    // Right still steps the carousel when the billboard itself (not a button) has focus.
    // Keys are handled on KeyEvent, not Preview, so Play / More info / Watchlist keep their own Left / Up.
    Box(Modifier.fillMaxWidth().height(heroH)
        .then(heroModifier)
        .focusable()
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            onTouch()
            when (e.key) {
                Key.DirectionLeft -> { onOpenMenu?.invoke(); onOpenMenu != null }
                Key.DirectionRight -> { if (slides > 1) { onStep(1); true } else false }
                Key.DirectionUp -> { onUp?.invoke(); onUp != null }
                else -> false
            }
        }) {
        if (backdrop != null) {
            androidx.compose.animation.Crossfade(backdrop, animationSpec = androidx.compose.animation.core.tween(550), label = "hero") { url ->
                Poster(url, name, Modifier.fillMaxSize(), ContentScale.Crop, initialsSize = 1)
            }
        } else {
            Box(Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF0B1826), Color(0xFF12304F), Color(0xFF071018)))))
        }
        Box(Modifier.fillMaxSize().background(Color(0x4D000000)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Pal.Bg.copy(alpha = 0.92f), Pal.Bg.copy(alpha = 0.45f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Pal.Bg)))
        Row(Modifier.fillMaxSize().padding(horizontal = if (isTv) 56.dp else hPad, vertical = if (isTv) 36.dp else 18.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 8.dp)) {
                Text(name, color = Pal.Text, fontSize = if (isTv) 42.sp else Ts.TITLE.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetaLine(item, meta)
                if (item.desc.isNotEmpty()) Text(item.desc, color = Pal.Desc, fontSize = if (isTv) Ts.ROW.sp else Ts.SECONDARY.sp,
                    maxLines = if (isTv) 3 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(if (isTv) 0.58f else 1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val heroBtnKeys: (Boolean) -> Modifier = { leftmost ->
                        Modifier.onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft -> if (leftmost && onOpenMenu != null) { onOpenMenu.invoke(); true } else false
                                Key.DirectionUp -> if (onUp != null) { onUp.invoke(); true } else false
                                else -> false
                            }
                        }
                    }
                    PillButton(playLabel.ifEmpty { "Play" }, onPlay, playModifier.onFocusChanged { if (it.isFocused) Keys.focused = raw }.then(heroBtnKeys(true)), primary = true, icon = Ic.Play)
                    PillButton("More info", onInfo, heroBtnKeys(false))
                    PillButton("Watchlist", { repo.toggleFav(raw); fav = repo.isFav(raw) }, heroBtnKeys(false), icon = if (fav) Ic.Check else Ic.Add)
                    if (slides > 1) {
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            repeat(slides) { i ->
                                Box(Modifier.size(if (i == slide) 10.dp else 7.dp).clip(RoundedCornerShape(50))
                                    .background(if (i == slide) Pal.Primary else Pal.Dim.copy(alpha = 0.6f)))
                            }
                        }
                    }
                }
            }
            if (!isTv && item.img.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                Poster(item.img, name, Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(Radii.poster)))
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
        meta?.runtime?.ifEmpty { null } ?: item.length.ifEmpty { null },
        if (item.isSeries) "Series" else null,
        meta?.genres?.ifEmpty { null } ?: item.genre.ifEmpty { null }
    )
    if (parts.isNotEmpty()) Text(parts.joinToString("   ·   "), color = Pal.Dim, fontSize = Ts.HEAD.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ShelfRow(repo: Repo, screen: String, shelf: Shelf, isTv: Boolean, hPad: Dp, onOpen: (Item) -> Unit, onMenu: (Item?) -> Unit, onSeeAll: (() -> Unit)?,
                     onLeftEdge: (() -> Unit)? = null, onCardFocus: (FocusRequester) -> Unit = {}, listViewport: () -> Int = { 0 },
                     listState: LazyListState? = null, onRefresh: (() -> Unit)? = null) {
    // Reaching the end of a row fetches a little more (that row's category, or the row's language)
    val onEnd: () -> Unit = {
        shelf.cat?.let { repo.lib.moreInCat(it.id) } ?: shelf.pool?.let { p -> repo.lib.more(p.tab, setOf(p.lang)) }
    }
    val state = remember(shelf.key) { repo.listStates.getOrPut("$screen|${shelf.key}") { LazyListState() } }
    val posterW = posterWidth(isTv)
    val tileW = if (isTv) 200.dp else 150.dp
    val shown = remember(shelf.items) { shelf.items.distinctBy { it.key }.take(ROW_LIMIT) }
    // Focused card in this row (-1: none) and how many cards there are, so the row's ends never hand focus to another row
    var focusIx by remember(shelf.key) { mutableIntStateOf(-1) }
    val frs = remember(shelf.key) { mutableMapOf<String, FocusRequester>() }
    fun frOf(k: String) = frs.getOrPut(k) { FocusRequester() }
    fun cardPivot(i: Int) = if (isTv) Modifier.pivotInList(vertical = false, state = state, index = { i }) else Modifier
    val hasSeeAll = onSeeAll != null && shelf.items.isNotEmpty()
    val cardCount = shown.size + (if (hasSeeAll) 1 else 0) + (if (onRefresh != null) 1 else 0)
    // TV: the focused card stays ~12 % in from the left; the focused row stays ~12 % down from the top
    val pivot = if (isTv && listState != null) Modifier.pivotInList(vertical = true, state = listState, key = shelf.key) else Modifier
    // After a suggestion is reviewed, move focus to the next (or previous) card, or the Refresh tile
    LaunchedEffect(shown.map { it.key }, onRefresh != null, Ai.pendingFocus) {
        if (shelf.key != "ai") return@LaunchedEffect
        val target = Ai.pendingFocus ?: return@LaunchedEffect
        Ai.pendingFocus = null
        kotlinx.coroutines.delay(16)
        when (target) {
            is AiSuggest.FocusAfter.Item -> runCatching { frOf(target.key).requestFocus() }
            AiSuggest.FocusAfter.Refresh -> runCatching { frOf("__refresh__").requestFocus() }
            AiSuggest.FocusAfter.None -> {}
        }
    }
    Column(Modifier.then(pivot).padding(top = if (isTv) 22.dp else 12.dp)) {
        Row(Modifier.padding(horizontal = hPad), verticalAlignment = Alignment.CenterVertically) {
            // Touch: tapping the row title opens the whole category. TV: the "See all" tile at the row's end does
            // (the title is not a focus stop, so Up / Down between rows needs no extra press)
            val titleTap = if (AppUi.phoneNav && hasSeeAll) Modifier.clip(RoundedCornerShape(Radii.row)).clickable { onSeeAll?.invoke() } else Modifier
            Text(shelf.title + (if (AppUi.phoneNav && hasSeeAll) "  ›" else ""), color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(titleTap))
            if (shelf.cat?.locked == true) Text("  🔒", fontSize = Ts.HEAD.sp)
            if (!isTv) {
                // Phones: "⋮" next to the title opens the row menu (move / hide). TV: Menu (or long-press OK) on any card in
                // the row opens the same menu, so Up / Down between rows needs no extra stop
                Spacer(Modifier.width(6.dp))
                Box(Modifier.focusCard({ onMenu(null) }, shape = RoundedCornerShape(Radii.row)).padding(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("⋮", color = Pal.Dim, fontSize = Ts.ROW.sp)
                }
            }
        }
        if (shelf.error.isNotEmpty() && shelf.items.isEmpty()) {
            Row(Modifier.padding(horizontal = hPad, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load: ${shelf.error}", color = Pal.Warn, fontSize = Ts.SECONDARY.sp, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                PillButton("Retry", { shelf.cat?.let { repo.retryRow(it) } }, size = Ts.SECONDARY)
            }
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 10.dp),
            contentPadding = PaddingValues(horizontal = hPad, vertical = if (isTv) 10.dp else 8.dp),
            modifier = Modifier.focusRestorer()
                .onFocusChanged { if (!it.hasFocus) focusIx = -1 }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (RowNav.onKey(focusIx, cardCount, when (e.key) { Key.DirectionLeft -> TabNav.Dir.LEFT; Key.DirectionRight -> TabNav.Dir.RIGHT; else -> TabNav.Dir.OTHER }, onLeftEdge != null)) {
                        RowNav.Action.NONE -> false
                        RowNav.Action.STAY -> true
                        RowNav.Action.TO_MENU -> { onLeftEdge?.invoke(); true }
                    }
                }
        ) {
            if (shelf.loading && shelf.items.isEmpty()) {
                items(7) {
                    Column(Modifier.width(posterW)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(Radii.poster)).shimmer())
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                    }
                }
            }
            itemsIndexed(shown, key = { _, it -> it.key }) { i, it ->
                val k = "${shelf.key}|${it.key}"
                val fr = frOf(it.key)
                RestoreFocus(repo, screen, k, fr)
                val track = cardPivot(i).then(if (isTv) Modifier.animateItemPlacement() else Modifier)
                    .focusRequester(fr).onFocusChanged { f -> if (f.hasFocus) { focusIx = i; onCardFocus(fr) } }
                if (shelf.kind == "itv") {
                    ChannelTile(it, tileW, repo.isFav(it), track, { onOpen(it) }, { onMenu(it) }) { repo.focusItem(screen, k); Keys.focused = it }
                } else {
                    PosterCard(it, posterW, shelf.progress[it.key], track, { onOpen(it) }, { onMenu(it) },
                        fav = repo.isFav(it), versions = shelf.versions[it.key] ?: 0) { repo.focusItem(screen, k); Keys.focused = it }
                }
            }
            if (onSeeAll != null && shelf.items.isNotEmpty()) {
                item(key = "seeall") {
                    LaunchedEffect(shelf.key) { onEnd() }
                    val sfr = frOf("__seeall__")
                    Box(
                        cardPivot(shown.size).then(if (isTv) Modifier.animateItemPlacement() else Modifier)
                            .width(if (shelf.kind == "itv") tileW else posterW).aspectRatio(if (shelf.kind == "itv") 16f / 9f else 2f / 3f).focusRequester(sfr)
                            .onFocusChanged { f -> if (f.hasFocus) { focusIx = shown.size; onCardFocus(sfr) } }
                            .focusCard(onSeeAll, scale = if (isTv) FocusPivot.CARD_SCALE else 1.06f).background(Pal.Card),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("See all  ›", color = Pal.Text, fontSize = Ts.ROW.sp, fontWeight = FontWeight.SemiBold)
                            Text(shelf.title, color = Pal.Dim, fontSize = Ts.CAPTION.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
            if (onRefresh != null) {
                // Suggested for you (1.97): Refresh Suggestions at the row's end; when every suggestion was reviewed
                // (watched, added to a list or disliked) it is the only, wider tile with a short note
                item(key = "refresh") {
                    val rfr = frOf("__refresh__")
                    val all = shown.isEmpty()
                    val busy = Ai.refreshing
                    Box(
                        cardPivot(shown.size + (if (hasSeeAll) 1 else 0)).then(if (isTv) Modifier.animateItemPlacement() else Modifier)
                            .width(if (all) posterW * 2 + 16.dp else posterW).aspectRatio(if (all) 4f / 3f else 2f / 3f).focusRequester(rfr)
                            .onFocusChanged { f -> if (f.hasFocus) { focusIx = shown.size + (if (hasSeeAll) 1 else 0); onCardFocus(rfr) } }
                            .focusCard({ if (!busy) onRefresh() }, scale = if (isTv) FocusPivot.CARD_SCALE else 1.06f).background(if (all) Pal.Accent.copy(alpha = 0.22f) else Pal.Card),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text("↻", color = Pal.Text, fontSize = Ts.TITLE.sp)
                            Text(if (busy) "Finding new picks…" else AiSuggest.REFRESH, color = Pal.Text, fontSize = Ts.ROW.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            val note = when {
                                Ai.lastError.isNotEmpty() && !busy -> Ai.lastError
                                all -> "You have reviewed every suggestion"
                                else -> ""
                            }
                            if (note.isNotEmpty()) Text(note, color = Pal.Dim, fontSize = Ts.CAPTION.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
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
    val tv = !AppUi.phoneNav
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Box(modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.hasFocus }
            .focusCard(onClick, onMenu, onFocused, scale = if (tv) FocusPivot.CARD_SCALE else 1.06f)) {
            Poster(item.img, name, Modifier.fillMaxSize())
            val tag = when {
                item.isSeries -> "SERIES"
                versions > 1 -> "$versions VERSIONS"
                q == "CAM" || q == "TS" -> q
                else -> ""
            }
            if (tag.isNotEmpty()) {
                Text(tag, color = Color.White, fontSize = Ts.CAPTION.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(if (tag == "CAM" || tag == "TS") Color(0xFF8D6E00) else Pal.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
            if (fav) Text("★", color = Pal.Highlight, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 5.dp))
            if (item.locked) Text("🔒", fontSize = Ts.HEAD.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x88FFFFFF)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(4.dp).background(Pal.Accent))
            }
        }
        Spacer(Modifier.height(if (tv) 8.dp else 6.dp))
        // Year and rating (IMDb when known)
        val r = meta?.imdbRating?.ifEmpty { null } ?: item.rating.takeIf { item.ratingNum > 0 }
        val y = item.year.ifEmpty { raw.parsed.year }
        val sub = listOfNotNull(y.ifEmpty { null }, r?.let { "★ $it" }).joinToString("  ·  ")
        val title = if (item.season.isNotEmpty()) "${item.season} · $name" else name
        if (tv) {
            // TV (1.92, less clutter): one quiet title line; year / rating only on the focused card. Fixed height, so
            // moving focus never re-lays out the row.
            // 1.97: the box used to be 36 dp while Material's default 24 sp line height made the two lines ~48 dp, so the
            // year / rating line was cut off. Explicit line heights, and the box sized in sp so the font scale fits too.
            Column(Modifier.fillMaxWidth().height(CardCaption.tvHeight())) {
                Text(title, color = if (focused) Pal.Text else Pal.Dim, fontSize = Ts.SECONDARY.sp, lineHeight = CardCaption.TITLE_LH.sp,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (focused && sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = Ts.CAPTION.sp, lineHeight = CardCaption.SUB_LH.sp, maxLines = 1)
            }
        } else {
            Text(title, color = Pal.Text2, fontSize = Ts.SECONDARY.sp, lineHeight = CardCaption.TITLE_LH.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = Ts.CAPTION.sp, lineHeight = CardCaption.SUB_LH.sp, maxLines = 1)
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
fun ChannelTile(item: Item, width: Dp, fav: Boolean, modifier: Modifier, onClick: () -> Unit, onMenu: () -> Unit, onFocused: () -> Unit = {}) {
    Column(Modifier.width(width)) {
        Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).focusCard(onClick, onMenu, onFocused, scale = if (!AppUi.phoneNav) FocusPivot.CARD_SCALE else 1.06f).background(Pal.Card)) {
            Poster(item.img, item.name, Modifier.fillMaxSize().padding(if (item.img.isNotEmpty()) 10.dp else 0.dp), ContentScale.Fit)
            if (item.num > 0) Text("${item.num}", color = Color.White, fontSize = Ts.CAPTION.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            if (fav) Text("★", color = Pal.Highlight, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(if (item.kind == "itv") ChannelTitle.pretty(item.name) else item.name, color = Pal.Text2, fontSize = Ts.SECONDARY.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val verPref = remember(versions) { VersionPick.prefKey(versions, item) }
    // Selected version: the one played last, else one in Continue watching, else the best
    var cur by remember(item.key, versions) {
        val inProgress = repo.store.continueList().map { it.first.key }.toSet()
        mutableStateOf(if (versions.size > 1) VersionPick.default(versions, repo.store.sort(verPref, ""), inProgress) ?: item else item)
    }
    val meta = rememberMeta(cur)
    val shownItem = cur.withMeta(meta)
    val name = HeroTitle.display(cur.name, meta?.title?.ifEmpty { null }, episode = cur.isEpisode() || cur.isEpisodeLike())
    // Seasons first (saved list = instant chips), then only the season being looked at (saved per season on disk)
    var refs by remember(cur.key) { mutableStateOf(if (cur.isSeries && !cur.id.startsWith("grp:")) repo.seasons.cached(cur).orEmpty() else emptyList()) }
    val loaded = remember(cur.key) { mutableStateMapOf<String, List<Item>>().also { m -> refs.forEach { r -> repo.seasons.cachedEpisodes(cur, r)?.let { m[r.id] = it } } } }
    val seasonBusy = remember(cur.key) { mutableStateMapOf<String, Boolean>() }
    var listLoading by remember(cur.key) { mutableStateOf(cur.isSeries && refs.isEmpty()) }
    var msg by remember(cur.key) { mutableStateOf("") }
    var fav by remember(cur.key) { mutableStateOf(repo.isFav(cur)) }
    var reload by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var detailPick by remember { mutableStateOf(false) }
    LaunchedEffect(cur.key) { Keys.focused = cur }
    LaunchedEffect(cur.key, reload) {
        if (!cur.isSeries) return@LaunchedEffect
        msg = ""
        if (refs.isEmpty()) listLoading = true
        val r = runCatching {
            if (cur.id.startsWith("grp:")) {
                // Loose "Show S01E02" entries: all local, one chip per season
                val all = EpisodeOrder.sort(withContext(Dispatchers.IO) { repo.lib.syntheticEpisodes(cur) })
                EpisodeOrder.seasons(all).map { n -> SeasonRef(n, "", "", all.filter { it.season == n }) }
            } else repo.seasons.list(cur, force = reload > 0)
        }
        r.getOrNull()?.let { l -> refs = l; l.forEach { s -> s.eps?.let { loaded[s.id] = it } } }
        if (refs.isEmpty()) msg = r.exceptionOrNull()?.message ?: "No episodes found for this series"
        listLoading = false
    }
    // Episodes of the seasons loaded so far, seasons ascending, episodes 1..N (next-episode logic uses this order too)
    val eps = remember(refs, loaded.toMap()) { EpisodeOrder.sort(refs.flatMap { loaded[it.id].orEmpty() }) }
    val seasons = remember(refs) { refs.map { it.name } }
    // Display names without the show's name ("Koffee With Karan Season 1" -> "Season 1"); raw names stay for requests
    val shows = listOfNotNull(cur.name, item.name, meta?.title?.ifEmpty { null })
    fun seasonName(s: String) = if (s.isEmpty()) "Episodes" else Names.seasonLabel(s, shows)
    var season by remember(cur.key) { mutableStateOf<String?>(null) }
    val cont = remember(tick) { repo.store.continueList().associate { it.first.key to it.second } }
    val target = focusEp?.let { k -> eps.indexOfFirst { it.key == k } } ?: -1
    // Where to open: the episode asked for, else the last one played (the next one if it was finished),
    // else one in Continue watching, else the first episode of the first season
    val lastIdx = remember(eps, tick, cont) {
        ResumeEpisode.index(eps, repo.store.lastEp(cur.key), cont.keys)
    }
    val resumeEp = when {
        target >= 0 -> target
        else -> lastIdx
    }
    val focusKey = focusEp ?: eps.getOrNull(resumeEp)?.key
    // The season chosen, else the last one played (remembered, so it loads first), else the first
    val lastSeason = remember(cur.key, tick) { repo.store.sort("season_${cur.key}", "").ifEmpty { null } }
    val curRef = SeasonPlan.first(refs, season ?: eps.getOrNull(resumeEp)?.season, lastSeason)
    val curSeason = curRef?.name ?: ""
    // Fetch the shown season (one request, or none when saved); other seasons only when their chip is chosen
    LaunchedEffect(cur.key, curRef?.id, reload) {
        val ref = curRef ?: return@LaunchedEffect
        if (cur.id.startsWith("grp:") || (loaded.containsKey(ref.id) && reload == 0)) return@LaunchedEffect
        seasonBusy[ref.id] = true
        runCatching { repo.seasons.episodes(cur, ref, force = reload > 0) }
            .onSuccess { loaded[ref.id] = it; if (it.isEmpty() && refs.size <= 1) msg = "No episodes found for this series" }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) msg = it.message ?: "Could not load ${seasonName(ref.name)}" }
        seasonBusy.remove(ref.id)
    }
    val seasonLoading = curRef != null && curRef.id !in loaded
    val loading = cur.isSeries && (listLoading || seasonLoading)
    val shown = if (seasons.size > 1) eps.filter { it.season == curSeason } else eps
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
    val (rp, rd) = remember(tick, cur.key) { repo.store.resumeInfo(cur) }
    val canResume = !cur.isSeries && rp > 30_000 && (rd <= 0 || rp < rd * 0.95)

    fun start(list: List<Item>, i: Int) {
        val e = repo.gate(cur.locked)
        if (e != null) { msg = e; return }
        if (cur.isSeries) {
            repo.store.saveParent(cur, eps)
            list.getOrNull(i)?.let { repo.store.saveSort("season_${cur.key}", it.season) }
        }
        if (versions.size > 1) repo.store.saveSort(verPref, cur.key)
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
        BackdropArt.distinct(meta?.backdrop, shownItem.img)?.let { url ->
            Poster(url, name, Modifier.fillMaxWidth().fillMaxHeight(if (isTv) 0.82f else 0.62f), ContentScale.Crop, initialsSize = 1)
        } ?: Box(Modifier.fillMaxWidth().fillMaxHeight(if (isTv) 0.82f else 0.62f).background(
            Brush.verticalGradient(listOf(Color(0xFF0E2238), Pal.Bg))))
        Box(Modifier.fillMaxSize().background(Color(0x4D000000)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.15f to Pal.Bg.copy(alpha = 0.35f), 0.7f to Pal.Bg)))
        val similar = remember(cur.key, cur.cat, repo.lib.version) {
            if (!repo.lib.hasData || cur.cat.isEmpty()) emptyList()
            else repo.lib.everything().asSequence()
                .filter { it.kind == "vod" && !it.isEpisode() && it.key != cur.key && it.cat == cur.cat && repo.listOk(it.locked) }
                .distinctBy { it.key }.take(12).toList()
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = hPad, vertical = if (isTv) 40.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 14.dp), verticalAlignment = Alignment.Bottom) {
                    if (!isTv) Poster(shownItem.img, name, Modifier.width(116.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(Radii.poster)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 8.dp)) {
                        Text(name, color = Pal.Text, fontSize = if (isTv) 42.sp else Ts.TITLE.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.6).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        MetaLine(shownItem, meta)
                        if (shownItem.desc.isNotEmpty()) Text(shownItem.desc, color = Pal.Desc, fontSize = if (isTv) Ts.ROW.sp else Ts.SECONDARY.sp,
                            maxLines = if (isTv) 5 else 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(if (isTv) 0.7f else 1f))
                        meta?.cast?.takeIf { it.isNotEmpty() }?.let { Text("Cast: $it", color = Pal.Dim, fontSize = Ts.SECONDARY.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (msg.isNotEmpty()) Text(msg, color = Pal.Warn, fontSize = Ts.HEAD.sp, maxLines = 4)
                        Spacer(Modifier.height(4.dp))
                        FlowRowCompat {
                            val pm = Modifier.focusRequester(playFr)
                            when {
                                cur.isSeries && eps.isNotEmpty() -> {
                                    val i = if (resumeEp >= 0) resumeEp else 0
                                    PillButton(ResumeEpisode.playLabel(eps, i), { start(eps, i) }, pm, primary = true, onMenu = { more = true }, icon = Ic.Play)
                                    PillButton("Episodes", {
                                        scope.launch {
                                            val header = 1 + (if (versions.size > 1) 1 else 0) + (if (seasons.size > 1) 1 else 0)
                                            runCatching { listState.animateScrollToItem(header) }
                                            delay(80)
                                            runCatching { epFr.requestFocus() }
                                        }
                                    }, onMenu = { more = true })
                                }
                                cur.isSeries && loading -> PillButton("Loading episodes…", {}, pm)
                                cur.isSeries -> PillButton("Retry", { reload++ }, pm)
                                canResume -> {
                                    PillButton(ResumeEpisode.movieLabel(true) + " · ${fmt(rp)}", { start(listOf(cur), 0) }, pm, primary = true, onMenu = { more = true }, icon = Ic.Play)
                                    PillButton("Start Over", { repo.store.removeResume(cur.key); start(listOf(cur), 0) }, icon = Ic.Restart)
                                }
                                else -> PillButton(ResumeEpisode.movieLabel(false), { start(listOf(cur), 0) }, pm, primary = true, onMenu = { more = true }, icon = Ic.Play)
                            }
                            PillButton("Watchlist", { repo.toggleFav(cur); fav = repo.isFav(cur) }, onMenu = { more = true }, icon = if (fav) Ic.Check else Ic.Add)
                        }
                    }
                }
            }
            if (versions.size > 1) item {
                val labels = remember(versions) { VersionPick.labels(versions.map { versionLabel(repo, it) }) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Versions", color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.focusRestorer()) {
                        itemsIndexed(versions, key = { _, v -> v.key }) { i, v ->
                            val sel = v.key == cur.key
                            val pct = cont[v.key]?.let { p -> repo.store.resumeInfo(v).second.takeIf { it > 0 }?.let { d -> (p * 100 / d).toInt().coerceIn(0, 100) } }
                            // OK / tap: a movie version plays at once (resuming where it was left); a series version opens its seasons
                            PillButton((if (sel) "✓  " else "") + labels[i] + (pct?.let { "  ·  $it%" } ?: ""),
                                { cur = v; repo.store.saveSort(verPref, v.key); if (!v.isSeries) start(listOf(v), 0) },
                                selected = sel, primary = sel, size = Ts.HEAD, onMenu = { more = true })
                        }
                    }
                }
            }
            if (cur.isSeries && listLoading && refs.isEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    repeat(4) { Box(Modifier.width(96.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).shimmer()) }
                }
            }
            if (seasons.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp), modifier = Modifier.focusRestorer()) {
                    items(refs, key = { it.id }) { r ->
                        val n = loaded[r.id]?.size
                        val tag = when { seasonBusy[r.id] == true -> "  …"; n != null -> "  $n"; else -> "" }
                        PillButton(seasonName(r.name) + tag, { season = r.name }, selected = r.name == curSeason, size = Ts.ROW)
                    }
                }
            }
            if (cur.isSeries && seasonLoading && !listLoading) {
                items(4) {
                    Row(Modifier.fillMaxWidth().background(Pal.Soft).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.width(epW).aspectRatio(16f / 9f).clip(RoundedCornerShape(Radii.card)).shimmer())
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth(0.6f).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                            Box(Modifier.fillMaxWidth(0.9f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                        }
                    }
                }
            }
            if (shown.isNotEmpty()) item {
                Text(if (seasons.size > 1) "${seasonName(curSeason)} · ${shown.size} episodes" else "${shown.size} episodes",
                    color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
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
                    Box(Modifier.width(epW).aspectRatio(16f / 9f).clip(RoundedCornerShape(Radii.card))) {
                        Poster(em?.still?.ifEmpty { null } ?: e.img.ifEmpty { shownItem.img }, title, Modifier.fillMaxSize(), initialsSize = 18)
                        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                        androidx.compose.material3.Icon(Ic.Play, null, tint = Color.White, modifier = Modifier.size(26.dp).align(Alignment.Center))
                        if (p != null && d > 0) {
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x88FFFFFF)))
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((p.toFloat() / d).coerceIn(0f, 1f)).height(3.dp).background(Pal.Accent))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Pal.Text, fontSize = Ts.ROW.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(e.length.ifEmpty { null }, em?.airDate?.ifEmpty { null }).joinToString("  ·  ")
                        if (sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = Ts.CAPTION.sp)
                        val ov = em?.overview?.ifEmpty { null } ?: e.desc.takeIf { it.isNotEmpty() && it != cur.desc }
                        if (ov != null) Text(ov, color = Pal.Dim, fontSize = Ts.SECONDARY.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        if (similar.isNotEmpty()) item {
            Text("More like this", color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        }
        if (similar.isNotEmpty()) item {
            val posterW = posterWidth(isTv)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp), contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.focusRestorer()) {
                itemsIndexed(similar, key = { _, it -> it.key }) { _, it ->
                    PosterCard(it, posterW, null, Modifier, { go(Screen.Detail(it, Screen.Detail(item, Screen.Home))) }, { })
                }
            }
        }
        }
        if (more) {
            ChoiceDialog(name, focusKey = "more", opts = buildList {
                add(AppChrome.GO_HOME to { more = false; AppChrome.goHome() })
                if (Tmdb.enabled) add("Look Up Details Again" to { more = false; scope.launch { Tmdb.clearFor(cur) } })
                if (cur.kind == "vod" && !cur.isEpisode()) {
                    add((if (repo.isFav(cur)) WatchLists.REMOVE else WatchLists.ADD) to { repo.toggleFav(cur); fav = repo.isFav(cur); more = false })
                    add(WatchLists.OTHER to { more = false; detailPick = true })
                }
                if (canResume) add("Remove from Continue Watching" to { repo.store.removeResume(cur.key); more = false })
            }) { more = false }
        }
        if (detailPick) WatchlistPicker(repo, cur) { detailPick = false; fav = repo.isFav(cur) }
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
    // The category's saved titles; its next page is fetched (gently, queued) when the grid's end comes into view
    val lib = repo.lib
    val items = remember(cat.id, lib.version) { lib.inCat(cat.id) }
    val more = remember(cat.id, lib.version) { lib.catHasMore(cat.id) }
    val busy = lib.loading
    var msg by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<Item?>(null) }
    var sort by remember { mutableStateOf(repo.store.sort("grid", Sorts.ADDED)) }
    var sortMenu by remember { mutableStateOf(false) }
    var refreshMsg by remember(cat.id) { mutableStateOf("") }
    val screenKey = "grid:${cat.id}"
    // Same title in several qualities shows once (best version first); sorted on what is loaded so far
    // Stable: titles already on screen keep their place when a page arrives (no jump back / repeats, see Paging)
    val order = remember(cat.id, sort) { arrayOf(emptyList<String>()) }
    val cards = remember(items, sort, Tmdb.version) {
        Paging.stable(order[0], repo.lib.sort(repo.lib.cards(items), sort)) { it.best.key }.also { c -> order[0] = c.map { it.best.key } }
    }
    val vcount = remember(cards) { cards.associate { it.best.key to it.items.size } }
    val shownItems = remember(cards) { cards.map { it.best } }

    fun next() { if (more && !busy) lib.moreInCat(cat.id) }
    LaunchedEffect(cat.id) { if (items.isEmpty()) next() }
    // Paging: once per list size, when the last few posters come into view
    var askedAt by remember(cat.id) { mutableIntStateOf(-1) }

    val posterW = posterWidth(isTv)
    val hPad = if (isTv) 48.dp else 14.dp
    val state = remember(cat.id) { repo.gridStates.getOrPut(screenKey) { LazyGridState() } }
    val wrap = remember(cat.id) { GridFocus() }
    LaunchedEffect(state, shownItems.size, more, busy) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            // grid index 0 is the header
            if (Paging.shouldLoad(last - 1, shownItems.size, more, busy, askedAt)) { askedAt = shownItems.size; next() }
        }
    }
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
                    Text(GroupTitle.display(cat.title), color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (more) "${items.size}+ titles" else "${items.size} titles", color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                    if (more) {
                        androidx.compose.material3.TextButton(onClick = { next() }, enabled = !busy) {
                            Text(if (busy) "Loading…" else "Load more", color = Pal.Accent)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton("Sort: " + Sorts.label(sort), { sortMenu = true }, size = Ts.HEAD)
                        // 1.97: Refresh re-reads this category's newest page (once per 10 min, paced like all paging)
                        PillButton("Refresh", { refreshMsg = lib.refreshCat(cat.id) }, size = Ts.HEAD)
                    }
                    val rNote = if (refreshMsg.startsWith("Checking") && !busy && lib.note.isNotEmpty()) lib.note else refreshMsg
                    if (rNote.isNotEmpty()) Text(rNote, color = Pal.Dim, fontSize = Ts.SECONDARY.sp, maxLines = 2)
                    if (msg.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = Pal.Warn, fontSize = Ts.SECONDARY.sp, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        PillButton("Retry", { next() }, size = Ts.SECONDARY)
                    }
                }
            }
            gridItemsIndexed(shownItems, key = { _, it -> it.key }) { i, it ->
                val k = it.key
                val fr = remember { FocusRequester() }
                RestoreFocus(repo, screenKey, k, fr)
                PosterCard(it, posterW, null, Modifier.then(if (i == 0) Modifier.focusRequester(firstFr) else Modifier).focusRequester(fr)
                    .then(if (isTv) Modifier.gridWrap(i, shownItems.size, state, wrap) else Modifier),
                    { if (it.isEpisode()) play(listOf(it), 0) else open(it) }, { menu = it }, fav = repo.isFav(it), versions = vcount[it.key] ?: 0) { repo.focusItem(screenKey, k); Keys.focused = it }
            }
            if (busy && more) items(if (items.isEmpty()) 12 else 6) { _ ->
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(Radii.poster)).shimmer())
            }
        }
        menu?.let { m -> TitleMenu(repo, m, vcount[m.key] ?: 0, open, { play(listOf(it), 0) }) { menu = null } }
        if (sortMenu) SortDialog(sort, { sort = it; repo.store.saveSort("grid", it); sortMenu = false }) { sortMenu = false }
    }
}

/**
 * Movie / series menu: only valid options, Close last (ChoiceDialog). Play / Episodes, My List, Like / Dislike,
 * Mark watched, Versions (when there are several), Hide this title.
 */
@Composable
fun TitleMenu(repo: Repo, m: Item, versions: Int, open: (Item) -> Unit, play: ((Item) -> Unit)?, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pick by remember(m.key) { mutableStateOf(false) }
    if (pick) { WatchlistPicker(repo, m, onClose); return }
    ChoiceDialog(Names.title(m.name), buildList {
        add(AppChrome.GO_HOME to { onClose(); AppChrome.goHome() })
        when {
            m.isSeries -> add("Episodes" to { onClose(); open(m) })
            play != null -> add("Play" to { onClose(); play(m) })
        }
        add("Details" to { onClose(); open(m) })
        addAll(watchlistOptions(repo, m, ctx, onClose) { pick = true })
        if (m.kind == "vod" && !m.isEpisode()) {
            AiFeedback.menuItems(Ai.voteOf(m)).forEach { (label, vote) ->
                add(label to { Ai.toggleVote(repo, m, vote); onClose() })
            }
        }
        if (!m.isSeries) add((if (repo.isWatched(m)) "Mark as Unwatched" else "Mark as Watched") to { repo.setWatched(m, !repo.isWatched(m)); onClose() })
        if (versions > 1) add("Versions: $versions" to { onClose(); open(m) })
        if (!m.isEpisode()) add("Hide This Title" to {
            repo.toggleHideTitle(m); onClose(); toast(ctx, "Hidden. Show it again in Settings > Hidden.")
        })
    }, focusKey = "title" + m.key, onClose = onClose)
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
    val order = remember(lang, genre, sort) { arrayOf(emptyList<String>()) }
    val cards by produceState(emptyList<Group>(), lang, genre, sort, lib.version, Tmdb.version) {
        value = withContext(Dispatchers.Default) {
            val g = lib.cards(lib.pool(tab, setOf(lang)))
            // Stable order: new titles are appended, what is on screen stays put (see Paging)
            Paging.stable(order[0], lib.sort(if (genre.isEmpty()) g else g.filter { genre in lib.genresOf(it.best) }, sort)) { it.key }
                .also { c -> order[0] = c.map { it.key } }
        }
    }
    var askedAt by remember(lang, genre) { mutableIntStateOf(-1) }
    val screenKey = "pool:$tab:$title"
    val posterW = posterWidth(isTv)
    val hPad = if (isTv) 48.dp else 14.dp
    val state = remember(screenKey) { repo.gridStates.getOrPut(screenKey) { LazyGridState() } }
    val wrap = remember(screenKey) { GridFocus() }
    LaunchedEffect(state, cards.size, lib.loading) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (Paging.shouldLoad(last - 1, cards.size, !lib.complete, lib.loading, askedAt)) { askedAt = cards.size; lib.more(tab, setOf(lang)) }
        }
    }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(cards.isNotEmpty()) { if (isTv && cards.isNotEmpty() && repo.lastFocus[screenKey] == null) { delay(100); runCatching { firstFr.requestFocus() } } }
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyVerticalGrid(columns = GridCells.Adaptive(posterW), state = state,
            contentPadding = PaddingValues(horizontal = hPad, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 12.dp), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold)
                    Text("${cards.size} titles" + (if (lib.loading) " · loading more…" else ""), color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                    PillButton("Sort: " + Sorts.label(sort), { sortMenu = true }, Modifier.focusRequester(firstFr), size = Ts.HEAD)
                }
            }
            gridItemsIndexed(cards, key = { _, g -> g.key }) { i, g ->
                val it = g.best
                val k = g.key
                val fr = remember { FocusRequester() }
                RestoreFocus(repo, screenKey, k, fr)
                PosterCard(it, posterW, null, Modifier.focusRequester(fr).then(if (isTv) Modifier.gridWrap(i, cards.size, state, wrap) else Modifier),
                    { open(it) }, { menu = it }, fav = repo.isFav(it), versions = g.items.size) { repo.focusItem(screenKey, k); Keys.focused = it }
            }
        }
        menu?.let { m -> TitleMenu(repo, m, cards.firstOrNull { it.best.key == m.key }?.items?.size ?: 0, open, null) { menu = null } }
        if (sortMenu) SortDialog(sort, { sort = it; repo.store.saveSort("pool:$title", it); sortMenu = false; if (it != Sorts.ADDED) lib.more(tab, setOf(lang)) }) { sortMenu = false }
    }
}

/** Languages checklist for a tab, with category counts. Nothing ticked = no language filter (old category rows). */
@Composable
fun LanguagesScreen(repo: Repo, tab: String, onBack: () -> Unit) {
    val isTv = rememberIsTv()
    val lib = repo.lib
    val liveTab = tab == "live"
    val langs by produceState(
        if (liveTab) LiveModes.languages(repo.channels, repo.liveIndex.info) else lib.languages(tab),
        repo.vodCats.size, lib.version, repo.channels, repo.liveIndex.info
    ) { value = withContext(Dispatchers.Default) {
        if (liveTab) LiveModes.languages(repo.channels, repo.liveIndex.info) else lib.languages(tab)
    } }
    val unit = if (liveTab) "channel" else lib.countUnit
    var sel by remember { mutableStateOf((if (liveTab) repo.liveLangs else lib.selected(tab)) ?: emptySet()) }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { firstFr.requestFocus() } }
    fun save(v: Set<String>) {
        sel = v
        if (liveTab) repo.chooseLiveLangs(v.ifEmpty { null })
        else {
            repo.store.saveLangs(tab, v.ifEmpty { null })
            if (v.isNotEmpty()) lib.ensure(tab, v)    // only the newly picked languages are fetched
        }
    }
    val hPad = if (isTv) 48.dp else 16.dp
    val tabTitle = when (tab) { "series" -> "Series"; "live" -> "Live TV"; else -> "Movies" }
    val blurb = if (liveTab)
        "Tick the languages to show on Live TV. The language comes from the category and channel names. " +
            "Channels with no recognisable language are listed under \"Other\". Untick everything to show every language. " +
            "Your choices are saved on this device and with Google sync."
    else
        "Tick the languages to show. The language comes from the category and title names (e.g. \"HINDI 4K\", \"|EN|\", \"[Tamil]\"); " +
            "Dub / CAM / 4K are treated as versions, not languages; a dubbed title counts as the language it is dubbed in. " +
            "Titles with no recognisable language are listed under \"Other\". Untick everything to go back to plain category rows. " +
            "Other languages stay reachable through Search."
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = hPad, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Languages · $tabTitle", color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold)
                    Text(blurb, color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Done", onBack, Modifier.focusRequester(firstFr), primary = true, size = Ts.HEAD)
                        PillButton("Select all", { save(langs.map { it.first }.toSet()) }, size = Ts.HEAD)
                        PillButton("Select none", { save(emptySet()) }, size = Ts.HEAD)
                    }
                }
            }
            items(langs, key = { it.first }) { (l, n) ->
                val on = l in sel
                Row(Modifier.fillMaxWidth().focusCard({ save(if (on) sel - l else sel + l) }, scale = 1.02f)
                    .background(if (on) Pal.Primary.copy(alpha = 0.22f) else Pal.Soft).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "☑" else "☐", color = Pal.Text, fontSize = Ts.TITLE.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(l, color = Pal.Text, fontSize = Ts.ROW.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text("%,d ".format(n) + when {
                        unit == "title" -> if (n == 1) "title" else "titles"
                        unit == "channel" -> if (n == 1) "channel" else "channels"
                        else -> if (n == 1) "category" else "categories"
                    }, color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                }
            }
        }
    }
}

/** Catalog Selector: languages, then Movies / Series / Live categories, then the shown list. */
@Composable
fun CategoriesScreen(repo: Repo, go: (Screen) -> Unit) = CatalogSelectorScreen(repo, go)
