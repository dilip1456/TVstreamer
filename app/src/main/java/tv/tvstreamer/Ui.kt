@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WARN = Color(0xFFFFB74D)
private val DIM = Color(0xFF9AA0B5)

/** Focusable, D-pad friendly. Menu key or long press fires onMenu. */
fun Modifier.tv(onClick: () -> Unit, onMenu: (() -> Unit)? = null): Modifier = composed {
    var f by remember { mutableStateOf(false) }
    this
        .onFocusChanged { f = it.isFocused }
        .background(if (f) Color(0xFF3A6BFF) else Color(0xFF1E1E26), RoundedCornerShape(8.dp))
        .onKeyEvent { e ->
            if (onMenu != null && e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false
        }
        .combinedClickable(onClick = onClick, onLongClick = onMenu)
}

@Composable
fun Label(text: String, size: Int = 18, color: Color = Color.White, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text, color = color, fontSize = size.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
fun Row1(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, onMenu: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().tv(onClick, onMenu).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Label(text)
    }
}

@Composable
fun HomeRow(title: String, list: List<Item>, play: (List<Item>, Int) -> Unit) {
    if (list.isEmpty()) return
    Column {
        Label(title, 18, DIM)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(list) { i, x ->
                Box(Modifier.width(220.dp).tv({ if (x.kind == "vod") play(listOf(x), 0) else play(list, i) }).padding(12.dp)) {
                    Label(x.name, 16)
                }
            }
        }
    }
}

@Composable
fun Home(repo: Repo, go: (Screen) -> Unit, play: (List<Item>, Int) -> Unit, tick: Int) {
    val cont = remember(tick) { repo.store.continueList().map { it.first } }
    val recent = remember(tick) { repo.store.list("recent") }
    val favs = repo.favs.filter { !it.locked }
    val isTv = LocalContext.current.resources.configuration.smallestScreenWidthDp >= 600
    Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 64.dp else 16.dp, vertical = if (isTv) 40.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val ctx = LocalContext.current
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        Label("TVStreamer v$ver", 26)
        if (repo.status.isNotEmpty()) Label(repo.status, 16, WARN, maxLines = 5)
        val scope = rememberCoroutineScope()
        var updateMsg by remember { mutableStateOf("") }
        Row1("Check for updates", {
            updateMsg = "Checking..."
            scope.launch {
                val code = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode } catch (e: Exception) { 1 }
                updateMsg = Updater.checkAndUpdate(ctx, code)
            }
        })
        if (updateMsg.isNotEmpty()) Label(updateMsg, 16, WARN, maxLines = 4)
        val tabs = listOf<Pair<String, Screen>>(
            "Live TV" to Screen.Live, "Movies" to Screen.Movies,
            "Search" to Screen.Search, "Settings" to Screen.Settings
        )
        // Always show all buttons. On phone they stack; on TV they sit in a row.
        if (isTv) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tabs.forEach { (t, s) ->
                    Box(Modifier.width(170.dp).tv({ go(s) }).padding(16.dp)) { Label(t, 20) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                tabs.forEach { (t, s) ->
                    Box(Modifier.fillMaxWidth().tv({ go(s) }).padding(14.dp)) { Label(t, 18) }
                }
            }
        }
        HomeRow("Continue watching", cont, play)
        HomeRow("Recent channels", recent, play)
        HomeRow("Favorites", favs, play)
    }
}

