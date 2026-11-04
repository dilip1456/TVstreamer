package tv.tvstreamer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** "19:30" in the device's time format. */
fun hhmm(ms: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))

/** Current time, ticking once a minute (progress bars move without re-reading the guide). */
@Composable
fun rememberMinute(): Long {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); t = System.currentTimeMillis() } }
    return t
}

/** Thin progress line (gold on a soft track). */
@Composable
fun ProgressLine(frac: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(Pal.Gold))
    }
}

/** Now / next of a channel with a progress bar (empty while the guide is unknown). */
@Composable
fun NowNext(repo: Repo, ch: Item, modifier: Modifier = Modifier) {
    val t = rememberMinute()
    val progs = repo.epg.guide[ch.id]?.progs
    val now = Epg.now(progs, t)
    val next = Epg.next(progs, t)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (now != null) {
            Text(now.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hhmm(now.start), color = Pal.Dim, fontSize = 12.sp)
                ProgressLine(now.progress(t), Modifier.weight(1f))
                Text(hhmm(now.stop), color = Pal.Dim, fontSize = 12.sp)
            }
        }
        if (next != null) Text("Next  ${hhmm(next.start)}  ${next.title}", color = Pal.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Read-only text popup (stream details, channel info) with Close. */
@Composable
fun InfoDialog(title: String, text: String, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
    PopupFrame(onClose) {
        Column(Modifier.widthIn(min = 320.dp, max = 620.dp).background(Pal.Surface, RoundedCornerShape(18.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(text, color = Pal.Dim, fontSize = 13.sp, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            MenuEntry("Close", onClose, Modifier.focusRequester(fr), dim = true)
        }
    }
}

/**
 * TV player channel panel (Left in live TV): the channels of the current list with now / next. Up / Down move,
 * Left / Right step through the focused channel's schedule, OK plays the channel, or on an upcoming show sets
 * (or clears) a reminder. Back closes it.
 */
@Composable
fun ChannelPanel(repo: Repo, list: List<Item>, playing: Int, onPlay: (Int) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (playing - 3).coerceAtLeast(0))
    val first = remember { FocusRequester() }
    val t = rememberMinute()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    // Guide only for the rows on screen
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.mapNotNull { list.getOrNull(it.index)?.id } }
            .collect { repo.epg.want(it) }
    }
    PopupFrame(onClose, alignment = Alignment.CenterStart, scrim = Color(0x66000000)) {
        Column(Modifier.fillMaxHeight().width(460.dp).background(Color(0xF00E0E14)).padding(vertical = 24.dp)) {
            Text("Channels", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
            Text("◀ ▶ schedule  ·  OK play / remind", color = Pal.Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(list, key = { _, c -> c.key }) { i, raw ->
                    val ch = repo.named(raw)
                    var step by remember { mutableIntStateOf(0) }
                    var f by remember { mutableStateOf(false) }
                    val progs = Epg.upcoming(repo.epg.guide[ch.id]?.progs, t)
                    val shown = progs.getOrNull(step)
                    Column(
                        Modifier.fillMaxWidth().then(if (i == playing) Modifier.focusRequester(first) else Modifier)
                            .popupOpener()
                            .onFocusChanged { f = it.isFocused; if (!it.isFocused) step = 0 }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionRight -> { if (step < progs.size - 1) step++; true }
                                    Key.DirectionLeft -> { if (step > 0) step--; true }
                                    else -> false
                                }
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (f) Color(0x33FFFFFF) else Color.Transparent)
                            .clickable {
                                val p = shown
                                if (p != null && step > 0 && p.start > System.currentTimeMillis()) {
                                    val on = repo.toggleReminder(ch, p); tick++
                                    toast(ctx, if (on) "Reminder set: ${p.title} at ${hhmm(p.start)}" else "Reminder removed")
                                } else onPlay(i)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (ch.num > 0) Text("${ch.num}", color = Pal.Dim, fontSize = 13.sp, modifier = Modifier.widthIn(min = 34.dp))
                            Text(ChannelTitle.pretty(ch.name), color = if (i == playing) Pal.Gold else Pal.Text, fontSize = 16.sp,
                                fontWeight = if (f) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (i == playing) Text("● NOW", color = Pal.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (shown != null) {
                            val on = shown.isOn(t)
                            val label = (if (on) "Now" else hhmm(shown.start)) + "  " + shown.title +
                                (if (!on && repo.hasReminder(ch, shown)) "  ⏰" else "")
                            Text(label, color = if (f) Pal.Text else Pal.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (on) ProgressLine(shown.progress(t), Modifier.fillMaxWidth().padding(top = 4.dp))
                            if (f && step > 0) Text(if (repo.hasReminder(ch, shown)) "OK: remove reminder" else "OK: remind me",
                                color = Pal.Gold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The channel menu (list and player): only the options valid for the channel, Close last.
 * Play, hide / show in the current mode, add to another mode, move to the top of the mode, guide, remind me about
 * the next show, rename / reset name, channel info, plus [extra] (player entries such as "Not working? Retry link").
 */
@Composable
fun ChannelMenu(repo: Repo, ch: Item, @Suppress("UNUSED_PARAMETER") folder: String?, extra: List<Pair<String, () -> Unit>>,
                @Suppress("UNUSED_PARAMETER") onSelect: (() -> Unit)?, inPlayer: Boolean = false, onPlay: (() -> Unit)? = null,
                onGuide: (() -> Unit)? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember(ch.key) { mutableStateOf("main") }
    when (page) {
        "modes" -> { ModePicker(repo, ch) { onClose() }; return }
        "rename" -> { NameDialog("Rename channel", ch.name, "Save name", { repo.renameChannel(ch, it); onClose() }) { page = "main" }; return }
        "info" -> {
            val cat = repo.liveCats.firstOrNull { it.id == ch.cat }?.title.orEmpty()
            InfoDialog(ChannelTitle.pretty(ch.name), listOfNotNull(
                if (ch.num > 0) "Number: ${ch.num}" else null,
                "Category: ${cat.ifBlank { "—" }}",
                "Portal name: ${repo.portalName(ch)}",
                "Channel id: ${ch.id}",
                repo.liveIndex.info[ch.key]?.let { "Type: ${it.type} · Language: ${it.lang}" },
                if (ch.locked) "Locked (password)" else null
            ).joinToString("\n")) { onClose() }
            return
        }
    }
    val mode = repo.mode()
    val on = repo.isOn(ch)
    val next = Epg.next(repo.epg.guide[ch.id]?.progs, System.currentTimeMillis())
    ChoiceDialog(ChannelTitle.pretty(ch.name), buildList {
        if (!inPlayer && onPlay != null) add("▶  Play" to { onClose(); onPlay() })
        add(when {
            mode == LiveModes.DEFAULT -> if (on) "Hide channel" else "Show channel again"
            on -> "Remove from \"$mode\""
            else -> "Add to \"$mode\""
        } to { repo.toggleOn(ch); onClose() })
        if (repo.modes.isNotEmpty() || mode == LiveModes.DEFAULT) add("Add to a mode…" to { page = "modes" })
        if (mode != LiveModes.DEFAULT && on) add("Move to top of \"$mode\"" to { repo.toTopOfMode(ch); onClose() })
        if (onGuide != null) add("Guide / schedule" to { onGuide() })
        if (next != null) add((if (repo.hasReminder(ch, next)) "Cancel reminder: " else "Remind me: ") + "${next.title} ${hhmm(next.start)}" to {
            val set = repo.toggleReminder(ch, next)
            toast(ctx, if (set) "Reminder set for ${hhmm(next.start)}" else "Reminder removed"); onClose()
        })
        add("Rename channel" to { page = "rename" })
        if (repo.isRenamed(ch)) add("Reset name (${repo.portalName(ch)})" to { repo.renameChannel(ch, null); onClose() })
        add("Channel info" to { page = "info" })
        if (!inPlayer && onPlay != null) add("Not working? Play with a fresh link" to { onClose(); onPlay() })
        addAll(extra)
    }, focusKey = ch.key + page, onClose = onClose)
}

/** Which custom modes a channel is in: tick to add / remove; "New mode…" creates one with this channel. */
@Composable
fun ModePicker(repo: Repo, ch: Item, onClose: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog("New mode", "", "Create", { n ->
            FolderSet.clean(n).takeIf { it.isNotEmpty() }?.let { name -> repo.createFolder(name); repo.addToFolder(name, listOf(ch)) }
            naming = false
        }) { naming = false }
        return
    }
    val f = repo.folders
    ChoiceDialog("Modes: ${ChannelTitle.pretty(ch.name)}", buildList {
        f.names.forEach { n ->
            val inIt = f.items(n).any { it.key == ch.key }
            add("${if (inIt) "☑" else "☐"}  $n" to { if (inIt) repo.removeFromFolder(n, listOf(ch)) else repo.addToFolder(n, listOf(ch)) })
        }
        add("＋  New mode…" to { naming = true })
    }, focusKey = "modes" + f.names.size, onClose = onClose)
}

/**
 * Modes: switch, create (empty = everything hidden, or from the channels listed now), rename, delete.
 * [current] = the channels the current mode lists (for "start from the current set").
 */
@Composable
fun ModeMenu(repo: Repo, current: List<Item>, onClose: () -> Unit) {
    var page by remember { mutableStateOf("main") }
    var target by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    when (page) {
        "new" -> { NameDialog("New mode", "", "Next", { newName = it; page = "start" }) { page = "main" }; return }
        "start" -> {
            ChoiceDialog("Start \"${FolderSet.clean(newName)}\" with", listOf(
                "Everything hidden (pick channels to show)" to { repo.createMode(newName, null); onClose() },
                "The channels listed now (${current.size})" to { repo.createMode(newName, current); onClose() }
            ), focusKey = "start", onClose = { page = "main" })
            return
        }
        "edit" -> {
            ChoiceDialog("Mode: $target", listOf(
                "Rename" to { page = "rename" },
                "Delete" to { page = "delete" }
            ), focusKey = "edit$target", onClose = { page = "main" })
            return
        }
        "rename" -> { NameDialog("Rename mode", target, "Save", { repo.renameMode(target, it); onClose() }) { page = "edit" }; return }
        "delete" -> {
            ChoiceDialog("Delete \"$target\"? Channels stay in Live TV.", listOf(
                "Yes, delete" to { repo.deleteMode(target); onClose() },
                "No, keep it" to { page = "edit" }
            ), focusKey = "del$target", onClose = { page = "edit" })
            return
        }
    }
    val cur = repo.mode()
    ChoiceDialog("Mode", buildList {
        add((if (cur == LiveModes.DEFAULT) "✓  " else "     ") + "Default (all channels not hidden)" to { repo.selectMode(LiveModes.DEFAULT); onClose() })
        repo.modes.forEach { m ->
            add((if (cur == m) "✓  " else "     ") + "$m  (${repo.folders.items(m).size})" to { repo.selectMode(m); onClose() })
        }
        add("＋  New mode…" to { page = "new" })
        repo.modes.forEach { m -> add("✎  Edit \"$m\"…" to { target = m; page = "edit" }) }
    }, focusKey = "modes" + repo.modes.size, onClose = onClose)
}

/** Live TV languages (multi-select): only the ticked languages are listed. Nothing ticked = all languages. */
@Composable
fun LanguagePicker(repo: Repo, langs: List<Pair<String, Int>>, onClose: () -> Unit) {
    val sel = repo.liveLangs
    ChoiceDialog("Languages", buildList {
        add((if (sel == null) "☑" else "☐") + "  All languages" to { repo.chooseLiveLangs(null) })
        langs.forEach { (l, n) ->
            val on = sel?.contains(l) == true
            add("${if (on) "☑" else "☐"}  $l  ($n)" to {
                val base = sel ?: emptySet()
                repo.chooseLiveLangs(if (on) base - l else base + l)
            })
        }
    }, focusKey = "langs", onClose = onClose)
}
