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
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch

/**
 * Live TV (v1.52): one clean list, grouped by portal type and category only (News incl. "Local News", Sports, …).
 *  - TV: a short left menu (icons; labels open while it has focus) with
 *    Search, Mode, Languages, Sections and Options. Sections open a jump list.
 *  - Rows: number badge, logo, clean name, small language tag; the channel playing is marked. The schedule is in the player.
 *  - Sections fold: OK on a header (or a sub-category in the left tree) hides / shows its channels; saved.
 *  - Modes: Default (every channel not hidden) or a custom mode (only its channels). "All channels" / "Only hidden"
 *    also list the others, dimmed; each row has a Hide or Show chip (tap / TV OK). Hold OK / long-press still
 *    toggles on TV when browsing hidden, or starts Select on the phone. Menu on a channel = channel menu.
 *    On the phone, Select shows a compact action bar (count, Hide, Show, Select all, Cancel) above the nav bar.
 *  - Languages: only the selected languages are listed (Options / Menu on the remote, or ⋮ on the phone).
 *  - Guide: now / next loaded only for the rows on screen. Back from the player lands on the channel that played.
 */
@Composable
fun Live(repo: Repo, play: (List<Item>, Int) -> Unit, go: (Screen) -> Unit = {}, onSearch: () -> Unit = {}) {
    val tv = rememberIsTv()
    // TV layout (shared top menu + side menu) wherever Home uses it; phones and tablets get the touch layout
    val rail = !AppUi.phoneNav
    // Kept in Repo (and saved): Back from the player must bring back the same list (1.53)
    var view by remember { mutableStateOf(repo.liveView.view) }
    val browseAll = view.showsHidden
    var popup by remember { mutableStateOf<String?>(null) }    // modes | langs | sections
    var menuCh by remember { mutableStateOf<Item?>(null) }

    // 1.72: the background title index waits while Live TV is on screen (nothing competes with the list)
    DisposableEffect(Unit) { repo.liveOpen = true; onDispose { repo.liveOpen = false; repo.lib.indexKick() } }
    LaunchedEffect(repo.rawChannels, repo.liveCats) { repo.liveIndex.ensure(repo.rawChannels, repo.liveCats) }
    val ix = repo.liveIndex
    val info = ix.info
    // 1.70: the list is worked out off the main thread from the saved channel list (no portal); the last result is
    // kept in Repo, so opening Live TV again (or after the start-up pre-build) shows it in the first frame.
    // 1.73: one inputs object per real change (a fresh one every recomposition restarted the layout each time), and a
    // layout already worked out for this mode / view (all are pre-built) is used in this very frame.
    val curMode = repo.mode()
    val inputs = remember(repo.channels, repo.liveCats, repo.liveOrder, repo.hidden, repo.showAdult, repo.liveLangs, info,
        curMode, repo.folders, view) { repo.liveInputs(view) }
    val openedAt = remember { android.os.SystemClock.elapsedRealtime() }
    val produced by produceState(repo.liveModelCached(inputs) ?: repo.liveModelLast, inputs) {
        value = repo.liveModel(inputs)
    }
    val model = repo.liveModelCached(inputs) ?: produced
    // After a hide / show, language or mode edit, lay the other modes / views out again in the background
    LaunchedEffect(repo.hidden, repo.folders, repo.liveLangs, repo.liveOrder, repo.showAdult, info) { repo.prewarmLive() }
    LaunchedEffect(model.inputs != null) {
        if (model.inputs != null) LiveTiming.shown(android.os.SystemClock.elapsedRealtime() - openedAt, model.flat.size)
    }
    val ready = model.inputs != null
    val mode = inputs.mode
    val langs = inputs.langs
    val rows = model.rows
    val tree = model.tree
    val sections = model.sections
    val types = model.types
    // List positions: type + section header index, flat channel list (what the player steps through)
    val collapsed = repo.liveCollapsed
    val collapseLayout = remember(sections, collapsed) { LiveCollapse.layout(sections, collapsed) }
    val layout = remember(model, collapseLayout) {
        Triple(collapseLayout.sectionStart, collapseLayout.typeStart, model.flat)
    }
    val flat = model.flat
    val flatPos = model.flatPos
    val allLangs = model.allLangs
    val modeCount = model.modeCount

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
    // Shared TV side menu (1.99): Left from a channel, header or the first action opens it; Right comes back
    val railFocus = remember { RailFocus() }
    val actionFr = remember { FocusRequester() }
    SideEffect { railFocus.fallback = actionFr }
    val toRail = if (rail) Modifier.focusProperties { left = railFocus.reqs.getValue(RailEntry.LIVE) } else Modifier
    val playingFr = remember { FocusRequester() }
    val restoreFr = remember { FocusRequester() }   // the last focused channel when nothing played yet
    val firstFr = remember { FocusRequester() }
    // Section header focus targets; Channel / Page up-down jump a whole section and land on its header (1.56)
    val headFrs = remember { HashMap<String, FocusRequester>() }
    fun headFr(k: String) = headFrs.getOrPut(k) { FocusRequester() }
    val ctx = LocalContext.current
    // Select mode (bulk hide / unhide): null = off, else the selected channel keys
    var sel by remember { mutableStateOf<Set<String>?>(null) }
    var focusSec by remember { mutableStateOf("") }
    val barFr = remember { FocusRequester() }
    BackHandler(sel != null && !Popups.any) { sel = null }
    fun bulk(on: Boolean) {
        val s0 = sel ?: return
        val targets = rows.filter { it.ch.key in s0 && it.on != on }.distinctBy { it.ch.key }
        if (targets.isNotEmpty()) {
            val leave = targets.map { it.ch.key }.toSet()
            if (!view.shows(on)) {
                val stay = rows.map { it.ch.key }.filter { it !in leave }
                val anchor = targets.first().ch.key
                focusKey = LiveModes.neighborKey(rows, anchor)?.takeIf { it in stay } ?: stay.firstOrNull().orEmpty()
            }
            repo.setOnRows(targets, on)
        }
        toast(ctx, LiveSelect.summary(targets.size, on, mode)); sel = null
    }
    /** Hide/show a channel, keeping list order; if the row leaves this view, move focus to the next row. */
    fun setChannelOn(ch: Item, on: Boolean? = null, row: LiveModes.Row? = null) {
        val nowOn = row?.on ?: repo.isOn(ch)
        val want = on ?: !nowOn
        val leaving = view.shows(nowOn) && !view.shows(want)
        if (leaving) {
            val next = LiveModes.neighborKey(rows, ch.key)
            if (next != null) focusKey = next
        } else {
            focusKey = ch.key
        }
        if (row != null && on != null) repo.setOnRows(listOf(row), on) else repo.toggleOn(ch)
        if (leaving) scope.launch { delay(80); runCatching { restoreFr.requestFocus() } }
    }
    // OK twice quickly on a row hides / unhides it; a single OK plays after the double-press window
    var pendingPlay by remember { mutableStateOf<Job?>(null) }
    var lastOkKey by remember { mutableStateOf<String?>(null) }
    var lastOkAt by remember { mutableStateOf(0L) }

    // 1.70: no guide requests from the list (the guide loads in the player, for the channel playing)
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
            setChannelOn(ch); toast(ctx, if (repo.isOn(ch)) "Unhidden" else "Hidden")
            return
        }
        lastOkKey = ch.key; lastOkAt = now
        pendingPlay?.cancel()
        pendingPlay = scope.launch { delay(DoublePress.WINDOW_MS); pendingPlay = null; playAt(ch) }
    }
    // After hiding a group from the context menu, a short Undo bar is offered
    var undo by remember { mutableStateOf<Pair<String, Boolean>?>(null) }   // group key, hidden now
    val undoFr = remember { FocusRequester() }
    LaunchedEffect(undo) { if (undo != null) { delay(120); if (tv) runCatching { undoFr.requestFocus() }; delay(5000); undo = null } }
    var groupMenu by remember { mutableStateOf<LiveModes.Node?>(null) }
    var expanded by remember { mutableStateOf(repo.liveNavType) }
    fun openNav(type: String) { expanded = type; repo.rememberNavType(type) }
    fun nodeSections(n: LiveModes.Node) = sections.filter { sc -> sc.type == n.type && when {
        n.region -> sc.rows.any { it.region == n.label }
        n.key == Taxonomy.typeKey(n.type) -> true
        else -> sc.label == n.label || sc.label.startsWith(n.label + " · ")
    } }
    fun collapseKeysOf(n: LiveModes.Node): List<String> =
        if (n.key == Taxonomy.typeKey(n.type) && !n.region) listOf(LiveCollapse.typeKey(n.type))
        else nodeSections(n).map { it.key }
    fun nodeFolded(n: LiveModes.Node) = collapseKeysOf(n).let { it.isNotEmpty() && it.all { k -> k in collapsed } }
    fun rowsOf(n: LiveModes.Node) = nodeSections(n).flatMap { it.rows }
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
    } + listOf("Select Channels" to { sel = emptySet(); close() })
    fun jumpTo(type: String) { layout.second[type]?.let { scope.launch { runCatching { state.animateScrollToItem(it) } } } }

    @Composable
    fun ListPane(modifier: Modifier) {
        Column(modifier) {
            if (ix.state == LiveIndex.State.RUNNING && info.isEmpty())
                Label("Sorting channels  ·  ${if (ix.total > 0) ix.done * 100 / ix.total else 0}%", Ts.SECONDARY, Pal.Dim, Modifier.padding(bottom = Layout.S))
            if (ix.state == LiveIndex.State.FAILED) Row(Modifier.padding(bottom = Layout.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.M)) {
                Label(ix.error.ifEmpty { "Could not sort channels" }, Ts.SECONDARY, Pal.Warn)
                PillButton("Try again", { ix.ensure(repo.channels, repo.liveCats, force = true) }, size = Ts.SECONDARY, compact = true)
            }
            when {
                repo.channels.isEmpty() -> Box(Modifier.weight(1f)) {
                    val msg = LiveStatus.empty(repo.localReading, repo.status)
                    // 1.73: the download failed or timed out and nothing is saved: try again on request
                    if (LiveStatus.failed(repo.status)) EmptyState("Channels could not be loaded", msg, warn = true, action = "Retry",
                        onAction = { repo.status = "Downloading channel list…"; repo.refreshAll() })
                    else BrandLoading(msg, Modifier.padding(top = Layout.L))
                }
                !ready -> Box(Modifier.weight(1f)) { BrandLoading("Arranging channels…", Modifier.padding(top = Layout.L)) }   // laid out on Live TV's own thread (a moment)
                sections.isEmpty() -> Box(Modifier.weight(1f)) {
                    when {
                        view == LiveModes.View.HIDDEN -> EmptyState("Nothing hidden", "Every channel in this list is shown.")
                        mode != LiveModes.DEFAULT && !browseAll -> EmptyState("$mode has no channels yet",
                            "Open Options, pick All channels, then hold OK on a channel to add it.")
                        langs != null -> EmptyState("No channels in these languages", "Pick more languages to see more channels.",
                            action = "Languages", onAction = { popup = "langs" })
                        else -> EmptyState("No channels to show", "Open Options to show hidden channels.")
                    }
                }
                else -> LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth().onPreviewKeyEvent { e -> pageKey(e) }, verticalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 4.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)) {
                    val firstVisibleKey = sections.firstOrNull { s ->
                        !LiveCollapse.isTypeCollapsed(s.type, collapsed) && s.key !in collapsed && s.rows.isNotEmpty()
                    }?.rows?.first()?.ch?.key
                    sections.groupBy { it.type }.forEach { (type, group) ->
                        val tKey = LiveCollapse.typeKey(type)
                        val typeFolded = tKey in collapsed
                        val nType = group.sumOf { it.rows.size }
                        stickyHeader(key = "t|$type") {
                            TypeHeader(type, nType, typeFolded, tv, Modifier.focusRequester(headFr(tKey))
                                .onFocusChanged { if (it.isFocused) { focusSec = tKey; railFocus.content = headFr(tKey) } }
                                .then(toRail),
                                onToggle = { repo.toggleCollapsed(listOf(tKey)) },
                                onMenu = { tree.firstOrNull { it.type == type }?.let { groupMenu = it } })
                        }
                        if (!typeFolded) {
                            val subs = LiveCollapse.showSubs(group)
                            group.forEach { s ->
                                val folded = s.key in collapsed
                                if (subs) stickyHeader(key = "h|${s.key}") {
                                    SectionHeader(s, folded, tv, Modifier.focusRequester(headFr(s.key))
                                        .onFocusChanged { if (it.isFocused) { focusSec = s.key; railFocus.content = headFr(s.key) } }
                                        .then(toRail),
                                        onToggle = { repo.toggleCollapsed(listOf(s.key)) },
                                        onMenu = { LiveModes.nodeForSection(tree, s)?.let { groupMenu = it } },
                                        underType = true)
                                }
                                if (!folded) items(s.rows, key = { r -> "${s.key}|${r.ch.key}" }) { r ->
                                    val ch = r.ch
                                    val first = ch.key == firstVisibleKey
                                    ChannelRow(repo, ch, r.on, browseAll, ch.key == repo.nowPlaying, tv, sel?.let { ch.key in it },
                                        Modifier
                                            .then(if (ch.key == repo.nowPlaying) Modifier.focusRequester(playingFr)
                                                else if (ch.key == focusKey) Modifier.focusRequester(restoreFr) else Modifier)
                                            .onFocusChanged { if (it.isFocused) { focusKey = ch.key; focusSec = s.key; railFocus.content = if (ch.key == repo.nowPlaying) playingFr else restoreFr } }
                                            .then(if (first) Modifier.focusRequester(firstFr) else Modifier)
                                            .then(toRail),
                                        onClick = { sel?.let { sel = LiveSelect.toggle(it, ch.key) } ?: playAt(ch) },
                                        onKeyOk = { keyOk(ch) },
                                        onToggle = { if (!tv) { focusSec = s.key; sel = LiveSelect.toggle(sel ?: emptySet(), ch.key) } else setChannelOn(ch, row = r) },
                                        onSetShown = {
                                            setChannelOn(ch, on = !r.on, row = r)
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
                val viewKeys = rows.map { it.ch.key }.distinct()
                val selScope = LiveSelect.selectScope(focusSec, sections, viewKeys)
                val whole = LiveSelect.scopeIsView(selScope, viewKeys)
                SelectBar(cur.size, mode, tv, barFr,
                    canHide = LiveSelect.targets(rows, cur, false).isNotEmpty(),
                    canShow = LiveSelect.targets(rows, cur, true).isNotEmpty(),
                    allLabel = LiveSelect.selectAllLabel(cur, selScope, whole),
                    onHide = { bulk(false) }, onShow = { bulk(true) },
                    onAll = { sel = LiveSelect.selectAll(cur, selScope) }, onCancel = { sel = null })
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

    // TV (1.99): the same frame as Home / Movies / TV Shows: shared top menu, shared side menu, title-safe margins.
    // Left from a channel (or the leftmost tab / action) opens the side menu; Right there comes back.
    // Back leaves Live TV in one press (no wasted "focus the tabs first" step).
    if (rail) Box(Modifier.fillMaxSize().background(Pal.Bg).onKeyEvent { e ->
        if (e.type == KeyEventType.KeyUp && e.key == Key.Menu && popup == null && menuCh == null) { popup = "more"; true }
        else false
    }) {
        // No top wash here: the list's pinned headers sit on the plain page colour
        SideRail(RailEntry.LIVE, railFocus, { e ->
            when (e) {
                RailEntry.SEARCH -> onSearch()
                RailEntry.HOME -> go(Screen.Home)
                RailEntry.MOVIES -> go(Screen.MoviesTab)
                RailEntry.SERIES -> go(Screen.SeriesTab)
                RailEntry.LIVE -> runCatching { railFocus.leave() }
                RailEntry.EXIT -> exitApp(ctx)
            }
        }, Modifier.align(Alignment.CenterStart))
        Column(Modifier.fillMaxSize().padding(start = RAIL_W - 32.dp)) {
            TopNavBar("live", topTabs(go, onSearch), railFocus, { AppUi.accountMenu = true },
                Modifier.padding(start = Layout.TV_SIDE, end = Layout.TV_SIDE, top = Layout.TV_TOP, bottom = Layout.S))
            Column(Modifier.weight(1f).fillMaxWidth().padding(start = Layout.TV_SIDE, end = Layout.TV_SIDE)) {
                // Page header: title, one quiet summary line, and the page actions as pills (like a catalog page)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.S),
                    modifier = Modifier.fillMaxWidth().padding(top = Layout.XS, bottom = Layout.S)) {
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text("Live TV", color = Pal.Text, fontSize = (Ts.TITLE + 4).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Label(liveSummary(mode, modeCount, langs, view), Ts.SECONDARY, Pal.Dim, Modifier.padding(top = 2.dp))
                    }
                    PillButton(if (mode == LiveModes.DEFAULT) "Mode" else mode, { popup = "modes" },
                        Modifier.focusProperties { left = railFocus.reqs.getValue(RailEntry.LIVE) }
                            .onFocusChanged { if (it.isFocused) railFocus.content = actionFr }.focusRequester(actionFr),
                        size = Ts.SECONDARY, compact = true, selected = mode != LiveModes.DEFAULT)
                    PillButton(LiveSummary.langButton(langs), { popup = "langs" }, size = Ts.SECONDARY, compact = true, selected = langs != null)
                    PillButton("Jump to", { popup = "sections" }, size = Ts.SECONDARY, compact = true)
                    PillButton("Options", { popup = "more" }, size = Ts.SECONDARY, compact = true)
                }
                ListPane(Modifier.weight(1f))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Label("Live TV", Ts.TITLE, Pal.Text, Modifier.weight(1f))
                PillButton(if (mode == LiveModes.DEFAULT) "Mode" else mode, { popup = "modes" }, size = Ts.SECONDARY, compact = true, selected = mode != LiveModes.DEFAULT)
                PillButton(LiveSummary.langButton(langs), { popup = "langs" }, size = Ts.SECONDARY, compact = true, selected = langs != null)
                PillButton("⋮", { popup = "more" }, size = Ts.SECONDARY, compact = true)
            }
            Label(liveSummary(mode, modeCount, langs, view), Ts.SECONDARY, Pal.Dim, Modifier.padding(bottom = Layout.S))
            ListPane(Modifier.weight(1f))
        }
    }

    when (popup) {
        "modes" -> ModeMenu(repo, rows.filter { it.on }.map { it.ch }) { popup = null }
        "langs" -> LanguagePicker(repo, allLangs) { popup = null }
        "sections" -> ChoiceDialog("Jump to", sections.map { s -> "${sectionTitle(s)}  ·  ${s.rows.size}" to {
            layout.first[s.key]?.let { scope.launch { runCatching { state.scrollToItem(it) } } }; popup = null
        } }, focusKey = "sections", onClose = { popup = null })
        "more" -> ChoiceDialog("Options", listOf(
            "Expand All" to { expandAll(); popup = null },
            "Collapse All" to { collapseAll(); popup = null },
            "Catalog Selector" to { popup = null; go(Screen.Categories) }
        ) + viewOpts { popup = null } + listOf("Exit ${ctx.getString(R.string.app_name)}" to { popup = null; exitApp(ctx) }), focusKey = "more", onClose = { popup = null })
    }
    menuCh?.let { ch ->
        val row = rows.firstOrNull { it.ch.key == ch.key }
        val nowOn = row?.on ?: repo.isOn(ch)
        ChannelContextMenu(repo, ch, nowOn,
            onShowHide = { setChannelOn(ch, on = !nowOn, row = row) },
            onSelect = { sel = setOf(ch.key) },
            onClose = { menuCh = null })
    }
    groupMenu?.let { n ->
        LiveGroupContextMenu(
            title = n.label,
            groupOn = repo.groupOn(n.key),
            folded = nodeFolded(n),
            onShowHide = {
                repo.toggleGroupKey(n.key, n.label)
                undo = n.key to !repo.groupOn(n.key)
            },
            onShowAll = {
                val rs = rowsOf(n)
                if (rs.isNotEmpty()) repo.setOnRows(rs, true)
                if (!repo.groupOn(n.key)) repo.toggleGroupKey(n.key, n.label)
            },
            onHideAll = {
                val rs = rowsOf(n)
                if (rs.isNotEmpty()) {
                    if (!view.shows(false)) {
                        val leave = rs.map { it.ch.key }.toSet()
                        focusKey = rows.map { it.ch.key }.firstOrNull { it !in leave }.orEmpty()
                    }
                    repo.setOnRows(rs, false)
                }
            },
            onToggleFold = { repo.toggleCollapsed(collapseKeysOf(n)) },
            onExpandAll = { expandAll() },
            onCollapseAll = { collapseAll() },
            onSelect = { sel = rowsOf(n).map { it.ch.key }.toSet() },
            onClose = { groupMenu = null }
        )
    }
}

private fun sectionTitle(s: LiveModes.Section): String = LiveOverlay.title(s.type, GroupTitle.display(s.label, s.type))

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
private fun TypeHeader(type: String, count: Int, folded: Boolean, tv: Boolean, modifier: Modifier, onToggle: () -> Unit, onMenu: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.row)
    val ring by androidx.compose.animation.core.animateFloatAsState(if (f) 1f else 0f, Motion.spec(), label = "typeRing")
    // Styled like a catalog row title: the type name large and white, the count quiet, a small fold chevron
    Row(Modifier.fillMaxWidth().background(Pal.Bg).padding(top = if (tv) Layout.L else Layout.M, bottom = Layout.XS).then(modifier).popupOpener()
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e -> holdOkKey(e, held, { held = it }, onMenu) }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false }
            .focusRing(ring, shape)
            .clip(shape).background(if (f) Pal.FocusBg else Color.Transparent)
            .combinedClickable(onClick = onToggle, onLongClick = onMenu)
            .padding(horizontal = Layout.M, vertical = if (tv) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.M)) {
        androidx.compose.material3.Text(type, color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Label("$count", Ts.SECONDARY, Pal.Dim)
        Spacer(Modifier.weight(1f))
        Label(if (folded) "›" else "⌄", Ts.ROW, if (f) Pal.PrimaryText else Pal.Dim)
    }
}

@Composable
private fun SectionHeader(s: LiveModes.Section, folded: Boolean, tv: Boolean, modifier: Modifier, onToggle: () -> Unit, onMenu: () -> Unit,
                          underType: Boolean = false) {
    var f by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.row)
    val ring by androidx.compose.animation.core.animateFloatAsState(if (f) 1f else 0f, Motion.spec(), label = "secRing")
    // Focusable header: OK / click folds the section; hold OK (TV) / long-press / Menu opens the group menu.
    Row(Modifier.fillMaxWidth().background(Pal.Bg).padding(top = Layout.S, bottom = 2.dp).then(modifier).popupOpener()
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e -> holdOkKey(e, held, { held = it }, onMenu) }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false }
            .focusRing(ring, shape)
            .clip(shape).background(if (f) Pal.FocusBg else Color.Transparent)
            .combinedClickable(onClick = onToggle, onLongClick = onMenu)
            .padding(start = Layout.M, end = Layout.M, top = if (tv) 6.dp else 5.dp, bottom = if (tv) 6.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.S)) {
        val title = if (underType || !s.label.equals(s.type, true)) s.label else s.type
        androidx.compose.material3.Text(if (underType) title else "${s.type}  ·  $title".takeIf { !s.label.equals(s.type, true) } ?: s.type,
            color = Pal.Text2, fontSize = Ts.HEAD.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Label("${s.rows.size}", Ts.CAPTION, Pal.Dim)
        Spacer(Modifier.weight(1f))
        Label(if (folded) "›" else "⌄", Ts.SECONDARY, if (f) Pal.PrimaryText else Pal.Dim)
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
    val ring by androidx.compose.animation.core.animateFloatAsState(if (f) 1f else 0f, Motion.spec(), label = "rowRing")
    // What is on now, from the guide already saved on this device (the list never asks the portal)
    val minute = rememberMinute()
    val nowTitle = repo.epg.guide[ch.id]?.progs?.let { Epg.now(it, minute)?.title }?.takeIf { it.isNotBlank() }
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
                            else if (!held && rc >= 8) { held = true; onMenu() }
                        }
                        KeyEventType.KeyUp -> if (!held) onKeyOk() else held = false
                    }
                    true
                }
                .focusRing(ring, shape)
                .clip(shape)
                .background(when { f -> Pal.FocusBg; playing -> Pal.Soft; else -> Pal.Surface.copy(alpha = 0.55f) })
                .combinedClickable(onClick = onClick, onLongClick = onMenu)
                .padding(horizontal = Layout.M, vertical = if (tv) 6.dp else Ts.ROW_PAD)
                .alpha(if (on || checked == true) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.M)
        ) {
            // 1.99: number, logo tile, name with what is on now (only when the guide is already saved: no portal call
            // from the list), language tag and a Playing badge
            if (checked != null) CheckDot(checked)
            Box(Modifier.widthIn(min = 32.dp), contentAlignment = Alignment.CenterEnd) {
                if (ch.num > 0) Label("${ch.num}", Ts.SECONDARY, if (playing) Pal.PrimaryText else Pal.Dim)
            }
            Box(Modifier.size(if (tv) 58.dp else 52.dp, if (tv) 34.dp else 32.dp).clip(RoundedCornerShape(8.dp)).background(Pal.Card), contentAlignment = Alignment.Center) {
                if (ch.img.isNotEmpty()) Poster(ch.img, name, Modifier.fillMaxSize().padding(5.dp), ContentScale.Fit, initialsSize = 12)
                else Label(ChannelTitle.initials(name), Ts.CAPTION, Pal.PrimaryText)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                androidx.compose.material3.Text(name, color = if (playing) Pal.PrimaryText else Pal.Text, fontSize = Ts.ROW.sp,
                    fontWeight = if (f || playing) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (nowTitle != null) Label(nowTitle, Ts.SECONDARY, Pal.Dim)
            }
            if (lang.isNotEmpty()) Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Pal.Soft).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Label(lang, Ts.CAPTION, Pal.Text2)
            }
            if (playing) Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Pal.Accent).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Label("Playing", Ts.CAPTION, Color.White)
            }
        }
        HideShowChip(shown = on, tv = tv, onClick = onSetShown)
    }
    }
    if (tv) {
        row()
        return
    }
    // Phone: swipe the row left or right (1.68) to reveal Hide / Show and commit past the threshold.
    // The reveal is only drawn while swiping, and the row has an opaque background, so the
    // affordance cannot show through and overlap the Hide / Show chip at rest.
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row))) {
        if (swipe != 0f) {
            val left = swipe < 0f
            Box(Modifier.matchParentSize().background(if (on) Color(0x44C04545) else Pal.Soft2),
                contentAlignment = if (left) Alignment.CenterEnd else Alignment.CenterStart) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.Icon(if (on) Ic.EyeOff else Ic.Eye, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Label(if (on) "Hide" else "Show", Ts.SECONDARY, Color.White)
                }
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
            .background(when { f -> Pal.Focus; !shown -> Pal.Soft2; else -> Pal.Soft })
            .then(if (f) Modifier.border(2.dp, Pal.Focus, shape) else Modifier)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = if (tv) 12.dp else 10.dp, vertical = if (tv) 6.dp else 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Label(label, Ts.CAPTION, if (f) Color.Black else Pal.Text)
    }
}

