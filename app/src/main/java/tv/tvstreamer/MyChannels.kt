package tv.tvstreamer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Keys of the channels picked in selection mode (null = not selecting). Cards show a tick for them. */
val LocalPicked = compositionLocalOf<Set<String>?> { null }

/** The channel as a card shows it: with a tick in selection mode. */
@Composable
fun pickShown(ch: Item): Item {
    val sel = LocalPicked.current ?: return ch
    return if (ch.key in sel) ch.copy(name = "✓ " + ch.name) else ch
}

/** Text entry dialog (new folder, rename): Done on the keyboard or the button saves; Back cancels. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(min = 300.dp, max = 480.dp).background(Pal.Surface.copy(alpha = 0.98f), RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { } }.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Label(title, 20)
            Box(Modifier.focusRequester(fr)) { SettingField("Name", text) { text = it } }
            PillButton(confirm, { if (text.isNotBlank()) onDone(text) }, primary = true, size = 15)
        }
    }
}

/**
 * Pick the My Channels folders for one or more channels. Selecting a folder adds them (or, when all are in it already,
 * takes them out) at once; several folders can be ticked. "New folder…" makes one and adds the channels to it.
 */
@Composable
fun FolderPicker(repo: Repo, chans: List<Item>, onClose: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    val keys = remember(chans) { chans.map { it.key }.toSet() }
    if (naming) {
        NameDialog("New folder", "", "Create and add", { n ->
            repo.createFolder(n)?.let { repo.addToFolder(it, chans) }
            naming = false
        }) { naming = false }
        return
    }
    val f = repo.folders
    val mem = f.membership(keys)
    val anyFav = chans.any { repo.isFav(it) }
    val title = if (chans.size == 1) "My Channels: ${chans[0].name}" else "My Channels: ${chans.size} channels"
    ChoiceDialog(title, buildList {
        f.names.forEach { n ->
            val m = mem[n] ?: FolderSet.Member.NONE
            val box = when (m) { FolderSet.Member.ALL -> "☑"; FolderSet.Member.SOME -> "◪"; else -> "☐" }
            add("$box  $n  (${f.items(n).size})" to {
                if (m == FolderSet.Member.ALL) repo.removeFromFolder(n, chans) else repo.addToFolder(n, chans)
            })
        }
        add("＋  New folder…" to { naming = true })
        if (f.names.isEmpty()) add("＋  Add to \"${FolderSet.DEFAULT}\"" to { repo.addToFolder(FolderSet.DEFAULT, chans) })
        if (anyFav) add("★  Remove from all folders" to { repo.removeFromFavorites(chans); onClose() })
    }, focusKey = "picker", onClose = onClose)
}

/**
 * The channel menu used in Live TV and in the player: favourites (folders), rename, folder order, plus [extra] entries.
 * [folder] = the My Channels folder the channel was opened from (adds move / remove there).
 */
@Composable
fun ChannelMenu(repo: Repo, ch: Item, folder: String?, extra: List<Pair<String, () -> Unit>>, onSelect: (() -> Unit)?, onClose: () -> Unit) {
    var page by remember(ch.key) { mutableStateOf("main") }
    when (page) {
        "folders" -> { FolderPicker(repo, listOf(ch)) { onClose() }; return }
        "rename" -> {
            NameDialog("Rename channel", ch.name, "Save name", { repo.renameChannel(ch, it); onClose() }) { page = "main" }
            return
        }
    }
    val fav = repo.isFav(ch)
    val inFolders = repo.folders.foldersOf(ch.key)
    ChoiceDialog(ch.name, buildList {
        if (fav) {
            add("★  Remove from favorites" to { repo.removeFromFavorites(listOf(ch)); onClose() })
            add("📁  Folders… (${inFolders.joinToString(", ")})" to { page = "folders" })
        } else add("☆  Add to favorites…" to { page = "folders" })
        if (folder != null && ch.key in repo.folders.items(folder).map { it.key }) {
            add("▲  Move earlier in \"$folder\"" to { repo.moveInFolder(folder, ch, -1) })
            add("▼  Move later in \"$folder\"" to { repo.moveInFolder(folder, ch, 1) })
            add("✕  Remove from \"$folder\"" to { repo.removeFromFolder(folder, listOf(ch)); onClose() })
        }
        add("✎  Rename channel" to { page = "rename" })
        if (repo.isRenamed(ch)) add("↺  Reset name (${repo.portalName(ch)})" to {
            repo.renameChannel(ch, null); onClose()
        })
        if (onSelect != null) add("☑  Select several channels" to { onSelect(); onClose() })
        addAll(extra)
    }, focusKey = ch.key + page, onClose = onClose)
}

