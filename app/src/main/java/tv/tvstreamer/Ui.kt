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

private val WARN get() = Pal.Warn
private val DIM get() = Pal.Dim

/** Focusable, D-pad friendly. Menu key or long press fires onMenu. */
fun Modifier.tv(onClick: () -> Unit, onMenu: (() -> Unit)? = null, onFocus: (() -> Unit)? = null): Modifier = composed {
    var f by remember { mutableStateOf(false) }
    this
        .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus?.invoke() }
        .background(if (f) Pal.FocusBg else Pal.Row, RoundedCornerShape(8.dp))
        .then(if (f) Modifier.border(2.dp, Pal.Gold, RoundedCornerShape(8.dp)) else Modifier)
        .onKeyEvent { e ->
            if (onMenu != null && e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false
        }
        .combinedClickable(onClick = onClick, onLongClick = onMenu)
}

@Composable
fun Label(text: String, size: Int = 18, color: Color = Pal.Text, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text, color = color, fontSize = size.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
fun Row1(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, onMenu: (() -> Unit)? = null, onFocus: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().tv(onClick, onMenu, onFocus).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Label(text, maxLines = 3)
    }
}

/** The Live TV group (English, Indian, …) a category is shown under. */
fun liveGroupOf(c: Cat): String = groupCategories(listOf(c)).keys.firstOrNull() ?: ""

fun groupCategories(cats: List<Cat>): Map<String, List<Cat>> {
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
fun ChannelCard(ch: Item, fav: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
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
    var linkNote by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    fun link(name: String, url: String) {
        linkNote = if (openUrl(ctx, url)) "Opened $name in the browser. Copy the key, come back and paste it below."
        else "No web browser on this device. On a phone or computer, open:  $url"
    }
    LaunchedEffect(Unit) {
        portal = s.portal
        mac = s.mac
        serial = s.serial
        pw = s.password
    }
    val statusText = note.ifEmpty { repo.status }

    LazyColumn(
        Modifier.fillMaxSize().imePadding().padding(horizontal = if (AppUi.phoneNav) 16.dp else 32.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Label("Settings", 32) }
        if (statusText.isNotEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth()
                        .background(Pal.WarnBox, RoundedCornerShape(8.dp))
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
                if (showLog) Box(Modifier.fillMaxWidth().background(Pal.Field, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Label(repo.api.debugLog(), 13, DIM, maxLines = 60)
                }
            }
        }
        item {
            Row1("Google account & sync: " + (if (Cloud.signedIn) Cloud.email.ifEmpty { "signed in" } else "not signed in"), { go(Screen.Account) })
        }
        item { SettingField("Portal URL", portal) { portal = it } }
        item { SettingField("MAC address", mac) { mac = it } }
        item { SettingField("Serial number", serial) { serial = it } }
        item { SettingField("Locked section password", pw, password = true) { pw = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                "An OMDb key (omdbapi.com) adds the IMDb rating. Without keys the portal's own info is used. With Google sync on, keys also sync to your other devices.", 13, DIM, maxLines = 10)
        }
        item {
            FieldWithLink({ SettingField("TMDB API key (v3 key or v4 read token)", tmdb, password = true) { tmdb = it } },
                "Get a TMDB key ↗") { link("TMDB", TMDB_KEY_URL) }
        }
        item {
            FieldWithLink({ SettingField("OMDb API key (optional, for IMDb ratings)", omdb, password = true) { omdb = it } },
                "Get an OMDb key ↗") { link("OMDb", OMDB_KEY_URL) }
        }
        if (linkNote.isNotEmpty()) item { Label(linkNote, 15, Pal.GoldText, maxLines = 6) }
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
fun SettingField(label: String, value: String, password: Boolean = false, multiLine: Boolean = false, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val tv = rememberIsTv()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label, if (tv) 16 else 14, DIM, maxLines = 3)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = !multiLine || password,
            maxLines = if (multiLine && !password) 3 else 1,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            // "Done" on the keyboard closes it and moves focus on (no separate hide-keyboard button; Back also closes it)
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                keyboard?.hide(); focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            }),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (tv) 64.dp else 56.dp)
                .onFocusChanged { if (it.isFocused) keyboard?.show() else keyboard?.hide() },
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = Pal.FieldFocus,
                unfocusedContainerColor = Pal.Field,
                focusedTextColor = Pal.Text,
                unfocusedTextColor = Pal.Text
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (tv) 20.sp else 17.sp),
            placeholder = { Text("Tap to enter", color = DIM, fontSize = if (tv) 18.sp else 15.sp) }
        )
    }
}


/** A text field with a link button: side by side on TV, stacked on a phone so the field keeps its full width. */
@Composable
fun FieldWithLink(field: @Composable () -> Unit, button: String, onClick: () -> Unit) {
    if (AppUi.phoneNav) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { field(); Row1(button, onClick) }
    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { field() }
        Row1(button, onClick, Modifier.width(280.dp))
    }
}

const val TMDB_KEY_URL = "https://www.themoviedb.org/settings/api"
const val OMDB_KEY_URL = "https://www.omdbapi.com/apikey.aspx"

/** Opens [url] in a browser; false when the device has none (common on Fire TV). Never throws. */
fun openUrl(ctx: android.content.Context, url: String): Boolean = try {
    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i); true
} catch (e: Exception) { false }