/** Round check for Select mode: blue filled when selected, a soft ring otherwise. */
@Composable
private fun CheckDot(on: Boolean) {
    val c by androidx.compose.animation.animateColorAsState(if (on) Pal.Primary else Color.Transparent, tween(160), label = "check")
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(c).border(2.dp, if (on) Pal.Primary else Pal.Dim, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center) { if (on) Label("✓", Ts.SECONDARY, Color.White) }
}

/** Slim Select-mode bar at the bottom: count and soft pill actions. */
@Composable
private fun SelectBar(n: Int, mode: String, tv: Boolean, fr: FocusRequester, canHide: Boolean, canShow: Boolean, allLabel: String,
                      onHide: () -> Unit, onShow: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit) {
    val custom = mode != LiveModes.DEFAULT
    val hideLabel = if (custom) "Remove" else "Hide"
    val showLabel = if (custom) "Add" else "Show"
    // First enabled action takes initial focus on TV; Hide / Show stay visible (dimmed) so the bar is predictable.
    val focusHide = canHide
    val focusShow = !canHide && canShow
    val focusAll = !canHide && !canShow
    fun act(enabled: Boolean, focused: Boolean): Modifier =
        Modifier.defaultMinSize(minHeight = 44.dp).then(if (focused) Modifier.focusRequester(fr) else Modifier).alpha(if (enabled) 1f else 0.4f)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(Radii.row))
            .background(Pal.Surface)
            .border(1.dp, Pal.Outline, RoundedCornerShape(Radii.row))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(if (n == 0) "Select channels" else "$n selected", Ts.HEAD, Pal.Text, Modifier.weight(1f))
            if (tv) Label("OK check · Menu actions", Ts.CAPTION, Pal.Dim)
        }
        ButtonFlow(gapDp = 8) {
            PillButton(hideLabel, { if (canHide) onHide() }, act(canHide, focusHide), primary = canHide, compact = true, size = Ts.HEAD)
            PillButton(showLabel, { if (canShow) onShow() }, act(canShow, focusShow), primary = canShow, compact = true, size = Ts.HEAD)
            PillButton(allLabel, onAll, act(true, focusAll), compact = true, size = Ts.HEAD)
            PillButton("Cancel", onCancel, Modifier.defaultMinSize(minHeight = 44.dp), compact = true, size = Ts.HEAD)
        }
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

