@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Catalog Selector (1.92): full-screen, three steps.
 *  1. Languages — one list for Movies, Series and Live TV (nothing ticked = every language).
 *  2. Categories — Movies | Series | Live TV columns, only for the picked languages; tick to show, untick to hide,
 *     All / None per column.
 *  3. Your selection — only what is shown, then Done.
 * No new portal calls: everything comes from the already loaded category lists, local catalog and channel index.
 */
@Composable
fun CatalogSelectorScreen(repo: Repo, go: (Screen) -> Unit) {
    val tv = rememberIsTv()
    val lib = repo.lib
    var step by remember { mutableIntStateOf(0) }
    var modes by remember { mutableStateOf(false) }
    val exit = { go(if (AppUi.phoneNav) Screen.Profile else Screen.Home) }

    // ---- Step 1 data ----
    val startLangs = remember {
        CatalogSelector.initial(lib.selected("movies"), lib.selected("series"), repo.liveLangs)
    }
    var langs by remember { mutableStateOf(startLangs) }
    var savedLangs by remember { mutableStateOf(startLangs) }
    val info = repo.liveIndex.info
    val langRows by produceState(emptyList<CatalogSelector.Lang>(), repo.vodCats.size, lib.version, repo.channels, info) {
        value = withContext(Dispatchers.Default) {
            CatalogSelector.mergeLangs(lib.languages("movies"), lib.languages("series"), LiveModes.languages(repo.channels, info))
        }
    }

    fun saveLangs() {
        if (langs == savedLangs) return
        val v = CatalogSelector.stored(langs)
        for (tab in listOf("movies", "series")) {
            repo.store.saveLangs(tab, v)
            if (v != null) lib.ensure(tab, v)    // same as the Languages picker: cached data first, gentle top-up
        }
        repo.chooseLiveLangs(v)
        savedLangs = langs
    }

    // ---- Step 2 data (for the saved languages) ----
    val vod by produceState(emptyList<CatalogGroups.Group>() to emptyList<CatalogGroups.Group>(),
        savedLangs, repo.vodCats, repo.vodOrder, lib.version) {
        value = withContext(Dispatchers.Default) {
            CatalogSelector.vodGroups(repo.ordered("vod"), repo::isSeriesCat, lib::catLangs, savedLangs) { c -> lib.inCat(c.id).size }
        }
    }
    // 1.99.2: Movies / Series fold under their language (from the saved category names: no portal call)
    val catLang = remember(repo.vodCats, savedLangs) {
        repo.vodCats.associate { c -> "vod:${c.id}" to CatalogSelector.pickLang(lib.catLang(c), lib.catLangs(c), savedLangs) }
    }
    val langOrder = remember(langRows) { langRows.map { it.name } }
    fun treeOf(section: String, groups: List<CatalogGroups.Group>): CatalogTree.Tree =
        if (section == CatalogSelector.LIVE) CatalogTree.of(groups)
        else CatalogTree.byLanguage(groups, { g -> catLang[g.key] ?: Tags.OTHER }, langOrder)
    val live by produceState(emptyList<CatalogGroups.Group>(), savedLangs, repo.channels, repo.liveCats, info) {
        value = withContext(Dispatchers.Default) {
            val titles = repo.liveCats.associate { it.id to it.title }
            val rows = LiveModes.rows(repo.channels, LiveModes.DEFAULT, FolderSet(), emptySet(), LiveModes.View.ALL,
                CatalogSelector.stored(savedLangs), info, titles)
            CatalogGroups.liveGroups(LiveModes.tree(rows, emptySet(), LiveModes.View.ALL))
        }
    }
    val columns = mapOf(CatalogSelector.MOVIES to vod.first, CatalogSelector.SERIES to vod.second, CatalogSelector.LIVE to live)
    fun on(key: String) = repo.groupOn(key)
    val trees = remember(columns, catLang, langOrder) { columns.mapValues { (sec, g) -> treeOf(sec, g) } }
    // Which parents are open, per column and step; a lone parent starts open
    val open = remember { mutableStateMapOf<String, Set<String>>() }
    /** Apply a tick change: turn keys on, then off (titles for the stored groups). */
    fun applyChange(t: CatalogTree.Tree, ch: CatalogTree.Change) {
        if (ch.isEmpty) return
        fun titled(keys: List<String>) = keys.map { k -> k to (t.nodes.values.firstOrNull { it.key == k }?.title ?: k) }
        if (ch.on.isNotEmpty()) repo.setGroupsOn(titled(ch.on), true)
        if (ch.off.isNotEmpty()) repo.setGroupsOn(titled(ch.off), false)
    }

    // Step 3 keeps the list it opened with, so unticking a row there does not make it jump away
    var summary by remember { mutableStateOf<Map<String, List<CatalogGroups.Group>>>(emptyMap()) }

    fun goStep(s: Int) {
        if (step == 0 && s > 0) saveLangs()
        if (s == 2) summary = columns.mapValues { (_, g) -> CatalogSelector.shownOnly(g, ::on) }
        if (s == 2) open.keys.filter { it.startsWith("2|") }.forEach { open.remove(it) }
        step = s
    }
    BackHandler(step > 0 && !Popups.any) { goStep(step - 1) }

    val firstFr = remember(step) { FocusRequester() }
    val navFr = remember { FocusRequester() }
    // Focus the first tile / row; when there is none (no languages yet, an empty first column) the Next / Done
    // button, so the remote always has somewhere to start (never a screen with nothing focused).
    LaunchedEffect(step, langRows.isEmpty()) {
        delay(120)
        if (runCatching { firstFr.requestFocus() }.isFailure) runCatching { navFr.requestFocus() }
    }

    val mode = repo.mode()
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        CinematicWash()
        Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 40.dp else 14.dp, vertical = if (tv) 18.dp else 10.dp)) {
            // Header: title, steps, navigation
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Catalog Selector", color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                StepDots(step, tv, Modifier.weight(1f))
                if (step > 0) PillButton("‹ Back", { goStep(step - 1) }, compact = true, size = Ts.HEAD)
                if (step < 2) PillButton("Next ›", { goStep(step + 1) }, Modifier.focusRequester(navFr), primary = true, compact = true, size = Ts.HEAD)
                else PillButton("Done", exit, Modifier.focusRequester(navFr), primary = true, compact = true, size = Ts.HEAD)
            }
            when (step) {
                0 -> LanguageStep(langRows, langs, tv, firstFr) { langs = it }
                1 -> Column(Modifier.fillMaxSize()) {
                    ButtonFlow {
                        PillButton((if (mode == LiveModes.DEFAULT) "Default" else mode) + "  ▾", { modes = true },
                            selected = true, compact = true, size = Ts.HEAD)
                        PillButton(CatalogSelector.langLabel(savedLangs), { goStep(0) }, compact = true, size = Ts.HEAD)
                    }
                    Spacer(Modifier.height(10.dp))
                    Columns(trees, tv, firstFr, summary = false, on = ::on, open = open, openKey = "1|",
                        onToggle = { t, id -> applyChange(t, CatalogTree.toggle(t, id, ::on)) },
                        onAll = { t, v -> applyChange(t, CatalogTree.Change(if (v) t.keys().filter { !on(it) } else emptyList(), if (v) emptyList() else t.keys().filter { on(it) })) })
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Text(CatalogSelector.langLabel(savedLangs) + "   ·   " +
                        CatalogSelector.SECTIONS.joinToString("   ·   ") { s -> "$s ${summary[s].orEmpty().count { on(it.key) }}" },
                        color = Pal.Dim, fontSize = Ts.SECONDARY.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 10.dp))
                    val sumTrees = remember(summary, catLang, langOrder) { summary.mapValues { (sec, g) -> treeOf(sec, g) } }
                    Columns(sumTrees, tv, firstFr, summary = true, on = ::on, open = open, openKey = "2|",
                        onToggle = { t, id -> applyChange(t, CatalogTree.toggle(t, id, ::on)) },
                        onAll = { _, _ -> })
                }
            }
        }
    }
    if (modes) {
        val current = remember(repo.channels, repo.hidden, repo.liveMode, repo.folders) { repo.channels.filter { repo.isOn(it) } }
        ModeMenu(repo, current) { modes = false }
    }
}

