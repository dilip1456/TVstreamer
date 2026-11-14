@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch

/**
 * Live TV (v1.52): one clean list, grouped by portal type and category only (News incl. "Local News", Sports, …).
 *  - TV: a Netflix-style left menu (icons; labels open while it has focus, it collapses when focus moves right) with
 *    Search, Mode and the sections. Left on a channel goes to its section in the menu.
 *  - Rows: number badge, logo, clean name, small language tag; the channel playing is marked. The schedule is in the player.
 *  - Sections fold: OK on a header (or a sub-category in the left tree) hides / shows its channels; saved.
 *  - Modes: Default (every channel not hidden) or a custom mode (only its channels). "All channels" / "Only hidden"
 *    also list the others, dimmed; each row has a Hide or Show chip (tap / TV OK). Hold OK / long-press still
 *    toggles on TV when browsing hidden, or starts Select on the phone. Menu on a channel = channel menu.
 *  - Languages: only the selected languages are listed (Options / Menu on the remote, or ⋮ on the phone).
 *  - Guide: now / next loaded only for the rows on screen. Back from the player lands on the channel that played.
 */
@Composable
fun Live(repo: Repo, play: (List<Item>, Int) -> Unit, go: (Screen) -> Unit = {}, onSearch: () -> Unit = {}) {
    val tv = rememberIsTv()
    val wide = LocalContext.current.resources.configuration.smallestScreenWidthDp >= 600
    val rail = tv || wide
    // Kept in Repo (and saved): Back from the player must bring back the same list (1.53)
    var view by remember { mutableStateOf(repo.liveView.view) }
    val browseAll = view.showsHidden
    var popup by remember { mutableStateOf<String?>(null) }    // modes | langs | sections
    var menuCh by remember { mutableStateOf<Item?>(null) }

    LaunchedEffect(repo.rawChannels, repo.liveCats) { repo.liveIndex.ensure(repo.rawChannels, repo.liveCats) }
    val ix = repo.liveIndex
    val info = ix.info
    val unlocked = repo.gate(true) == null
    val cats = repo.visibleLive()
    val catIds = remember(cats) { cats.map { it.id }.toSet() }
    val catOrder = remember(cats) { cats.map { it.id } }
    val catTitle = remember(repo.liveCats) { repo.liveCats.associate { it.id to it.title } }
    val base = remember(repo.channels, catIds, unlocked) { repo.channels.filter { it.cat in catIds && (!it.locked || unlocked) } }
    val mode = repo.mode()
    val langs = repo.liveLangs
    val rows = remember(base, mode, repo.folders, repo.hidden, view, langs, info, catTitle) {
        LiveModes.rows(base, mode, repo.folders, repo.hidden, view, langs, info, catTitle)
    }
    val tree = remember(rows, repo.hidden, view) { LiveModes.tree(rows, repo.hidden, view) }
    val sections = remember(rows, info, catTitle, catOrder, mode) {
        LiveModes.sections(rows, info, catTitle, catOrder, if (mode != LiveModes.DEFAULT) repo.folders.items(mode).map { it.key } else null)
    }
    val types = remember(sections) { sections.map { it.type }.distinct() }
    // List positions: type + section header index, flat channel list (what the player steps through)
    val collapsed = repo.liveCollapsed
    val collapseLayout = remember(sections, collapsed) { LiveCollapse.layout(sections, collapsed) }
    val layout = remember(sections, collapseLayout) {
        Triple(collapseLayout.sectionStart, collapseLayout.typeStart, sections.flatMap { s -> s.rows.map { it.ch } })
    }
    val flat = layout.third
    val flatPos = remember(flat) { flat.withIndex().associate { it.value.key to it.index } }
    val allLangs = remember(base, info) { LiveModes.languages(base, info) }
    val modeCount = rows.count { it.on }

    val state = remember {
        repo.listStates.getOrPut("live152") { repo.liveView.let { androidx.compose.foundation.lazy.LazyListState(it.index, it.offset) } }
    }
    var focusKey by remember { mutableStateOf(repo.liveView.focus) }
    fun saveView() = repo.saveLiveView(LiveView(view, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, focusKey))
    LaunchedEffect(view) { saveView() }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { delay(400); saveView() }   // collect is sequential: at most one save per 400 ms while scrolling
    }
    DisposableEffect(Unit) { onDispose { saveView() } }
    val scope = rememberCoroutineScope()
    val navFr = remember { HashMap<String, FocusRequester>() }
    fun navReq(k: String) = navFr.getOrPut(k) { FocusRequester() }
    val playingFr = remember { FocusRequester() }
    val restoreFr = remember { FocusRequester() }   // the last focused channel when nothing played yet
    val firstFr = remember { FocusRequester() }
    // Section header focus targets; Channel / Page up-down jump a whole section and land on its header (1.56)
    val headFrs = remember { HashMap<String, FocusRequester>() }
    fun headFr(k: String) = headFrs.getOrPut(k) { FocusRequester() }
    var navOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // Select mode (bulk hide / unhide): null = off, else the selected channel keys
    var sel by remember { mutableStateOf<Set<String>?>(null) }
    var focusSec by remember { mutableStateOf("") }
    val barFr = remember { FocusRequester() }
    BackHandler(sel != null && !Popups.any) { sel = null }
    fun bulk(on: Boolean) {
        val s0 = sel ?: return
        val targets = rows.filter { it.ch.key in s0 && it.on != on }.distinctBy { it.ch.key }
        if (targets.isNotEmpty()) repo.setOnRows(targets, on)
        toast(ctx, LiveSelect.summary(targets.size, on, mode)); sel = null
    }
    // OK twice quickly on a row hides / unhides it; a single OK plays after the double-press window
    var pendingPlay by remember { mutableStateOf<Job?>(null) }
    var lastOkKey by remember { mutableStateOf<String?>(null) }
    var lastOkAt by remember { mutableStateOf(0L) }

    // Guide for the rows on screen only (debounced in EpgLoader)
    LaunchedEffect(state, flat) {
        // Visible rows plus a few ahead (scrolling down shows the guide at once); cached ones show instantly
        snapshotFlow { state.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.substringAfter('|', "")?.takeIf { k -> k.startsWith("itv:") } } }
            .collect { keys ->
                val pos = keys.mapNotNull { flatPos[it] }
                val ids = Epg.ahead(pos, flat.size).mapNotNull { flat.getOrNull(it)?.id }
                repo.epg.want(ids.ifEmpty { keys.map { it.removePrefix("itv:") } })
            }
    }
    // Back from the player: land on the channel that was playing (else the first channel)
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(flat.isNotEmpty()) {
        if (restored || flat.isEmpty()) return@LaunchedEffect
        restored = true
        val k = repo.nowPlaying.ifEmpty { focusKey }
        val sec = sections.firstOrNull { s -> s.rows.any { it.ch.key == k } }
        if (sec != null) {
            if (sec.key in collapsed) { runCatching { state.scrollToItem(layout.first[sec.key] ?: 0) }; delay(120); if (tv) runCatching { headFr(sec.key).requestFocus() }; return@LaunchedEffect }
            val at = (layout.first[sec.key] ?: 0) + 1 + sec.rows.indexOfFirst { it.ch.key == k }
            // Keep the saved scroll position when the channel is on screen there; scroll only when it is not
            LiveView.scrollFor(state.firstVisibleItemIndex, at)?.let { runCatching { state.scrollToItem(it) } }
            delay(120)
            if (tv) runCatching { (if (k == repo.nowPlaying) playingFr else restoreFr).requestFocus() }
        } else if (tv) { delay(150); runCatching { firstFr.requestFocus() }.onFailure { sections.firstOrNull()?.let { runCatching { headFr(it.key).requestFocus() } } } }
    }

    // Search closed (Back): back to the channel focused last (else the one playing), scrolled into view (1.55)
    LaunchedEffect(AppUi.searchClosed) {
        if (AppUi.searchClosed == 0 || !tv) return@LaunchedEffect
        val k = SearchReturn.liveTarget(focusKey, repo.nowPlaying).ifEmpty { return@LaunchedEffect }
        val sec = sections.firstOrNull { s -> s.rows.any { it.ch.key == k } } ?: return@LaunchedEffect
        if (sec.key in collapsed) { runCatching { state.scrollToItem(layout.first[sec.key] ?: 0) }; delay(120); runCatching { headFr(sec.key).requestFocus() }; return@LaunchedEffect }
        val at = (layout.first[sec.key] ?: 0) + 1 + sec.rows.indexOfFirst { it.ch.key == k }
        LiveView.scrollFor(state.firstVisibleItemIndex, at)?.let { runCatching { state.scrollToItem(it) } }
        delay(120)
        runCatching { (if (k == repo.nowPlaying) playingFr else restoreFr).requestFocus() }
    }

    fun playAt(ch: Item) { focusKey = ch.key; saveView(); val i = flatPos[ch.key] ?: return; val e = repo.gate(ch.locked); if (e == null) { repo.liveSpans = LiveOverlay.spans(sections.map { sectionTitle(it) to it.rows.size }); repo.liveSpanKeys = LiveOverlay.keysOf(flat); play(flat, i) } else repo.status = e }
    fun keyOk(ch: Item) {
        sel?.let { sel = LiveSelect.toggle(it, ch.key); return }
        val now = android.os.SystemClock.uptimeMillis()
        if (DoublePress.isDouble(lastOkKey, lastOkAt, ch.key, now)) {
            pendingPlay?.cancel(); pendingPlay = null; lastOkKey = null
            repo.toggleOn(ch); toast(ctx, if (repo.isOn(ch)) "Unhidden" else "Hidden")
            return
        }
        lastOkKey = ch.key; lastOkAt = now
        pendingPlay?.cancel()
        pendingPlay = scope.launch { delay(DoublePress.WINDOW_MS); pendingPlay = null; playAt(ch) }
    }
    // Hold on a left-nav group hides it (or shows it again); the bar at the bottom offers Undo for a few seconds
    var undo by remember { mutableStateOf<Pair<String, Boolean>?>(null) }   // group key, hidden now
    val undoFr = remember { FocusRequester() }
    LaunchedEffect(undo) { if (undo != null) { delay(120); if (tv) runCatching { undoFr.requestFocus() }; delay(5000); undo = null } }
    fun holdGroup(n: LiveModes.Node) { repo.toggleGroup(n.key); undo = n.key to (n.key in repo.hidden) }
    var expanded by remember { mutableStateOf(repo.liveNavType) }
    fun openNav(type: String) { expanded = type; repo.rememberNavType(type) }
    fun nodeSections(n: LiveModes.Node) = sections.filter { sc -> sc.type == n.type && when {
        n.region -> sc.rows.any { it.region == n.label }
        n.key == Taxonomy.typeKey(n.type) -> true
        else -> sc.label == n.label || sc.label.startsWith(n.label + " · ")
    } }
    fun jumpNode(n: LiveModes.Node) {
        if (n.key == Taxonomy.typeKey(n.type) || LiveCollapse.isTypeCollapsed(n.type, collapsed)) {
            layout.second[n.type]?.let { at -> scope.launch { runCatching { state.animateScrollToItem(at) } } }
            return
        }
        val sec = nodeSections(n).firstOrNull() ?: return
        var at = layout.first[sec.key] ?: return
        if (n.region && sec.key !in collapsed && !LiveCollapse.isTypeCollapsed(n.type, collapsed))
            at += 1 + sec.rows.indexOfFirst { it.region == n.label }.coerceAtLeast(0)
        scope.launch { runCatching { state.animateScrollToItem(at) } }
    }
    fun expandAll() { repo.setCollapsed(LiveCollapse.expandAll()) }
    fun collapseAll() { repo.setCollapsed(LiveCollapse.collapseAll(types)) }
    // Channel / Page up-down jump a whole section and land on its header (1.56)
    fun pageKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val down = when (e.key) { Key.ChannelDown, Key.PageDown, Key.MediaFastForward -> true; Key.ChannelUp, Key.PageUp, Key.MediaRewind -> false; else -> return false }
        if (e.type != KeyEventType.KeyDown) return true
        val cur = focusSec.ifEmpty { collapseLayout.visibleHeaders.firstOrNull().orEmpty() }
        val k = LiveCollapse.step(collapseLayout.visibleHeaders, cur, down) ?: return true
        val at = layout.first[k] ?: return true
        scope.launch { runCatching { state.scrollToItem(at) }; delay(60); runCatching { headFr(k).requestFocus() } }
        return true
    }
    // Live TV options (Menu on the left nav / ⋮ on phone): view state, Select, languages
    fun viewOpts(close: () -> Unit): List<Pair<String, () -> Unit>> = LiveModes.View.values().map { v ->
        ((if (v == view) "✓  " else "     ") + v.label) to { view = v; close() }
    } + listOf("Select channels…" to { sel = emptySet(); close() })
    fun jumpTo(type: String) { layout.second[type]?.let { scope.launch { runCatching { state.animateScrollToItem(it) } } } }

    @Composable
    fun ListPane(modifier: Modifier) {
        Column(modifier) {
            if (ix.state == LiveIndex.State.RUNNING && info.isEmpty()) Label("Sorting channels… ${if (ix.total > 0) ix.done * 100 / ix.total else 0}%", Ts.HEAD, Pal.Dim)
            if (ix.state == LiveIndex.State.FAILED) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(ix.error.ifEmpty { "Could not sort channels" }, Ts.HEAD, Pal.Warn)
                PillButton("Try again", { ix.ensure(repo.channels, repo.liveCats, force = true) }, size = Ts.CAPTION)
            }
            when {
                repo.channels.isEmpty() -> Label(repo.status.ifEmpty { "No channels loaded yet" }, Ts.ROW, Pal.Dim, maxLines = 3)
                sections.isEmpty() -> Label(when {
                    view == LiveModes.View.HIDDEN -> "Nothing hidden."
                    mode != LiveModes.DEFAULT && !browseAll -> "\"$mode\" has no channels yet. Menu > All channels, then hold OK on a channel to add it."
                    langs != null -> "No channels in the selected languages."
                    else -> "No channels to show."
                }, Ts.ROW, Pal.Dim, maxLines = 3)
                else -> LazyColumn(state = state, modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e -> pageKey(e) }, verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 48.dp)) {
                    val firstVisibleKey = sections.firstOrNull { s ->
                        !LiveCollapse.isTypeCollapsed(s.type, collapsed) && s.key !in collapsed && s.rows.isNotEmpty()
                    }?.rows?.first()?.ch?.key
                    sections.groupBy { it.type }.forEach { (type, group) ->
                        val tKey = LiveCollapse.typeKey(type)
                        val typeFolded = tKey in collapsed
                        val nType = group.sumOf { it.rows.size }
                        stickyHeader(key = "t|$type") {
                            TypeHeader(type, nType, typeFolded, tv, Modifier.focusRequester(headFr(tKey))
                                .onFocusChanged { if (it.isFocused) focusSec = tKey }
                                .then(if (rail) Modifier.focusProperties { left = navReq("t:$type") } else Modifier),
                                onToggle = { repo.toggleCollapsed(listOf(tKey)) },
                                onHold = { tree.firstOrNull { it.type == type }?.let { holdGroup(it) } })
                        }
                        if (!typeFolded) {
                            val subs = LiveCollapse.showSubs(group)
                            group.forEach { s ->
                                val folded = s.key in collapsed
                                if (subs) stickyHeader(key = "h|${s.key}") {
                                    SectionHeader(s, folded, tv, Modifier.focusRequester(headFr(s.key))
                                        .onFocusChanged { if (it.isFocused) focusSec = s.key }
                                        .then(if (rail) Modifier.focusProperties { left = navReq("t:" + s.type) } else Modifier),
                                        onToggle = { repo.toggleCollapsed(listOf(s.key)) },
                                        onHold = { LiveModes.nodeForSection(tree, s)?.let { holdGroup(it) } },
                                        underType = true)
                                }
                                if (!folded) items(s.rows, key = { r -> "${s.key}|${r.ch.key}" }) { r ->
                                    val ch = r.ch
                                    val first = ch.key == firstVisibleKey
                                    ChannelRow(repo, ch, r.on, browseAll, ch.key == repo.nowPlaying, tv, sel?.let { ch.key in it },
                                        Modifier
                                            .then(if (ch.key == repo.nowPlaying) Modifier.focusRequester(playingFr)
                                                else if (ch.key == focusKey) Modifier.focusRequester(restoreFr) else Modifier)
                                            .onFocusChanged { if (it.isFocused) { focusKey = ch.key; focusSec = s.key } }
                                            .then(if (first) Modifier.focusRequester(firstFr) else Modifier)
                                            .then(if (rail) Modifier.focusProperties { left = navReq("t:" + s.type) } else Modifier),
                                        onClick = { sel?.let { sel = LiveSelect.toggle(it, ch.key) } ?: playAt(ch) },
                                        onKeyOk = { keyOk(ch) },
                                        onToggle = { if (!tv) { focusSec = s.key; sel = LiveSelect.toggle(sel ?: emptySet(), ch.key) } else repo.toggleOn(ch) },
                                        onSetShown = {
                                            repo.setOnRows(listOf(r), !r.on)
                                            toast(ctx, LiveSelect.summary(1, !r.on, mode))
                                        },
                                        onMenu = { if (sel != null) runCatching { barFr.requestFocus() } else menuCh = ch })
                                }
                            }
                        }
                    }
                }
            }
            sel?.let { cur ->
                val secKeys = sections.firstOrNull { it.key == focusSec }?.rows?.map { it.ch.key }.orEmpty()
                SelectBar(cur.size, mode, tv, barFr,
                    canHide = LiveSelect.targets(rows, cur, false).isNotEmpty(),
                    canShow = LiveSelect.targets(rows, cur, true).isNotEmpty(),
                    allLabel = if (secKeys.isNotEmpty() && cur.containsAll(secKeys)) "Clear section" else "Select section",
                    onHide = { bulk(false) }, onShow = { bulk(true) },
                    onAll = { sel = LiveSelect.selectAll(cur, secKeys) }, onCancel = { sel = null })
            }
            undo?.let { (k, nowHidden) ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(Radii.row)).background(Pal.Surface.copy(alpha = 0.94f))
                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label((if (nowHidden) "Hidden " else "Showing ") + Taxonomy.label(k), Ts.HEAD, Pal.Text, Modifier.weight(1f))
                    PillButton("Undo", { repo.toggleGroup(k); undo = null }, Modifier.focusRequester(undoFr), size = Ts.SECONDARY)
                }
            }
        }
    }

    // TV: the same top menu as Home / Movies / Series, so Up from the list reaches it.
    // Back leaves Live TV in one press (no wasted "focus the tabs first" step).
    val liveTabFr = remember { FocusRequester() }
    var headerFocused by remember { mutableStateOf(false) }
    val header = tv && !AppUi.phoneNav
    if (rail) Column(Modifier.fillMaxSize().onKeyEvent { e ->
        if (e.type == KeyEventType.KeyUp && e.key == Key.Menu && popup == null && menuCh == null) { popup = "more"; true }
        else false
    }) {
        if (header) Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 2.dp)
                .onFocusChanged { headerFocused = it.hasFocus; if (it.hasFocus) navOpen = false },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            NavTab("🔍", onSearch)
            NavTab("Home", { go(Screen.Home) })
            NavTab("Movies", { go(Screen.MoviesTab) })
            NavTab("Series", { go(Screen.SeriesTab) })
            NavTab("Live TV", {}, Modifier.focusRequester(liveTabFr), selected = true)
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            val w by animateDpAsState(if (navOpen) 268.dp else 80.dp, tween(180), label = "nav")
            Column(
                Modifier.width(w).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
                    .background(if (navOpen) Pal.Surface.copy(alpha = 0.94f) else Color.Transparent)
                    // Stay open while D-pad / OK is in the rail. Only close when focus moves to the list (or the top tabs).
                    .onFocusChanged { if (it.hasFocus) navOpen = true }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { popup = "more"; true } else false
                    }
                    .padding(vertical = 16.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val toList = Modifier
                NavEntry("🔍", "Search", navOpen, toList, onClick = onSearch)
                NavEntry("◉", if (mode == LiveModes.DEFAULT) "Mode: Default" else "Mode: $mode", navOpen, toList, onClick = { popup = "modes" })
                Spacer(Modifier.height(10.dp))
                // A plain column (all entries composed) so Left from any channel always finds its section entry
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Tree: type -> sub-categories (and Sports regions) with counts. Focus opens a type; hold OK hides.
                    tree.forEach { n ->
                        val open = expanded == n.type
                        NavEntry(LiveGroups.icon(n.type), n.label, navOpen, Modifier.focusRequester(navReq("t:${n.type}")).then(toList),
                            count = n.count, dim = n.hidden,
                            arrow = if (n.children.isEmpty()) (if (LiveCollapse.isTypeCollapsed(n.type, collapsed)) "▸" else "▾")
                                    else if (open) "▾" else "▸",
                            onFocus = { openNav(n.type); jumpNode(n) }, onLongClick = { holdGroup(n) },
                            onClick = { openNav(n.type); repo.toggleCollapsed(listOf(LiveCollapse.typeKey(n.type))); jumpNode(n) })
                        if (open && navOpen) n.children.forEachIndexed { ci, c ->
                            if (c.region && (ci == 0 || !n.children[ci - 1].region)) Label("Regions", Ts.CAPTION, Pal.Dim, Modifier.padding(start = 44.dp, top = 6.dp))
                            NavEntry(if (c.region) "◦" else "·", c.label, navOpen, Modifier.padding(start = 22.dp), count = c.count, dim = c.hidden, small = true,
                                onFocus = { jumpNode(c) }, onLongClick = { holdGroup(c) },
                                // 1.56: OK on a sub-category folds its channels in the list (or shows them again)
                                onClick = { repo.toggleCollapsed(nodeSections(c).map { it.key }); jumpNode(c) })
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 32.dp, top = 18.dp)
                .onFocusChanged { if (it.hasFocus) navOpen = false }) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Label("Live TV", Ts.TITLE, Pal.Text)
                    Label(listOfNotNull(
                        if (mode == LiveModes.DEFAULT) null else mode,
                        "$modeCount channels",
                        langs?.sorted()?.joinToString(", ")?.let { if (it.length > 40) "${langs.size} languages" else it },
                        if (view != LiveModes.View.ACTIVE) view.label.lowercase() else null
                    ).joinToString("  ·  "), 14, Pal.Dim, Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    PillButton("Expand all", { expandAll() }, size = Ts.SECONDARY, compact = true)
                    PillButton("Collapse all", { collapseAll() }, size = Ts.SECONDARY, compact = true)
                }
                ListPane(Modifier.weight(1f))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Label("Live TV", Ts.TITLE, Pal.Text, Modifier.weight(1f))
                PillButton((if (mode == LiveModes.DEFAULT) "Default" else mode) + " ▾", { popup = "modes" }, size = Ts.SECONDARY)
                PillButton("☰", { popup = "sections" }, size = Ts.SECONDARY)
                PillButton("⋮", { popup = "more" }, size = Ts.SECONDARY)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                PillButton("Expand all", { expandAll() }, size = Ts.SECONDARY, compact = true)
                PillButton("Collapse all", { collapseAll() }, size = Ts.SECONDARY, compact = true)
            }
            ListPane(Modifier.weight(1f))
        }
    }

    when (popup) {
        "modes" -> ModeMenu(repo, rows.filter { it.on }.map { it.ch }) { popup = null }
        "langs" -> LanguagePicker(repo, allLangs) { popup = null }
        "sections" -> ChoiceDialog("Jump to", sections.map { s -> "${LiveGroups.icon(s.type)}  ${sectionTitle(s)}  (${s.rows.size})" to {
            layout.first[s.key]?.let { scope.launch { runCatching { state.scrollToItem(it) } } }; popup = null
        } }, focusKey = "sections", onClose = { popup = null })
        "more" -> ChoiceDialog("Live TV", listOf(
            "Languages…" to { popup = "langs" }) + viewOpts { popup = null } + listOf(
            "Expand all" to { expandAll(); popup = null },
            "Collapse all" to { collapseAll(); popup = null },
            "What to show…" to { popup = null; go(Screen.Categories) },
            "Search" to { popup = null; onSearch() }
        ), focusKey = "more", onClose = { popup = null })
    }
    menuCh?.let { ch ->
        ChannelMenu(repo, repo.named(ch), null, viewOpts { menuCh = null } + listOf("Languages…" to { menuCh = null; popup = "langs" }), null,
            onPlay = { playAt(ch) }) { menuCh = null }
    }
}

