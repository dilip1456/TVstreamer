package tv.tvstreamer

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/**
 * Watchlist rows for a title's menu: Add to Watchlist (the default list) or Remove from Watchlist, then
 * Add to Other List… (picker). Episodes have no watchlist rows.
 */
fun watchlistOptions(repo: Repo, item: Item, ctx: android.content.Context, close: () -> Unit, openPicker: () -> Unit): List<Pair<String, () -> Unit>> {
    if (item.kind != "vod" || item.isEpisode()) return emptyList()
    val def = repo.defaultList()
    return buildList {
        if (repo.isInList(def.id, item)) add(WatchLists.REMOVE to { repo.removeFromList(def.id, item); close(); toast(ctx, "Removed from ${def.name}") })
        else add(WatchLists.ADD to { repo.addToList(def.id, item); close(); toast(ctx, "Added to ${def.name}") })
        add(WatchLists.OTHER to { openPicker() })
    }
}

/**
 * Watchlist picker (a title: add to / remove from any list, make a new one, choose the default) and, with no
 * [item], the manager from Settings (rename, delete, set as default, new list). Back steps out one page.
 */
@Composable
fun WatchlistPicker(repo: Repo, item: Item?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf("main") }
    var target by remember { mutableStateOf("") }
    val lists = repo.watchLists
    val defId = repo.defaultList().id
    fun nameOf(id: String) = repo.watchLists.firstOrNull { it.id == id }?.name ?: WatchLists.MY_NAME
    when (page) {
        "new" -> {
            NameDialog("New Watchlist", "", if (item != null) "Create and Add" else "Create", { n ->
                val id = repo.createList(n)
                if (id != null && item != null) { repo.addToList(id, item); toast(ctx, "Added to ${repo.watchLists.firstOrNull { it.id == id }?.name ?: n}"); onClose() }
                else page = "main"
            }) { page = "main" }
            return
        }
        "rename" -> {
            NameDialog("Rename Watchlist", nameOf(target), "Save", { n -> repo.renameList(target, n); page = "list" }) { page = "list" }
            return
        }
        "delete" -> {
            ChoiceDialog("Delete ${nameOf(target)}?", listOf(
                "Delete Watchlist" to { repo.deleteList(target); toast(ctx, "Watchlist deleted"); page = "main" },
                "Cancel" to { page = "list" }
            ), focusKey = "wl-del-$target") { page = "list" }
            return
        }
        "list" -> {
            val l = lists.firstOrNull { it.id == target }
            if (l == null) { page = "main"; return }
            ChoiceDialog(l.name, buildList {
                if (l.id != defId) add(WatchLists.SET_DEFAULT to { repo.setDefaultList(l.id); toast(ctx, "${l.name} is now the default"); page = "main" })
                add("Rename" to { page = "rename" })
                if (l.id != WatchLists.MY) add("Delete" to { page = "delete" })
            }, focusKey = "wl-list-${l.id}") { page = "main" }
            return
        }
        "default" -> {
            ChoiceDialog("Default Watchlist", lists.map { l ->
                ((if (l.id == defId) "✓  " else "") + l.name) to {
                    repo.setDefaultList(l.id); toast(ctx, "${l.name} is now the default")
                    page = "main"
                }
            }, focusKey = "wl-default") { page = "main" }
            return
        }
    }
    if (item != null) {
        ChoiceDialog("Add to a Watchlist", buildList {
            lists.forEach { l ->
                val has = l.items.any { it.key == item.key }
                add(WatchLists.pickerLabel(l, has, l.id == defId) to {
                    if (has) { repo.removeFromList(l.id, item); toast(ctx, "Removed from ${l.name}") }
                    else { repo.addToList(l.id, item); toast(ctx, "Added to ${l.name}") }
                    onClose()
                })
            }
            add(WatchLists.NEW to { page = "new" })
            if (lists.size > 1) add("Change Default Watchlist…" to { page = "default" })
        }, focusKey = "wl-pick-${item.key}", onClose = onClose)
    } else {
        ChoiceDialog("Watchlists", buildList {
            lists.forEach { l ->
                val n = l.items.size
                add((l.name + "  ·  " + (if (n == 1) "1 title" else "$n titles") + (if (l.id == defId) "  ·  Default" else "")) to { target = l.id; page = "list" })
            }
            add(WatchLists.NEW to { page = "new" })
        }, focusKey = "wl-manage", onClose = onClose)
    }
}