@Composable
private fun StepDots(step: Int, tv: Boolean, modifier: Modifier) {
    val names = listOf("Languages", "Categories", "Your Selection")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (tv) 14.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            val cur = i == step
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.alpha(if (cur) 1f else 0.55f)) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (i <= step) Pal.Primary else Pal.Soft2),
                    contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = if (i <= step) Color.White else Pal.Text, fontSize = Ts.CAPTION.sp, fontWeight = FontWeight.Bold)
                }
                if (tv || cur) Text(n, color = if (cur) Pal.Text else Pal.Dim, fontSize = Ts.SECONDARY.sp,
                    fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LanguageStep(rows: List<CatalogSelector.Lang>, sel: Set<String>, tv: Boolean, firstFr: FocusRequester, set: (Set<String>) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 10.dp)) {
            PillButton("Select All", { set(rows.map { it.name }.toSet()) }, compact = true, size = Ts.HEAD)
            PillButton("Select None", { set(emptySet()) }, compact = true, size = Ts.HEAD)
            Text(if (sel.isEmpty()) "All languages" else "${sel.size} selected", color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
        }
        if (rows.isEmpty()) {
            Text("Loading…", color = Pal.Dim, fontSize = Ts.ROW.sp, modifier = Modifier.padding(top = 16.dp))
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(if (tv) 220.dp else 150.dp), Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp, start = 4.dp, end = 4.dp)) {
            itemsIndexed(rows, key = { _, l -> l.name }) { i, l ->
                val on = l.name in sel
                val counts = listOfNotNull(
                    l.movies.takeIf { it > 0 }?.let { "%,d movies".format(it) },
                    l.series.takeIf { it > 0 }?.let { "%,d series".format(it) },
                    l.live.takeIf { it > 0 }?.let { "%,d ch".format(it) }
                ).joinToString(" · ")
                Tile(l.name, counts, on, if (i == 0) Modifier.focusRequester(firstFr) else Modifier) {
                    set(if (on) sel - l.name else sel + l.name)
                }
            }
        }
    }
}

