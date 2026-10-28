@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package tv.tvstreamer

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the search overlay looks in. */
enum class Scope(val label: String) { ALL("All"), MOVIES("Movies"), SERIES("TV Series"), LIVE("Live") }

/** Query + scope survive closing the overlay (and opening a result), so reopening shows the same results. */
class SearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var scope by mutableStateOf(Scope.ALL)
    /** Bumped to start voice capture when the overlay is (or becomes) visible; [voiceHandled] is the last one acted on. */
    var voiceReq by mutableIntStateOf(0)
    var voiceHandled = 0
    /** Gemini assistant picks from the library, shown above the normal results. */
    var aiItems by mutableStateOf<List<Item>>(emptyList())
    var aiMsg by mutableStateOf("")
}

data class SearchResult(val live: List<Item>, val movies: List<Group>, val series: List<Group>) {
    val empty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/**
 * Searches the whole portal (every category, every language, including ones not picked on the tabs)
 * plus everything already loaded in the library. Movies vs series follows the per-item classification
 * (Item.seriesLike); loose "Show S01E02" entries are merged into one show card.
 */
fun searchAll(repo: Repo, q: String, scope: Scope): SearchResult {
    val wantLive = scope == Scope.ALL || scope == Scope.LIVE
    val wantVod = scope != Scope.LIVE
    val (live0, vod0) = runCatching { repo.search(if (wantVod) q else "$q live") }.getOrDefault(emptyList<Item>() to emptyList())
    val live = if (wantLive) live0 else emptyList()
    if (!wantVod) return SearchResult(live, emptyList(), emptyList())
    // Titles already loaded locally (any language) that match every word of the query
    val words = Tags.norm(q).split(' ').filter { it.length > 1 || it.all(Char::isDigit) }
    val lib = repo.lib
    val catTitle = repo.vodCats.associate { it.id to Tags.norm(it.title) }
    val local = if (words.isEmpty()) emptyList() else {
        // Title words first; when nothing matches the title, the category / language counts too ("hindi pathaan")
        val inTitle = lib.everything().filter { i -> val h = Tags.norm(i.name); words.all { it in h } }
        val wide = if (inTitle.size >= 20) inTitle else (inTitle + lib.everything().filter { i ->
            val h = Tags.norm(i.name) + " " + (catTitle[i.cat] ?: "") + " " + Tags.norm(lib.itemLang(i))
            words.all { it in h }
        }).distinctBy { it.key }
        wide.take(600)
    }
    val unlocked = repo.gate(true) == null
    val all = (vod0 + local).distinctBy { it.key }.filter { (!it.locked || unlocked) && "vod:${it.cat}" !in repo.hidden }
    val groups = lib.cards(all)
    return SearchResult(
        live,
        if (scope == Scope.SERIES) emptyList() else groups.filter { !lib.seriesItem(it.best) },
        if (scope == Scope.MOVIES) emptyList() else groups.filter { lib.seriesItem(it.best) }
    )
}

/** Floating gold search button, bottom-right. Focusable with the D-pad (Down from the last row, or Right). */
@Composable
fun SearchFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val isTv = rememberIsTv()
    val size = if (isTv) 60.dp else 56.dp
    Box(
        modifier
            .size(size)
            .onFocusChanged { f = it.isFocused }
            .then(if (f) Modifier.border(3.dp, Pal.Text, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(if (f) Color(0xFFF3D97A) else Pal.Gold)
            .clickable(onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center
    ) { Text("🔍", fontSize = if (isTv) 24.sp else 22.sp, color = Color.Black) }
}

@Composable
fun SearchOverlay(repo: Repo, st: SearchState, open: (Item) -> Unit, play: (List<Item>, Int) -> Unit) {
    val isTv = rememberIsTv()
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fieldFr = remember { FocusRequester() }
    val firstResFr = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    /** A spoken or typed request: Gemini (when a key is set) turns it into search words or picks titles; otherwise a plain search. */
    fun handle(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        heard = ""
        if (!Gemini.enabled) { st.aiItems = emptyList(); st.aiMsg = ""; st.query = t; return }
        aiBusy = true; status = "Asking AI about \"$t\"…"
        scope.launch {
            when (val o = Ai.ask(repo, t)) {
                is AiOutcome.Search -> { st.aiItems = emptyList(); st.aiMsg = o.message; st.query = o.query.ifBlank { t } }
                is AiOutcome.Suggest -> { st.aiItems = o.items; st.aiMsg = o.message; st.query = "" }
                is AiOutcome.Failed -> { st.aiItems = emptyList(); st.aiMsg = "AI unavailable (${o.message}). Showing a normal search."; st.query = t }
            }
            aiBusy = false; status = ""
            if (st.aiItems.isNotEmpty()) { delay(150); runCatching { firstResFr.requestFocus() } }
        }
    }
    val voice = rememberVoiceInput(
        onPartial = { heard = it },
        onText = { handle(it) },
        onStatus = { m ->
            status = when (m) {
                NO_VOICE -> if (Gemini.enabled) "Voice input isn't available here. Type your request and press ✨ Ask AI." else m
                else -> m
            }
            if (m == NO_VOICE || m.contains("Type your request")) runCatching { fieldFr.requestFocus() }
        }
    )
    LaunchedEffect(Unit) { delay(150); runCatching { fieldFr.requestFocus() } }
    // The remote's mic / search key (or a long press on 🎤) asked for voice
    LaunchedEffect(st.voiceReq) {
        if (st.voiceReq > st.voiceHandled) { st.voiceHandled = st.voiceReq; delay(250); voice.start() }
    }
    // Debounced search: waits for typing to pause
    LaunchedEffect(st.query, st.scope) {
        val q = st.query.trim()
        if (q.length < 2) { result = null; busy = false; return@LaunchedEffect }
        delay(400)
        busy = true
        result = withContext(Dispatchers.IO) { searchAll(repo, q, st.scope) }
        busy = false
    }
    val posterW = posterWidth(isTv)
    val hPad = if (isTv) 48.dp else 12.dp
    Box(Modifier.fillMaxSize().background(Pal.Bg.copy(alpha = 0.97f))) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(posterW),
            contentPadding = PaddingValues(horizontal = hPad, vertical = if (isTv) 24.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = st.query, onValueChange = { st.query = it; if (it.isNotBlank() && st.aiItems.isNotEmpty()) { st.aiItems = emptyList(); st.aiMsg = "" } }, singleLine = true,
                            placeholder = { Text(if (Gemini.enabled) "Search, or ask: \"funny movies for tonight\"" else if (isTv) "Search movies, series, channels" else "Search movies, series, channels") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { kb?.hide(); runCatching { firstResFr.requestFocus() } }),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(28.dp)).focusRequester(fieldFr)
                        )
                        Spacer(Modifier.width(8.dp))
                        PillButton(if (voice.listening) "■" else "🎤", { if (voice.listening) voice.stop() else voice.start() }, selected = voice.listening, size = 14)
                        if (Gemini.enabled) {
                            Spacer(Modifier.width(8.dp))
                            PillButton("✨ Ask AI", {
                                kb?.hide()
                                if (st.query.isBlank()) { status = "Type or say a request, e.g. \"a feel-good movie\""; runCatching { fieldFr.requestFocus() } }
                                else handle(st.query)
                            }, size = 14)
                        }
                        Spacer(Modifier.width(8.dp))
                        PillButton("✕  Close", { voice.stop(); st.open = false }, size = 14)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Scope.entries.forEach { s -> PillButton(s.label, { st.scope = s }, selected = st.scope == s, size = 14) }
                    }
                    if (heard.isNotEmpty() && voice.listening) Text("“$heard”", color = Pal.Text, fontSize = 15.sp)
                    if (status.isNotEmpty()) Text(status, color = if (aiBusy || voice.listening) Pal.Dim else Pal.Warn, fontSize = 14.sp)
                    if (st.aiMsg.isNotEmpty()) Text(st.aiMsg, color = Pal.Dim, fontSize = 13.sp)
                    when {
                        st.aiItems.isNotEmpty() || aiBusy || voice.listening -> {}
                        st.query.trim().length < 2 -> Text("Type at least 2 letters. Searches every category and language on the portal.", color = Pal.Dim, fontSize = 13.sp)
                        busy -> Text("Searching…", color = Pal.Dim, fontSize = 13.sp)
                        result?.empty == true -> Text("No results for \"${st.query.trim()}\"" + (if (st.scope != Scope.ALL) " in ${st.scope.label}. Try All." else "."), color = Pal.Warn, fontSize = 15.sp)
                    }
                }
            }
            if (busy && result == null) {
                gridItems((0 until 12).toList()) { Shimmer(posterW) }
            }
            val r = result
            var first = true
            fun firstMod(): Modifier = if (first) { first = false; Modifier.focusRequester(firstResFr) } else Modifier
            if (st.aiItems.isNotEmpty()) {
                val ai = st.aiItems
                item(span = { GridItemSpan(maxLineSpan) }) { Section("✨ Suggested", ai.size) }
                val a0 = firstMod()
                gridItemsIndexed(ai, key = { i, it -> "ai|$i|${it.key}" }) { i, it ->
                    PosterCard(it, posterW, null, if (i == 0) a0 else Modifier, { open(it) },
                        { repo.toggleFav(it) }, fav = repo.isFav(it)) { Keys.focused = it }
                }
            }
            if (r != null) {
                if (r.live.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Section("Live channels", r.live.size) }
                    val lm = firstMod()
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = lm) {
                            items(r.live.size) { i ->
                                ChannelCard(r.live[i], repo.isFav(r.live[i]), { st.open = false; play(r.live, i) }, { repo.toggleFav(r.live[i]) })
                            }
                        }
                    }
                }
                for ((title, list) in listOf("Movies" to r.movies, "TV Series" to r.series)) {
                    if (list.isEmpty()) continue
                    item(span = { GridItemSpan(maxLineSpan) }) { Section(title, list.size) }
                    val m0 = firstMod()
                    gridItemsIndexed(list, key = { i, g -> "$title|$i|${g.key}" }) { i, g ->
                        PosterCard(g.best, posterW, null, if (i == 0) m0 else Modifier, { open(g.best) },
                            { repo.toggleFav(g.best) }, fav = repo.isFav(g.best), versions = g.items.size) { Keys.focused = g.best }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, n: Int) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
        Text(title, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("$n", color = Pal.Dim, fontSize = 13.sp)
    }
}

@Composable
private fun Shimmer(w: androidx.compose.ui.unit.Dp) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Column {
        Box(Modifier.width(w).aspectRatio(2f / 3f).alpha(a).clip(RoundedCornerShape(8.dp)).background(Pal.Shimmer))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(w * 0.7f).height(10.dp).alpha(a).background(Pal.Shimmer))
    }
}
