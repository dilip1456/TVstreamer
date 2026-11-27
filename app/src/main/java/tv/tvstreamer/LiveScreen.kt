@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
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
 * Live TV (v1.99.6): categories on the left, that category's channels on the right.
 *  - Opens on Favourites, or All when Favourites is empty; the last category is not remembered
 *    across sessions. In-session, the selected category stays.
 *  - Visible rows request now / next from the guide, one at a time, only for what is on screen.
 *  - TV: Left from a channel goes to the category list; Left from a category opens the side
 *    menu; Up from the first category goes to the top menu. Back climbs that same way.
 *  - Rewind / Fast forward / Channel +/− pages the channel list. Number keys jump to a channel.
 *  - Phone: a chip row of categories, or two panes when the screen is wide enough.
 *  - Modes, Languages, Options, Select and Hide / Show stay as they were. Back never leaves
 *    the app without confirmation. Long-press Menu still goes to Home.
 */
@Composable
fun Live(repo: Repo, play: (List<Item>, Int) -> Unit, go: (Screen) -> Unit = {}, onSearch: () -> Unit = {}) {
    val tv = rememberIsTv()
    val rail = !AppUi.phoneNav
    val wide = rail || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    var view by remember { mutableStateOf(repo.liveView.view) }
    val browseAll = view.showsHidden
    var popup by remember { mutableStateOf<String?>(null) }
    var menuCh by remember { mutableStateOf<Item?>(null) }

    DisposableEffect(Unit) { repo.liveOpen = true; onDispose { repo.liveOpen = false; repo.lib.indexKick() } }
    LaunchedEffect(repo.rawChannels, repo.liveCats) { repo.liveIndex.ensure(repo.rawChannels, repo.liveCats) }
    val ix = repo.liveIndex
    val info = ix.info
    val curMode = repo.mode()
    val inputs = remember(repo.channels, repo.liveCats, repo.liveOrder, repo.hidden, repo.showAdult, repo.liveLangs, info,
        curMode, repo.folders, view) { repo.liveInputs(view) }
    val openedAt = remember { android.os.SystemClock.elapsedRealtime() }
    val produced by produceState(repo.liveModelCached(inputs) ?: repo.liveModelLast, inputs) {
        value = repo.liveModel(inputs)
    }
    val model = repo.liveModelCached(inputs) ?: produced
    LaunchedEffect(repo.hidden, repo.folders, repo.liveLangs, repo.liveOrder, repo.showAdult, info) { repo.prewarmLive() }
    LaunchedEffect(model.inputs != null) {
        if (model.inputs != null) LiveTiming.shown(android.os.SystemClock.elapsedRealtime() - openedAt, model.flat.size)
    }
    val ready = model.inputs != null
    val mode = inputs.mode
    val langs = inputs.langs
    val rows = model.rows
    val tree = model.tree
    val allLangs = model.allLangs
    val modeCount = model.modeCount

    val favKeysAll = remember(repo.folders) { repo.folders.channels().map { it.key } }
    val haveKeys = remember(rows) { rows.map { it.ch.key }.toSet() }
    val favKeys = remember(favKeysAll, haveKeys) { favKeysAll.filter { it in haveKeys } }
    val recentKeys = remember(repo.nowPlaying, haveKeys) { repo.store.list("recent").map { it.key }.filter { it in haveKeys } }
    val pins = LivePane.pins(favKeys.size, recentKeys.size, rows.size)
    var selected by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val cats = remember(pins, tree, expanded) { LivePane.visible(pins, tree, expanded) }
    val selKey = LivePane.settle(selected.ifEmpty { LivePane.initial(favKeys.size) }, cats, favKeys.size)
    val paneRows = remember(selKey, rows, favKeys, recentKeys, tree) {
        LivePane.rowsOf(selKey, rows, favKeys, recentKeys, tree)
    }
    val paneTitle = LivePane.titleOf(selKey, cats)

    val chState = remember(selKey) {
        repo.listStates.getOrPut("live-ch:$selKey") { androidx.compose.foundation.lazy.LazyListState() }
    }
    val catState = remember { repo.listStates.getOrPut("live-cats") { androidx.compose.foundation.lazy.LazyListState() } }
    // Keep a list state named like the old one so chrome "at top" still works
    val state = chState
    var topBarFocused by remember { mutableStateOf(false) }
    val topBarVisible by remember { derivedStateOf { TvChrome.topBarVisible(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, topBarFocused) } }
    val firstLoad = FirstLoad.of(
        FirstLoad.Kind.LIVE,
        hasPortal = repo.store.portal.isNotBlank() && repo.store.mac.isNotBlank(),
        hasChannels = repo.channels.isNotEmpty(),
        hasCatalog = repo.lib.hasData,
        localReading = repo.localReading,
        liveFetching = repo.liveFetching,
        liveStatus = repo.status,
        channelCount = repo.channels.size,
        catalogLoading = repo.lib.loading,
        catalogDone = repo.lib.done,
        catalogTotal = repo.lib.total,
        catalogSize = repo.lib.size,
        catalogStopped = repo.lib.stopped,
    )
    var focusKey by remember { mutableStateOf(repo.liveView.focus) }
    var focusOnChannels by remember { mutableStateOf(true) }
    fun saveView() = repo.saveLiveView(LiveView(view, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, focusKey))
    LaunchedEffect(view) { saveView() }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { delay(400); saveView() }
    }
    DisposableEffect(Unit) { onDispose { saveView() } }
    val scope = rememberCoroutineScope()
    val navFr = remember { FocusRequester() }
    val actionFr = remember { FocusRequester() }
    SideEffect { SideMenu.fallback = actionFr }
    val openRail: () -> Unit = { SideMenu.open() }
    val catFrs = remember { HashMap<String, FocusRequester>() }
    fun catFr(k: String) = catFrs.getOrPut(k) { FocusRequester() }
    val chFrs = remember { HashMap<String, FocusRequester>() }
    fun chFr(k: String) = chFrs.getOrPut(k) { FocusRequester() }
    val playingFr = remember { FocusRequester() }
    val restoreFr = remember { FocusRequester() }
    val firstFr = remember { FocusRequester() }
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf<Set<String>?>(null) }
    val barFr = remember { FocusRequester() }
    var pendingTop by remember { mutableStateOf(false) }
    val focusTop: () -> Unit = {
        pendingTop = true
        scope.launch { runCatching { state.animateScrollToItem(0) } }
    }
    LaunchedEffect(pendingTop, topBarVisible) {
        if (!pendingTop) return@LaunchedEffect
        if (tv && rail && !topBarVisible) return@LaunchedEffect
        delay(80)
        if (tv && rail) runCatching { navFr.requestFocus() }
        pendingTop = false
    }
    LaunchedEffect(AppChrome.topFocusTick) {
        if (AppChrome.topFocusTick == 0) return@LaunchedEffect
        focusTop()
    }
    MainBackSync(
        isTv = tv && rail,
        railFocused = SideMenu.open,
        topMenuFocused = topBarFocused,
        listState = state,
        closeRail = { SideMenu.close() },
        focusTop = focusTop,
    )
    var jumpBuf by remember { mutableStateOf("") }
    var jumpTick by remember { mutableIntStateOf(0) }
    BackHandler(jumpBuf.isNotEmpty() && !Popups.any) { jumpBuf = "" }
    BackHandler(sel != null && !Popups.any && jumpBuf.isEmpty()) { sel = null }
    BackHandler(focusOnChannels && tv && rail && !Popups.any && sel == null && jumpBuf.isEmpty()) {
        focusOnChannels = false
        scope.launch { delay(40); runCatching { catFr(selKey).requestFocus() } }
    }
    fun stayAfter(keys: List<String>, removed: String) {
        focusKey = FocusStay.afterRemove(keys, removed) ?: removed
    }
    fun bulk(on: Boolean) {
        val s0 = sel ?: return
        val targets = paneRows.filter { it.ch.key in s0 && it.on != on }.distinctBy { it.ch.key }
        if (targets.isNotEmpty()) {
            val leave = targets.map { it.ch.key }.toSet()
            if (!view.shows(on)) {
                focusKey = FocusStay.afterRemoveMany(paneRows.map { it.ch.key }, leave, focusKey).orEmpty()
            }
            repo.setOnRows(targets, on)
        }
        toast(ctx, LiveSelect.summary(targets.size, on, mode)); sel = null
    }
    fun setChannelOn(ch: Item, on: Boolean? = null, row: LiveModes.Row? = null) {
        val nowOn = row?.on ?: repo.isOn(ch)
        val want = on ?: !nowOn
        val leaving = view.shows(nowOn) && !view.shows(want)
        if (leaving) stayAfter(paneRows.map { it.ch.key }, ch.key) else focusKey = ch.key
        if (row != null && on != null) repo.setOnRows(listOf(row), on) else repo.toggleOn(ch)
        if (leaving) scope.launch { delay(80); runCatching { chFr(focusKey).requestFocus() } }
    }
    fun toggleFavStay(ch: Item) {
        if (selKey == LivePane.FAVOURITES && repo.isFav(ch)) stayAfter(paneRows.map { it.ch.key }, ch.key)
        else focusKey = ch.key
        repo.toggleFav(ch)
        scope.launch { delay(80); runCatching { chFr(focusKey).requestFocus() } }
    }
    var pendingPlay by remember { mutableStateOf<Job?>(null) }
    var lastOkKey by remember { mutableStateOf<String?>(null) }
    var lastOkAt by remember { mutableStateOf(0L) }

    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(paneRows.isNotEmpty(), selKey) {
        if (restored || paneRows.isEmpty()) return@LaunchedEffect
        restored = true
        val k = paneRows.firstOrNull { it.ch.key == repo.nowPlaying }?.ch?.key
            ?: paneRows.firstOrNull { it.ch.key == focusKey }?.ch?.key
            ?: paneRows.first().ch.key
        focusKey = k
        val at = paneRows.indexOfFirst { it.ch.key == k }.coerceAtLeast(0)
        LiveView.scrollFor(chState.firstVisibleItemIndex, at)?.let { runCatching { chState.scrollToItem(it) } }
        delay(120)
        if (tv) runCatching { (if (k == repo.nowPlaying) playingFr else restoreFr).requestFocus() }
    }
    LaunchedEffect(AppUi.searchClosed) {
        if (AppUi.searchClosed == 0 || !tv) return@LaunchedEffect
        val k = SearchReturn.liveTarget(focusKey, repo.nowPlaying).ifEmpty { return@LaunchedEffect }
        val at = paneRows.indexOfFirst { it.ch.key == k }
        if (at < 0) return@LaunchedEffect
        focusOnChannels = true
        LiveView.scrollFor(chState.firstVisibleItemIndex, at)?.let { runCatching { chState.scrollToItem(it) } }
        delay(120)
        runCatching { (if (k == repo.nowPlaying) playingFr else restoreFr).requestFocus() }
    }
    // After hide / unfavourite, keep focus on the neighbour, not the top of the list
    LaunchedEffect(focusKey, paneRows.map { it.ch.key }) {
        if (!tv || !focusOnChannels || focusKey.isEmpty()) return@LaunchedEffect
        if (paneRows.none { it.ch.key == focusKey }) return@LaunchedEffect
        delay(16)
        runCatching { chFr(focusKey).requestFocus() }
    }

    fun playAt(ch: Item) {
        focusKey = ch.key; saveView()
        val list = paneRows.map { it.ch }
        val i = list.indexOfFirst { it.key == ch.key }.takeIf { it >= 0 } ?: return
        val e = repo.gate(ch.locked)
        if (e == null) {
            repo.liveSpans = LiveOverlay.spans(listOf(paneTitle to list.size))
            repo.liveSpanKeys = LiveOverlay.keysOf(list)
            play(list, i)
        } else repo.status = e
    }
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

    var undo by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val undoFr = remember { FocusRequester() }
    LaunchedEffect(undo) { if (undo != null) { delay(120); if (tv) runCatching { undoFr.requestFocus() }; delay(5000); undo = null } }
    var groupMenu by remember { mutableStateOf<LiveModes.Node?>(null) }

    fun pickCat(c: LivePane.Cat, toChannels: Boolean) {
        if (c.key == LivePane.SEARCH) { onSearch(); return }
        selected = c.key
        if (c.expandable && !toChannels) expanded = LivePane.toggleExpand(expanded, c.key)
        if (c.expandable && toChannels) expanded = LivePane.expandedFor(c.key, tree, expanded)
        if (toChannels || !c.expandable) {
            focusOnChannels = true
            scope.launch {
                delay(40)
                val k = paneRows.firstOrNull { it.ch.key == focusKey }?.ch?.key ?: paneRows.firstOrNull()?.ch?.key
                if (k != null) runCatching { chFr(k).requestFocus() }
            }
        }
    }
    fun expandAll() { expanded = tree.filter { it.children.isNotEmpty() }.map { it.key }.toSet() }
    fun collapseAll() { expanded = emptySet() }

    fun pageKey(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val down = when (e.key) { Key.ChannelDown, Key.PageDown, Key.MediaFastForward -> true; Key.ChannelUp, Key.PageUp, Key.MediaRewind -> false; else -> return false }
        if (e.type != KeyEventType.KeyDown) return true
        if (e.nativeKeyEvent.repeatCount > 0 && e.nativeKeyEvent.repeatCount % 3 != 0) return true
        val keys = paneRows.map { it.ch.key }
        if (keys.isEmpty()) return true
        val i = keys.indexOf(focusKey).let { if (it < 0) 0 else it }
        val at = LivePane.pageIndex(i, down, keys.size)
        val k = keys[at]
        focusKey = k
        focusOnChannels = true
        scope.launch { runCatching { chState.scrollToItem(at) }; delay(60); runCatching { chFr(k).requestFocus() } }
        return true
    }
    fun listKeys(e: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (Popups.any) return false
        if (pageKey(e)) return true
        if (e.type != KeyEventType.KeyDown) return false
        val digit = ChannelJump.digitOfCode(e.nativeKeyEvent.keyCode.toLong())
        if (digit != null) {
            jumpBuf = ChannelJump.append(jumpBuf, digit)
            jumpTick++
            return true
        }
        return false
    }
    LaunchedEffect(jumpTick, jumpBuf) {
        if (jumpBuf.isEmpty()) return@LaunchedEffect
        delay(ChannelJump.TIMEOUT_MS)
        val hit = ChannelJump.find(jumpBuf, paneRows, rows)
        jumpBuf = ""
        if (hit == null) return@LaunchedEffect
        if (hit.switchToAll) selected = LivePane.ALL
        focusKey = hit.key
        focusOnChannels = true
    }

    // Gentle now / next for visible rows only
    LaunchedEffect(selKey, chState, paneRows.size) {
        snapshotFlow { chState.layoutInfo.visibleItemsInfo.map { it.index } }.collect { vis ->
            if (vis.isEmpty() || paneRows.isEmpty()) return@collect
            val ids = Epg.ahead(vis, paneRows.size).mapNotNull { paneRows.getOrNull(it)?.ch?.id }.filter { it.isNotBlank() }
            if (ids.isNotEmpty()) repo.epg.want(ids)
        }
    }

    fun viewOpts(close: () -> Unit): List<Pair<String, () -> Unit>> = LiveModes.View.values().map { v ->
        ((if (v == view) "✓  " else "     ") + v.label) to { view = v; close() }
    } + listOf("Select Channels" to { sel = emptySet(); close() })

    @Composable
    fun ChannelList(modifier: Modifier) {
        LazyColumn(state = chState, modifier = modifier.onPreviewKeyEvent { listKeys(it) }) {
            itemsIndexed(paneRows, key = { _, r -> r.ch.key }) { i, r ->
                val ch = r.ch
                val fr = when {
                    ch.key == repo.nowPlaying -> playingFr
                    ch.key == focusKey -> restoreFr
                    i == 0 -> firstFr
                    else -> chFr(ch.key)
                }
                // Always also register under the key so stay-after-hide can find it
                val keyFr = chFr(ch.key)
                ChannelRow(repo, ch, r.on, browseAll, ch.key == repo.nowPlaying, tv, sel?.let { ch.key in it },
                    Modifier
                        .focusRequester(fr)
                        .then(if (fr !== keyFr) Modifier.focusRequester(keyFr) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                focusKey = ch.key
                                focusOnChannels = true
                                SideMenu.content = if (ch.key == repo.nowPlaying) playingFr else restoreFr
                            }
                        }
                        .then(
                            if (tv && rail) Modifier.onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                                    focusOnChannels = false
                                    scope.launch { delay(20); runCatching { catFr(selKey).requestFocus() } }
                                    true
                                } else false
                            } else Modifier
                        ),
                    onClick = { if (sel != null) keyOk(ch) else playAt(ch) },
                    onKeyOk = { keyOk(ch) },
                    onToggle = { setChannelOn(ch) },
                    onSetShown = { setChannelOn(ch, on = !r.on, row = r) },
                    onMenu = { menuCh = ch })
            }
        }
    }

    @Composable
    fun CatList(modifier: Modifier) {
        LazyColumn(state = catState, modifier = modifier) {
            items(cats, key = { it.key }) { c ->
                val selectedCat = c.key == selKey
                var f by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(Radii.row)
                val ring by androidx.compose.animation.core.animateFloatAsState(if (f) 1f else 0f, Motion.spec(), label = "catRing")
                val isFirst = c.key == cats.firstOrNull()?.key
                Row(
                    Modifier.fillMaxWidth()
                        .focusRequester(catFr(c.key))
                        .onFocusChanged {
                            f = it.isFocused
                            if (it.isFocused) {
                                focusOnChannels = false
                                SideMenu.content = catFr(c.key)
                            }
                        }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionRight -> { pickCat(c, toChannels = true); true }
                                Key.DirectionLeft -> if (tv && rail) { SideMenu.open(); true } else false
                                Key.DirectionUp -> if (isFirst && tv && rail) { focusTop(); true } else false
                                else -> false
                            }
                        }
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp && e.key == Key.Menu && !LivePane.isPin(c.key)) {
                                LivePane.findNode(tree, c.key)?.let { groupMenu = it }; true
                            } else false
                        }
                        .focusRing(ring, shape)
                        .clip(shape)
                        .background(when { f -> Pal.FocusBg; selectedCat -> Pal.Soft; else -> Color.Transparent })
                        .combinedClickable(onClick = { pickCat(c, toChannels = !c.expandable) }, onLongClick = {
                            LivePane.findNode(tree, c.key)?.let { groupMenu = it }
                        })
                        .padding(start = (Layout.M.value + c.depth * 16).dp, end = Layout.M, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Layout.S)
                ) {
                    androidx.compose.material3.Text(c.label, color = if (selectedCat || f) Pal.PrimaryText else Pal.Text,
                        fontSize = if (c.depth == 0) Ts.HEAD.sp else Ts.SECONDARY.sp,
                        fontWeight = if (selectedCat || f) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (c.key != LivePane.SEARCH) Label("${c.count}", Ts.CAPTION, Pal.Dim)
                    Spacer(Modifier.weight(1f))
                    if (c.expandable) Label(if (c.key in expanded) "⌄" else "›", Ts.SECONDARY, if (f) Pal.PrimaryText else Pal.Dim)
                }
            }
        }
    }

    @Composable
    fun ChipRow() {
        val chipCats = LivePane.chips(pins, tree, selKey)
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = Layout.S)
        ) {
            items(chipCats, key = { it.key }) { c ->
                val on = c.key == selKey
                PillButton(
                    if (c.key == LivePane.SEARCH || c.count <= 0) c.label else "${c.label}  ·  ${c.count}",
                    { pickCat(c, toChannels = true) },
                    size = Ts.SECONDARY, compact = true, selected = on
                )
            }
        }
    }

    @Composable
    fun ListPane(modifier: Modifier) {
        Column(modifier) {
            if (ix.state == LiveIndex.State.RUNNING && info.isEmpty())
                Label("Sorting channels  ·  ${if (ix.total > 0) ix.done * 100 / ix.total else 0}%", Ts.SECONDARY, Pal.Dim, Modifier.padding(bottom = Layout.S))
            if (ix.state == LiveIndex.State.FAILED) Row(Modifier.padding(bottom = Layout.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.M)) {
                Label(ix.error.ifEmpty { "Could not sort channels" }, Ts.SECONDARY, Pal.Warn)
                PillButton("Try again", { ix.ensure(repo.channels, repo.liveCats, force = true) }, size = Ts.SECONDARY, compact = true)
            }
            if (jumpBuf.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(bottom = Layout.S), contentAlignment = Alignment.Center) {
                    Box(Modifier.clip(RoundedCornerShape(Radii.row)).background(Pal.Surface).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Label(jumpBuf, Ts.TITLE, Pal.PrimaryText)
                    }
                }
            }
            when {
                repo.channels.isEmpty() -> Box(Modifier.weight(1f)) {
                    val msg = LiveStatus.empty(repo.localReading, repo.status)
                    if (LiveStatus.failed(repo.status)) EmptyState("Channels could not be loaded", msg, warn = true, action = "Retry",
                        onAction = { repo.status = "Downloading channel list…"; repo.refreshAll() })
                    else if (firstLoad.visible) FirstLoadPanel(firstLoad, onStop = { repo.lib.stop() }, Modifier.padding(top = Layout.L), onResume = { repo.lib.resume() })
                    else BrandLoading(msg, Modifier.padding(top = Layout.L))
                }
                !ready -> Box(Modifier.weight(1f)) { BrandLoading("Arranging channels…", Modifier.padding(top = Layout.L)) }
                else -> {
                    if (!wide) ChipRow()
                    val emptyPane = paneRows.isEmpty()
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (wide) {
                            Column(Modifier.width(if (tv) 280.dp else 240.dp).fillMaxHeight().padding(end = Layout.M)) {
                                CatList(Modifier.weight(1f))
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (emptyPane) {
                                val empty = when (selKey) {
                                    LivePane.RECENT -> "Channels you play will show up here." to "No recent channels"
                                    LivePane.FAVOURITES -> "Add a channel to Favourites from its menu." to "No favourites"
                                    else -> "Open Options to show hidden channels, or pick another category." to "No channels to show"
                                }
                                val hiddenEmpty = view == LiveModes.View.HIDDEN
                                val modeEmpty = mode != LiveModes.DEFAULT && !browseAll
                                when {
                                    hiddenEmpty && rows.isEmpty() -> EmptyState("Nothing hidden", "Every channel in this list is shown.")
                                    modeEmpty && rows.isEmpty() -> EmptyState("$mode has no channels yet",
                                        "Open Options, pick All channels, then hold OK on a channel to add it.")
                                    langs != null && rows.isEmpty() -> EmptyState("No channels in the selected languages",
                                        "Open Languages to include more.", action = "Languages", onAction = { popup = "langs" })
                                    else -> EmptyState(empty.second, empty.first)
                                }
                            } else ChannelList(Modifier.fillMaxSize())
                        }
                    }
                }
            }
            sel?.let { cur ->
                val viewKeys = paneRows.map { it.ch.key }.distinct()
                SelectBar(cur.size, mode, tv, barFr,
                    canHide = LiveSelect.targets(paneRows, cur, false).isNotEmpty(),
                    canShow = LiveSelect.targets(paneRows, cur, true).isNotEmpty(),
                    allLabel = LiveSelect.selectAllLabel(cur, viewKeys, true),
                    onHide = { bulk(false) }, onShow = { bulk(true) },
                    onAll = { sel = LiveSelect.selectAll(cur, viewKeys) }, onCancel = { sel = null })
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

    if (rail) Box(Modifier.fillMaxSize().background(Pal.Bg).onKeyEvent { e ->
        if (e.type == KeyEventType.KeyUp && e.key == Key.Menu && popup == null && menuCh == null) { popup = "more"; true }
        else false
    }) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = topBarVisible,
                enter = slideInVertically(tween(Motion.SCROLL_MS)) { -it } + fadeIn(tween(Motion.SCROLL_MS)),
                exit = slideOutVertically(tween(Motion.SCROLL_MS)) { -it } + fadeOut(tween(Motion.SCROLL_MS))
            ) {
                TopNavBar("live", topTabs(go, onSearch), if (tv && rail) ({ SideMenu.open() }) else null, { AppUi.accountMenu = true },
                    Modifier.padding(start = Layout.TV_SIDE, end = Layout.TV_SIDE, top = Layout.TV_TOP, bottom = Layout.S)
                        .onFocusChanged { topBarFocused = it.hasFocus }) { k ->
                        if (k == "live") Modifier.focusRequester(navFr) else Modifier
                    }
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(start = Layout.TV_SIDE, end = Layout.TV_SIDE)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.S),
                    modifier = Modifier.fillMaxWidth().padding(top = Layout.XS, bottom = Layout.S)) {
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text("Live TV", color = Pal.Text, fontSize = (Ts.TITLE + 4).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Label(liveSummary(mode, modeCount, langs, view), Ts.SECONDARY, Pal.Dim, Modifier.padding(top = 2.dp))
                        if (cats.isNotEmpty()) Label("Rewind or Channel +/− pages the list  ·  Left opens categories",
                            Ts.CAPTION, Pal.Dim.copy(alpha = 0.8f), Modifier.padding(top = 2.dp))
                    }
                    PillButton(if (mode == LiveModes.DEFAULT) "Mode" else mode, { popup = "modes" },
                        Modifier.onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { SideMenu.open(actionFr); true } else false
                        }.onFocusChanged { if (it.isFocused) SideMenu.rememberContent(actionFr) }.focusRequester(actionFr),
                        size = Ts.SECONDARY, compact = true, selected = mode != LiveModes.DEFAULT)
                    PillButton(LiveSummary.langButton(langs), { popup = "langs" }, size = Ts.SECONDARY, compact = true, selected = langs != null)
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
        "more" -> ChoiceDialog("Options", listOf(
            AppChrome.GO_HOME to { popup = null; AppChrome.goHome() },
            "Expand All" to { expandAll(); popup = null },
            "Collapse All" to { collapseAll(); popup = null },
            "Catalog Selector" to { popup = null; go(Screen.Categories) }
        ) + viewOpts { popup = null } + listOf("Exit ${ctx.getString(R.string.app_name)}" to { popup = null; exitApp(ctx) }), focusKey = "more", onClose = { popup = null })
    }
    menuCh?.let { ch ->
        val row = paneRows.firstOrNull { it.ch.key == ch.key } ?: rows.firstOrNull { it.ch.key == ch.key }
        val nowOn = row?.on ?: repo.isOn(ch)
        ChannelContextMenu(repo, ch, nowOn,
            onShowHide = { setChannelOn(ch, on = !nowOn, row = row) },
            onSelect = { sel = setOf(ch.key) },
            onClose = { menuCh = null },
            onFav = { toggleFavStay(ch) })
    }
    groupMenu?.let { n ->
        val nodeRows = LivePane.rowsOf(n.key, rows, favKeys, recentKeys, tree)
        LiveGroupContextMenu(
            title = n.label,
            groupOn = repo.groupOn(n.key),
            folded = n.key !in expanded && n.children.isNotEmpty(),
            onShowHide = {
                repo.toggleGroupKey(n.key, n.label)
                undo = n.key to !repo.groupOn(n.key)
            },
            onShowAll = {
                if (nodeRows.isNotEmpty()) repo.setOnRows(nodeRows, true)
                if (!repo.groupOn(n.key)) repo.toggleGroupKey(n.key, n.label)
            },
            onHideAll = {
                if (nodeRows.isNotEmpty()) {
                    if (!view.shows(false)) {
                        val leave = nodeRows.map { it.ch.key }.toSet()
                        focusKey = FocusStay.afterRemoveMany(paneRows.map { it.ch.key }, leave, focusKey).orEmpty()
                    }
                    repo.setOnRows(nodeRows, false)
                }
            },
            onToggleFold = { expanded = LivePane.toggleExpand(expanded, n.key) },
            onExpandAll = { expandAll() },
            onCollapseAll = { collapseAll() },
            onSelect = { sel = nodeRows.map { it.ch.key }.toSet() },
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
    val nextTitle = repo.epg.guide[ch.id]?.progs?.let { Epg.next(it, minute)?.title }?.takeIf { it.isNotBlank() }
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
                if (nowTitle != null) Label(nowTitle, Ts.SECONDARY, Pal.Dim, maxLines = 1)
                if (nextTitle != null) Label("Next: $nextTitle", Ts.CAPTION, Pal.Dim, maxLines = 1)
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
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).focusCard(onClick, onMenu, { Keys.focused = ch }, scale = if (tv) FocusPivot.CARD_SCALE else 1.06f, shape = shape)
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