private fun sectionTitle(s: LiveModes.Section): String = LiveOverlay.title(s.type, s.label)

/**
 * Hold OK / Enter on the focused row (Fire TV and D-pad): fires [onHold] after a short repeat, and swallows
 * the KeyUp so the normal click (fold / open) does not also run. A short press is left for combinedClickable.
 */
private fun holdOkKey(e: androidx.compose.ui.input.key.KeyEvent, held: Boolean, setHeld: (Boolean) -> Unit, onHold: () -> Unit): Boolean {
    if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return false
    return when (e.type) {
        KeyEventType.KeyDown -> {
            val rc = e.nativeKeyEvent.repeatCount
            when {
                rc == 0 -> { setHeld(false); false }
                !held && rc >= 8 -> { setHeld(true); onHold(); true }
                else -> held
            }
        }
        KeyEventType.KeyUp -> { val swallow = held; setHeld(false); swallow }
        else -> false
    }
}

@Composable
private fun TypeHeader(type: String, count: Int, folded: Boolean, tv: Boolean, modifier: Modifier, onToggle: () -> Unit, onHold: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.row)
    Row(Modifier.fillMaxWidth().background(Pal.Bg).padding(top = 8.dp, bottom = 2.dp).then(modifier).popupOpener()
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e -> holdOkKey(e, held, { held = it }, onHold) }
            .clip(shape).background(if (f) Pal.FocusBg else Color.Transparent)
            .then(if (f) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)
            .combinedClickable(onClick = onToggle, onLongClick = onHold)
            .padding(horizontal = 10.dp, vertical = if (tv) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(if (folded) "▸" else "▾", Ts.SECONDARY, if (f) Pal.Gold else Pal.Dim)
        Label(type.uppercase(), Ts.HEAD, Pal.GoldText, Modifier.weight(1f, fill = false))
        Label("$count", Ts.CAPTION, Pal.Dim)
    }
}