@Composable
fun Live(repo: Repo, play: (List<Item>, Int) -> Unit) {
    var group by remember { mutableStateOf(repo.lastLiveCat.ifEmpty { "All" }) }
    var msg by remember { mutableStateOf("") }
    val cats = repo.visibleLive()
    val groups = remember(cats) { groupCategories(cats) }
    val currentGroup = groups.keys.find { it == group } ?: groups.keys.firstOrNull() ?: "All"
    val groupCats = groups[currentGroup] ?: emptyList()
    var selCat by remember {
        mutableStateOf(groupCats.find { it.id == repo.lastLiveCatId } ?: groupCats.firstOrNull())
    }
    var sortAz by remember { mutableStateOf(false) }
    val channels = remember(selCat, repo.channels, sortAz) {
        selCat?.let { c -> repo.channels.filter { it.cat == c.id && !repo.isChannelHidden(it) }.let { if (sortAz) it.sortedBy { ch -> ch.name } else it } } ?: emptyList()
    }

    var menuTarget by remember { mutableStateOf<Any?>(null) }
    var asList by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = if (LocalContext.current.resources.configuration.smallestScreenWidthDp >= 600) 32.dp else 12.dp, vertical = 12.dp)) {
        Label("Live TV", 28)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row1(if (asList) "Icons" else "List", { asList = !asList }, Modifier.width(100.dp))
            Row1(if (sortAz) "A-Z" else "Default", { sortAz = !sortAz }, Modifier.width(100.dp))
        }
        val favItems = repo.favs
        if (favItems.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label("Favorites", 16, Color(0xFFFFD54F))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(favItems) { i, ch ->
                    ChannelCard(ch, true, { play(favItems, i) }, { menuTarget = ch })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (repo.customCats.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            repo.customCats.forEach { (name, items) ->
                Label(name, 16, DIM)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(items) { i, ch ->
                        ChannelCard(ch, repo.isFav(ch), { play(items, i) }, { repo.toggleFav(ch) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        // Language / region groups
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(groups.keys.toList()) { g ->
                Box(
                    Modifier.tv({ group = g; selCat = groups[g]?.firstOrNull(); repo.lastLiveCat = g }, onMenu = { menuTarget = g })
                        .background(if (g == currentGroup) Color(0xFF3A6BFF) else Color(0xFF1E1E26), RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) { Label(g, 16) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Categories in this group as large selectable chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groupCats) { c ->
                val selected = selCat?.id == c.id
                Box(
                    Modifier.tv({
                        val e = repo.gate(c.locked)
                        if (e == null) { selCat = c; repo.lastLiveCatId = c.id; msg = "" } else msg = e
                    }, onMenu = { menuTarget = c })
                        .background(if (selected) Color(0xFF2A4A9F) else Color(0xFF1A1A22), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Label((if (c.locked) "🔒 " else "") + c.title, 15) }
            }
        }
        if (msg.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Label(msg, 14, WARN) }
        Spacer(Modifier.height(12.dp))
        // Netflix-style grid of channel thumbnails
        var showHidden by remember { mutableStateOf(false) }
        if (selCat != null) {
            Row1(if (showHidden) "Hide hidden" else "Show hidden in this folder", { showHidden = !showHidden }, Modifier.width(220.dp))
            if (showHidden) {
                val hiddenHere = repo.channels.filter { it.cat == selCat?.id && repo.isChannelHidden(it) }
                if (hiddenHere.isEmpty()) Label("No hidden channels here", 14, DIM)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(120.dp)) {
                    items(hiddenHere) { ch ->
                        Row1("Hidden: ${ch.name}", { repo.toggleHideChannel(ch) })
                    }
                }
            }
        }
        if (channels.isEmpty()) {
            Label("Select a category", 16, DIM)
        } else {
            if (asList) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(channels) { i, ch ->
                        Row1((if (repo.isFav(ch)) "★ " else "") + ch.name, {
                            repo.lastLiveIndex = i
                            play(channels, i)
                        }, onMenu = { menuTarget = ch })
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(channels) { i, ch ->
                        ChannelCard(ch, repo.isFav(ch), {
                            repo.lastLiveIndex = i
                            play(channels, i)
                        }, { menuTarget = ch })
                    }
                }
            }
        }
        // Popup menu for group / category / channel
        if (menuTarget != null) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.background(Color(0xFF1A1A22), RoundedCornerShape(12.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (val t = menuTarget) {
                        is String -> {
                            Label("Group: $t", 18)
                            Row1("Close", { menuTarget = null })
                        }
                        is Cat -> {
                            Label(t.title, 18)
                            Row1(if ("itv:${t.id}" in repo.hidden) "Show folder" else "Hide folder", { repo.toggleHide("itv", t.id); menuTarget = null })
                            Row1("Close", { menuTarget = null })
                        }
                        is Item -> {
                            Label(t.name, 18)
                            Row1(if (repo.isFav(t)) "Remove favorite" else "Add favorite", { repo.toggleFav(t); menuTarget = null })
                            Row1("Move to My Channels", { repo.moveToCustom(t, "My Channels"); menuTarget = null })
                            Row1(if (repo.isChannelHidden(t)) "Unhide channel" else "Hide channel", { repo.toggleHideChannel(t); menuTarget = null })
                            Row1("Close", { menuTarget = null })
                        }
                        else -> Row1("Close", { menuTarget = null })
                    }
                }
            }
        }
    }
}

private fun groupCategories(cats: List<Cat>): Map<String, List<Cat>> {
    val order = listOf("English", "Indian", "Spanish", "Portuguese", "French", "Arabic", "Asian", "European", "African", "Other")
    val map = mutableMapOf<String, MutableList<Cat>>()
    for (c in cats) {
        val t = c.title.uppercase()
        val g = when {
            "ENGLISH" in t || "UK" in t || "USA" in t || "US " in t || "CANADA" in t || "AUSTRALIA" in t -> "English"
            "HINDI" in t || "KANNADA" in t || "TAMIL" in t || "TELUGU" in t || "MALAYALAM" in t ||
            "PUNJABI" in t || "BENGALI" in t || "MARATHI" in t || "GUJARATI" in t || "INDIAN" in t || "INDIA" in t -> "Indian"
            "SPANISH" in t || "ESPAÑOL" in t || "MEXICO" in t || "ARGENTINA" in t || "COLOMBIA" in t ||
            "CHILE" in t || "PERU" in t || "ECUADOR" in t || "PARAGUAY" in t -> "Spanish"
            "PORTUGUESE" in t || "BRASIL" in t || "BRAZIL" in t || "PORTUGAL" in t -> "Portuguese"
            "FRENCH" in t || "FRANCE" in t -> "French"
            "ARABIC" in t || "ARAB" in t -> "Arabic"
            "CHINESE" in t || "JAPAN" in t || "KOREA" in t || "FILIPINO" in t || "THAI" in t || "VIET" in t -> "Asian"
            "POLISH" in t || "GERMAN" in t || "ITALIAN" in t || "DUTCH" in t || "RUSSIAN" in t || "TURK" in t || "GREEK" in t -> "European"
            "AFRICAN" in t || "AFRICA" in t -> "African"
            else -> "Other"
        }
        map.getOrPut(g) { mutableListOf() }.add(c)
    }
    return order.filter { it in map }.associateWith { map[it]!! }
}

@Composable
private fun ChannelCard(ch: Item, fav: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val hue = (ch.name.hashCode() and 0xFFFFFF) % 360
    val bg = Color.hsl(hue.toFloat(), 0.45f, 0.28f)
    Column(
        Modifier.width(170.dp).tv(onClick, onMenu).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(140.dp, 90.dp).background(bg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Label(ch.name.take(2).uppercase(), 28)
            if (fav) Label("★", 16, Color.Yellow, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Label(ch.name, 13, maxLines = 2)
        if (ch.num > 0) Label("#${ch.num}", 11, DIM)
    }
}

@Composable
fun Movies(repo: Repo, play: (List<Item>, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var sel by remember { mutableStateOf<Cat?>(null) }
    var movies by remember { mutableStateOf(listOf<Item>()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var asList by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<Any?>(null) }
    val cats = repo.visibleVod()
    val groups = remember(cats) { groupCategories(cats) }
    var group by remember { mutableStateOf(groups.keys.firstOrNull() ?: "All") }
    val groupCats = groups[group] ?: emptyList()
    val vodFavs = repo.favs.filter { it.kind == "vod" }

    fun fetch(c: Cat, p: Int) {
        scope.launch {
            busy = true
            try {
                val r = withContext(Dispatchers.IO) { repo.api.vod(c.id, p) }
                movies = if (p == 1) r.first else movies + r.first
                page = p
                more = r.second
                msg = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { msg = e.message ?: "Load failed" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Label("Movies", 28)
        Spacer(Modifier.height(8.dp))

        // Favorites section
        if (vodFavs.isNotEmpty()) {
            Label("Favorites", 16, Color(0xFFFFD54F))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(vodFavs) { i, m ->
                    MovieCard(m, true, {
                        if (m.num == -1) {
                            scope.launch {
                                busy = true
                                try {
                                    val eps = withContext(Dispatchers.IO) { repo.api.seasons(m.id) }
                                    if (eps.isNotEmpty()) { movies = eps; sel = Cat(m.id, m.name, false); msg = "Episodes of ${m.name}" }
                                    else msg = "No episodes"
                                } catch (e: Exception) { msg = e.message ?: "Failed" }
                                busy = false
                            }
                        } else play(listOf(m), 0)
                    }, { menuTarget = m })
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Main group folders
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups.keys.toList()) { g ->
                Box(
                    Modifier.tv({ group = g })
                        .background(if (g == group) Color(0xFF3A6BFF) else Color(0xFF1E1E26), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Label(g, 15) }
            }
        }
        Spacer(Modifier.height(10.dp))

        // Sub-folders (categories) in this group
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groupCats) { c ->
                val selected = sel?.id == c.id
                Box(
                    Modifier.tv({
                        val e = repo.gate(c.locked)
                        if (e == null) { sel = c; movies = emptyList(); fetch(c, 1) } else msg = e
                    }, onMenu = { menuTarget = c })
                        .background(if (selected) Color(0xFF2A4A9F) else Color(0xFF1A1A22), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Label((if (c.locked) "🔒 " else "") + c.title, 14) }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row1(if (asList) "Icons" else "List", { asList = !asList }, Modifier.width(90.dp))
            if (sel != null) Label(sel!!.title, 16, DIM)
        }
        if (msg.isNotEmpty()) Label(msg, 14, WARN)
        if (busy) Label("Loading...", 14, DIM)
        Spacer(Modifier.height(6.dp))

        if (sel == null) {
            Label("Select a folder", 16, DIM)
        } else if (asList) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(movies) { i, m ->
                    Row1((if (repo.isFav(m)) "★ " else "") + (if (m.num == -1) "[Series] " else "") + m.name, {
                        if (m.num == -1) {
                            scope.launch {
                                busy = true
                                try {
                                    val eps = withContext(Dispatchers.IO) { repo.api.seasons(m.id) }
                                    if (eps.isNotEmpty()) { movies = eps; sel = Cat(m.id, m.name, false); msg = "Episodes" }
                                    else msg = "No episodes"
                                } catch (e: Exception) { msg = e.message ?: "Failed" }
                                busy = false
                            }
                        } else play(listOf(m), 0)
                    }, onMenu = { menuTarget = m })
                    if (i == movies.size - 1 && more && !busy) {
                        LaunchedEffect(movies.size) { sel?.let { fetch(it, page + 1) } }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(movies) { i, m ->
                    MovieCard(m, repo.isFav(m), {
                        if (m.num == -1) {
                            scope.launch {
                                busy = true
                                try {
                                    val eps = withContext(Dispatchers.IO) { repo.api.seasons(m.id) }
                                    if (eps.isNotEmpty()) { movies = eps; sel = Cat(m.id, m.name, false); msg = "Episodes of ${m.name}" }
                                    else msg = "No episodes"
                                } catch (e: Exception) { msg = e.message ?: "Failed" }
                                busy = false
                            }
                        } else play(listOf(m), 0)
                    }, { menuTarget = m })
                    if (i == movies.size - 1 && more && !busy) {
                        LaunchedEffect(movies.size) { sel?.let { fetch(it, page + 1) } }
                    }
                }
            }
        }

        // Popup menu
        if (menuTarget != null) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.background(Color(0xFF1A1A22), RoundedCornerShape(12.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (val t = menuTarget) {
                        is Cat -> {
                            Label(t.title, 18)
                            Row1(if ("vod:${t.id}" in repo.hidden) "Show folder" else "Hide folder", { repo.toggleHide("vod", t.id); menuTarget = null })
                            Row1("Close", { menuTarget = null })
                        }
                        is Item -> {
                            Label(t.name, 18)
                            Row1(if (repo.isFav(t)) "Remove favorite" else "Add favorite", { repo.toggleFav(t); menuTarget = null })
                            Row1("Close", { menuTarget = null })
                        }
                        else -> Row1("Close", { menuTarget = null })
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieCard(m: Item, fav: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val hue = (m.name.hashCode() and 0x7FFFFFFF) % 360
    Column(
        Modifier.width(150.dp).tv(onClick, onMenu)
            .background(Color.hsl(hue.toFloat(), 0.4f, 0.18f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(90.dp).background(Color.hsl(hue.toFloat(), 0.5f, 0.28f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Label(if (m.num == -1) "Series" else "Movie", 12, Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Label((if (fav) "★ " else "") + m.name, 13, maxLines = 2)
    }
}

@Composable
fun Search(repo: Repo, play: (List<Item>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(listOf<Item>()) }
    var vod by remember { mutableStateOf(listOf<Item>()) }
    var busy by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(250); runCatching { fr.requestFocus() } }
    LaunchedEffect(q) {
        delay(300)
        if (q.isBlank()) { live = emptyList(); vod = emptyList(); return@LaunchedEffect }
        busy = true
        val r = withContext(Dispatchers.IO) { runCatching { repo.search(q) }.getOrDefault(emptyList<Item>() to emptyList()) }
        live = r.first
        vod = r.second
        busy = false
    }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("Hold the mic and say: hindi movies 2023") },
            modifier = Modifier.fillMaxWidth().focusRequester(fr)
        )
        if (busy) Label("Searching...", 16, DIM)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(live) { i, c -> Row1("LIVE   ${c.name}", { play(live, i) }, onMenu = { repo.toggleFav(c) }) }
            itemsIndexed(vod) { _, m -> Row1("MOVIE   ${m.name}", { play(listOf(m), 0) }, onMenu = { repo.toggleFav(m) }) }
        }
    }
}

@Composable
fun SettingsScreen(repo: Repo) {
    val s = repo.store
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var portal by remember { mutableStateOf(s.portal) }
    var mac by remember { mutableStateOf(s.mac) }
    var serial by remember { mutableStateOf(s.serial) }
    var pw by remember { mutableStateOf(s.password) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        portal = s.portal
        mac = s.mac
        serial = s.serial
        pw = s.password
    }
    val statusText = note.ifEmpty { repo.status }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Label("Settings", 32) }
        if (statusText.isNotEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF2A1F1A), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Label(statusText, 18, WARN, maxLines = 8)
                }
            }
        }
        item { SettingField("Portal URL", portal) { portal = it } }
        item { SettingField("MAC address", mac) { mac = it } }
        item { SettingField("Serial number", serial) { serial = it } }
        item { SettingField("Locked section password", pw, password = true) { pw = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row1("Hide keyboard", { keyboard?.hide() }, Modifier.weight(1f))
                Row1("Save and reload", {
                    keyboard?.hide()
                    s.portal = portal
                    s.mac = mac.trim().uppercase()
                    s.serial = serial.trim()
                    s.password = pw
                    note = "Saved. Reloading..."
                    scope.launch {
                        repo.api.reset()
                        repo.load()
                        note = repo.status.ifEmpty { "Loaded" }
                    }
                }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row1("Clear password", { s.password = ""; pw = ""; note = "Password cleared" }, Modifier.weight(1f))
                Row1("Clear history", { s.clearHistory(); note = "History cleared" }, Modifier.weight(1f))
            }
        }
        item {
            var showHide by remember { mutableStateOf(false) }
            Row1("Show / hide categories", { showHide = true })
            if (showHide) {
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.background(Color(0xFF1A1A22), RoundedCornerShape(16.dp)).padding(20.dp).widthIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Label("Show or hide", 20)
                        Label("Tap a group or category", 13, DIM)
                        repo.liveCats.take(20).forEach { c ->
                            Row1((if ("itv:${c.id}" in repo.hidden) "Hidden  " else "Shown   ") + c.title,
                                { repo.toggleHide("itv", c.id) })
                        }
                        Row1("Close", { showHide = false })
                    }
                }
            }
        }
    }
}

@Composable
fun SettingField(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label, 13, DIM)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().height(52.dp)
                .onFocusChanged { if (it.isFocused) keyboard?.show() else keyboard?.hide() },
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1E1E26),
                unfocusedContainerColor = Color(0xFF1A1A22),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
            placeholder = { Text("Tap to enter", color = DIM, fontSize = 14.sp) }
        )
    }
}

private fun fmt(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

private fun saveNow(repo: Repo, item: Item, exo: ExoPlayer) {
    if (item.kind == "vod" && exo.duration > 0) repo.store.saveResume(item, exo.currentPosition, exo.duration)
}

@Composable
fun PlayerScreen(repo: Repo, list: List<Item>, start: Int) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(start) }
    var err by remember { mutableStateOf("") }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var hud by remember { mutableIntStateOf(0) }
    var showHud by remember { mutableStateOf(true) }
    val item = list[idx]
    val cur by rememberUpdatedState(item)
    val fr = remember { FocusRequester() }
    val exo = remember {
        val cookie = repo.api.cookie()
        val auth = repo.api.token
        val headers = mutableMapOf("Cookie" to cookie, "X-User-Agent" to "Model: MAG250; Link: WiFi")
        if (auth.isNotEmpty()) headers["Authorization"] = "Bearer $auth"
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setUserAgent(UA)
                        .setDefaultRequestProperties(headers)
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(20000)
                )
            ).build()
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { err = "Playback error: ${error.errorCodeName} ${error.cause?.message ?: ""}" }
        }
        exo.addListener(l)
        onDispose { saveNow(repo, cur, exo); exo.release() }
    }

    LaunchedEffect(idx) {
        err = ""
        try {
            val url = withContext(Dispatchers.IO) { repo.api.link(item) }
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            if (item.kind == "vod") {
                val p = repo.store.resumePos(item)
                if (p > 5000) exo.seekTo(p)
            }
            exo.playWhenReady = true
            repo.markPlayed(item)
            hud++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: "Could not open stream"
        }
    }

    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            delay(500)
            pos = exo.currentPosition
            dur = exo.duration.coerceAtLeast(0)
            n++
            if (n % 20 == 0) saveNow(repo, cur, exo)
        }
    }

    LaunchedEffect(hud) { showHud = true; delay(3500); showHud = false }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    var drag by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(fr).focusable()
            .pointerInput(list.size) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (item.kind == "itv" && list.size > 1) {
                            if (drag < -100) idx = (idx + 1) % list.size
                            else if (drag > 100) idx = (idx - 1 + list.size) % list.size
                        }
                        drag = 0f
                        hud++
                    },
                    onVerticalDrag = { _, d -> drag += d }
                )
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val live = item.kind == "itv"
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> {
                        if (live && list.size > 1) idx = (idx + 1) % list.size
                        hud++; true
                    }
                    Key.DirectionDown, Key.ChannelDown -> {
                        if (live && list.size > 1) idx = (idx - 1 + list.size) % list.size
                        hud++; true
                    }
                    Key.DirectionLeft -> {
                        if (!live) exo.seekTo((exo.currentPosition - 10000).coerceAtLeast(0))
                        hud++; true
                    }
                    Key.DirectionRight -> {
                        if (!live) exo.seekTo(exo.currentPosition + 10000)
                        hud++; true
                    }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        exo.playWhenReady = !exo.playWhenReady
                        hud++; true
                    }
                    else -> false
                }
            }
    ) {
        var menu by remember { mutableStateOf(false) }
        var fit by remember { mutableStateOf("fit") } // fit = no clip, fill = crop
        var barPos by remember { mutableStateOf("bottom") }
        var fullScreen by remember { mutableStateOf(false) }
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    player = exo
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view ->
                view.resizeMode = if (fit == "fill") AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize()
        )
        // Error overlay
        if (err.isNotEmpty()) {
            Label(err, 16, Color(0xFFFF8A80), Modifier.align(Alignment.Center).padding(16.dp), maxLines = 4)
        }
        // Floating controls
        if (!fullScreen) {
            val fav = repo.isFav(item)
            val buttons = listOf(
                (if (fav) "★" else "☆") to { repo.toggleFav(item) },
                (if (exo.isPlaying) "⏸" else "▶") to { exo.playWhenReady = !exo.playWhenReady },
                "Fit" to { fit = "fit" },
                "Fill" to { fit = "fill" },
                "Full" to { fullScreen = true },
                "Hide" to { if (item.kind == "itv") repo.toggleHideChannel(item) },
                "Move" to { barPos = when (barPos) { "bottom" -> "left"; "left" -> "right"; else -> "bottom" } }
            )
            val align = when (barPos) {
                "left" -> Alignment.CenterStart
                "right" -> Alignment.CenterEnd
                else -> Alignment.BottomCenter
            }
            val pad = when (barPos) {
                "left" -> Modifier.padding(start = 12.dp)
                "right" -> Modifier.padding(end = 12.dp)
                else -> Modifier.padding(bottom = 20.dp)
            }
            if (barPos == "bottom") {
                Row(
                    Modifier.align(align).then(pad)
                        .background(Color(0xE6121218), RoundedCornerShape(28.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    buttons.forEach { (label, action) ->
                        Box(Modifier.tv(action).padding(horizontal = 10.dp, vertical = 6.dp)) { Label(label, 14) }
                    }
                }
            } else {
                Column(
                    Modifier.align(align).then(pad)
                        .background(Color(0xE6121218), RoundedCornerShape(28.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    buttons.forEach { (label, action) ->
                        Box(Modifier.tv(action).padding(horizontal = 10.dp, vertical = 6.dp)) { Label(label, 14) }
                    }
                }
            }
        }
        if (fullScreen) {
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp).tv({ fullScreen = false })) {
                Label("Exit full", 14, Color.White)
            }
        } else if (showHud || menu) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color(0xAA000000)).padding(16.dp)
            ) {
                Label(item.name, 20)
                if (err.isNotEmpty()) Label(err, 16, Color(0xFFFF8A80))
            }
        }
        if (menu) {
            Column(
                Modifier.align(Alignment.Center).background(Color(0xDD111118), RoundedCornerShape(12.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Label("Channel options", 20)
                Row1("Fit to screen", { fit = "fit"; menu = false })
                Row1("Stretch / fill", { fit = "fill"; menu = false })
                Row1(if (repo.isFav(item)) "Remove favorite" else "Add favorite", { repo.toggleFav(item); menu = false })
                Row1("Move to My Channels", {
                    repo.moveToCustom(item, "My Channels")
                    menu = false
                })
                Row1("Close menu", { menu = false })
            }
        }
    }
}
