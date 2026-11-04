@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live TV (v1.52): one clean list, grouped by portal type and category only (News incl. "Local News", Sports, …).
 *  - TV: a Netflix-style left menu (icons; labels open while it has focus, it collapses when focus moves right) with
 *    Search, Mode, All channels, Languages and the sections. Left on a channel goes to its section in the menu.
 *  - Rows: number badge, logo, clean name, what is on now with a progress bar, what is next; the channel playing is marked.
 *  - Modes: Default (every channel not hidden) or a custom mode (only its channels). "All channels" also lists the
 *    others, dimmed and marked Hidden; hold OK / long-press shows or hides one. Menu on a channel = channel menu.
 *  - Languages: only the selected languages are listed (Menu on the left menu, or its Languages entry; phone: ⋮).
 *  - Guide: now / next loaded only for the rows on screen. Back from the player lands on the channel that played.
 */
@Composable
fun Live(repo: Repo, play: (List<Item>, Int) -> Unit, onSearch: () -> Unit = {}) {
    val tv = rememberIsTv()
    val wide = LocalContext.current.resources.configuration.smallestScreenWidthDp >= 600
    val rail = tv || wide
    var browseAll by remember { mutableStateOf(false) }
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
    val rows = remember(base, mode, repo.folders, repo.hidden, browseAll, langs, info) {
        LiveModes.rows(base, mode, repo.folders, repo.hidden, browseAll, langs, info)
    }
    val sections = remember(rows, info, catTitle, catOrder, mode) {
        LiveModes.sections(rows, info, catTitle, catOrder, if (mode != LiveModes.DEFAULT) repo.folders.items(mode).map { it.key } else null)
    }
    val types = remember(sections) { sections.map { it.type }.distinct() }
    // List positions: section header index, flat channel list (what the player steps through)
    val layout = remember(sections) {
        val starts = HashMap<String, Int>(); val typeStart = HashMap<String, Int>()
        var i = 0
        for (s in sections) { starts[s.key] = i; typeStart.putIfAbsent(s.type, i); i += 1 + s.rows.size }
        Triple(starts, typeStart, sections.flatMap { s -> s.rows.map { it.ch } })
    }
    val flat = layout.third
    val flatPos = remember(flat) { flat.withIndex().associate { it.value.key to it.index } }
    val allLangs = remember(base, info) { LiveModes.languages(base, info) }
    val modeCount = rows.count { it.on }

    val state = remember { repo.listStates.getOrPut("live152") { androidx.compose.foundation.lazy.LazyListState() } }
    val scope = rememberCoroutineScope()
    val navFr = remember { HashMap<String, FocusRequester>() }
    fun navReq(k: String) = navFr.getOrPut(k) { FocusRequester() }
    val playingFr = remember { FocusRequester() }
    val firstFr = remember { FocusRequester() }
    var navOpen by remember { mutableStateOf(false) }

    // Guide for the rows on screen only (debounced in EpgLoader)
    LaunchedEffect(state, flat) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.substringAfter('|', "")?.takeIf { k -> k.startsWith("itv:") } } }
            .collect { keys -> repo.epg.want(keys.map { it.removePrefix("itv:") }) }
    }
    // Back from the player: land on the channel that was playing (else the first channel)
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(flat.isNotEmpty()) {
        if (restored || flat.isEmpty()) return@LaunchedEffect
        restored = true
        val k = repo.nowPlaying
        val sec = sections.firstOrNull { s -> s.rows.any { it.ch.key == k } }
        if (sec != null) {
            val at = (layout.first[sec.key] ?: 0) + 1 + sec.rows.indexOfFirst { it.ch.key == k }
            runCatching { state.scrollToItem((at - 2).coerceAtLeast(0)) }
            delay(120); if (tv) runCatching { playingFr.requestFocus() }
        } else if (tv) { delay(150); runCatching { firstFr.requestFocus() } }
    }

    fun playAt(ch: Item) { val i = flatPos[ch.key] ?: return; val e = repo.gate(ch.locked); if (e == null) play(flat, i) else repo.status = e }
    fun jumpTo(type: String) { layout.second[type]?.let { scope.launch { runCatching { state.animateScrollToItem(it) } } } }

    @Composable
    fun ListPane(modifier: Modifier) {
        Column(modifier) {
            if (ix.state == LiveIndex.State.RUNNING && info.isEmpty()) Label("Sorting channels… ${if (ix.total > 0) ix.done * 100 / ix.total else 0}%", 14, Pal.Dim)
            if (ix.state == LiveIndex.State.FAILED) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(ix.error.ifEmpty { "Could not sort channels" }, 14, Pal.Warn)
                PillButton("Try again", { ix.ensure(repo.channels, repo.liveCats, force = true) }, size = 12)
            }
            when {
                repo.channels.isEmpty() -> Label(repo.status.ifEmpty { "No channels loaded yet" }, 16, Pal.Dim, maxLines = 3)
                sections.isEmpty() -> Label(when {
                    mode != LiveModes.DEFAULT && !browseAll -> "\"$mode\" has no channels yet. Open All channels and hold OK on a channel to add it."
                    langs != null -> "No channels in the selected languages."
                    else -> "No channels to show."
                }, 16, Pal.Dim, maxLines = 3)
                else -> LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 48.dp)) {
                    sections.forEachIndexed { si, s ->
                        stickyHeader(key = "h|${s.key}") { SectionHeader(s) }
                        items(s.rows, key = { r -> "${s.key}|${r.ch.key}" }) { r ->
                            val ch = r.ch
                            val first = si == 0 && r === s.rows.first()
                            ChannelRow(repo, ch, r.on, browseAll, ch.key == repo.nowPlaying, tv,
                                Modifier
                                    .then(if (ch.key == repo.nowPlaying) Modifier.focusRequester(playingFr) else Modifier)
                                    .then(if (first) Modifier.focusRequester(firstFr) else Modifier)
                                    .then(if (rail) Modifier.focusProperties { left = navReq("t:" + s.type) } else Modifier),
                                onClick = { playAt(ch) },
                                onToggle = { repo.toggleOn(ch) },
                                onMenu = { menuCh = ch })
                        }
                    }
                }
            }
        }
    }

    if (rail) {
        Row(Modifier.fillMaxSize()) {
            val w by animateDpAsState(if (navOpen) 264.dp else 76.dp, tween(180), label = "nav")
            Column(
                Modifier.width(w).fillMaxHeight().background(if (navOpen) Pal.Surface.copy(alpha = 0.96f) else Color.Transparent)
                    .onFocusChanged { navOpen = it.hasFocus }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { popup = "langs"; true } else false
                    }
                    .padding(vertical = 18.dp, horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val toList = Modifier
                NavEntry("🔍", "Search", navOpen, toList, onClick = onSearch)
                NavEntry("◉", if (mode == LiveModes.DEFAULT) "Mode: Default" else "Mode: $mode", navOpen, toList, onClick = { popup = "modes" })
                NavEntry(if (browseAll) "◎" else "◌", if (browseAll) "All channels (on)" else "All channels", navOpen, toList, selected = browseAll,
                    onClick = { browseAll = !browseAll })
                NavEntry("🌐", if (langs == null) "Languages: all" else "Languages: ${langs.size}", navOpen, toList, onClick = { popup = "langs" })
                Spacer(Modifier.height(10.dp))
                // A plain column (all entries composed) so Left from any channel always finds its section entry
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    types.forEach { t ->
                        val n = sections.filter { it.type == t }.sumOf { it.rows.size }
                        NavEntry(LiveGroups.icon(t), t, navOpen, Modifier.focusRequester(navReq("t:$t")).then(toList),
                            count = n, onFocus = { jumpTo(t) }, onClick = { jumpTo(t) })
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 32.dp, top = 18.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    Label("Live TV", 26, Pal.Text)
                    Label(listOfNotNull(
                        if (mode == LiveModes.DEFAULT) null else mode,
                        "$modeCount channels",
                        langs?.sorted()?.joinToString(", ")?.let { if (it.length > 40) "${langs.size} languages" else it },
                        if (browseAll) "showing hidden" else null
                    ).joinToString("  ·  "), 14, Pal.Dim, Modifier.padding(bottom = 4.dp))
                }
                ListPane(Modifier.weight(1f))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Label("Live TV", 22, Pal.Text, Modifier.weight(1f))
                PillButton((if (mode == LiveModes.DEFAULT) "Default" else mode) + " ▾", { popup = "modes" }, size = 13)
                PillButton("☰", { popup = "sections" }, size = 13)
                PillButton("⋮", { popup = "more" }, size = 13)
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
            "Languages…" to { popup = "langs" },
            (if (browseAll) "Hide hidden channels" else "Show all channels (incl. hidden)") to { browseAll = !browseAll; popup = null },
            "Search" to { popup = null; onSearch() }
        ), focusKey = "more", onClose = { popup = null })
    }
    menuCh?.let { ch ->
        ChannelMenu(repo, repo.named(ch), null, listOf("Languages…" to { menuCh = null; popup = "langs" }), null,
            onPlay = { playAt(ch) }) { menuCh = null }
    }
}

