package tv.tvstreamer

import androidx.compose.foundation.border

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
fun ChannelPanel(repo: Repo, list: List<Item>, playing: Int, title: String = "Channels", onPlay: (Int) -> Unit, onClose: () -> Unit) {
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
    PopupFrame(onClose, alignment = Alignment.CenterStart, scrim = Color(0x26000000)) {
        Column(Modifier.padding(horizontal = 20.dp).fillMaxHeight(0.84f).width(360.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xB80E0E14)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(18.dp)).padding(vertical = 14.dp)) {
            Text(title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp))
            Text("OK play · ◀ ▶ later shows", color = Pal.Dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                            .background(if (f) Color(0x26FFFFFF) else Color.Transparent)
                            .clickable {
                                val p = shown
                                if (p != null && step > 0 && p.start > System.currentTimeMillis()) {
                                    val on = repo.toggleReminder(ch, p); tick++
                                    toast(ctx, if (on) "Reminder set: ${p.title} at ${hhmm(p.start)}" else "Reminder removed")
                                } else onPlay(i)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (ch.num > 0) Text("${ch.num}", color = Pal.Dim, fontSize = 12.sp, modifier = Modifier.widthIn(min = 34.dp))
                            Text(ChannelTitle.pretty(ch.name), color = if (i == playing) Pal.Gold else Pal.Text, fontSize = 14.sp,
                                fontWeight = if (f) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (i == playing) Text("● NOW", color = Pal.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (shown != null) {
                            val on = shown.isOn(t)
                            val label = (if (on) "Now" else hhmm(shown.start)) + "  " + shown.title +
                                (if (!on && repo.hasReminder(ch, shown)) "  ⏰" else "")
                            Text(label, color = if (f) Pal.Text else Pal.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (ch.locked) "Locked (password)" else null,
                repo.api.liveMeta[ch.id]?.let { m -> "Stream: " + (if (m.useTmp) "temporary link" else "direct link") +
                    (if (m.cmds.size > 1) " · ${m.cmds.size} alternates" else "") },
                LinkDiag.of(ch.key)?.let { "\nLast link attempt\n" + it.line() } ?: "\nNot played on this device yet"
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
        if (onGuide != null) add("Guide / schedule" to { onGuide() })
        if (next != null) add((if (repo.hasReminder(ch, next)) "Cancel reminder: " else "Remind me: ") + "${next.title} ${hhmm(next.start)}" to {
            val set = repo.toggleReminder(ch, next)
            toast(ctx, if (set) "Reminder set for ${hhmm(next.start)}" else "Reminder removed"); onClose()
        })
        add("Rename channel" to { page = "rename" })
        if (repo.isRenamed(ch)) add("Reset name (${repo.portalName(ch)})" to { repo.renameChannel(ch, null); onClose() })
        add("Channel info" to { page = "info" })
        if (!inPlayer && onPlay != null) add("Play with a fresh link" to { onClose(); onPlay() })
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

/**
 * Live player schedule (Right): this channel's guide as a list (now with progress, next, later). OK on an upcoming show
 * sets or clears a reminder; Back closes.
 */
@Composable
fun GuidePanel(repo: Repo, ch: Item, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = rememberMinute()
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ch.id) { repo.epg.wantFirst(ch.id); repo.epg.wantDay(ch.id) }
    val progs = Epg.upcoming(repo.epg.guide[ch.id]?.progs, t)
    val loaded = repo.epg.guide[ch.id] != null
    LaunchedEffect(progs.isNotEmpty()) { delay(80); runCatching { first.requestFocus() } }
    PopupFrame(onClose, alignment = Alignment.CenterEnd, scrim = Color(0x26000000)) {
        Column(Modifier.padding(horizontal = 20.dp).fillMaxHeight(0.84f).width(380.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xB80E0E14)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(18.dp)).padding(vertical = 14.dp)) {
            Text(ChannelTitle.pretty(ch.name), color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp))
            Text("Schedule  ·  OK on a later show: remind me", color = Pal.Dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            if (progs.isEmpty()) {
                Text(if (loaded) "No schedule for this channel." else "Loading the schedule…", color = Pal.Dim, fontSize = 15.sp,
                    modifier = Modifier.padding(24.dp))
                MenuEntry("Close", onClose, Modifier.padding(horizontal = 14.dp).focusRequester(first), dim = true)
            } else LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(progs, key = { _, p -> p.start }) { i, p ->
                    var f by remember { mutableStateOf(false) }
                    val on = p.isOn(t)
                    val reminded = remember(tick, p.start) { repo.hasReminder(ch, p) }
                    Column(
                        Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .popupOpener()
                            .onFocusChanged { f = it.isFocused }
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (f) Color(0x26FFFFFF) else Color.Transparent)
                            .clickable {
                                if (on) onClose() else {
                                    val set = repo.toggleReminder(ch, p); tick++
                                    toast(ctx, if (set) "Reminder set: ${p.title} at ${hhmm(p.start)}" else "Reminder removed")
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (on) "NOW" else hhmm(p.start), color = if (on) Pal.Gold else Pal.Dim, fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.widthIn(min = 56.dp))
                            Text(p.title, color = Pal.Text, fontSize = 14.sp, fontWeight = if (f || on) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (reminded) Text("⏰", fontSize = 12.sp)
                        }
                        if (on) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp)) {
                            Text(hhmm(p.start), color = Pal.Dim, fontSize = 11.sp)
                            ProgressLine(p.progress(t), Modifier.weight(1f))
                            Text(hhmm(p.stop), color = Pal.Dim, fontSize = 11.sp)
                        }
                        if (f && p.desc.isNotBlank()) Text(p.desc, color = Pal.Dim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp))
                        if (f && !on) Text(if (reminded) "OK: remove reminder" else "OK: remind me", color = Pal.Gold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/**
 * Live player info bar (OK / after zapping): a slim strip along the bottom (about an eighth of the screen) on a soft
 * gradient scrim: number and name, what is on now with a thin progress line and times, and what is next. Small text,
 * no box over the picture. It never takes focus, so Up / Down keep zapping.
 */
@Composable
fun LiveInfoBar(repo: Repo, ch: Item, pos: Int, count: Int, isTv: Boolean, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    LaunchedEffect(ch.id) { repo.epg.wantFirst(ch.id) }
    val t = rememberMinute()
    val entry = repo.epg.guide[ch.id]
    val now = Epg.now(entry?.progs, t)
    val next = Epg.next(entry?.progs, t)
    Column(
        modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xE6000000))))
            .padding(start = if (isTv) 48.dp else 14.dp, end = if (isTv) 48.dp else 14.dp, top = if (isTv) 28.dp else 18.dp, bottom = if (isTv) 18.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (ch.num > 0) Text("${ch.num}", color = Pal.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(ChannelTitle.pretty(ch.name), color = Color.White, fontSize = if (isTv) 16.sp else 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            Text(now?.title ?: if (entry == null) "Loading guide…" else "No guide", color = if (now != null) Color.White else Pal.Dim,
                fontSize = if (isTv) 14.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (next != null) Text("Next ${hhmm(next.start)}  ${next.title}", color = Pal.Dim, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if (isTv) 340.dp else 160.dp))
            if (!repo.isOn(ch)) Text("Hidden", color = Pal.Dim, fontSize = 11.sp,
                modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            if (count > 1 && isTv) Text("${pos + 1}/$count", color = Pal.Dim, fontSize = 11.sp)
        }
        if (now != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hhmm(now.start), color = Pal.Dim, fontSize = 11.sp)
            ProgressLine(now.progress(t), Modifier.weight(1f))
            Text(hhmm(now.stop), color = Pal.Dim, fontSize = 11.sp)
        }
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp), content = actions)
    }
}
