@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package tv.tvstreamer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
    val error: String = ""
)

/** A single episode (plays directly rather than opening details). */
fun Item.isEpisode(): Boolean = kind == "vod" && num != -1 && (series > 0 || season.isNotEmpty() || pid.isNotEmpty())

private const val ROW_LIMIT = 40

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

/** Home / Movies / Series: hero banner, Continue watching, My List, then one row per category. */
@Composable
fun Catalog(repo: Repo, mode: String, go: (Screen) -> Unit, open: (Item) -> Unit, play: (List<Item>, Int) -> Unit, tick: Int) {
    val ctx = LocalContext.current
    val isTv = rememberIsTv()
    val scope = rememberCoroutineScope()
    val unlocked = repo.gate(true) == null
    var contVer by remember { mutableIntStateOf(0) }
    val cont = remember(tick, contVer) { repo.store.continueList() }
    val recent = remember(tick) { repo.store.list("recent").filter { !it.locked } }
    var menu by remember { mutableStateOf<Pair<Shelf, Item?>?>(null) }
    var updateMsg by remember { mutableStateOf("") }
    val seriesSplit = repo.hasSeriesCats()

    // What belongs on this screen
    fun wantItem(i: Item): Boolean = when (mode) {
        "movies" -> !i.isSeries && !i.isEpisode()
        "series" -> i.isSeries || i.isEpisode()
        else -> true
    }
    fun wantCat(c: Cat): Boolean = when {
        mode == "home" || !seriesSplit -> true
        mode == "series" -> repo.isSeriesCat(c)
        else -> !repo.isSeriesCat(c)
    }

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
        if (mode == "home") {
            repo.favs.filter { !it.locked && it.kind == "itv" }.takeIf { it.isNotEmpty() }?.let { add(Shelf("favc", "Favorite channels", it, "itv")) }
            if (recent.isNotEmpty()) add(Shelf("recent", "Recent channels", recent, "itv"))
        }
        repo.visibleVod().filter { (!it.locked || unlocked) && wantCat(it) }.forEach { cat ->
            val raw = repo.rows[cat.id]
            // Without series-named categories the Series / Movies tabs filter each row by item type
            val items = raw?.let { l -> if (mode != "home" && !seriesSplit) l.filter { wantItem(it) } else l }
            if (items == null || items.isNotEmpty() || repo.rowErrors[cat.id] != null) {
                add(Shelf("vod:${cat.id}", Names.cat(cat.title), items ?: emptyList(), "vod", cat,
                    loading = raw == null && repo.rowErrors[cat.id] == null, error = repo.rowErrors[cat.id] ?: ""))
            }
        }
    }
    val hero = remember(shelves.map { it.key to it.items.size }) {
        shelves.asSequence().filter { it.cat != null }.flatMap { it.items.asSequence() }.firstOrNull { it.img.isNotEmpty() && !it.locked }
            ?: shelves.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.kind == "vod" && it.img.isNotEmpty() }
    }

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
                        Text("TVSTREAMER", color = Pal.Accent, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(14.dp))
                        val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" } }
                        Text("v$ver", color = Pal.Dim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.focusRestorer()) {
                        items(tabs, key = { it.second }) { (t, k, a) ->
                            PillButton(t, a, if (k == mode) Modifier.focusRequester(navFr).onFocusChanged { if (it.isFocused) repo.lastFocus[screenKey] = "nav" } else Modifier,
                                selected = k == mode, size = 15)
                        }
                    }
                    if (repo.status.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(repo.status, color = Pal.Warn, fontSize = 15.sp, maxLines = 5) }
                    if (updateMsg.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(updateMsg, color = Pal.Warn, fontSize = 14.sp, maxLines = 4) }
                }
            }
            if (hero != null) item(key = "hero") {
                Hero(repo, hero, isTv, hPad,
                    onPlay = { if (hero.isSeries) open(hero) else { val e = repo.gate(hero.locked); if (e == null) play(listOf(hero), 0) else repo.status = e } },
                    onInfo = { open(hero) },
                    playModifier = Modifier.focusRequester(heroFr).onFocusChanged { if (it.isFocused) repo.lastFocus[screenKey] = "hero" })
            }
            if (shelves.isEmpty() && repo.status.isEmpty()) item(key = "empty") {
                Text(if (repo.vodCats.isEmpty()) "Loading your portal…" else "Nothing here yet. Open Categories to show hidden ones.",
                    color = Pal.Dim, fontSize = 16.sp, modifier = Modifier.padding(hPad))
            }
            items(shelves.distinctBy { it.key }, key = { it.key }) { shelf ->
                // Lazy: a category's items are requested only when its row is composed (scrolled into view)
                if (shelf.cat != null && shelf.loading) LaunchedEffect(shelf.key) { repo.loadRow(shelf.cat) }
                ShelfRow(repo, screenKey, shelf, isTv, hPad,
                    onOpen = { it ->
                        when {
                            it.kind == "itv" -> play(shelf.items, shelf.items.indexOfFirst { x -> x.key == it.key }.coerceAtLeast(0))
                            shelf.key == "cont" || it.isEpisode() -> {
                                val e = repo.gate(it.locked); if (e == null) play(listOf(it), 0) else repo.status = e
                            }
                            else -> open(it)
                        }
                    },
                    onMenu = { menu = shelf to it },
                    onSeeAll = shelf.cat?.let { c -> { go(Screen.Grid(c, Screen.Home)) } }
                )
            }
        }

        menu?.let { (shelf, item) ->
            val opts = buildList<Pair<String, () -> Unit>> {
                if (item != null) {
                    if (item.kind == "vod" && !item.isEpisode()) add("Details" to { menu = null; open(item) })
                    add((if (repo.isFav(item)) "★  Remove from My List" else "☆  Add to My List") to { repo.toggleFav(item); menu = null })
                    if (shelf.key == "cont") add("Remove from Continue watching" to { repo.store.removeResume(item.key); menu = null; contVer++ })
                    if (item.kind == "itv") add("Hide channel" to { repo.toggleHideChannel(item); menu = null })
                }
                shelf.cat?.let { c ->
                    add("See all in ${shelf.title}" to { menu = null; go(Screen.Grid(c, Screen.Home)) })
                    add("▲  Move row up" to { repo.moveCat("vod", c.id, -1) })
                    add("▼  Move row down" to { repo.moveCat("vod", c.id, 1) })
                    add("⤒  Move row to top" to { repo.moveCatTo("vod", c.id, true); menu = null })
                    add("Hide this category" to { repo.toggleHide("vod", c.id); menu = null })
                    if (shelf.error.isNotEmpty()) add("Retry loading" to { repo.retryRow(c); menu = null })
                }
                add("Browse / arrange all categories…" to { menu = null; go(Screen.Categories) })
            }
            val title = item?.let { Names.title(it.name) } ?: shelf.cat?.let { c ->
                val pos = repo.visibleVod().indexOfFirst { it.id == c.id }
                "${shelf.title}  (row ${pos + 1})"
            } ?: shelf.title
            ChoiceDialog(title, opts, focusKey = shelf.key + (item?.key ?: "")) { menu = null }
        }
    }
}