private fun sectionTitle(s: LiveModes.Section): String = if (s.label.equals(s.type, true)) s.type else "${s.type} · ${s.label}"

@Composable
private fun SectionHeader(s: LiveModes.Section) {
    Row(Modifier.fillMaxWidth().background(Pal.Bg).padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(s.type.uppercase(), 13, Pal.GoldText)
        if (!s.label.equals(s.type, true)) Label(s.label, 15, Pal.Text)
        Label("${s.rows.size}", 12, Pal.Dim)
    }
}

/** Left menu entry: icon always, label when the menu is open; focus = soft highlight. */
@Composable
private fun NavEntry(icon: String, label: String, open: Boolean, modifier: Modifier = Modifier, selected: Boolean = false, count: Int = -1,
                     onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().popupOpener()
            .onFocusChanged { if (it.isFocused != f) { f = it.isFocused; if (f) onFocus?.invoke() } }
            .clip(RoundedCornerShape(12.dp))
            .background(when { f -> Pal.Gold; selected -> Pal.ChipSel; else -> Color.Transparent })
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Label(icon, 18, if (f) Color.Black else Pal.Text)
        if (open) {
            Label(label, 15, if (f) Color.Black else Pal.Text, Modifier.weight(1f))
            if (count >= 0) Label("$count", 12, if (f) Color.Black else Pal.Dim)
        }
    }
}

