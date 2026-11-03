@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DIM2 get() = Pal.Dim
private val WARN2 get() = Pal.Warn

/** One entry of the Live TV group rail (TV) / chip row (phone). */
private data class LiveGroup(val key: String, val icon: String, val title: String, val count: Int, val folder: String? = null)

/**
 * Live TV: groups first (My Channels folders, Recently watched, then News / Sports / Movies / Music / Kids / ... by type,
 * and the portal's own categories), each filterable by language and country.
 *  - TV: a left group rail and the channel grid with sticky section headers; Menu on a channel or folder for actions.
 *  - Phone: group chips on top and a grid or list (bottom nav stays).
 * Logos are rounded tiles with the channel number as a badge; the channel playing now is marked; in selection mode
 * picked channels get a check. Channel up / down in the player follows the group's list as shown.
 */
@Composable
fun Live(repo: Repo, play: (List<Item>, Int) -> Unit, onSearch: () -> Unit = {}) {
    val st = repo.store
    var group by remember { mutableStateOf(st.sort("live_g", "")) }
    var asList by remember { mutableStateOf(st.sort("live_view", "icons") == "list") }
    var sortAz by remember { mutableStateOf(st.sort("live_az", "default") == "az") }
    var langs by remember { mutableStateOf(st.langs("live")) }
    var pickLangs by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<Any?>(null) }
    var menuFolder by remember { mutableStateOf<String?>(null) }   // My Channels folder the channel menu was opened from
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    // Selection mode: OK / tap ticks channels; then add them all to folders at once
    var picked by remember { mutableStateOf<Map<String, Item>?>(null) }
    var pickFolders by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    fun save(k: String, v: String) = st.saveSort(k, v)

    val unlocked = repo.gate(true) == null
    val cats = repo.visibleLive()
    val catIds = remember(cats) { cats.map { it.id }.toSet() }
    val catTitle = remember(repo.liveCats) { repo.liveCats.associate { it.id to it.title } }
    // Every channel's type and language: worked out once per channel list off the main thread (and saved), see LiveIndex
    LaunchedEffect(repo.rawChannels, repo.liveCats) { repo.liveIndex.ensure(repo.rawChannels, repo.liveCats) }
    val ix = repo.liveIndex
    val info = ix.info
    val visible = remember(repo.channels, catIds, repo.hidden, unlocked) {
        repo.channels.filter { it.cat in catIds && !repo.isChannelHidden(it) && (!it.locked || unlocked) }
    }
    val langCounts = remember(visible, info) { visible.mapNotNull { info[it.key]?.lang }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second } }
    val byLang = remember(visible, info, langs) { if (langs.isNullOrEmpty()) visible else visible.filter { info[it.key]?.lang?.let { l -> l in langs!! } == true } }
    val folders = repo.folders
    val recent = remember(repo.nowPlaying, repo.channels) { st.list("recent").filter { it.kind == "itv" }.map(repo::fresh) }
    val types = remember(byLang, info) { LiveGroups.typeCounts(byLang, info) }

    val groups = buildList {
        folders.names.forEach { n -> add(LiveGroup(LiveGroups.folderKey(n), "📁", n, folders.items(n).size, n)) }
        if (recent.isNotEmpty()) add(LiveGroup(LiveGroups.RECENT, "🕘", "Recently watched", recent.size))
        types.forEach { (t, n) -> add(LiveGroup(LiveGroups.typeKey(t), LiveGroups.icon(t), t, n)) }
        add(LiveGroup(LiveGroups.ORIG, "🗂", "All categories", cats.size))
    }
    val cur = groups.find { it.key == group } ?: groups.firstOrNull { it.folder != null && it.count > 0 } ?: groups.firstOrNull()
    fun choose(g: LiveGroup) { group = g.key; save("live_g", g.key) }

    // The chosen group's channels, then its language / country filters (per group)
    val inGroup = remember(cur?.key, folders, recent, byLang, info) {
        when {
            cur == null -> emptyList()
            cur.folder != null -> folders.items(cur.folder).map(repo::fresh)
            cur.key == LiveGroups.RECENT -> recent
            cur.key.startsWith("t:") -> LiveGroups.ofType(byLang, info, cur.key.removePrefix("t:"))
            else -> emptyList()
        }
    }
    var lang by remember(cur?.key) { mutableStateOf("") }
    var country by remember(cur?.key) { mutableStateOf("") }
    val langChips = remember(inGroup, info) { LiveGroups.langCounts(inGroup, info) }
    val countryChips = remember(inGroup, catTitle) { LiveGroups.countryCounts(inGroup, catTitle) }
    val shown = remember(inGroup, lang, country, info) { LiveGroups.filter(inGroup, info, catTitle, lang, country) }
    // Folders and Recently watched keep their own order unless A-Z; types are split by language when not filtered
    val sections = remember(shown, sortAz, lang, cur?.key) {
        LiveGroups.sections(shown, info, sortAz, byLanguage = cur?.key?.startsWith("t:") == true && lang.isEmpty())
    }

    val tvLive = rememberIsTv()
    val wide = LocalContext.current.resources.configuration.smallestScreenWidthDp >= 600
    val rail = tvLive || wide
    val railFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tvLive) { delay(150); runCatching { railFr.requestFocus() } } }

    // OK on a channel: play it (the player steps through this group as shown), or in selection mode tick / untick it
    val sel = picked
    val playOrPick: (List<Item>, Int) -> Unit = { l, i ->
        val p = picked
        if (p == null) { repo.lastLiveIndex = i; play(l, i) } else l.getOrNull(i)?.let { c -> picked = if (c.key in p) p - c.key else p + (c.key to c) }
    }
    val chMenu: (Item) -> Unit = { menuFolder = cur?.folder; menuTarget = it }

    @Composable
    fun Toolbar() {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            if (tvLive) item { PillButton("🔍  Search", onSearch, size = 13) }
            item { PillButton(if (sel != null) "☑  Selecting" else "☐  Select", { picked = if (sel != null) null else emptyMap() }, selected = sel != null, size = 13) }
            item { PillButton(if (sortAz) "A-Z" else if (cur?.folder != null) "Folder order" else "Portal order", { sortAz = !sortAz; save("live_az", if (sortAz) "az" else "default") }, size = 13) }
            item { PillButton(if (asList) "☰  List" else "▦  Grid", { asList = !asList; save("live_view", if (asList) "list" else "icons") }, size = 13) }
            item { PillButton("🌐  " + (langs?.let { if (it.size <= 2) it.joinToString(", ") else "${it.size} languages" } ?: "All languages"), { pickLangs = true }, size = 13) }
        }
    }

    @Composable
    fun Filters() {
        if (langChips.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item { FilterChip("All languages", inGroup.size, lang.isEmpty()) { lang = "" } }
            items(langChips, key = { "l" + it.first }) { (l, n) -> FilterChip(l, n, l == lang) { lang = if (lang == l) "" else l } }
        }
        if (countryChips.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item { FilterChip("All countries", inGroup.size, country.isEmpty()) { country = "" } }
            items(countryChips, key = { "c" + it.first }) { (c, n) -> FilterChip(c, n, c == country) { country = if (country == c) "" else c } }
        }
    }

    @Composable
    fun Content(modifier: Modifier) {
        Column(modifier) {
            if (sel != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Label(if (sel.isEmpty()) "Select channels with ${if (tvLive) "OK" else "a tap"}" else "${sel.size} selected", 15, Pal.Gold)
                    if (sel.isNotEmpty()) {
                        PillButton("📁  Add to folders…", { pickFolders = true }, primary = true, size = 13)
                        if (sel.values.any { repo.isFav(it) }) PillButton("Remove from favorites", { repo.removeFromFavorites(sel.values.toList()); picked = emptyMap() }, size = 13)
                        PillButton("Clear", { picked = emptyMap() }, size = 13)
                    }
                    PillButton("Done", { picked = null }, size = 13)
                }
            }
            if (msg.isNotEmpty()) Label(msg, 14, WARN2)
            androidx.compose.animation.Crossfade(cur?.key, animationSpec = androidx.compose.animation.core.tween(220), label = "liveGroup") { gk ->
                Column {
                    when {
                        gk == LiveGroups.ORIG -> OriginalView(repo, cats, asList, sortAz, playOrPick, chMenu, { menuTarget = it }) { msg = it }
                        cur == null -> Label(if (repo.channels.isEmpty()) "No channels loaded yet" else "Nothing to show", 16, DIM2)
                        gk?.startsWith("t:") == true && info.isEmpty() -> {
                            IndexStatus(repo, ix)
                            LiveSkeleton(rail)
                        }
                        cur.folder != null && inGroup.isEmpty() ->
                            Label("\"${cur.folder}\" is empty. Menu (or long-press) on a channel > Add to favorites, or ☐ Select several channels.", 15, DIM2, maxLines = 3)
                        else -> {
                            if (gk?.startsWith("t:") == true) IndexStatus(repo, ix)
                            Filters()
                            Spacer(Modifier.height(4.dp))
                            LiveChannels(repo, sections, asList, rail, sel?.keys, repo.nowPlaying, playOrPick, chMenu)
                        }
                    }
                }
            }
        }
    }

    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Live TV", if (rail) 26 else 22, Pal.GoldText)
            Spacer(Modifier.width(12.dp))
            cur?.let { Label("${it.icon}  ${it.title}  ·  ${if (it.key == LiveGroups.ORIG) "${it.count} categories" else "${shown.size} channels"}", 15, DIM2, Modifier.weight(1f)) }
        }
    }

    CompositionLocalProvider(LocalPicked provides sel?.keys) {
        if (rail) {
            Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 24.dp, top = 12.dp)) {
                // Group rail: Up / Down moves between groups (OK or focus picks it), Right goes into the channels
                LazyColumn(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item { Label("MY CHANNELS", 12, DIM2, Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp)) }
                    val fs = groups.filter { it.folder != null }
                    items(fs, key = { it.key }) { g ->
                        RailEntry(g, g.key == cur?.key, if (g.key == cur?.key) Modifier.focusRequester(railFr) else Modifier, { choose(g) }) { folderMenu = g.folder }
                    }
                    item(key = "newfolder") { RailEntry(LiveGroup("new", "＋", "New folder", -1), false, if (cur == null) Modifier.focusRequester(railFr) else Modifier, { naming = true }, null) }
                    item { Label("BROWSE", 12, DIM2, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp)) }
                    items(groups.filter { it.folder == null }, key = { it.key }) { g ->
                        RailEntry(g, g.key == cur?.key, if (g.key == cur?.key) Modifier.focusRequester(railFr) else Modifier, { choose(g) }, null)
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    header()
                    Toolbar()
                    Content(Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                header()
                Spacer(Modifier.height(6.dp))
                // Group chips: folders first (long-press = folder actions), then Recently watched and the types
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(groups, key = { it.key }) { g -> GroupChip(g, g.key == cur?.key, { choose(g) }, g.folder?.let { f -> { folderMenu = f } }) }
                    item(key = "new") { GroupChip(LiveGroup("new", "＋", "Folder", -1), false, { naming = true }, null) }
                }
                Toolbar()
                Content(Modifier.weight(1f))
            }
        }
    }
    androidx.activity.compose.BackHandler(picked != null && menuTarget == null && !pickFolders) { picked = null }
    if (pickFolders) picked?.let { p -> FolderPicker(repo, p.values.toList()) { pickFolders = false; picked = null } }
    if (pickLangs) LiveLanguages(langCounts, langs, { langs = it; st.saveLangs("live", it) }) { pickLangs = false }
    folderMenu?.let { n -> FolderMenu(repo, n, { group = LiveGroups.folderKey(it); save("live_g", group) }) { folderMenu = null } }
    if (naming) NameDialog("New folder", "", "Create folder", { n ->
        repo.createFolder(n)?.let { group = LiveGroups.folderKey(it); save("live_g", group) }; naming = false
    }) { naming = false }

    menuTarget?.let { t ->
        val close = { menuTarget = null }
        when (t) {
            is Cat -> ChoiceDialog(t.title, listOf(
                (if (repo.isHidden("itv", t.id)) "Show folder" else "Hide folder") to { repo.toggleHide("itv", t.id); close() }
            ), onClose = close)
            is Item -> ChannelMenu(repo, repo.named(t), menuFolder, buildList {
                add((if (repo.isChannelHidden(t)) "Unhide channel" else "Hide channel") to { repo.toggleHideChannel(t); close() })
                info[t.key]?.let { add("Type: ${it.type} · Language: ${it.lang}" to close) }
            }, onSelect = { picked = mapOf(t.key to t) }, onClose = close)
            else -> close()
        }
    }
}

