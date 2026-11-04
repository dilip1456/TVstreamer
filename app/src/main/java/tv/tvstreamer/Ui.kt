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