@Composable
private fun Columns(
    trees: Map<String, CatalogTree.Tree>, tv: Boolean, firstFr: FocusRequester, summary: Boolean,
    on: (String) -> Boolean, open: MutableMap<String, Set<String>>, openKey: String,
    onToggle: (CatalogTree.Tree, String) -> Unit, onAll: (CatalogTree.Tree, Boolean) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = tv || maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CatalogSelector.SECTIONS.forEachIndexed { i, s ->
                    SectionColumn(s, trees[s] ?: EMPTY_TREE, summary, on, open, openKey + s, tv, onToggle, onAll,
                        if (i == 0) firstFr else null, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            var cur by remember { mutableStateOf(CatalogSelector.MOVIES) }
            Column(Modifier.fillMaxSize()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    CatalogSelector.SECTIONS.forEach { s ->
                        PillButton(s, { cur = s }, selected = s == cur, compact = true, size = Ts.HEAD)
                    }
                }
                SectionColumn(cur, trees[cur] ?: EMPTY_TREE, summary, on, open, openKey + cur, tv, onToggle, onAll, firstFr, Modifier.fillMaxSize())
            }
        }
    }
}

private val EMPTY_TREE = CatalogTree.Tree(emptyMap(), emptyList())

/**
 * One column as a tree (1.99.2). OK / tap ticks a row (a parent ticks or clears everything under it).
 * Right opens a folded parent, Left folds an open one; Left on a child folds its parent and lands on it.
 * Menu / long-press, or the chevron on the phone, also folds and unfolds. Open parents stay pinned at the
 * top while their rows scroll, so they can be folded from anywhere.
 */
