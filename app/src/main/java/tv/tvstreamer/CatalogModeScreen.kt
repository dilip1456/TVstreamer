@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.tvstreamer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * One list to show or hide movie groups, series groups and Live TV categories, under the same
 * Default / custom modes as Live TV. Click or OK toggles a group; hold / long-press starts bulk select.
 */
@Composable
fun CatalogModeScreen(repo: Repo, go: (Screen) -> Unit) {
    val tv = rememberIsTv()
    val mode = repo.mode()
    var view by remember { mutableStateOf(LiveModes.View.ALL) }
    var q by remember { mutableStateOf("") }
    var popup by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    val folded = repo.catalogFolded
    fun setFolded(s: Set<String>) = repo.foldCatalog(s)
    var groupMenu by remember { mutableStateOf<CatalogGroups.Group?>(null) }
    var sectionMenu by remember { mutableStateOf<String?>(null) }
    val hPad = if (tv) 48.dp else 16.dp
    val firstFr = remember { FocusRequester() }
    val selectFr = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val vod = remember(repo.vodCats, repo.vodOrder, repo.lib.version, repo.hidden, repo.liveMode, repo.folders) {
        CatalogGroups.vodGroups(repo.ordered("vod"), repo::isSeriesCat) { c -> repo.lib.inCat(c.id).size }
    }
    val live = remember(repo.channels, repo.liveCats, repo.liveIndex.info, repo.hidden, repo.liveMode, repo.folders) {
        val titles = repo.liveCats.associate { it.id to it.title }
        val rows = LiveModes.rows(repo.channels, LiveModes.DEFAULT, FolderSet(), emptySet(), LiveModes.View.ALL, null, repo.liveIndex.info, titles)
        CatalogGroups.liveGroups(LiveModes.tree(rows, emptySet(), LiveModes.View.ALL))
    }
    val all = remember(vod, live) { vod + live }
    val visibleCount = all.count { repo.groupOn(it.key) }

    fun matches(g: CatalogGroups.Group) = view.shows(repo.groupOn(g.key)) &&
        (q.isBlank() || g.title.contains(q, true) || g.section.contains(q, true) || g.key.contains(q, true))

    /** Section headers stay visible when folded; Live type rows hide their children when collapsed. */
    val lines = remember(all, view, q, repo.hidden, repo.liveMode, repo.folders, folded) {
        val matched = all.filter(::matches)
        val out = mutableListOf<Any>()
        for (section in listOf("Movies", "Series", "Live TV")) {
            val groups = matched.filter { it.section == section }
            if (groups.isEmpty() && all.none { it.section == section }) continue
            if (groups.isEmpty() && q.isNotBlank()) continue
            out += section
            if (section in folded) continue
            var skipUnder: String? = null
            for (g in groups) {
                if (skipUnder != null) {
                    if (g.depth > 0 && (g.parent == skipUnder || g.key.startsWith(skipUnder))) continue
                    skipUnder = null
                }
                out += g
                if (g.depth == 0 && section == "Live TV" && "live:${g.key}" in folded) skipUnder = g.key
            }
        }
        out
    }
    val visible = lines.filterIsInstance<CatalogGroups.Group>()

    val liveOnScreen = remember(repo.channels, repo.hidden, repo.liveMode, repo.folders) {
        repo.channels.filter { repo.isOn(it) }
    }

    LaunchedEffect(visible.isNotEmpty()) {
        if (tv && visible.isNotEmpty()) { delay(120); runCatching { firstFr.requestFocus() } }
    }

    fun toggle(g: CatalogGroups.Group) = repo.toggleGroupKey(g.key, g.title)
    fun startSelect(key: String) { selected = setOf(key) }
    fun clearSelect() { selected = null }

    Box(Modifier.fillMaxSize().background(Pal.Bg).onKeyEvent { e ->
        if (e.type == KeyEventType.KeyUp && e.key == Key.Menu && popup == null) { popup = "more"; true } else false
    }) {
        CinematicWash()
        Column(Modifier.fillMaxSize().padding(horizontal = hPad, vertical = if (tv) 18.dp else 12.dp)) {
            Text("CATALOG", color = Pal.GoldText, fontSize = Ts.CAPTION.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.2.sp, modifier = Modifier.padding(bottom = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("What to show", color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                Text(
                    listOfNotNull(
                        if (mode == LiveModes.DEFAULT) "Default" else mode,
                        "$visibleCount of ${all.size} groups"
                    ).joinToString("  ·  "),
                    color = Pal.Dim, fontSize = Ts.SECONDARY.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Text("Hide a group to remove it from Movies, Series or Live TV in this mode.",
                color = Pal.Dim, fontSize = Ts.CAPTION.sp, modifier = Modifier.padding(bottom = 10.dp))
            ButtonFlow {
                PillButton((if (mode == LiveModes.DEFAULT) "Default" else mode) + "  ▾", { popup = "modes" },
                    selected = true, size = Ts.HEAD, compact = true)
                PillButton(viewLabel(view) + "  ▾", { view = view.next() }, size = Ts.HEAD, compact = true)
                PillButton("Expand all", { setFolded(emptySet()) }, size = Ts.SECONDARY, compact = true)
                PillButton("Collapse all", {
                    setFolded(ItemContext.catalogCollapseAll(live.filter { it.depth == 0 }.map { it.key }))
                }, size = Ts.SECONDARY, compact = true)
                PillButton("⋮", { popup = "more" }, size = Ts.SECONDARY, compact = true)
            }
            Spacer(Modifier.height(10.dp))
            val kb = LocalSoftwareKeyboardController.current
            val fm = LocalFocusManager.current
            KeyboardGate(Modifier.fillMaxWidth(if (tv) 0.46f else 1f).padding(bottom = 10.dp)) { inner ->
                TextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { kb?.hide(); fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                    placeholder = { Text("Filter groups…", color = Pal.Dim, fontSize = Ts.ROW.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Pal.Text, fontSize = Ts.ROW.sp),
                    modifier = inner.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Pal.Surface, unfocusedContainerColor = Pal.Surface.copy(alpha = 0.92f),
                        focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Pal.Gold
                    )
                )
            }

            if (lines.isEmpty()) {
                Text(
                    when {
                        all.isEmpty() -> "Loading your catalog…"
                        q.isNotBlank() -> "No groups match \"$q\"."
                        view == LiveModes.View.HIDDEN -> "Nothing hidden in this mode."
                        else -> "Nothing to show in this mode. Switch to All groups, or pick groups to show."
                    },
                    color = Pal.Dim, fontSize = Ts.ROW.sp, modifier = Modifier.padding(top = 20.dp)
                )
            } else {
                val firstGroup = lines.indexOfFirst { it is CatalogGroups.Group }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = if (selected != null) 88.dp else 28.dp)) {
                    items(lines.size, key = { i ->
                        when (val line = lines[i]) {
                            is CatalogGroups.Group -> line.key
                            else -> "sec:$line"
                        }
                    }) { i ->
                        when (val line = lines[i]) {
                            is String -> SectionLabel(line, folded = line in folded, tv = tv,
                                onToggle = { setFolded(if (line in folded) folded - line else folded + line) }, onMenu = { sectionMenu = line })
                            is CatalogGroups.Group -> {
                                val g = line
                                val on = repo.groupOn(g.key)
                                GroupRow(
                                    g = g, on = on, tv = tv, checking = selected,
                                    modifier = if (i == firstGroup) Modifier.focusRequester(firstFr) else Modifier,
                                    onClick = {
                                        val sel = selected
                                        if (sel != null) selected = if (g.key in sel) sel - g.key else sel + g.key
                                        else toggle(g)
                                    },
                                    onToggleFold = {
                                        if (g.section == "Live TV" && g.depth == 0) {
                                            val k = "live:${g.key}"
                                            setFolded(if (k in folded) folded - k else folded + k)
                                        }
                                    },
                                    onHold = { groupMenu = g },
                                    onChip = { toggle(g) }
                                )
                            }
                        }
                    }
                }
            }
        }

        selected?.let { sel ->
            val picked = all.filter { it.key in sel }
            CatalogSelectBar(
                n = sel.size, mode = if (mode == LiveModes.DEFAULT) "Default" else mode, tv = tv, fr = selectFr,
                onShow = { repo.setGroupsOn(picked.map { it.key to it.title }, true); clearSelect() },
                onHide = { repo.setGroupsOn(picked.map { it.key to it.title }, false); clearSelect() },
                onAll = {
                    val keys = visible.map { it.key }.toSet()
                    selected = if (keys.isNotEmpty() && keys.all { it in sel }) emptySet() else keys
                },
                onCancel = { clearSelect() }
            )
            LaunchedEffect(Unit) { delay(80); runCatching { selectFr.requestFocus() } }
        }
    }

    when (popup) {
        "modes" -> ModeMenu(repo, liveOnScreen) { popup = null }
        "more" -> ChoiceDialog("What to show", listOf(
            "Modes…" to { popup = "modes" },
            "Only visible" to { view = LiveModes.View.ACTIVE; popup = null },
            "Only hidden" to { view = LiveModes.View.HIDDEN; popup = null },
            "All groups" to { view = LiveModes.View.ALL; popup = null },
            "Expand all" to { setFolded(emptySet()); popup = null },
            "Collapse all" to {
                setFolded(ItemContext.catalogCollapseAll(live.filter { it.depth == 0 }.map { it.key }))
                popup = null
            }
        ), focusKey = "catalog-more", onClose = { popup = null })
    }

    groupMenu?.let { g ->
        val keys = CatalogGroups.withDescendants(g, all).map { it.key to it.title }
        val liveType = g.section == "Live TV" && g.depth == 0
        val foldKey = if (liveType) "live:${g.key}" else null
        val labels = ItemContext.catalogGroupItems(repo.groupOn(g.key), liveType, foldKey?.let { it in folded })
        ChoiceDialog(g.title, labels.map { label ->
            label to {
                when (label) {
                    ItemContext.SHOW -> repo.setGroupsOn(listOf(g.key to g.title), true)
                    ItemContext.HIDE -> repo.setGroupsOn(listOf(g.key to g.title), false)
                    ItemContext.SHOW_ALL -> repo.setGroupsOn(keys, true)
                    ItemContext.HIDE_ALL -> repo.setGroupsOn(keys, false)
                    ItemContext.COLLAPSE, ItemContext.EXPAND -> foldKey?.let { k ->
                        setFolded(if (k in folded) folded - k else folded + k)
                    }
                    ItemContext.EXPAND_ALL -> setFolded(emptySet())
                    ItemContext.COLLAPSE_ALL -> setFolded(ItemContext.catalogCollapseAll(live.filter { it.depth == 0 }.map { it.key }))
                    ItemContext.SELECT -> startSelect(g.key)
                }
                groupMenu = null
            }
        }, focusKey = "cat-ctx-" + g.key, onClose = { groupMenu = null })
    }
    sectionMenu?.let { sec ->
        val labels = ItemContext.sectionItems(sec in folded)
        ChoiceDialog(sec, labels.map { label ->
            label to {
                when (label) {
                    ItemContext.COLLAPSE, ItemContext.EXPAND ->
                        setFolded(if (sec in folded) folded - sec else folded + sec)
                    ItemContext.EXPAND_ALL -> setFolded(emptySet())
                    ItemContext.COLLAPSE_ALL -> setFolded(ItemContext.catalogCollapseAll(live.filter { it.depth == 0 }.map { it.key }))
                }
                sectionMenu = null
            }
        }, focusKey = "sec-ctx-" + sec, onClose = { sectionMenu = null })
    }
}

@Composable
private fun SectionLabel(title: String, folded: Boolean, tv: Boolean, onToggle: () -> Unit, onMenu: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.row)
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)
            .popupOpener()
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e -> holdOk(e, held, { held = it }, onMenu) }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenu(); true } else false }
            .clip(shape)
            .background(if (f) Pal.FocusBg else Color.Transparent)
            .then(if (f) Modifier.border(2.dp, Pal.Gold.copy(alpha = 0.9f), shape) else Modifier)
            .combinedClickable(onClick = onToggle, onLongClick = onMenu)
            .padding(horizontal = 10.dp, vertical = if (tv) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(if (folded) "▸" else "▾", color = if (f) Pal.Gold else Pal.Dim, fontSize = Ts.SECONDARY.sp)
        Text(title.uppercase(), color = Pal.GoldText, fontSize = Ts.HEAD.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun GroupRow(
    g: CatalogGroups.Group, on: Boolean, tv: Boolean, checking: Set<String>?,
    modifier: Modifier = Modifier, onClick: () -> Unit, onToggleFold: () -> Unit, onHold: () -> Unit, onChip: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.03f else 1f, Motion.spec(), label = "gScale")
    val ring by animateFloatAsState(if (f) 1f else 0f, Motion.spec(), label = "gRing")
    val shape = RoundedCornerShape(Radii.row)
    val bg by animateColorAsState(
        when { f -> Pal.FocusBg; !on -> Pal.Card.copy(alpha = 0.35f); else -> Color.Transparent },
        Motion.colorSpec(), label = "gBg"
    )
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = s; scaleY = s }
            .padding(start = (g.depth * 18).dp)
            .popupOpener()
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { e -> holdOk(e, held, { held = it }, onHold) }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onHold(); true } else false }
            .then(if (ring > 0.01f) Modifier
                .border(6.dp, Pal.Focus.copy(alpha = 0.20f * ring), shape)
                .border(2.dp, Pal.Focus.copy(alpha = 0.95f * ring), shape) else Modifier)
            .clip(shape)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onHold)
            .padding(start = 12.dp, end = 8.dp, top = if (tv) 9.dp else 7.dp, bottom = if (tv) 9.dp else 7.dp)
            .alpha(if (on || checking != null) 1f else 0.48f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (checking != null) CheckMark(g.key in checking)
        if (g.depth == 0 && g.section == "Live TV") {
            Text("▾", color = if (f) Pal.Gold else Pal.Dim, fontSize = Ts.CAPTION.sp, modifier = Modifier.combinedClickable(onClick = onToggleFold))
        }
        Column(Modifier.weight(1f)) {
            Text(g.title, color = if (f || on) Pal.Text else Pal.Text2, fontSize = Ts.ROW.sp,
                fontWeight = if (f) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (g.count > 0) Text("${g.count}" + if (g.section == "Live TV") " channels" else " titles",
                color = Pal.Dim, fontSize = Ts.CAPTION.sp, maxLines = 1)
        }
        QuietChip(if (on) "Hide" else "Show", accent = !on, tv = tv, onClick = onChip)
    }
}