/** Sorting progress / problems for the type groups: never an endless spinner. */
@Composable
private fun IndexStatus(repo: Repo, ix: LiveIndex) {
    if (ix.state == LiveIndex.State.READY || repo.channels.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        when (ix.state) {
            LiveIndex.State.RUNNING, LiveIndex.State.IDLE -> {
                val pct = if (ix.total > 0) ix.done * 100 / ix.total else 0
                Label("Sorting channels… $pct%", 14, DIM2)
                PillButton("Cancel", { ix.cancel() }, size = 12)
            }
            else -> {
                Label(if (ix.state == LiveIndex.State.CANCELLED) "Sorting stopped" else ix.error.ifEmpty { "Could not sort channels" }, 14, WARN2)
                PillButton("Try again", { ix.ensure(repo.channels, repo.liveCats, force = true) }, size = 12)
            }
        }
    }
}

@Composable
private fun RailEntry(g: LiveGroup, selected: Boolean, modifier: Modifier, onClick: () -> Unit, onMenu: (() -> Unit)?) {
    var focused by remember { mutableStateOf(false) }
    val bg = when { focused -> Pal.FocusBg; selected -> Pal.ChipSel; else -> Color.Transparent }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusCard(onClick, onMenu, scale = 1.02f, shape = RoundedCornerShape(12.dp))
            .background(bg).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Label(g.icon, 17)
        Label(g.title, 15, if (selected) Pal.GoldText else Pal.Text, Modifier.weight(1f))
        if (g.count >= 0) Label("${g.count}", 12, DIM2)
    }
}

