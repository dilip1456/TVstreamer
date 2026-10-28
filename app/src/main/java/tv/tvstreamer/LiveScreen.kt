@file:OptIn(ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val DIM2 get() = Pal.Dim
private val WARN2 get() = Pal.Warn

/** Type and language of one channel. */
data class ChInfo(val type: String, val lang: String)

/**
 * Live TV with two modes (saved):
 *  - All channels: grouped by type (News, Sports, …) with a language filter, or the portal's original categories.
 *  - Favorites: only favourite channels, grouped (only the groups that have favourites) or as one list (My order / A-Z).
 * Channel up/down in the player follows the list that was on screen.
 */
@Composable
fun Live(repo: Repo, play: (List<Item>, Int) -> Unit) {
    val st = repo.store
    var mode by remember { mutableStateOf(st.sort("live_mode", "all")) }
    var groupBy by remember { mutableStateOf(st.sort("live_group", "type")) }
    var favView by remember { mutableStateOf(st.sort("live_favview", "grouped")) }
    var favOrder by remember { mutableStateOf(st.sort("live_favorder", "mine")) }
    var asList by remember { mutableStateOf(st.sort("live_view", "icons") == "list") }
    var sortAz by remember { mutableStateOf(st.sort("live_az", "default") == "az") }
    var langs by remember { mutableStateOf(st.langs("live")) }
    var pickLangs by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<Any?>(null) }
    var msg by remember { mutableStateOf("") }
    fun save(k: String, v: String) = st.saveSort(k, v)

    val unlocked = repo.gate(true) == null
    val cats = repo.visibleLive()
    val catIds = remember(cats) { cats.map { it.id }.toSet() }
    val catTitle = remember(repo.liveCats) { repo.liveCats.associate { it.id to it.title } }
    // Classify every channel once per channel list (off the main thread)
    val info by produceState(emptyMap<String, ChInfo>(), repo.channels, repo.liveCats) {
        value = withContext(Dispatchers.Default) {
            repo.channels.associate { c -> c.key to (catTitle[c.cat] ?: "").let { t -> ChInfo(LiveKind.type(t, c.name), LiveKind.language(t, c.name).ifEmpty { Tags.OTHER }) } }
        }
    }
    val visible = remember(repo.channels, catIds, repo.hidden, unlocked) {
        repo.channels.filter { it.cat in catIds && !repo.isChannelHidden(it) && (!it.locked || unlocked) }
    }
    val langCounts = remember(visible, info) { visible.groupingBy { info[it.key]?.lang ?: Tags.OTHER }.eachCount().toList().sortedByDescending { it.second } }
    val byLang = remember(visible, info, langs) { if (langs.isNullOrEmpty()) visible else visible.filter { (info[it.key]?.lang ?: Tags.OTHER) in langs!! } }
    fun ordered(l: List<Item>) = if (sortAz) l.sortedBy { it.name.lowercase() } else l

    val wide = LocalContext.current.resources.configuration.smallestScreenWidthDp >= 600
    // TV: start on the mode buttons so D-pad has a sensible first focus
    val topFr = remember { FocusRequester() }
    val tvLive = rememberIsTv()
    LaunchedEffect(Unit) { if (tvLive) { delay(150); runCatching { topFr.requestFocus() } } }
    Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 32.dp else 12.dp, vertical = 12.dp)) {
        Label("Live TV", 28)
        Spacer(Modifier.height(6.dp))
        // Mode and view options
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            item { PillButton("All channels", { mode = "all"; save("live_mode", mode) }, Modifier.focusRequester(topFr), selected = mode == "all", size = 14) }
            item { PillButton("★  Favorites", { mode = "fav"; save("live_mode", mode) }, selected = mode == "fav", size = 14) }
            item { Spacer(Modifier.width(12.dp)) }
            if (mode == "all" || favView == "grouped") item {
                PillButton(if (groupBy == "type") "Grouped by type" else "Original categories",
                    { groupBy = if (groupBy == "type") "orig" else "type"; save("live_group", groupBy) }, size = 14)
            }
            if (mode == "fav") {
                item { PillButton(if (favView == "grouped") "Grouped" else "Single list", { favView = if (favView == "grouped") "single" else "grouped"; save("live_favview", favView) }, size = 14) }
                if (favView == "single") item {
                    PillButton(if (favOrder == "mine") "My order" else "A-Z", { favOrder = if (favOrder == "mine") "az" else "mine"; save("live_favorder", favOrder) }, size = 14)
                }
            }
            if (mode == "all" && groupBy == "type") item {
                PillButton("Languages: " + (langs?.let { if (it.size <= 2) it.joinToString(", ") else "${it.size} chosen" } ?: "all"), { pickLangs = true }, size = 14)
            }
            item { PillButton(if (asList) "List" else "Icons", { asList = !asList; save("live_view", if (asList) "list" else "icons") }, size = 14) }
            if (!(mode == "fav" && favView == "single")) item {
                PillButton(if (sortAz) "A-Z" else "Portal order", { sortAz = !sortAz; save("live_az", if (sortAz) "az" else "default") }, size = 14)
            }
        }
        if (repo.customCats.isNotEmpty() && mode == "all") {
            repo.customCats.forEach { (name, items) ->
                Label(name, 16, DIM2)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(items) { i, ch -> ChannelCard(ch, repo.isFav(ch), { play(items, i) }, { menuTarget = ch }) }
                }
            }
        }
        if (msg.isNotEmpty()) Label(msg, 14, WARN2)
        Spacer(Modifier.height(8.dp))
        val chMenu: (Item) -> Unit = { menuTarget = it }
        when {
            mode == "fav" -> FavoritesView(repo, info, catTitle, groupBy, favView, favOrder, asList, sortAz, play, chMenu)
            groupBy == "type" -> TypeView(repo, byLang, info, asList, ::ordered, play, chMenu)
            else -> OriginalView(repo, cats, asList, sortAz, play, chMenu, { menuTarget = it }) { msg = it }
        }
    }

    if (pickLangs) LiveLanguages(langCounts, langs, { langs = it; st.saveLangs("live", it) }) { pickLangs = false }

    menuTarget?.let { t ->
        val close = { menuTarget = null }
        when (t) {
            is Cat -> ChoiceDialog(t.title, listOf(
                (if (repo.isHidden("itv", t.id)) "Show folder" else "Hide folder") to { repo.toggleHide("itv", t.id); close() }
            ), onClose = close)
            is Item -> ChoiceDialog(t.name, buildList {
                add((if (repo.isFav(t)) "★  Remove from favorites" else "☆  Add to favorites") to { repo.toggleFav(t); close() })
                if (mode == "fav" && favView == "single" && favOrder == "mine" && repo.isFav(t)) {
                    add("▲  Move up" to { repo.moveFav(t, -1) })
                    add("▼  Move down" to { repo.moveFav(t, 1) })
                }
                add("Move to My Channels" to { repo.moveToCustom(t, "My Channels"); close() })
                add((if (repo.isChannelHidden(t)) "Unhide channel" else "Hide channel") to { repo.toggleHideChannel(t); close() })
                info[t.key]?.let { add("Type: ${it.type} · Language: ${it.lang}" to close) }
            }, focusKey = t.key, onClose = close)
            else -> close()
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

/** Channels as icons (grid) or a list; [list] is what the player steps through with channel up/down. */
@Composable
private fun ChannelGrid(repo: Repo, list: List<Item>, asList: Boolean, play: (List<Item>, Int) -> Unit, menu: (Item) -> Unit) {
    if (asList) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(list, key = { i, c -> "$i|${c.key}" }) { i, ch ->
                Row1((if (repo.isFav(ch)) "★ " else "") + (if (ch.num > 0) "${ch.num}  " else "") + ch.name,
                    { repo.lastLiveIndex = i; play(list, i) }, onMenu = { menu(ch) }, onFocus = { Keys.focused = ch })
            }
        }
    } else {
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            gridItemsIndexed(list, key = { i, c -> "$i|${c.key}" }) { i, ch ->
                ChannelCard(ch, repo.isFav(ch), { repo.lastLiveIndex = i; play(list, i) }, { menu(ch) })
            }
        }
    }
}

