@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
fun Modifier.tv(onClick: () -> Unit, onMenu: (() -> Unit)? = null, onFocus: (() -> Unit)? = null): Modifier = composed {
    var f by remember { mutableStateOf(false) }
    this
        .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus?.invoke() }
        .background(if (f) Color(0xFF2E2710) else Color(0xFF1E1E26), RoundedCornerShape(8.dp))
        .then(if (f) Modifier.border(2.dp, Pal.Gold, RoundedCornerShape(8.dp)) else Modifier)
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
fun Row1(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, onMenu: (() -> Unit)? = null, onFocus: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().tv(onClick, onMenu, onFocus).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Label(text)
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
                        }, onMenu = { menuTarget = ch }, onFocus = { Keys.focused = ch })
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

/** The Live TV group (English, Indian, …) a category is shown under. */
fun liveGroupOf(c: Cat): String = groupCategories(listOf(c)).keys.firstOrNull() ?: ""

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
        Modifier.width(170.dp).tv(onClick, onMenu, { Keys.focused = ch }).padding(10.dp),
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
fun Search(repo: Repo, play: (List<Item>, Int) -> Unit, open: (Item) -> Unit) {
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
            itemsIndexed(vod) { _, m -> Row1((if (m.isSeries) "SERIES   " else "MOVIE   ") + Names.title(m.name), { open(m) }, onMenu = { repo.toggleFav(m) }) }
        }
    }
}

@Composable
fun SettingsScreen(repo: Repo, go: (Screen) -> Unit) {
    val s = repo.store
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var portal by remember { mutableStateOf(s.portal) }
    var mac by remember { mutableStateOf(s.mac) }
    var serial by remember { mutableStateOf(s.serial) }
    var pw by remember { mutableStateOf(s.password) }
    var tmdb by remember { mutableStateOf(s.tmdbKey) }
    var omdb by remember { mutableStateOf(s.omdbKey) }
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
        item {
            var showLog by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row1(if (showLog) "Hide last portal replies" else "Show last portal replies (for stream problems)", { showLog = !showLog })
                if (showLog) Box(Modifier.fillMaxWidth().background(Color(0xFF1A1A22), RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Label(repo.api.debugLog(), 13, DIM, maxLines = 60)
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
        item { Row1("Browse, arrange and hide categories", { go(Screen.Categories) }) }
        item { Row1("Languages for Movies", { go(Screen.Languages("movies", Screen.Settings)) }) }
        item { Row1("Languages for Series", { go(Screen.Languages("series", Screen.Settings)) }) }
        item { Row1("Refresh movie / series library (re-load the language categories)", {
            repo.lib.clear(); for (t in listOf("movies", "series")) repo.lib.selected(t)?.let { repo.lib.ensure(t, it, true) }; note = "Library cleared; it reloads in the background"
        }) }
        item { Row1("Remote buttons (assign actions to remote keys)", { go(Screen.Remote) }) }
        item { Label("Movie & series info (optional)", 18) }
        item {
            Label("Posters, plots, cast and release dates come from TMDB when a TMDB API key is set (free at themoviedb.org > Settings > API). " +
                "An OMDb key (omdbapi.com) adds the IMDb rating. Without keys the portal's own info is used. Keys stay on this device.", 13, DIM, maxLines = 4)
        }
        item { SettingField("TMDB API key (v3 key or v4 read token)", tmdb, password = true) { tmdb = it } }
        item { SettingField("OMDb API key (optional, for IMDb ratings)", omdb, password = true) { omdb = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row1("Save keys and test", {
                    keyboard?.hide()
                    s.tmdbKey = tmdb; s.omdbKey = omdb
                    Tmdb.setKeys(tmdb, omdb)
                    note = "Testing keys..."
                    scope.launch { note = withContext(Dispatchers.IO) { Tmdb.test(tmdb, omdb) } }
                }, Modifier.weight(1f))
                Row1("Clear movie info cache", { Tmdb.clearCache(); note = "Movie info cache cleared" }, Modifier.weight(1f))
            }
        }
        item {
            val n = repo.hiddenChannelCount()
            Row1(if (n > 0) "Unhide all hidden channels ($n)" else "No hidden channels", { if (n > 0) { repo.unhideAllChannels(); note = "All channels shown again" } })
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