@Composable
private fun GroupChip(g: LiveGroup, selected: Boolean, onClick: () -> Unit, onMenu: (() -> Unit)?) {
    Row(
        Modifier.focusCard(onClick, onMenu, scale = 1.04f, shape = RoundedCornerShape(20.dp))
            .background(if (selected) Pal.Gold else Pal.Card, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Label(g.icon, 15)
        Label(g.title, 14, if (selected) Color.Black else Pal.Text)
        if (g.count >= 0) Label("${g.count}", 12, if (selected) Color(0xAA000000) else DIM2)
    }
}

@Composable
private fun FilterChip(text: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.focusCard(onClick, scale = 1.04f, shape = RoundedCornerShape(16.dp))
            .background(if (selected) Pal.ChipSel else Pal.Soft, RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.border(1.dp, Pal.Gold, RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Label("$text  $count", 13, if (selected) Pal.GoldText else Pal.Text2) }
}

/** Placeholder tiles while channels are sorted into groups. */
@Composable
private fun LiveSkeleton(tv: Boolean) {
    val cols = if (tv) 5 else 3
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(cols) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).shimmer())
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth(0.7f).height(11.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                    }
                }
            }
        }
    }
}

/**
 * A group's channels: sticky section headers (language, or A-Z letters with a quick-jump strip) and rows of logo
 * tiles (or list rows). Lazy, so thousands of channels scroll smoothly.
 */
