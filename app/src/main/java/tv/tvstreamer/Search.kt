@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package tv.tvstreamer
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key

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

/** "Movies with Shah Rukh Khan": the saved titles of a person typed in the search box (1.69). */
data class PersonRow(val title: String, val groups: List<Group>)

data class SearchResult(val live: List<Item>, val movies: List<Group>, val series: List<Group>, val person: PersonRow? = null) {
    val empty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty() && (person?.groups?.isEmpty() != false)
    val vodCount get() = movies.size + series.size + (person?.groups?.size ?: 0)
}

/** Fewer matching cards than this in the saved catalog: ask the portal's search too (gently, see Repo.portalSearch). */
const val FEW_SEARCH = 20

private fun Repo.searchable(i: Item) = listOk(i.locked) && "vod:${i.cat}" !in hidden

/** One saved title, prepared for search: its cleaned title folded once, its year and language. */
class SearchEntry(val item: Item, val title: SearchIndex.Folded, val year: String, val lang: String) {
    /** Full portal name, language, cast and director words (folded on first use, then kept). */
    @Volatile var extra: List<String>? = null
    val titleKey: String by lazy { SearchIndex.titleKey(title.words) }
}

fun searchEntry(i: Item): SearchEntry {
    val p = i.parsed
    return SearchEntry(i, SearchIndex.Folded(SearchIndex.words(p.title.ifBlank { i.name })), i.year.ifEmpty { p.year }, p.lang)
}

/**
 * The saved catalog prepared for search, built once (in the background when search opens) and updated by reusing
 * the entries of titles already prepared, so every keystroke only compares folded words.
 */
object SearchSnapshot {
    @Volatile private var src: List<Item>? = null
    @Volatile private var entries: List<SearchEntry> = emptyList()
    fun of(items: List<Item>): List<SearchEntry> = synchronized(this) {
        if (src === items) return entries
        val old = HashMap<String, SearchEntry>(entries.size * 2)
        for (e in entries) old[e.item.key] = e
        entries = items.mapNotNull { i -> if (i.kind != "vod") null else old[i.key]?.takeIf { it.item === i } ?: searchEntry(i) }
        src = items
        entries
    }
}

/**
 * Saved titles (every language, every category ever loaded, plus earlier portal search results) matching the query,
 * best match first. Offline and instant: titles are matched on their cleaned names (see [SearchIndex]), with cast,
 * director, category and language as extra words.
 */
fun localVod(repo: Repo, q: String): List<Item> {
    val query = SearchIndex.query(q)
    if (query.empty) return emptyList()
    val catWords = repo.vodCats.associate { it.id to SearchIndex.words(it.title) }
    return SearchSnapshot.of(repo.lib.everything()).asSequence()
        .mapNotNull { e ->
            val sc = SearchIndex.score(query, e.title, {
                (e.extra ?: (SearchIndex.words(e.item.name) + SearchIndex.words(e.lang) + SearchIndex.words(e.item.cast) +
                    SearchIndex.words(e.item.director)).distinct().also { e.extra = it }) + (catWords[e.item.cat] ?: emptyList())
            }, e.year)
            if (sc > 0) e.item to sc else null
        }
        .sortedByDescending { it.second }
        .take(600).map { it.first }.toList()
}

/** Saved titles whose portal actors / director field names the query (every word). */
private fun castHits(repo: Repo, q: SearchIndex.Query): List<Item> {
    if (q.words.size < 1 || q.squashed.length < 4) return emptyList()
    return repo.lib.everything().filter { i ->
        (i.cast.isNotEmpty() || i.director.isNotEmpty()) && run {
            val w = SearchIndex.cachedWords(i.cast + " " + i.director)
            q.words.all { qw -> w.any { it == qw || (qw.length >= 3 && it.startsWith(qw)) } }
        }
    }
}

/** Saved titles in a person's credits (same cleaned title, years agree), in the credits' order. */
fun creditHits(entries: List<SearchEntry>, credits: List<People.Credit>): List<Item> {
    if (credits.isEmpty()) return emptyList()
    val want = credits.withIndex().groupBy { SearchIndex.titleKey(it.value.title) }
    return entries.mapNotNull { e ->
        val hit = want[e.titleKey]?.firstOrNull { (_, c) -> SearchIndex.sameTitle(c.title, c.year, e.title.spaced, e.year) }
        hit?.let { e.item to it.index }
    }.sortedBy { it.second }.map { it.first }
}