/** Rounded logo tile (search results): logo (or initials), number badge, ★ when in a mode, blue ring when playing. */
@Composable
fun LiveTile(ch: Item, fav: Boolean, playing: Boolean, checked: Boolean, tv: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(Radii.row)
    val name = ChannelTitle.pretty(ch.name)
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).focusCard(onClick, onMenu, { Keys.focused = ch }, scale = 1.06f, shape = shape)
            .background(Pal.Card, shape)
            .then(if (playing) Modifier.border(2.dp, Pal.Primary, shape) else Modifier)) {
            if (ch.img.isNotEmpty()) Poster(ch.img, name, Modifier.fillMaxSize().padding(if (tv) 14.dp else 10.dp), ContentScale.Fit, initialsSize = if (tv) 26 else 20)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label(ChannelTitle.initials(name), if (tv) Ts.DISPLAY else Ts.TITLE, Pal.PrimaryText) }
            NumBadge(ch.num, Modifier.align(Alignment.TopStart).padding(5.dp))
            if (fav) Label("★", Ts.HEAD, Pal.Highlight, Modifier.align(Alignment.TopEnd).padding(5.dp))
            if (checked) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) { Label("✓", Ts.TITLE, Color.White) }
        }
        Spacer(Modifier.height(5.dp))
        Label(name, Ts.SECONDARY, if (playing) Pal.PrimaryText else Pal.Text2, maxLines = if (tv) 1 else 2)
    }
}

