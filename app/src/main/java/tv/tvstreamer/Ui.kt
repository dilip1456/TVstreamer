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
        .popupOpener()
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
    val order = listOf("English", "Indian", "Spanish", "Portuguese", "French", "Arabic", "Asian", "European", "African", "Adult", "Other")
    val map = mutableMapOf<String, MutableList<Cat>>()
    for (c in cats) {
        val t = c.title.uppercase()
        val g = when {
            "ADULT" in t || "XXX" in t || "18+" in t || "PORN" in t || "EROTIC" in t || "SEXY" in t -> "Adult"
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
    // Same look as Live TV: rounded logo tile, number badge, readable name
    Box(Modifier.width(150.dp)) { LiveTile(ch, fav, false, LocalPicked.current?.contains(ch.key) == true, rememberIsTv(), onClick, onMenu) }
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
    var gem by remember { mutableStateOf(s.geminiKey) }
    var osk by remember { mutableStateOf(s.osKey) }
    var autoNext by remember { mutableStateOf(s.autoNext) }
    var subSize by remember { mutableStateOf(s.subSize) }
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
    // TV: start on the first row (there is no on-screen Back button; remote Back leaves)
    val firstFr = remember { FocusRequester() }
    val isTvS = rememberIsTv()
    LaunchedEffect(Unit) { if (isTvS) { delay(150); runCatching { firstFr.requestFocus() } } }

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
            Row1("Google account & sync: " + (if (Cloud.signedIn) Cloud.email.ifEmpty { "signed in" } else "not signed in"), { go(Screen.Account) },
                Modifier.focusRequester(firstFr))
        }
        item { Section("Portal") }
        item { SettingField("Portal URL", portal) { portal = it } }
        item { SettingField("MAC address", mac) { mac = it } }
        item { SettingField("Serial number", serial) { serial = it } }
        item { SettingField("Locked section password (empty = no lock)", pw, password = true) { pw = it } }
        item {
            PillButton("Save and reload", {
                keyboard?.hide()
                s.portal = portal
                s.mac = mac.trim().uppercase()
                s.serial = serial.trim()
                s.password = pw
                note = "Saved. Reloading..."
                scope.launch {
                    repo.api.reset()
                    repo.load(force = true)
                    note = repo.status.ifEmpty { "Loaded" }
                }
            }, primary = true)
        }
        item { Section("Content") }
        item { Row1("Categories: order and hide", { go(Screen.Categories) }) }
        item { Row1("Languages for Movies", { go(Screen.Languages("movies", Screen.Settings)) }) }
        item { Row1("Languages for Series", { go(Screen.Languages("series", Screen.Settings)) }) }
        item { Row1("Remote buttons", { go(Screen.Remote) }) }
        item {
            val lib = repo.lib
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Library: " + libraryStatus(repo), 13, DIM, maxLines = 3)
                var confirm by remember { mutableStateOf(false) }
                ButtonFlow {
                    if (lib.loading) PillButton("■  Stop loading", { lib.stop(); note = "Loading stopped" })
                    else if (confirm) {
                        PillButton("Yes, check for new titles", { confirm = false; note = lib.requestManual() }, primary = true)
                        PillButton("Cancel", { confirm = false })
                    } else PillButton("↻  Refresh library", {
                        val w = lib.manualWait()
                        if (w > 0) note = "Refreshed a moment ago. The next refresh is possible in ${(w + 59_999) / 60_000} min (keeps your provider happy)."
                        else confirm = true
                    })
                    if (lib.stopped && !lib.loading) PillButton("▶  Resume loading", { lib.resume(); note = "Loading resumed" })
                }
                if (confirm) Label("Asks the portal for the newest page of each category in your languages, one request at a time (at most every 10 min).", 13, DIM, maxLines = 3)
            }
        }
        item { Section("Player") }
        item {
            ButtonFlow {
                PillButton((if (autoNext) "✓ " else "    ") + "Play next episode automatically", {
                    autoNext = !autoNext; s.autoNext = autoNext
                    note = if (autoNext) "Next episode starts automatically (10 s countdown)" else "Next episode autoplay is off"
                }, selected = autoNext)
                PillButton("Subtitle size: " + subSize.replaceFirstChar { it.uppercase() }, {
                    subSize = TrackPrefs.SIZES[(TrackPrefs.SIZES.indexOf(subSize) + 1) % TrackPrefs.SIZES.size]; s.subSize = subSize
                })
                val remembered = listOfNotNull(
                    s.audioLang.takeIf { it.isNotEmpty() }?.let { "audio ${OpenSubs.langName(TrackPrefs.norm(it))}" },
                    s.subLang.takeIf { it.isNotEmpty() }?.let { if (it == "off") "subtitles off" else "subtitles ${OpenSubs.langName(TrackPrefs.norm(it))}" }
                )
                if (remembered.isNotEmpty()) PillButton("Forget audio/subtitle choices (${remembered.joinToString(", ")})", {
                    s.audioLang = ""; s.subLang = ""; s.seriesTracks = ""; note = "Audio and subtitle choices forgotten"
                })
            }
        }
        item { Section("API keys (optional)") }
        item {
            Label("TMDB adds posters, plots and cast; OMDb adds IMDb ratings; OpenSubtitles downloads subtitles; Gemini powers " +
                "\"Suggested for you\" and 🎤 / ✨ search (titles from your history are sent to Google). Keys sync with Google sync.", 13, DIM, maxLines = 5)
        }
        item { FieldWithLink({ SettingField("TMDB API key (v3 key or v4 read token)", tmdb, password = true) { tmdb = it } }, "Get key ↗") { link("TMDB", TMDB_KEY_URL) } }
        item { FieldWithLink({ SettingField("OMDb API key", omdb, password = true) { omdb = it } }, "Get key ↗") { link("OMDb", OMDB_KEY_URL) } }
        item { FieldWithLink({ SettingField("OpenSubtitles API key", osk, password = true) { osk = it } }, "Get key ↗") { link("OpenSubtitles", OpenSubs.KEY_URL) } }
        item { FieldWithLink({ SettingField("Gemini API key", gem, password = true) { gem = it } }, "Get key ↗") { link("Google AI Studio", Gemini.KEY_URL) } }
        if (linkNote.isNotEmpty()) item { Label(linkNote, 15, Pal.GoldText, maxLines = 6) }
        item {
            // One button for all keys: saves them and tests the ones that can be tested
            PillButton("Save keys and test", {
                keyboard?.hide()
                s.tmdbKey = tmdb; s.omdbKey = omdb; s.osKey = osk
                Tmdb.setKeys(tmdb, omdb)
                val gemChanged = gem != s.geminiKey
                s.geminiKey = gem
                Gemini.useKey(gem)
                note = "Testing keys..."
                scope.launch {
                    val parts = mutableListOf<String>()
                    if (tmdb.isNotBlank() || omdb.isNotBlank()) parts += withContext(Dispatchers.IO) { Tmdb.test(tmdb, omdb) }
                    if (gem.isNotBlank()) parts += withContext(Dispatchers.IO) { Gemini.test(gem) } else if (gemChanged) parts += "Gemini key removed; AI features are off"
                    parts += if (osk.isBlank()) "No OpenSubtitles key" else "OpenSubtitles key saved"
                    note = parts.joinToString("\n")
                    if (gem.isNotBlank()) launch { Ai.refreshSuggested(repo, force = true) }
                }
            }, primary = true)
        }
        item { Spacer(Modifier.height(4.dp)); HiddenRowsPanel(repo) }
        item { Section("Troubleshooting") }
        item {
            var showLog by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ButtonFlow {
                    PillButton(if (showLog) "Hide portal replies" else "Last portal replies", { showLog = !showLog }, selected = showLog)
                    PillButton("Clear movie info cache", { Tmdb.clearCache(); note = "Movie info cache cleared" })
                    PillButton("Clear watch history", { s.clearHistory(); note = "History cleared" })
                }
                if (showLog) Box(Modifier.fillMaxWidth().background(Pal.Field, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Label(repo.api.debugLog(), 13, DIM, maxLines = 60)
                }
                val ver = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" } }
                Label("TVstreamer v$ver", 12, DIM)
            }
        }
    }
}