@Composable
private fun LiveChannels(repo: Repo, sections: List<LiveSection>, asList: Boolean, tv: Boolean, picked: Set<String>?, playing: String,
                         play: (List<Item>, Int) -> Unit, menu: (Item) -> Unit) {
    if (sections.isEmpty()) { Label("No channels match these filters", 15, DIM2); return }
    val flat = remember(sections) { sections.flatMap { it.items } }
    val pos = remember(flat) { flat.withIndex().associate { it.value.key to it.index } }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minW = if (asList) maxWidth else if (tv) 176.dp else 106.dp
        val cols = if (asList) (if (maxWidth > 700.dp) 2 else 1) else (maxWidth / minW).toInt().coerceIn(1, 10)
        val rows = remember(sections, cols) { sections.map { s -> s to LiveGroups.rows(s.items, cols) } }
        // Index of each section's header in the list, for the A-Z / section quick jump
        val starts = remember(rows) {
            var i = 0
            rows.associate { (s, r) -> (s.title to i).also { i += 1 + r.size } }
        }
        Column {
            val titled = sections.filter { it.title.isNotEmpty() }
            if (titled.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
                items(titled, key = { "j" + it.title }) { s ->
                    Box(Modifier.focusCard({ starts[s.title]?.let { scope.launch { state.animateScrollToItem(it) } } }, scale = 1.1f, shape = RoundedCornerShape(8.dp))
                        .background(Pal.Soft, RoundedCornerShape(8.dp)).padding(horizontal = if (s.title.length == 1) 10.dp else 10.dp, vertical = 5.dp)) {
                        Label(s.title, 13, Pal.GoldText)
                    }
                }
            }
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(if (asList) 4.dp else 12.dp), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                rows.forEach { (s, r) ->
                    stickyHeader(key = "h|${s.title}") {
                        if (s.title.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Pal.Bg).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label(s.title, 16, Pal.GoldText)
                            Spacer(Modifier.width(8.dp))
                            Label("${s.items.size}", 13, DIM2)
                        } else Spacer(Modifier.height(1.dp))
                    }
                    itemsIndexed(r, key = { i, row -> "r|${s.title}|$i|${row.first().key}" }) { _, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { ch ->
                                val i = pos[ch.key] ?: 0
                                Box(Modifier.weight(1f)) {
                                    if (asList) LiveRow(ch, repo.isFav(ch), ch.key == playing, picked?.contains(ch.key) == true, { play(flat, i) }, { menu(ch) })
                                    else LiveTile(ch, repo.isFav(ch), ch.key == playing, picked?.contains(ch.key) == true, tv, { play(flat, i) }, { menu(ch) })
                                }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
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

/** Rounded logo tile: logo (or initials), number badge, ★ for favourites, gold ring + LIVE for the channel playing now, ✓ when picked. */
@Composable
fun LiveTile(ch: Item, fav: Boolean, playing: Boolean, checked: Boolean, tv: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).focusCard(onClick, onMenu, { Keys.focused = ch }, scale = 1.06f, shape = shape)
            .background(Pal.Card, shape)
            .then(if (playing) Modifier.border(2.dp, Pal.Gold, shape) else Modifier)) {
            if (ch.img.isNotEmpty()) Poster(ch.img, ch.name, Modifier.fillMaxSize().padding(if (tv) 14.dp else 10.dp), ContentScale.Fit, initialsSize = if (tv) 26 else 20)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label(ChannelTitle.initials(ch.name), if (tv) 26 else 20, Pal.GoldText) }
            NumBadge(ch.num, Modifier.align(Alignment.TopStart).padding(5.dp))
            if (fav) Label("★", 14, Pal.Gold, Modifier.align(Alignment.TopEnd).padding(5.dp))
            if (playing) Box(Modifier.align(Alignment.BottomStart).padding(5.dp).background(Pal.Gold, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp)) {
                Label("● LIVE", 10, Color.Black)
            }
            if (checked) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(30.dp).background(Pal.Gold, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Label("✓", 18, Color.Black) }
            }
        }
        Spacer(Modifier.height(5.dp))
        Label(ch.name, if (tv) 14 else 12, if (playing) Pal.GoldText else Pal.Text2, maxLines = if (tv) 1 else 2)
    }
}