@Composable
private fun QuietChip(label: String, accent: Boolean, tv: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.popupOpener().onFocusChanged { f = it.isFocused }
            .clip(shape)
            .background(when { f -> Pal.Gold; accent -> Pal.Gold.copy(alpha = 0.16f); else -> Pal.Soft })
            .then(if (f) Modifier.border(2.dp, Pal.Gold, shape) else if (accent) Modifier.border(1.dp, Pal.Gold.copy(alpha = 0.45f), shape) else Modifier)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = if (tv) 14.dp else 11.dp, vertical = if (tv) 6.dp else 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = when { f -> Color.Black; accent -> Pal.GoldText; else -> Pal.Text },
            fontSize = Ts.CAPTION.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    }
}

@Composable
private fun CheckMark(on: Boolean) {
    val c by animateColorAsState(if (on) Pal.Gold else Color.Transparent, Motion.colorSpec(), label = "chk")
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(c)
            .border(2.dp, if (on) Pal.Gold else Pal.Dim.copy(alpha = 0.7f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) { if (on) Text("✓", color = Color.Black, fontSize = Ts.SECONDARY.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun CatalogSelectBar(n: Int, mode: String, tv: Boolean, fr: FocusRequester,
                             onShow: () -> Unit, onHide: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (tv) 48.dp else 12.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(Radii.panel))
                .background(Pal.Surface.copy(alpha = 0.96f))
                .border(1.dp, Pal.Gold.copy(alpha = 0.28f), RoundedCornerShape(Radii.panel))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (n == 0) "Select groups" else "$n selected  ·  $mode", color = Pal.Text, fontSize = Ts.HEAD.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            PillButton("Show", onShow, Modifier.focusRequester(fr), primary = true, compact = true, size = Ts.HEAD)
            PillButton("Hide", onHide, compact = true, size = Ts.HEAD)
            PillButton("All on this list", onAll, compact = true, size = Ts.SECONDARY)
            PillButton("Cancel", onCancel, compact = true, size = Ts.SECONDARY)
        }
    }
}

private fun holdOk(e: KeyEvent, held: Boolean, setHeld: (Boolean) -> Unit, onHold: () -> Unit): Boolean {
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

private fun viewLabel(v: LiveModes.View) = when (v) {
    LiveModes.View.ACTIVE -> "Only visible"
    LiveModes.View.HIDDEN -> "Only hidden"
    LiveModes.View.ALL -> "All groups"
}
