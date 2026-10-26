@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    Box(modifier.fillMaxWidth().tv(onClick, onMenu).padding(horizontal = 14.dp, vertical = 12.dp)) {
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
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val ctx = LocalContext.current
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        Label("TVStreamer v$ver", 26)
        if (repo.status.isNotEmpty()) Label(repo.status, 16, WARN, maxLines = 5)
        val ctx = LocalContext.current
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tabs = listOf<Pair<String, Screen>>(
                "Live TV" to Screen.Live, "Movies" to Screen.Movies,
                "Search" to Screen.Search, "Settings" to Screen.Settings
            )
            tabs.forEach { (t, s) ->
                Box(Modifier.width(170.dp).tv({ go(s) }).padding(16.dp)) { Label(t, 20) }
            }
        }
        HomeRow("Continue watching", cont, play)
        HomeRow("Recent channels", recent, play)
        HomeRow("Favorites", favs, play)
    }
}

@Composable
fun Live(repo: Repo, play: (List<Item>, Int) -> Unit) {
    var sel by remember { mutableStateOf<Cat?>(null) }
    var msg by remember { mutableStateOf("") }
    val cats = repo.visibleLive()
    val list = remember(sel, repo.channels) {
        sel?.let { c -> repo.channels.filter { it.cat == c.id } } ?: emptyList()
    }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(cats) { c ->
                Row1((if (c.locked) "[lock] " else "") + c.title,
                    onClick = {
                        val e = repo.gate(c.locked)
                        if (e == null) { sel = c; msg = "" } else msg = e
                    },
                    onMenu = { repo.toggleHide("itv", c.id) })
            }
        }
        Column(Modifier.weight(1f)) {
            Label("Menu button: favorite a channel, or hide a category", 14, DIM)
            if (msg.isNotEmpty()) Label(msg, 16, WARN)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(list) { i, ch ->
                    Row1((if (repo.isFav(ch)) "* " else "") + "${ch.num}  ${ch.name}",
                        onClick = { play(list, i) }, onMenu = { repo.toggleFav(ch) })
                }
            }
        }
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
    val cats = repo.visibleVod()

    fun fetch(c: Cat, p: Int) {
        scope.launch {
            busy = true
            try {
                val r = withContext(Dispatchers.IO) { repo.api.vod(c.id, p) }
                movies = if (p == 1) r.first else movies + r.first
                page = p
                more = r.second
                msg = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = e.message ?: "Load failed"
            }
            busy = false
        }
    }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(cats) { c ->
                Row1((if (c.locked) "[lock] " else "") + c.title,
                    onClick = {
                        val e = repo.gate(c.locked)
                        if (e == null) { sel = c; movies = emptyList(); fetch(c, 1) } else msg = e
                    },
                    onMenu = { repo.toggleHide("vod", c.id) })
            }
        }
        Column(Modifier.weight(1f)) {
            Label("Menu button: favorite a movie, or hide a category", 14, DIM)
            if (msg.isNotEmpty()) Label(msg, 16, WARN)
            if (busy) Label("Loading...", 16, DIM)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(movies) { i, m ->
                    Row1((if (repo.isFav(m)) "* " else "") + m.name,
                        onClick = { play(listOf(m), 0) }, onMenu = { repo.toggleFav(m) })
                    if (i == movies.size - 1 && more && !busy) {
                        LaunchedEffect(movies.size) { sel?.let { fetch(it, page + 1) } }
                    }
                }
            }
        }
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
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
        item { Label("Categories (select to hide or show)", 20, DIM) }
        items(repo.liveCats) { c ->
            Row1((if ("itv:${c.id}" in repo.hidden) "Hidden  " else "Shown   ") + "TV  " + c.title,
                { repo.toggleHide("itv", c.id) })
        }
        items(repo.vodCats) { c ->
            Row1((if ("vod:${c.id}" in repo.hidden) "Hidden  " else "Shown   ") + "Movies  " + c.title,
                { repo.toggleHide("vod", c.id) })
        }
    }
}

@Composable
fun SettingField(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label, 16, DIM)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1E1E26),
                unfocusedContainerColor = Color(0xFF1A1A22),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
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
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true)
                )
            ).build()
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { err = "Playback error: ${error.errorCodeName}" }
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

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(fr).focusable()
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
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    player = exo
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (showHud || err.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color(0xAA000000)).padding(20.dp)
            ) {
                Label(item.name, 22)
                if (item.kind == "vod") Label("${fmt(pos)} / ${fmt(dur)}", 16, DIM)
                if (err.isNotEmpty()) Label(err, 18, Color(0xFFFF8A80))
            }
        }
    }
}