/** Section title for a person row: "Movies with X", "TV Series with X", "With X", or "Directed by X". */
fun personTitle(name: String, groups: List<Group>, series: (Group) -> Boolean, directed: Boolean): String = when {
    directed -> "Directed by $name"
    groups.isNotEmpty() && groups.all(series) -> "TV Series with $name"
    groups.isNotEmpty() && groups.none(series) -> "Movies with $name"
    else -> "With $name"
}

/**
 * Search (1.69): everything saved on the device first (instant, offline), then, when that finds few titles,
 * the portal's own search ([portal]); a person ([person], from TMDB / Cinemeta, or the portal's actors field)
 * becomes a "Movies with X" row. Movies vs series follows the per-item classification (Item.seriesLike);
 * loose "Show S01E02" entries are merged into one show card.
 */
fun searchAll(repo: Repo, q: String, scope: Scope, portal: Boolean = false, person: People.Person? = null): SearchResult {
    val wantLive = scope == Scope.ALL || scope == Scope.LIVE
    val wantVod = scope != Scope.LIVE
    val live = if (wantLive) runCatching { repo.searchLive(q) }.getOrDefault(emptyList()) else emptyList()
    if (!wantVod) return SearchResult(live, emptyList(), emptyList())
    val lib = repo.lib
    val fromPortal = if (portal) runCatching { repo.portalSearch(q) }.getOrDefault(emptyList()) else emptyList()
    val local = localVod(repo, q)
    val all = (local + fromPortal).distinctBy { it.key }.filter { repo.searchable(it) }
    // Person row: titles in their credits that are saved, plus titles whose actors / director field names them
    val query = SearchIndex.query(q)
    val cast = castHits(repo, query).filter { repo.searchable(it) }
    val credits = person?.let { p -> creditHits(SearchSnapshot.of(lib.everything()), p.credits).filter { repo.searchable(it) } }.orEmpty()
    val titleHits = all.count { i -> val w = SearchIndex.cachedWords(i.parsed.title); query.words.all { qw -> w.any { it.startsWith(qw) } } }
    val trusted = person?.source == "TMDB"
    val showPerson = cast.isNotEmpty() || (person != null && SearchIndex.personLikely(credits.size, titleHits, trusted))
    val personItems = if (showPerson) (cast + if (person != null) credits else emptyList()).distinctBy { it.key } else emptyList()
    val inPerson = personItems.mapTo(HashSet()) { it.key }
    val groups = lib.cards(all.filter { it.key !in inPerson })
    val pGroups = lib.cards(personItems).let { g ->
        when (scope) { Scope.MOVIES -> g.filter { !lib.seriesItem(it.best) }; Scope.SERIES -> g.filter { lib.seriesItem(it.best) }; else -> g }
    }
    val row = if (pGroups.isEmpty()) null else {
        val name = person?.name ?: SearchIndex.titleCase(query.words.joinToString(" "))
        PersonRow(personTitle(name, pGroups, { lib.seriesItem(it.best) }, person?.directed == true), pGroups)
    }
    return SearchResult(
        live,
        if (scope == Scope.SERIES) emptyList() else groups.filter { !lib.seriesItem(it.best) },
        if (scope == Scope.MOVIES) emptyList() else groups.filter { lib.seriesItem(it.best) },
        row
    )
}

/** Floating blue search button, bottom-right. Focusable with the D-pad (Down from the last row, or Right). */
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
            .background(if (f) Pal.Highlight else Pal.Primary)
            .clickable(onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center
    ) { androidx.compose.material3.Icon(RailIcons.Search, "Search", tint = if (f) Pal.OnFocusPill else Color.White, modifier = Modifier.size(if (isTv) 26.dp else 24.dp)) }
}