/** List view row: small logo, number badge, name; same marks as the tile. */
@Composable
private fun LiveRow(ch: Item, fav: Boolean, playing: Boolean, checked: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().focusCard(onClick, onMenu, { Keys.focused = ch }, scale = 1.02f, shape = shape)
        .background(if (playing) Pal.FocusBg else Pal.Row, shape).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp, 40.dp).background(Pal.Card, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (ch.img.isNotEmpty()) Poster(ch.img, ch.name, Modifier.fillMaxSize().padding(4.dp), ContentScale.Fit, initialsSize = 14)
            else Label(ChannelTitle.initials(ch.name), 14, Pal.GoldText)
        }
        NumBadge(ch.num)
        Label(ch.name, 15, if (playing) Pal.GoldText else Pal.Text, Modifier.weight(1f))
        if (playing) Label("● LIVE", 11, Pal.Gold)
        if (fav) Label("★", 14, Pal.Gold)
        if (checked) Label("✓", 18, Pal.Gold)
    }
}

/** Channels as logo tiles (grid) or a list; [list] is what the player steps through with channel up/down. */
@Composable
private fun ChannelGrid(repo: Repo, list: List<Item>, asList: Boolean, play: (List<Item>, Int) -> Unit, menu: (Item) -> Unit) {
    val picked = LocalPicked.current
    val tv = rememberIsTv()
    if (asList) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(list, key = { i, c -> "$i|${c.key}" }) { i, ch ->
                LiveRow(ch, repo.isFav(ch), ch.key == repo.nowPlaying, picked?.contains(ch.key) == true, { play(list, i) }, { menu(ch) })
            }
        }
    } else {
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = if (tv) 176.dp else 106.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            gridItemsIndexed(list, key = { i, c -> "$i|${c.key}" }) { i, ch ->
                LiveTile(ch, repo.isFav(ch), ch.key == repo.nowPlaying, picked?.contains(ch.key) == true, tv, { play(list, i) }, { menu(ch) })
            }
        }
    }
}