/** Folder actions: rename, move earlier / later, delete (asks first). */
@Composable
fun FolderMenu(repo: Repo, name: String, onRenamed: (String) -> Unit, onClose: () -> Unit) {
    var page by remember(name) { mutableStateOf("main") }
    if (page == "rename") {
        NameDialog("Rename folder", name, "Save name", { n -> repo.renameFolder(name, n); onRenamed(FolderSet.clean(n)); onClose() }) { page = "main" }
        return
    }
    val n = repo.folders.items(name).size
    val opts = if (page == "delete") listOf(
        "Yes, delete \"$name\"" to { repo.deleteFolder(name); onClose() },
        "No, keep it" to { page = "main" }
    ) else listOf(
        "✎  Rename folder" to { page = "rename" },
        "◀  Move earlier" to { repo.moveFolder(name, -1) },
        "▶  Move later" to { repo.moveFolder(name, 1) },
        "🗑  Delete folder ($n channels)" to { page = "delete" }
    )
    ChoiceDialog(if (page == "delete") "Delete folder? The channels stay in Live TV." else "Folder: $name", opts, focusKey = name + page, onClose = onClose)
}

/**
 * Live TV's first section: the folder chips (Menu / long-press = folder actions, last chip = new folder) and the
 * chosen folder's channels in one row. [play] steps through that folder in the player.
 */
@Composable
fun MyChannelsSection(repo: Repo, play: (List<Item>, Int) -> Unit, chMenu: (Item, String) -> Unit, modifier: Modifier = Modifier) {
    val f = repo.folders
    var cur by remember { mutableStateOf(repo.store.sort("live_folder", "")) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    val names = f.names
    val shown = names.find { it == cur } ?: names.firstOrNull()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item { Label("My Channels", 16, Pal.Gold) }
            items(names, key = { it }) { n ->
                PillButton("$n  ${f.items(n).size}", { cur = n; repo.store.saveSort("live_folder", n) }, selected = n == shown, size = 13,
                    onMenu = { folderMenu = n })
            }
            item { PillButton("＋ Folder", { naming = true }, size = 13) }
        }
        if (names.isEmpty()) Label("Add channels to your own folders: Menu (or long-press) on a channel > Add to favorites.", 13, Pal.Dim, maxLines = 2)
        else if (shown != null) {
            val list = f.items(shown)
            if (list.isEmpty()) Label("\"$shown\" is empty. Menu on a channel > Add to favorites, or Select several channels.", 13, Pal.Dim, maxLines = 2)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(list, key = { _, c -> c.key }) { i, ch ->
                    ChannelCard(pickShown(ch), true, { play(list, i) }, { chMenu(ch, shown) })
                }
            }
        }
    }
    folderMenu?.let { n -> FolderMenu(repo, n, { cur = it; repo.store.saveSort("live_folder", it) }) { folderMenu = null } }
    if (naming) NameDialog("New folder", "", "Create folder", { n ->
        repo.createFolder(n)?.let { cur = it; repo.store.saveSort("live_folder", it) }; naming = false
    }) { naming = false }
}