@Composable
private fun Hero(repo: Repo, item: Item, isTv: Boolean, hPad: Dp, onPlay: () -> Unit, onInfo: () -> Unit, playModifier: Modifier) {
    var fav by remember(item.key) { mutableStateOf(repo.isFav(item)) }
    val name = Names.title(item.name)
    Box(Modifier.fillMaxWidth().height(if (isTv) 320.dp else 260.dp)) {
        Poster(item.img, name, Modifier.fillMaxSize(), ContentScale.Crop, initialsSize = 1)
        Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Pal.Bg, Pal.Bg.copy(alpha = 0.55f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Pal.Bg)))
        Row(Modifier.fillMaxSize().padding(horizontal = hPad, vertical = 16.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, color = Color.White, fontSize = if (isTv) 34.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetaLine(item)
                if (item.desc.isNotEmpty()) Text(item.desc, color = Color(0xFFDDDDDD), fontSize = if (isTv) 15.sp else 13.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(if (isTv) 0.8f else 1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(if (item.isSeries) "▶  Episodes" else "▶  Play", onPlay, playModifier, primary = true)
                    PillButton(if (fav) "✓  My List" else "+  My List", { repo.toggleFav(item); fav = repo.isFav(item) })
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
fun MetaLine(item: Item) {
    val parts = listOfNotNull(
        item.year.ifEmpty { null },
        item.rating.ifEmpty { null }?.takeIf { it != "0" && it != "0.0" }?.let { "★ $it" },
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
            Text(shelf.title, color = Color.White, fontSize = if (isTv) 20.sp else 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (shelf.cat?.locked == true) Text("  🔒", fontSize = 14.sp)
            if (!isTv && shelf.cat != null) {
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
                    ChannelTile(it, tileW, repo.isFav(it), Modifier.focusRequester(fr), { onOpen(it) }, { onMenu(it) }) { repo.lastFocus[screen] = k }
                } else {
                    PosterCard(it, posterW, shelf.progress[it.key], Modifier.focusRequester(fr), { onOpen(it) }, { onMenu(it) },
                        fav = repo.isFav(it)) { repo.lastFocus[screen] = k }
                }
            }
            if (onSeeAll != null && shelf.items.isNotEmpty()) {
                item(key = "seeall") {
                    Box(
                        Modifier.width(posterW).aspectRatio(2f / 3f).focusCard(onSeeAll).background(Pal.Card),
                        contentAlignment = Alignment.Center
                    ) { Text("See all  ›", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun PosterCard(item: Item, width: Dp, progress: Float?, modifier: Modifier, onClick: () -> Unit, onMenu: () -> Unit,
               fav: Boolean = false, onFocused: () -> Unit = {}) {
    val name = Names.title(item.name)
    Column(Modifier.width(width)) {
        Box(modifier.fillMaxWidth().aspectRatio(2f / 3f).focusCard(onClick, onMenu, onFocused)) {
            Poster(item.img, name, Modifier.fillMaxSize())
            if (item.isSeries) {
                Text("SERIES", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Pal.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
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
        Text(if (item.season.isNotEmpty()) "${item.season} · $name" else name, color = Color(0xFFE0E0E0), fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        Text(item.name, color = Color(0xFFE0E0E0), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Movie / series page: backdrop, poster, info, Play / Resume, My List, seasons and episodes. */
@Composable
fun DetailScreen(repo: Repo, item: Item, play: (List<Item>, Int) -> Unit, onBack: () -> Unit, tick: Int) {
    val isTv = rememberIsTv()
    val name = Names.title(item.name)
    var eps by remember(item.key) { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember(item.key) { mutableStateOf(item.isSeries) }
    var msg by remember(item.key) { mutableStateOf("") }
    var fav by remember(item.key) { mutableStateOf(repo.isFav(item)) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(item.key, reload) {
        if (item.isSeries) {
            loading = true
            msg = ""
            val r = withContext(Dispatchers.IO) { runCatching { repo.api.episodes(item) } }
            eps = r.getOrDefault(emptyList())
            msg = r.exceptionOrNull()?.message ?: if (eps.isEmpty()) "No episodes found for this series" else ""
            loading = false
        }
    }
    val seasons = remember(eps) { eps.map { it.season }.distinct() }
    var season by remember(item.key) { mutableStateOf<String?>(null) }
    val cont = remember(tick) { repo.store.continueList().associate { it.first.key to it.second } }
    val resumeEp = eps.indexOfFirst { it.key in cont }
    val curSeason = season ?: (if (resumeEp >= 0) eps[resumeEp].season else null) ?: seasons.firstOrNull() ?: ""
    val shown = if (seasons.size > 1) eps.filter { it.season == curSeason } else eps
    val (rp, rd) = remember(tick, item.key) { repo.store.resumeInfo(item) }
    val canResume = !item.isSeries && rp > 30_000 && (rd <= 0 || rp < rd * 0.95)

    fun start(list: List<Item>, i: Int) {
        val e = repo.gate(item.locked)
        if (e != null) msg = e else play(list, i)
    }

    val playFr = remember { FocusRequester() }
    LaunchedEffect(item.key, loading) { if (isTv && !loading) { delay(100); runCatching { playFr.requestFocus() } } }
    val hPad = if (isTv) 48.dp else 16.dp
    val epW = if (isTv) 180.dp else 124.dp

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Poster(item.img, name, Modifier.fillMaxWidth().fillMaxHeight(0.7f), ContentScale.Crop, initialsSize = 1)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x990B0B0F), 0.55f to Pal.Bg)))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = hPad, vertical = if (isTv) 32.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 14.dp)) {
                    Poster(item.img, name, Modifier.width(if (isTv) 200.dp else 116.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, color = Color.White, fontSize = if (isTv) 34.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        MetaLine(item)
                        if (item.desc.isNotEmpty()) Text(item.desc, color = Color(0xFFDDDDDD), fontSize = if (isTv) 16.sp else 14.sp,
                            maxLines = if (isTv) 6 else 8, overflow = TextOverflow.Ellipsis)
                        if (msg.isNotEmpty()) Text(msg, color = Pal.Warn, fontSize = 14.sp, maxLines = 4)
                        Spacer(Modifier.height(4.dp))
                        FlowRowCompat {
                            when {
                                item.isSeries && eps.isNotEmpty() -> {
                                    val i = if (resumeEp >= 0) resumeEp else 0
                                    val e = eps[i]
                                    val label = listOf(e.season, Names.title(e.name)).filter { it.isNotEmpty() }.joinToString(" · ")
                                    PillButton((if (resumeEp >= 0) "▶  Resume " else "▶  Play ") + label, { start(eps, i) }, Modifier.focusRequester(playFr), primary = true)
                                }
                                item.isSeries && loading -> PillButton("Loading episodes…", {}, Modifier.focusRequester(playFr))
                                item.isSeries -> PillButton("↻  Retry", { reload++ }, Modifier.focusRequester(playFr))
                                canResume -> {
                                    PillButton("▶  Resume from ${fmt(rp)}", { start(listOf(item), 0) }, Modifier.focusRequester(playFr), primary = true)
                                    PillButton("↺  Start over", { repo.store.removeResume(item.key); start(listOf(item), 0) })
                                }
                                else -> PillButton("▶  Play", { start(listOf(item), 0) }, Modifier.focusRequester(playFr), primary = true)
                            }
                            PillButton(if (fav) "★  In My List" else "☆  Add to My List", { repo.toggleFav(item); fav = repo.isFav(item) })
                            PillButton("Back", onBack)
                        }
                    }
                }
            }
            if (item.isSeries && loading) item { CircularProgressIndicator(color = Pal.Accent, modifier = Modifier.padding(16.dp)) }
            if (seasons.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp), modifier = Modifier.focusRestorer()) {
                    items(seasons) { s -> PillButton(s.ifEmpty { "Episodes" }, { season = s }, selected = s == curSeason, size = 15) }
                }
            }
            if (shown.isNotEmpty()) item {
                Text(if (seasons.size > 1) "$curSeason · ${shown.size} episodes" else "${shown.size} episodes",
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
            itemsIndexed(shown, key = { i, e -> "$i|${e.key}" }) { _, e ->
                val all = eps.indexOf(e)
                val p = cont[e.key]
                val d = if (p != null) repo.store.resumeInfo(e).second else 0L
                Row(
                    Modifier.fillMaxWidth().focusCard({ start(eps, all) }, scale = 1.02f).background(Color(0x22FFFFFF)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(epW).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                        Poster(e.img.ifEmpty { item.img }, Names.title(e.name), Modifier.fillMaxSize(), initialsSize = 18)
                        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                        Text("▶", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
                        if (p != null && d > 0) {
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x88FFFFFF)))
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((p.toFloat() / d).coerceIn(0f, 1f)).height(3.dp).background(Pal.Accent))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(Names.title(e.name), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (e.length.isNotEmpty()) Text(e.length, color = Pal.Dim, fontSize = 12.sp)
                        if (e.desc.isNotEmpty() && e.desc != item.desc) Text(e.desc, color = Pal.Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
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
    val screenKey = "grid:${cat.id}"

    fun next() {
        if (busy || !more) return
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
    LaunchedEffect(cat.id) { if (page == 0) next() }

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
                    Text(Names.cat(cat.title), color = Color.White, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (more) "${items.size}+ titles" else "${items.size} titles", color = Pal.Dim, fontSize = 13.sp)
                    if (msg.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = Pal.Warn, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        PillButton("Retry", { more = true; next() }, size = 13)
                    }
                }
            }
            gridItemsIndexed(items, key = { i, it -> "$i|${it.key}" }) { i, it ->
                val k = it.key
                val fr = remember { FocusRequester() }
                RestoreFocus(repo, screenKey, k, fr)
                PosterCard(it, posterW, null, Modifier.then(if (i == 0) Modifier.focusRequester(firstFr) else Modifier).focusRequester(fr),
                    { if (it.isEpisode()) play(listOf(it), 0) else open(it) }, { menu = it }, fav = repo.isFav(it)) { repo.lastFocus[screenKey] = k }
                // Paging: ask for the next page when the last few posters come into view
                if (i >= items.size - 6 && more && !busy) LaunchedEffect(items.size) { next() }
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
    }
}

/** Browse all categories: grid with a sample poster each, filter box, open / move / hide / unhide. */
@Composable
fun CategoriesScreen(repo: Repo, go: (Screen) -> Unit) {
    val isTv = rememberIsTv()
    var kind by remember { mutableStateOf("vod") }
    var showHidden by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Cat?>(null) }
    val unlocked = repo.gate(true) == null
    val all = repo.ordered(kind)
    val q = repo.catFilter.trim()
    val cats = all.filter { c ->
        (showHidden || !repo.isHidden(kind, c.id)) &&
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
                    Text("All categories", color = Color.White, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (isTv) "OK opens a category. Menu (or long-press OK) moves it up / down or hides it. The order is used on Home, Movies, Series and Live TV."
                        else "Tap to open. Long-press to move up / down or hide. The order is used on Home, Movies, Series and Live TV.",
                        color = Pal.Dim, fontSize = 13.sp)
                    FlowRowCompat {
                        PillButton("Movies & Series", { kind = "vod" }, Modifier.focusRequester(firstFr), selected = kind == "vod", size = 14)
                        PillButton("Live TV", { kind = "itv" }, selected = kind == "itv", size = 14)
                        PillButton(if (showHidden) "Showing hidden" else "Show hidden", { showHidden = !showHidden }, selected = showHidden, size = 14)
                        PillButton("Reset order", { repo.resetOrder(kind) }, size = 14)
                    }
                    TextField(
                        value = repo.catFilter, onValueChange = { repo.catFilter = it }, singleLine = true,
                        placeholder = { Text("Filter categories…", color = Pal.Dim) },
                        modifier = Modifier.fillMaxWidth(if (isTv) 0.5f else 1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Pal.Surface, unfocusedContainerColor = Pal.Surface,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        )
                    )
                    Text("${cats.size} of ${all.size}" + (if (q.isNotEmpty()) " match \"$q\"" else ""), color = Pal.Dim, fontSize = 12.sp)
                }
            }
            gridItems(cats, key = { "$kind:${it.id}" }) { c ->
                val hidden = repo.isHidden(kind, c.id)
                val locked = c.locked && !unlocked
                if (kind == "vod" && !locked) LaunchedEffect(c.id) { repo.loadRow(c) }
                val sample = if (kind == "vod") repo.rows[c.id]?.firstOrNull { it.img.isNotEmpty() }?.img ?: ""
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
                        Text((if (c.locked) "🔒 " else "") + label, color = if (hidden) Color(0xFF999999) else Color.White, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val n = if (kind == "itv") repo.channels.count { it.cat == c.id } else -1
                        Text(listOfNotNull("#${all.indexOf(c) + 1}", if (n >= 0) "$n channels" else null, if (hidden) "hidden" else null).joinToString("  ·  "),
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