@Composable
private fun TypeView(repo: Repo, chans: List<Item>, info: Map<String, ChInfo>, asList: Boolean, ordered: (List<Item>) -> List<Item>,
                     play: (List<Item>, Int) -> Unit, menu: (Item) -> Unit) {
    val byType = remember(chans, info) { chans.groupBy { info[it.key]?.type ?: LiveKind.OTHER } }
    val types = LiveKind.ORDER.filter { byType.containsKey(it) }
    var type by remember { mutableStateOf(repo.store.sort("live_type", "")) }
    val cur = types.find { it == type } ?: types.firstOrNull()
    var lang by remember(cur) { mutableStateOf("") }
    val inType = byType[cur] ?: emptyList()
    val langsHere = remember(inType, info) { inType.groupingBy { info[it.key]?.lang ?: Tags.OTHER }.eachCount().toList().sortedByDescending { it.second } }
    if (info.isEmpty() && repo.channels.isNotEmpty()) { Label("Sorting channels…", 16, DIM2); return }
    if (types.isEmpty()) { Label(if (repo.channels.isEmpty()) "No channels loaded" else "No channels for the chosen languages", 16, DIM2); return }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(types) { t -> Chip("$t  ${byType[t]?.size ?: 0}", t == cur, { type = t; repo.store.saveSort("live_type", t) }) }
    }
    if (langsHere.size > 1) {
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("All languages", lang.isEmpty(), { lang = "" }) }
            items(langsHere) { (l, n) -> Chip("$l  $n", l == lang, { lang = l }) }
        }
    }
    Spacer(Modifier.height(8.dp))
    val list = remember(inType, lang, ordered) { ordered(if (lang.isEmpty()) inType else inType.filter { (info[it.key]?.lang ?: Tags.OTHER) == lang }) }
    HiddenToggle(repo) { c -> c.let { info[it.key]?.type == cur } }
    ChannelGrid(repo, list, asList, play, menu)
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

