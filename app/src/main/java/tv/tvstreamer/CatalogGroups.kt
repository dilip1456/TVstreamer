package tv.tvstreamer

/**
 * Catalog-wide group visibility for Movies, Series and Live TV, driven by the same modes as Live TV
 * (Default + named [FolderSet] modes, synced as custom_cats / hidden).
 *
 * Default: a group is on unless its key is in [hidden] (`vod:<id>`, `grp:Type`, `grp:Type/Sub`, `grp:Type/@Region`).
 * Custom mode:
 *  - empty mode (just created, "everything hidden"): every group is off
 *  - legacy mode with only channels, no keys of this family: fall back to the Default hidden set (Movies/Series
 *    stay as they were until the user edits groups in this mode)
 *  - mode with keys of this family: whitelist — the group is on only when its key is in the folder
 *
 * Group stubs stored in a mode are [Item]s whose key is the group key (kind `vod` or `grp`), so they sync with
 * the existing folder JSON and are ignored as channels / favourites (`kind == "itv"` only).
 */
object CatalogGroups {
    const val VOD = "vod:"
    const val GRP = Taxonomy.PREFIX // "grp:"

    data class Group(
        val key: String,
        val title: String,
        val section: String,
        val count: Int,
        val parent: String? = null,
        val depth: Int = 0
    )

    fun family(key: String): String = when {
        key.startsWith(VOD) -> VOD
        key.startsWith(GRP) -> GRP
        else -> key.substringBefore(':', "") + ":"
    }

    fun stub(key: String, title: String): Item {
        val kind = key.substringBefore(':', "grp")
        val id = key.substringAfter(':', key)
        return Item(kind, id, title, "", "", 0, false)
    }

    fun isGroupItem(i: Item): Boolean = i.kind != "itv"

    /** Whether [key] is shown in [mode]. */
    fun groupOn(key: String, mode: String, folders: FolderSet, hidden: Set<String>): Boolean {
        if (mode == LiveModes.DEFAULT || mode !in folders.folders) return key !in hidden
        val items = folders.items(mode).map { it.key }
        if (items.isEmpty()) return false
        val fam = family(key)
        val on = items.filter { it.startsWith(fam) }.toSet()
        if (on.isEmpty()) return key !in hidden
        return key in on
    }

    /**
     * Live channel: hidden by its type / sub / region, using [groupOn] so a custom mode's group
     * whitelist applies the same way as Default's hidden set.
     */
    fun liveChannelOn(type: String, sub: String, region: String, mode: String, folders: FolderSet, hidden: Set<String>): Boolean {
        if (type.isEmpty()) return true
        if (!groupOn(Taxonomy.typeKey(type), mode, folders, hidden)) return false
        val items = if (mode != LiveModes.DEFAULT && mode in folders.folders) folders.items(mode).map { it.key } else emptyList()
        val grpOn = items.filter { it.startsWith(GRP) }.toSet()
        val managed = mode != LiveModes.DEFAULT && mode in folders.folders && (items.isEmpty() || grpOn.isNotEmpty())
        if (sub.isNotEmpty()) {
            val subKey = Taxonomy.subKey(type, sub)
            if (managed) {
                val subManaged = grpOn.any { it.startsWith("$GRP$type/") && !it.contains("/@") }
                if (subManaged && !groupOn(subKey, mode, folders, hidden)) return false
            } else if (subKey in hidden) return false
        }
        if (region.isNotEmpty()) {
            val rKey = Taxonomy.regionKey(type, region)
            if (managed) {
                val regManaged = grpOn.any { it.startsWith("$GRP$type/@") }
                if (regManaged && !groupOn(rKey, mode, folders, hidden)) return false
            } else if (rKey in hidden) return false
        }
        return true
    }

    /**
     * Toggle [key] in [mode]. [seed] is used once, when a non-empty legacy mode first gains groups of this
     * family (so one hide does not wipe the rest of Movies / Series). Empty modes are not seeded — the user
     * is picking what to show, one group at a time.
     */
    fun toggle(key: String, title: String, mode: String, folders: FolderSet, hidden: Set<String>,
               seed: List<Item> = emptyList()): Pair<FolderSet, Set<String>> {
        if (mode == LiveModes.DEFAULT || mode !in folders.folders) {
            return folders to LiveModes.toggleGroup(key, hidden)
        }
        var f = folders
        val items = f.items(mode).map { it.key }
        val fam = family(key)
        val managed = items.any { it.startsWith(fam) }
        if (!managed && items.isNotEmpty() && seed.isNotEmpty()) {
            f = f.add(mode, seed)
        }
        val on = f.items(mode).any { it.key == key }
        f = if (on) f.remove(mode, setOf(key)) else {
            val extra = if (key.startsWith(GRP) && '/' in key.substringAfter(GRP)) {
                val type = key.removePrefix(GRP).substringBefore('/')
                listOf(stub(Taxonomy.typeKey(type), type))
            } else emptyList()
            f.add(mode, extra + stub(key, title))
        }
        return f to hidden
    }

    fun setOn(keys: List<Pair<String, String>>, on: Boolean, mode: String, folders: FolderSet, hidden: Set<String>,
              seed: List<Item> = emptyList()): Pair<FolderSet, Set<String>> {
        var f = folders
        var h = hidden
        for ((key, title) in keys) {
            if (groupOn(key, mode, f, h) == on) continue
            val r = toggle(key, title, mode, f, h, seed)
            f = r.first; h = r.second
        }
        return f to h
    }

    /** Movie and series portal categories as list groups (Movies first, then Series). */
    fun vodGroups(cats: List<Cat>, series: (Cat) -> Boolean, count: (Cat) -> Int): List<Group> {
        val movies = cats.filter { !series(it) }
        val shows = cats.filter { series(it) }
        return movies.map { Group("vod:${it.id}", GroupTitle.display(it.title, "Movies"), "Movies", count(it)) } +
            shows.map { Group("vod:${it.id}", GroupTitle.display(it.title, "Series"), "Series", count(it)) }
    }

    /** Live type / sub / region groups from [LiveModes.tree], in tree order. */
    fun liveGroups(tree: List<LiveModes.Node>): List<Group> {
        val out = ArrayList<Group>()
        for (n in tree) {
            out += Group(n.key, GroupTitle.display(n.label, "Live TV"), "Live TV", n.count, depth = 0)
            for (c in n.children) {
                out += Group(c.key, GroupTitle.display(c.label, n.label), "Live TV", c.count, parent = n.key, depth = 1)
                for (g in c.children) {
                    out += Group(g.key, GroupTitle.display(g.label, c.label), "Live TV", g.count, parent = c.key, depth = 2)
                }
            }
        }
        return out
    }
}