@Composable
private fun SectionHeader(s: LiveModes.Section, folded: Boolean, tv: Boolean, modifier: Modifier, onToggle: () -> Unit, onHold: () -> Unit,
                          underType: Boolean = false) {
    var f by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.row)
    // Focusable header: OK / click folds the section; hold OK (TV) / long-press (phone) hides or shows the group.
    Row(Modifier.fillMaxWidth().background(Pal.Bg).padding(top = 6.dp, bottom = 2.dp).then(modifier).popupOpener()
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e -> holdOkKey(e, held, { held = it }, onHold) }
            .clip(shape).background(if (f) Pal.FocusBg else Color.Transparent)
            .then(if (f) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)
            .combinedClickable(onClick = onToggle, onLongClick = onHold)
            .padding(start = if (underType) 22.dp else 10.dp, end = 10.dp, top = if (tv) 6.dp else 5.dp, bottom = if (tv) 6.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(if (folded) "▸" else "▾", Ts.SECONDARY, if (f) Pal.Gold else Pal.Dim)
        if (!underType) Label(s.type.uppercase(), Ts.HEAD, Pal.GoldText)
        Label(if (underType || !s.label.equals(s.type, true)) s.label else s.type, Ts.HEAD, Pal.Text, Modifier.weight(1f, fill = false))
        Label("${s.rows.size}", Ts.CAPTION, Pal.Dim)
    }
}