@Composable
private fun FavoritesView(repo: Repo, info: Map<String, ChInfo>, catTitle: Map<String, String>, groupBy: String, favView: String,
                          favOrder: String, asList: Boolean, sortAz: Boolean, play: (List<Item>, Int) -> Unit, menu: (Item) -> Unit) {
    val favs = repo.favs.filter { it.kind == "itv" }
    if (favs.isEmpty()) {
        Label("No favorite channels yet. In All channels, press Menu (or long-press / hold Menu) on a channel and choose Add to favorites.", 16, DIM2, maxLines = 3)
        return
    }
    if (favView == "single") {
        val list = if (favOrder == "az") favs.sortedBy { it.name.lowercase() } else favs
        if (favOrder == "mine") Label("Menu on a channel: Move up / Move down to change the order", 13, DIM2)
        Spacer(Modifier.height(6.dp))
        ChannelGrid(repo, list, asList, play, menu)
        return
    }
    // Grouped: only the groups that contain favourites, each showing just its favourite channels
    val sections = remember(favs, info, groupBy, sortAz) {
        val g = favs.groupBy { if (groupBy == "type") info[it.key]?.type ?: LiveKind.OTHER else Names.cat(catTitle[it.cat] ?: "Other") }
        val keys = if (groupBy == "type") LiveKind.ORDER.filter { it in g } else g.keys.toList()
        keys.map { k -> k to g[k]!!.let { l -> if (sortAz) l.sortedBy { it.name.lowercase() } else l } }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(sections, key = { it.first }) { (title, list) ->
            Column {
                Label("$title  ·  ${list.size}", 17, Pal.Gold)
                Spacer(Modifier.height(4.dp))
                if (asList) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    list.forEachIndexed { i, ch -> Row1("★ ${ch.name}", { play(list, i) }, onMenu = { menu(ch) }, onFocus = { Keys.focused = ch }) }
                } else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(list, key = { i, c -> "$i|${c.key}" }) { i, ch -> ChannelCard(ch, true, { play(list, i) }, { menu(ch) }) }
                }
            }
        }
    }
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