@Composable
private fun SectionColumn(
    title: String, tree: CatalogTree.Tree, summary: Boolean, on: (String) -> Boolean,
    open: MutableMap<String, Set<String>>, openKey: String, tv: Boolean,
    onToggle: (CatalogTree.Tree, String) -> Unit, onAll: (CatalogTree.Tree, Boolean) -> Unit,
    firstFr: FocusRequester?, modifier: Modifier
) {
    val shape = RoundedCornerShape(Radii.panel)
    val startOpen = remember(tree) { CatalogTree.initialExpanded(tree) }
    val expanded = open[openKey] ?: startOpen
    val rows = remember(tree, expanded) { CatalogTree.visible(tree, expanded) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val frs = remember(tree) { HashMap<String, FocusRequester>() }
    fun fr(id: String) = frs.getOrPut(id) { FocusRequester() }
    fun setOpen(next: Set<String>) { open[openKey] = next }
    fun foldTo(next: Set<String>, focusId: String) {
        setOpen(next)
        val at = CatalogTree.indexIn(CatalogTree.visible(tree, next), focusId)
        scope.launch {
            if (at >= 0 && (at < list.firstVisibleItemIndex || at > list.firstVisibleItemIndex + 3)) runCatching { list.scrollToItem(at) }
            delay(40)
            runCatching { fr(focusId).requestFocus() }
        }
    }
    // Opening turns the row into a pinned header (a new list item): keep the focus on it
    fun expand(id: String) {
        setOpen(expanded + id)
        scope.launch { delay(40); runCatching { fr(id).requestFocus() } }
    }
    Column(modifier.clip(shape).background(Pal.Surface.copy(alpha = 0.72f))
        .border(1.dp, Pal.Primary.copy(alpha = 0.18f), shape).padding(horizontal = 8.dp, vertical = 8.dp)) {
        val stored = tree.nodes.values.filter { it.key != null }
        val n = stored.count { CatalogTree.state(tree, it.id, on) == CatalogTree.Tri.ON }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp)) {
            Text(title.uppercase(), color = Pal.PrimaryText, fontSize = Ts.HEAD.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
            Text("$n / ${stored.size}", color = Pal.Dim, fontSize = Ts.CAPTION.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (!summary && !tree.isEmpty) {
                PillButton("All", { onAll(tree, true) }, compact = true, size = Ts.CAPTION)
                PillButton("None", { onAll(tree, false) }, compact = true, size = Ts.CAPTION)
            }
        }
        if (tree.isEmpty) {
            Text(if (summary) "Nothing selected" else "No categories", color = Pal.Dim, fontSize = Ts.SECONDARY.sp,
                modifier = Modifier.padding(4.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)) {
            rows.forEachIndexed { i, node ->
                val content: @Composable () -> Unit = {
                    val isOpen = node.id in expanded
                    val tri = CatalogTree.state(tree, node.id, on)
                    val unit = if (title == CatalogSelector.LIVE) " ch" else ""
                    val sub = when {
                        node.isParent -> CatalogTree.tally(tree, node.id, on).let { (a, b) -> "$a / $b" }
                        node.count > 0 -> "%,d".format(node.count) + unit
                        else -> ""
                    }
                    TreeRow(node, tri, isOpen, sub, tv,
                        Modifier.focusRequester(fr(node.id))
                            .then(if (i == 0 && firstFr != null) Modifier.focusRequester(firstFr) else Modifier)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val first = e.nativeKeyEvent.repeatCount == 0
                                when (e.key) {
                                    Key.DirectionRight -> if (node.isParent && !isOpen) { if (first) expand(node.id); true } else false
                                    Key.DirectionLeft -> when {
                                        node.isParent && isOpen -> { if (first) foldTo(CatalogTree.collapseFrom(tree, node.children.first(), expanded).first, node.id); true }
                                        node.parent != null -> {
                                            if (first) CatalogTree.collapseFrom(tree, node.id, expanded).let { (next, p) -> if (p != null) foldTo(next, p) }
                                            true
                                        }
                                        else -> false
                                    }
                                    else -> false
                                }
                            },
                        onClick = { onToggle(tree, node.id) },
                        onFold = if (node.isParent) ({ if (isOpen) foldTo(expanded - node.id, node.id) else expand(node.id) }) else null)
                }
                // An open top-level parent stays pinned while its rows scroll
                if (node.isParent && node.depth == 0 && node.id in expanded) stickyHeader(key = "h|" + node.id) {
                    Box(Modifier.fillMaxWidth().background(Pal.Surface)) { content() }
                }
                else item(key = node.id) { content() }
            }
        }
    }
}

/** Compact tree row: chevron (parents), tri-state box, name and a quiet count. */
@Composable
private fun TreeRow(node: CatalogTree.Node, tri: CatalogTree.Tri, open: Boolean, sub: String, tv: Boolean, modifier: Modifier,
                    onClick: () -> Unit, onFold: (() -> Unit)?) {
    val shape = RoundedCornerShape(Radii.row)
    val parent = node.isParent
    Row(modifier.fillMaxWidth().padding(start = (node.depth * 14).dp).focusCard(onClick, onMenu = onFold, scale = 1.015f, shape = shape)
        .background(if (parent) Pal.Soft else Color.Transparent, shape)
        .padding(start = 4.dp, end = 8.dp, top = if (tv) 4.dp else 5.dp, bottom = if (tv) 4.dp else 5.dp)
        .alpha(if (tri == CatalogTree.Tri.OFF) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Chevron: tap target on the phone (not a separate TV focus stop: Right / Left fold there)
        Box(Modifier.size(width = 22.dp, height = 22.dp)
            .then(if (onFold != null && !tv) Modifier.pointerInput(onFold) { detectTapGestures { onFold() } } else Modifier),
            contentAlignment = Alignment.Center) {
            if (parent) Text(if (open) "⌄" else "›", color = Pal.PrimaryText, fontSize = Ts.ROW.sp, fontWeight = FontWeight.Bold)
        }
        TriCheck(tri)
        Text(node.title, color = Pal.Text, fontSize = (if (parent) Ts.HEAD else Ts.SECONDARY).sp,
            fontWeight = if (parent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = Ts.CAPTION.sp, maxLines = 1)
    }
}

/** Tri-state box: ticked (blue with a tick), partly ticked (blue with a bar), empty. */
@Composable
private fun TriCheck(tri: CatalogTree.Tri) {
    val filled = tri != CatalogTree.Tri.OFF
    Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (filled) Pal.Primary else Color.Transparent)
        .border(2.dp, if (filled) Pal.Primary else Pal.Dim.copy(alpha = 0.7f), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center) {
        when (tri) {
            CatalogTree.Tri.ON -> Text("✓", color = Color.White, fontSize = Ts.CAPTION.sp, fontWeight = FontWeight.Bold)
            CatalogTree.Tri.PARTIAL -> Box(Modifier.size(width = 9.dp, height = 2.dp).background(Color.White))
            CatalogTree.Tri.OFF -> {}
        }
    }
}

@Composable
private fun Tile(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.card)
    Row(modifier.fillMaxWidth().focusCard(onClick, scale = 1.03f, shape = shape)
        .background(if (on) Pal.Primary.copy(alpha = 0.18f) else Pal.Soft, shape)
        .then(if (on) Modifier.border(1.dp, Pal.Primary.copy(alpha = 0.6f), shape) else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Check(on)
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = Ts.ROW.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = Pal.Dim, fontSize = Ts.CAPTION.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Check(on: Boolean) {
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Pal.Primary else Color.Transparent)
        .border(2.dp, if (on) Pal.Primary else Pal.Dim.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center) {
        if (on) Text("✓", color = Color.White, fontSize = Ts.SECONDARY.sp, fontWeight = FontWeight.Bold)
    }
}