/** Left menu entry: icon always, label when the menu is open; focus = soft highlight. */
@Composable
private fun NavEntry(icon: String, label: String, open: Boolean, modifier: Modifier = Modifier, selected: Boolean = false, count: Int = -1,
                     dim: Boolean = false, arrow: String = "", small: Boolean = false,
                     onFocus: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.row)
    val bg by androidx.compose.animation.animateColorAsState(when { f -> Pal.FocusBg; selected -> Pal.ChipSel.copy(alpha = 0.55f); else -> Color.Transparent }, tween(160), label = "nav")
    val fg = if (f) Pal.GoldText else Pal.Text
    Row(
        modifier.fillMaxWidth().popupOpener()
            .onFocusChanged { if (it.isFocused != f) { f = it.isFocused; if (f) onFocus?.invoke() } }
            .then(if (onLongClick != null) Modifier.onPreviewKeyEvent { e -> holdOkKey(e, held, { held = it }, onLongClick!!) } else Modifier)
            .clip(shape)
            .background(bg)
            .then(if (f) Modifier.border(2.dp, Pal.Gold.copy(alpha = 0.95f), shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = if (small) 6.dp else 9.dp)
            .alpha(if (dim && !f) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Label(icon, if (small) Ts.SECONDARY else Ts.ROW + 2, fg)
        if (open) {
            Label(label, if (small) Ts.SECONDARY else Ts.ROW, fg, Modifier.weight(1f))
            if (dim) Label("Hidden", Ts.CAPTION, if (f) Pal.GoldText else Pal.Dim)
            if (count >= 0) Label("$count", Ts.CAPTION, if (f) Pal.GoldText else Pal.Dim)
            if (arrow.isNotEmpty()) Label(arrow, Ts.CAPTION, if (f) Pal.GoldText else Pal.Dim)
        }
    }
}

/**
 * One channel: number badge, logo, clean name, a small language tag, and a compact Hide / Show chip.
 * The chip is a separate focus target so a TV OK / phone tap on it hides or shows the channel (the row's
 * own OK handler must not sit on a parent of the chip, or it would swallow the chip's OK).
 * Dimmed when hidden. OK on the row plays; hold OK / long-press: show or hide (All / Only hidden) or the
 * menu on TV, select mode on the phone; Menu = menu.
 */
@Composable
private fun ChannelRow(repo: Repo, ch: Item, on: Boolean, browseAll: Boolean, playing: Boolean, tv: Boolean, checked: Boolean?, modifier: Modifier,
                       onClick: () -> Unit, onKeyOk: () -> Unit, onToggle: () -> Unit, onSetShown: () -> Unit, onMenu: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.row)
    val local = remember(ch.cat, repo.liveCats) { LiveKind.isLocalNews(repo.liveCats.firstOrNull { it.id == ch.cat }?.title.orEmpty()) }
    val name = remember(ch.name, local) { ChannelTitle.pretty(ch.name, local) }
    val lang = remember(ch.key, repo.liveIndex.info) { ChannelTitle.langTag(repo.liveIndex.info[ch.key]?.lang) }
    val revealPx = with(LocalDensity.current) { LiveSwipe.REVEAL_DP.dp.toPx() }
    var swipe by remember { mutableStateOf(0f) }
    val drag = rememberDraggableState { delta -> swipe = LiveSwipe.clamp(swipe + delta, revealPx) }
    val row = @Composable {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier.weight(1f).popupOpener()
                .onFocusChanged { f = it.isFocused; if (it.isFocused) Keys.focused = ch }
                .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false }
                // Remote OK is handled here only (not on a parent of the Hide / Show chip)
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            val rc = e.nativeKeyEvent.repeatCount
                            if (rc == 0) held = false
                            else if (!held && rc >= 8) { held = true; if (checked == null && browseAll) onToggle() else onMenu() }
                        }
                        KeyEventType.KeyUp -> if (!held) onKeyOk() else held = false
                    }
                    true
                }
                .clip(shape)
                .background(when { f -> Pal.FocusBg; playing -> Pal.Soft; else -> Color.Transparent })
                .then(if (f) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = if (!tv || browseAll) onToggle else onMenu)
                .padding(horizontal = 10.dp, vertical = if (tv) Ts.ROW_PAD_TV else Ts.ROW_PAD)
                .alpha(if (on || checked == true) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1.56: compact row, no schedule (that lives in the player and the channel menu)
            if (checked != null) CheckDot(checked)
            Box(Modifier.widthIn(min = 32.dp), contentAlignment = Alignment.CenterEnd) {
                if (ch.num > 0) Label("${ch.num}", Ts.SECONDARY, if (playing) Pal.GoldText else Pal.Dim)
            }
            Box(Modifier.size(if (tv) 56.dp else 48.dp, if (tv) 34.dp else 30.dp).background(Pal.Card, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                if (ch.img.isNotEmpty()) Poster(ch.img, name, Modifier.fillMaxSize().padding(4.dp), ContentScale.Fit, initialsSize = 12)
                else Label(ChannelTitle.initials(name), Ts.CAPTION, Pal.GoldText)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(name, Ts.ROW, if (playing) Pal.GoldText else Pal.Text, Modifier.weight(1f))
                if (lang.isNotEmpty()) Label(lang, Ts.CAPTION, Pal.Dim)
                if (playing) Label("● NOW", Ts.CAPTION, Pal.Gold)
            }
        }
        HideShowChip(shown = on, tv = tv, onClick = onSetShown)
    }
    }
    if (tv) {
        row()
        return
    }
    // Phone: swipe the row left to reveal Hide / Show and commit past the threshold.
    // The reveal is only drawn while swiping, and the row has an opaque background, so the
    // affordance cannot show through and overlap the Hide / Show chip at rest.
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row))) {
        if (swipe < 0f) {
            Box(Modifier.matchParentSize().background(if (on) Color(0x44C04545) else Pal.Soft2), contentAlignment = Alignment.CenterEnd) {
                Label(if (on) "Hide" else "Show", Ts.SECONDARY, Color.White, Modifier.padding(end = 18.dp))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(Pal.Bg)
                .offset { IntOffset(swipe.roundToInt(), 0) }
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (LiveSwipe.shouldCommit(swipe, revealPx)) onSetShown()
                        swipe = 0f
                    }
                )
        ) { row() }
    }
}