/** "Show hidden" for the current group, so hidden channels can still be brought back. */
@Composable
private fun HiddenToggle(repo: Repo, inGroup: (Item) -> Boolean) {
    var show by remember { mutableStateOf(false) }
    PillButton(if (show) "Hide hidden" else "Show hidden in this group", { show = !show }, selected = show)
    if (show) {
        val hiddenHere = repo.channels.filter { repo.isChannelHidden(it) && inGroup(it) }
        if (hiddenHere.isEmpty()) Label("No hidden channels here", 14, DIM2)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 140.dp)) {
            items(hiddenHere) { ch -> Row1("Hidden: ${ch.name}  (select to unhide)", { repo.toggleHideChannel(ch) }) }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** The portal's own groups and categories (as before 1.40). */
@Composable
private fun OriginalView(repo: Repo, cats: List<Cat>, asList: Boolean, sortAz: Boolean, play: (List<Item>, Int) -> Unit,
                         menu: (Item) -> Unit, catMenu: (Cat) -> Unit, message: (String) -> Unit) {
    var group by remember { mutableStateOf(repo.lastLiveCat.ifEmpty { "All" }) }
    val groups = remember(cats) { groupCategories(cats) }
    val currentGroup = groups.keys.find { it == group } ?: groups.keys.firstOrNull() ?: "All"
    val groupCats = groups[currentGroup] ?: emptyList()
    var selCat by remember { mutableStateOf(groupCats.find { it.id == repo.lastLiveCatId } ?: groupCats.firstOrNull()) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(groups.keys.toList()) { g -> Chip(g, g == currentGroup, { group = g; selCat = groups[g]?.firstOrNull(); repo.lastLiveCat = g }) }
    }
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(groupCats) { c ->
            Chip((if (c.locked) "🔒 " else "") + c.title, selCat?.id == c.id, {
                val e = repo.gate(c.locked)
                if (e == null) { selCat = c; repo.lastLiveCatId = c.id; message("") } else message(e)
            }, { catMenu(c) })
        }
    }
    Spacer(Modifier.height(8.dp))
    val channels = remember(selCat, repo.channels, sortAz, repo.hidden) {
        selCat?.let { c -> repo.channels.filter { it.cat == c.id && !repo.isChannelHidden(it) }.let { if (sortAz) it.sortedBy { ch -> ch.name.lowercase() } else it } } ?: emptyList()
    }
    if (selCat != null) HiddenToggle(repo) { it.cat == selCat?.id }
    if (channels.isEmpty()) Label("Select a category", 16, DIM2) else ChannelGrid(repo, channels, asList, play, menu)
}

/** Language checklist for Live TV. Nothing ticked = all languages. */
@Composable
private fun LiveLanguages(counts: List<Pair<String, Int>>, cur: Set<String>?, save: (Set<String>?) -> Unit, onClose: () -> Unit) {
    var sel by remember { mutableStateOf(cur ?: emptySet()) }
    fun set(v: Set<String>) { sel = v; save(v.ifEmpty { null }) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxHeight(0.9f).background(Pal.Surface, RoundedCornerShape(12.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Live TV languages", 20)
            Label("Tick the languages to show (from category and channel names). Nothing ticked = all.", 13, DIM2, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Done", onClose, Modifier.focusRequester(fr), primary = true, size = 14)
                PillButton("Select all", { set(counts.map { it.first }.toSet()) }, size = 14)
                PillButton("Select none", { set(emptySet()) }, size = 14)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(counts, key = { it.first }) { (l, n) ->
                    val on = l in sel
                    Row1((if (on) "☑  " else "☐  ") + "$l   ($n channels)", { set(if (on) sel - l else sel + l) })
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    Box(
        Modifier.tv(onClick, onMenu)
            .background(if (selected) Pal.ChipSel else Color.Transparent, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) { Label(text, 15) }
}