@Composable
private fun Section(title: String) = Label(title, 18, Pal.GoldText, Modifier.padding(top = 10.dp))

@Composable
fun SettingField(label: String, value: String, password: Boolean = false, multiLine: Boolean = false, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val tv = rememberIsTv()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(label, if (tv) 16 else 14, DIM, maxLines = 3)
        // TV: the keyboard opens only on OK / click, never because focus moved here (KeyboardGate)
        KeyboardGate(Modifier.fillMaxWidth()) { inner ->
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
                modifier = inner.fillMaxWidth().heightIn(min = if (tv) 64.dp else 56.dp),
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
}


/** A text field with a link button: side by side on TV, stacked on a phone so the field keeps its full width. */
@Composable
fun FieldWithLink(field: @Composable () -> Unit, button: String, onClick: () -> Unit) {
    if (AppUi.phoneNav) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { field(); PillButton(button, onClick) }
    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { field() }
        PillButton(button, onClick)
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

/** "1,234 titles · refreshed today 09:12 · next automatic refresh in about 24 h", with progress while loading. */
fun libraryStatus(repo: Repo): String {
    val lib = repo.lib
    lib.version
    val parts = mutableListOf<String>()
    if (lib.hasData) parts += "%,d titles saved on this device (only your languages)".format(lib.size)
    val t = lib.refreshedAt
    parts += if (t > 0) "last refreshed " + whenText(t) else if (lib.hasData) "not fully refreshed yet" else "not loaded yet"
    if (lib.loading) parts += if (lib.total > 0) "loading ${lib.done} of ${lib.total} pages" else "refreshing…"
    else if (t > 0) parts += "checks for new titles once a day; older pages load as you scroll"
    if (lib.stopped) parts += "loading stopped"
    if (lib.note.isNotEmpty()) parts += lib.note
    return parts.joinToString(" · ")
}

fun whenText(t: Long): String {
    val d = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val now = java.util.Calendar.getInstance()
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
    val sameDay = d.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) && d.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) "today $hm" else java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
}

/**
 * A TV menu row: Left on its first item and Right on its last do nothing, instead of letting focus jump out to
 * whatever happens to be beside it (e.g. a floating button) with no way back. [index] is the focused item (-1: none).
 */
fun Modifier.menuRow(index: () -> Int, count: () -> Int): Modifier = onPreviewKeyEvent {
    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val i = index()
    when (it.key) {
        Key.DirectionLeft -> i == 0
        Key.DirectionRight -> i >= 0 && i == count() - 1
        else -> false
    }
}