/** Compact per-row Hide / Show control: tappable on the phone, a separate D-pad target (OK) on TV. */
@Composable
private fun HideShowChip(shown: Boolean, tv: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val label = if (shown) "Hide" else "Show"
    Box(
        modifier
            .popupOpener()
            .onFocusChanged { f = it.isFocused }
            .clip(shape)
            .background(when { f -> Pal.Gold; !shown -> Pal.Soft2; else -> Pal.Soft })
            .then(if (f) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = if (tv) 12.dp else 10.dp, vertical = if (tv) 6.dp else 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Label(label, Ts.CAPTION, if (f) Color.Black else Pal.Text)
    }
}

/** Round check for Select mode: gold filled when selected, a soft ring otherwise. */
@Composable
private fun CheckDot(on: Boolean) {
    val c by androidx.compose.animation.animateColorAsState(if (on) Pal.Gold else Color.Transparent, tween(160), label = "check")
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(c).border(2.dp, if (on) Pal.Gold else Pal.Dim, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center) { if (on) Label("✓", Ts.SECONDARY, Color.Black) }
}

/** Slim Select-mode bar at the bottom: count and soft pill actions. */
@Composable
private fun SelectBar(n: Int, mode: String, tv: Boolean, fr: FocusRequester, canHide: Boolean, canShow: Boolean, allLabel: String,
                      onHide: () -> Unit, onShow: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit) {
    val custom = mode != LiveModes.DEFAULT
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(Radii.row)).background(Pal.Surface.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Label(if (n == 0) "Select channels" else "$n selected", Ts.HEAD, Pal.Text, Modifier.weight(1f))
        if (tv) Label("OK check · Menu actions", Ts.CAPTION, Pal.Dim)
        if (canHide) PillButton(if (custom) "Remove from mode" else "Hide", onHide, Modifier.focusRequester(fr), size = Ts.SECONDARY)
        if (canShow) PillButton(if (custom) "Add to mode" else "Unhide", onShow, if (canHide) Modifier else Modifier.focusRequester(fr), size = Ts.SECONDARY)
        PillButton(allLabel, onAll, if (canHide || canShow) Modifier else Modifier.focusRequester(fr), size = Ts.SECONDARY)
        PillButton("Cancel", onCancel, size = Ts.SECONDARY)
    }
}