@Composable
fun SearchOverlay(repo: Repo, st: SearchState, open: (Item) -> Unit, play: (List<Item>, Int) -> Unit, onClose: () -> Unit = {}) {
    val isTv = rememberIsTv()
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }   // portal search / person lookup still running
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
    // Search opened: a few more saved pages in the background (gentle, capped), so later searches find more offline
    LaunchedEffect(Unit) {
        repo.lib.searchSweep()
        withContext(Dispatchers.Default) { SearchSnapshot.of(repo.lib.everything()) }   // prepare the saved catalog for search
    }
    // Debounced search: saved titles at once, then (only if needed) the portal's search and a person lookup
    LaunchedEffect(st.query, st.scope) {
        val q = st.query.trim()
        more = false
        if (q.length < 2) { result = null; busy = false; return@LaunchedEffect }
        delay(250)
        busy = true
        val local = withContext(Dispatchers.Default) { searchAll(repo, q, st.scope) }
        result = local; busy = false
        if (st.scope == Scope.LIVE) return@LaunchedEffect
        val few = local.vodCount < FEW_SEARCH
        more = true
        // A person lookup (TMDB with the user's key, else Cinemeta; no portal requests) for names, or when little matched
        val askPerson = few || SearchIndex.query(q).words.size >= 2
        val person = if (!askPerson) null else withContext(Dispatchers.IO) { runCatching { People.lookup(q) }.getOrNull() }
        if (few || person != null) result = withContext(Dispatchers.IO) { searchAll(repo, q, st.scope, portal = few, person = person) }
        more = false
        if (few) repo.lib.searchSweep()
    }
    var aiMenu by remember { mutableStateOf<Item?>(null) }
    val posterW = posterWidth(isTv)
    val hPad = if (isTv) 48.dp else 12.dp
    var expectUp by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Pal.Bg.copy(alpha = 0.97f)).focusTrap()
        .onPreviewKeyEvent { e ->
            if (e.key != Key.Back && e.key != Key.Escape) return@onPreviewKeyEvent false
            if (Popups.any) return@onPreviewKeyEvent false
            val d = OverlayBack.onKey(
                isDown = e.type == KeyEventType.KeyDown,
                isUp = e.type == KeyEventType.KeyUp,
                repeat = e.nativeKeyEvent.repeatCount > 0,
                expectUp = expectUp,
            )
            expectUp = d.expectUp
            if (d.close) onClose()
            d.consume
        }) {
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
                    val onType: (String) -> Unit = { st.query = it; if (it.isNotBlank() && st.aiItems.isNotEmpty()) { st.aiItems = emptyList(); st.aiMsg = "" } }
                    val askAi: () -> Unit = {
                        kb?.hide()
                        if (st.query.isBlank()) { status = "Type or say a request, e.g. \"a feel-good movie\""; runCatching { fieldFr.requestFocus() } }
                        else handle(st.query)
                    }
                    val hint = if (Gemini.enabled) "Search, or ask: \"funny movies for tonight\"" else "Search movies, series, channels"
                    if (!isTv) {
                        // Phone: one large full-width field (52 dp, 18 sp); scope chips and mic / Ask AI / close on a tidy row below
                        TextField(
                            value = st.query, onValueChange = onType, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = Ts.ROW.sp),
                            placeholder = { Text(hint, fontSize = Ts.ROW.sp, color = Pal.Dim, maxLines = 1) },
                            leadingIcon = { androidx.compose.material3.Icon(RailIcons.Search, null, tint = Pal.Dim, modifier = Modifier.size(20.dp)) },
                            trailingIcon = if (st.query.isNotEmpty()) ({
                                Box(Modifier.clip(CircleShape).clickable { st.query = ""; runCatching { fieldFr.requestFocus() } }.padding(10.dp)) {
                                    Text("✕", color = Pal.Dim, fontSize = Ts.ROW.sp)
                                }
                            }) else null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
                            shape = RoundedCornerShape(26.dp),
                            colors = androidx.compose.material3.TextFieldDefaults.colors(
                                focusedContainerColor = Pal.Surface, unfocusedContainerColor = Pal.Surface,
                                focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text, cursorColor = Pal.Primary,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .border(1.dp, Pal.Primary.copy(alpha = 0.45f), RoundedCornerShape(26.dp)).focusRequester(fieldFr)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(Scope.entries.toList()) { s -> PillButton(s.label, { st.scope = s }, selected = st.scope == s, size = Ts.HEAD) }
                            }
                            PillButton(if (voice.listening) "■" else "🎤", { if (voice.listening) voice.stop() else voice.start() }, selected = voice.listening, size = Ts.HEAD)
                            if (Gemini.enabled) PillButton("✨ AI", askAi, size = Ts.HEAD)
                            PillButton("✕", { voice.stop(); st.open = false }, size = Ts.HEAD)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KeyboardGate(Modifier.weight(1f).focusRequester(fieldFr), shape = RoundedCornerShape(28.dp)) { inner ->
                                TextField(
                                    value = st.query, onValueChange = onType, singleLine = true,
                                    placeholder = { Text(hint + "  ·  OK to type") },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { kb?.hide(); runCatching { firstResFr.requestFocus() } }),
                                    modifier = inner.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            PillButton(if (voice.listening) "■" else "🎤", { if (voice.listening) voice.stop() else voice.start() }, selected = voice.listening, size = Ts.HEAD)
                            if (Gemini.enabled) { Spacer(Modifier.width(8.dp)); PillButton("✨ Ask AI", askAi, size = Ts.HEAD) }
                            Spacer(Modifier.width(8.dp))
                            PillButton("Close", { voice.stop(); st.open = false }, size = Ts.HEAD)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Scope.entries.forEach { s -> PillButton(s.label, { st.scope = s }, selected = st.scope == s, size = Ts.HEAD) }
                        }
                    }
                    if (heard.isNotEmpty() && voice.listening) Text("“$heard”", color = Pal.Text, fontSize = Ts.ROW.sp)
                    if (status.isNotEmpty()) Text(status, color = if (aiBusy || voice.listening) Pal.Dim else Pal.Warn, fontSize = Ts.HEAD.sp)
                    if (st.aiMsg.isNotEmpty()) Text(st.aiMsg, color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                    when {
                        st.aiItems.isNotEmpty() || aiBusy || voice.listening -> {}
                        st.query.trim().length < 2 -> Text("Type at least 2 letters. Searches every saved title in every language, then the portal.", color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                        busy -> Text("Searching…", color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                        more && result?.empty != false -> Text("Searching the portal…", color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
                        result?.empty == true -> Text("No results for \"${st.query.trim()}\"" + (if (st.scope != Scope.ALL) " in ${st.scope.label}. Try All." else "."), color = Pal.Warn, fontSize = Ts.ROW.sp)
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
                val ai = AiFeedback.visible(st.aiItems, Ai.feedback)
                item(span = { GridItemSpan(maxLineSpan) }) { Section("✨ Suggested", ai.size) }
                val a0 = firstMod()
                gridItemsIndexed(ai, key = { i, it -> "ai|$i|${it.key}" }) { i, it ->
                    PosterCard(it, posterW, null, if (i == 0) a0 else Modifier, { open(it) },
                        { aiMenu = it }, fav = repo.isFav(it)) { Keys.focused = it }
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
                r.person?.let { pr ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Section(pr.title, pr.groups.size) }
                    val p0 = firstMod()
                    gridItemsIndexed(pr.groups, key = { i, g -> "person|$i|${g.key}" }) { i, g ->
                        PosterCard(g.best, posterW, null, if (i == 0) p0 else Modifier, { open(g.best) },
                            { repo.toggleFav(g.best) }, fav = repo.isFav(g.best), versions = g.items.size) { Keys.focused = g.best }
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
        aiMenu?.let { m -> TitleMenu(repo, m, 0, open, { play(listOf(it), 0) }) { aiMenu = null } }
    }
}

@Composable
private fun Section(title: String, n: Int) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
        Text(title, color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("$n", color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
    }
}

@Composable
private fun Shimmer(w: androidx.compose.ui.unit.Dp) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Column {
        Box(Modifier.width(w).aspectRatio(2f / 3f).alpha(a).clip(RoundedCornerShape(Radii.poster)).background(Pal.Shimmer))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(w * 0.7f).height(10.dp).alpha(a).background(Pal.Shimmer))
    }
}