/**
 * One channel: number badge, logo, clean name, now / next with progress. Dimmed + "Hidden" when it is not in the
 * current mode (All channels). OK plays; hold OK / long-press: show or hide (All channels) or the menu; Menu = menu.
 */
@Composable
private fun ChannelRow(repo: Repo, ch: Item, on: Boolean, browseAll: Boolean, playing: Boolean, tv: Boolean, modifier: Modifier,
                       onClick: () -> Unit, onToggle: () -> Unit, onMenu: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val local = remember(ch.cat, repo.liveCats) { LiveKind.isLocalNews(repo.liveCats.firstOrNull { it.id == ch.cat }?.title.orEmpty()) }
    val name = remember(ch.name, local) { ChannelTitle.pretty(ch.name, local) }
    val t = rememberMinute()
    val progs = repo.epg.guide[ch.id]?.progs
    val now = Epg.now(progs, t)
    val next = Epg.next(progs, t)
    Row(
        modifier.fillMaxWidth().popupOpener()
            .onFocusChanged { f = it.isFocused; if (it.isFocused) Keys.focused = ch }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false }
            .clip(shape)
            .background(when { f -> Pal.FocusBg; playing -> Pal.Soft; else -> Color.Transparent })
            .then(if (f) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = if (browseAll) onToggle else onMenu)
            .padding(horizontal = 12.dp, vertical = if (tv) 10.dp else 8.dp)
            .alpha(if (on) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.widthIn(min = 38.dp), contentAlignment = Alignment.CenterEnd) {
            if (ch.num > 0) Label("${ch.num}", if (tv) 15 else 13, if (playing) Pal.GoldText else Pal.Dim)
        }
        Box(Modifier.size(if (tv) 76.dp else 60.dp, if (tv) 46.dp else 38.dp).background(Pal.Card, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (ch.img.isNotEmpty()) Poster(ch.img, name, Modifier.fillMaxSize().padding(5.dp), ContentScale.Fit, initialsSize = 14)
            else Label(ChannelTitle.initials(name), 14, Pal.GoldText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(name, if (tv) 17 else 15, if (playing) Pal.GoldText else Pal.Text, Modifier.weight(1f, fill = false))
                if (playing) Label("● NOW PLAYING", 10, Pal.Gold)
                if (!on) Box(Modifier.background(Pal.Soft, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) { Label("Hidden", 11, Pal.Dim) }
            }
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(now.title, 13, Pal.Text2, Modifier.weight(1f, fill = false))
                    ProgressLine(now.progress(t), Modifier.width(if (tv) 90.dp else 56.dp))
                }
            }
        }
        if (tv && next != null) Label("Next ${hhmm(next.start)} · ${next.title}", 12, Pal.Dim, Modifier.widthIn(max = 260.dp))
    }
}

/** Channel number as a small badge. */
@Composable
fun NumBadge(num: Int, modifier: Modifier = Modifier) {
    if (num <= 0) return
    Box(modifier.background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Label("$num", 11, Color.White)
    }
}

/** Rounded logo tile (search results): logo (or initials), number badge, ★ when in a mode, gold ring when playing. */
@Composable
fun LiveTile(ch: Item, fav: Boolean, playing: Boolean, checked: Boolean, tv: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val name = ChannelTitle.pretty(ch.name)
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).focusCard(onClick, onMenu, { Keys.focused = ch }, scale = 1.06f, shape = shape)
            .background(Pal.Card, shape)
            .then(if (playing) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)) {
            if (ch.img.isNotEmpty()) Poster(ch.img, name, Modifier.fillMaxSize().padding(if (tv) 14.dp else 10.dp), ContentScale.Fit, initialsSize = if (tv) 26 else 20)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label(ChannelTitle.initials(name), if (tv) 26 else 20, Pal.GoldText) }
            NumBadge(ch.num, Modifier.align(Alignment.TopStart).padding(5.dp))
            if (fav) Label("★", 14, Pal.Gold, Modifier.align(Alignment.TopEnd).padding(5.dp))
            if (checked) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) { Label("✓", 18, Pal.Gold) }
        }
        Spacer(Modifier.height(5.dp))
        Label(name, if (tv) 14 else 12, if (playing) Pal.GoldText else Pal.Text2, maxLines = if (tv) 1 else 2)
    }
}