/** Channel number as a small badge. */
@Composable
fun NumBadge(num: Int, modifier: Modifier = Modifier) {
    if (num <= 0) return
    Box(modifier.background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Label("$num", Ts.CAPTION, Color.White)
    }
}

/** Rounded logo tile (search results): logo (or initials), number badge, ★ when in a mode, gold ring when playing. */
@Composable
fun LiveTile(ch: Item, fav: Boolean, playing: Boolean, checked: Boolean, tv: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(Radii.row)
    val name = ChannelTitle.pretty(ch.name)
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).focusCard(onClick, onMenu, { Keys.focused = ch }, scale = 1.06f, shape = shape)
            .background(Pal.Card, shape)
            .then(if (playing) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)) {
            if (ch.img.isNotEmpty()) Poster(ch.img, name, Modifier.fillMaxSize().padding(if (tv) 14.dp else 10.dp), ContentScale.Fit, initialsSize = if (tv) 26 else 20)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label(ChannelTitle.initials(name), if (tv) Ts.DISPLAY else Ts.TITLE, Pal.GoldText) }
            NumBadge(ch.num, Modifier.align(Alignment.TopStart).padding(5.dp))
            if (fav) Label("★", Ts.HEAD, Pal.Gold, Modifier.align(Alignment.TopEnd).padding(5.dp))
            if (checked) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) { Label("✓", Ts.TITLE, Pal.Gold) }
        }
        Spacer(Modifier.height(5.dp))
        Label(name, Ts.SECONDARY, if (playing) Pal.GoldText else Pal.Text2, maxLines = if (tv) 1 else 2)
    }
}
