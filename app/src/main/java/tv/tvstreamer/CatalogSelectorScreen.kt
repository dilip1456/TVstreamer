@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

    // Step 3 keeps the list it opened with, so unticking a row there does not make it jump away
    var summary by remember { mutableStateOf<Map<String, List<CatalogGroups.Group>>>(emptyMap()) }

    fun goStep(s: Int) {
        if (step == 0 && s > 0) saveLangs()
        if (s == 2) summary = columns.mapValues { (_, g) -> CatalogSelector.shownOnly(g, ::on) }
        step = s
    }
    BackHandler(step > 0 && !Popups.any) { goStep(step - 1) }

    val firstFr = remember(step) { FocusRequester() }
    val ready = when (step) { 0 -> langRows.isNotEmpty(); else -> true }
    LaunchedEffect(step, ready) { if (ready) { delay(120); runCatching { firstFr.requestFocus() } } }

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
                if (step < 2) PillButton("Next ›", { goStep(step + 1) }, primary = true, compact = true, size = Ts.HEAD)
                else PillButton("Done", exit, primary = true, compact = true, size = Ts.HEAD)
            }
            when (step) {
                0 -> LanguageStep(langRows, langs, tv, firstFr) { langs = it }
                1 -> Column(Modifier.fillMaxSize()) {
                    ButtonFlow {
                        PillButton((if (mode == LiveModes.DEFAULT) "Default" else mode) + "  ▾", { modes = true },
                            selected = true, compact = true, size = Ts.HEAD)
                        PillButton("🌐  " + CatalogSelector.langLabel(savedLangs), { goStep(0) }, compact = true, size = Ts.HEAD)
                    }
                    Spacer(Modifier.height(10.dp))
                    Columns(columns, tv, firstFr, summary = false, on = ::on,
                        onToggle = { g -> repo.toggleGroupKey(g.key, g.title) },
                        onAll = { gs, v -> repo.setGroupsOn(gs.map { it.key to it.title }, v) })
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Text("🌐  " + CatalogSelector.langLabel(savedLangs) + "   ·   " +
                        CatalogSelector.SECTIONS.joinToString("   ·   ") { s -> "$s ${summary[s].orEmpty().count { on(it.key) }}" },
                        color = Pal.Dim, fontSize = Ts.SECONDARY.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 10.dp))
                    Columns(summary, tv, firstFr, summary = true, on = ::on,
                        onToggle = { g -> repo.toggleGroupKey(g.key, g.title) },
                        onAll = { gs, v -> repo.setGroupsOn(gs.map { it.key to it.title }, v) })
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
    val names = listOf("Languages", "Categories", "Your selection")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (tv) 14.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            val cur = i == step
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.alpha(if (cur) 1f else 0.55f)) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (i <= step) Pal.Gold else Pal.Soft2),
                    contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = if (i <= step) Color.Black else Pal.Text, fontSize = Ts.CAPTION.sp, fontWeight = FontWeight.Bold)
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
            PillButton("Select all", { set(rows.map { it.name }.toSet()) }, compact = true, size = Ts.HEAD)
            PillButton("Select none", { set(emptySet()) }, compact = true, size = Ts.HEAD)
            Text(if (sel.isEmpty()) "All languages" else "${sel.size} selected", color = Pal.Dim, fontSize = Ts.SECONDARY.sp)
        }
        if (rows.isEmpty()) {
            Text("Loading…", color = Pal.Dim, fontSize = Ts.ROW.sp, modifier = Modifier.padding(top = 16.dp))
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(if (tv) 250.dp else 160.dp), Modifier.fillMaxSize(),
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
    columns: Map<String, List<CatalogGroups.Group>>, tv: Boolean, firstFr: FocusRequester, summary: Boolean,
    on: (String) -> Boolean, onToggle: (CatalogGroups.Group) -> Unit, onAll: (List<CatalogGroups.Group>, Boolean) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = tv || maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CatalogSelector.SECTIONS.forEachIndexed { i, s ->
                    SectionColumn(s, columns[s].orEmpty(), summary, on, onToggle, onAll,
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
                SectionColumn(cur, columns[cur].orEmpty(), summary, on, onToggle, onAll, firstFr, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SectionColumn(
    title: String, groups: List<CatalogGroups.Group>, summary: Boolean, on: (String) -> Boolean,
    onToggle: (CatalogGroups.Group) -> Unit, onAll: (List<CatalogGroups.Group>, Boolean) -> Unit,
    firstFr: FocusRequester?, modifier: Modifier
) {
    val shape = RoundedCornerShape(Radii.panel)
    Column(modifier.clip(shape).background(Pal.Surface.copy(alpha = 0.72f))
        .border(1.dp, Pal.Gold.copy(alpha = 0.18f), shape).padding(10.dp)) {
        val (n, total) = CatalogSelector.tally(groups, on)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp)) {
            Text(title.uppercase(), color = Pal.GoldText, fontSize = Ts.HEAD.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Text("$n / $total", color = Pal.Dim, fontSize = Ts.SECONDARY.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (!summary && groups.isNotEmpty()) {
                PillButton("All", { onAll(groups, true) }, compact = true, size = Ts.SECONDARY)
                PillButton("None", { onAll(groups, false) }, compact = true, size = Ts.SECONDARY)
            }
        }
        if (groups.isEmpty()) {
            Text(if (summary) "Nothing selected" else "No categories", color = Pal.Dim, fontSize = Ts.SECONDARY.sp,
                modifier = Modifier.padding(4.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(4.dp)) {
            itemsIndexed(groups, key = { _, g -> g.key }) { i, g ->
                val unit = if (g.section == CatalogSelector.LIVE) " ch" else ""
                CheckRow(g.title, if (g.count > 0) "%,d".format(g.count) + unit else "", on(g.key), g.depth,
                    if (i == 0 && firstFr != null) Modifier.focusRequester(firstFr) else Modifier) { onToggle(g) }
            }
        }
    }
}

@Composable
private fun Tile(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.card)
    Row(modifier.fillMaxWidth().focusCard(onClick, scale = 1.03f, shape = shape)
        .background(if (on) Pal.Gold.copy(alpha = 0.18f) else Pal.Soft, shape)
        .then(if (on) Modifier.border(1.dp, Pal.Gold.copy(alpha = 0.6f), shape) else Modifier)
        .padding(horizontal = 14.dp, vertical = 12.dp),
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
private fun CheckRow(title: String, count: String, on: Boolean, depth: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.row)
    Row(modifier.fillMaxWidth().padding(start = (depth * 16).dp).focusCard(onClick, scale = 1.02f, shape = shape)
        .background(if (on) Color.Transparent else Pal.Card.copy(alpha = 0.35f), shape)
        .padding(horizontal = 10.dp, vertical = 8.dp)
        .alpha(if (on) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Check(on)
        Text(title, color = Pal.Text, fontSize = Ts.ROW.sp, fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count.isNotEmpty()) Text(count, color = Pal.Dim, fontSize = Ts.CAPTION.sp, maxLines = 1)
    }
}

@Composable
private fun Check(on: Boolean) {
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Pal.Gold else Color.Transparent)
        .border(2.dp, if (on) Pal.Gold else Pal.Dim.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center) {
        if (on) Text("✓", color = Color.Black, fontSize = Ts.SECONDARY.sp, fontWeight = FontWeight.Bold)
    }
}
