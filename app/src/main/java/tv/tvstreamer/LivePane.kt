package tv.tvstreamer

/**
 * Two-pane Live TV (v1.99.6): categories on the left, that category's channels on the right.
 * Pure and unit tested. Favourites, Recent, All and Search are pinned at the top; types expand
 * into groups. Live TV opens on Favourites, or All when Favourites is empty; the last category
 * is not remembered across sessions.
 */
object LivePane {
    const val SEARCH = "search"
    const val FAVOURITES = "favourites"
    const val RECENT = "recent"
    const val ALL = "all"

    const val LABEL_SEARCH = "Search channels"
    const val LABEL_FAVOURITES = "Favourites"
    const val LABEL_RECENT = "Recent"
    const val LABEL_ALL = "All"

    /** Rows to skip when paging with Rewind / Fast forward / Channel +/−. */
    const val PAGE = 8

    data class Cat(
        val key: String,
        val label: String,
        val count: Int,
        val type: String = "",
        val parent: String? = null,
        val depth: Int = 0,
        val expandable: Boolean = false,
    )

    fun pins(favCount: Int, recentCount: Int, allCount: Int): List<Cat> = listOf(
        Cat(SEARCH, LABEL_SEARCH, 0),
        Cat(FAVOURITES, LABEL_FAVOURITES, favCount),
        Cat(RECENT, LABEL_RECENT, recentCount),
        Cat(ALL, LABEL_ALL, allCount),
    )

    /** Left-pane rows: pins, then types, with children of [expanded] types inserted. */
    fun visible(pins: List<Cat>, tree: List<LiveModes.Node>, expanded: Set<String>): List<Cat> {
        val out = ArrayList<Cat>(pins.size + tree.size * 2)
        out += pins
        for (n in tree) {
            out += Cat(n.key, n.label, n.count, type = n.type, expandable = n.children.isNotEmpty())
            if (n.key in expanded) for (c in n.children) {
                out += Cat(c.key, c.label, c.count, type = c.type, parent = n.key, depth = 1)
            }
        }
        return out
    }

    fun toggleExpand(expanded: Set<String>, key: String): Set<String> =
        if (key in expanded) expanded - key else expanded + key

    /** Keep a selected child visible by expanding its parent type. */
    fun expandedFor(selected: String, tree: List<LiveModes.Node>, expanded: Set<String>): Set<String> {
        val parent = tree.firstOrNull { n -> n.children.any { it.key == selected } } ?: return expanded
        return expanded + parent.key
    }

    fun initial(favCount: Int): String = if (favCount > 0) FAVOURITES else ALL

    /**
     * If Favourites became empty (or the selected group disappeared), fall back to All.
     * Search is left as Search. Does not restore a remembered category.
     */
    fun settle(selected: String, visible: List<Cat>, favCount: Int): String {
        if (selected == SEARCH) return SEARCH
        if (selected == FAVOURITES && favCount == 0) return ALL
        if (visible.any { it.key == selected }) return selected
        return initial(favCount)
    }

    fun isPin(key: String): Boolean =
        key == SEARCH || key == FAVOURITES || key == RECENT || key == ALL

    /** Phone chips: pins plus types, with the selected type's groups expanded underneath. */
    fun chips(pins: List<Cat>, tree: List<LiveModes.Node>, selected: String): List<Cat> {
        val open = tree.firstOrNull { it.key == selected || it.children.any { c -> c.key == selected } }
            ?.takeIf { it.children.isNotEmpty() }
            ?.key
        return visible(pins, tree, if (open != null) setOf(open) else emptySet())
    }

    fun titleOf(key: String, cats: List<Cat>): String {
        val c = cats.firstOrNull { it.key == key } ?: return LABEL_ALL
        val p = c.parent?.let { id -> cats.firstOrNull { it.key == id } }
        return if (p != null) "${p.label} · ${c.label}" else c.label
    }

    /**
     * Channels for [key]. Favourites and Recent keep the given order; All and type / group
     * keys use [allRows] order. Search has no channels.
     */
    fun rowsOf(
        key: String,
        allRows: List<LiveModes.Row>,
        favKeys: List<String>,
        recentKeys: List<String>,
        tree: List<LiveModes.Node>,
    ): List<LiveModes.Row> {
        val byKey = LinkedHashMap<String, LiveModes.Row>(allRows.size * 2)
        for (r in allRows) if (r.ch.key !in byKey) byKey[r.ch.key] = r
        return when (key) {
            SEARCH -> emptyList()
            ALL -> allRows
            FAVOURITES -> favKeys.mapNotNull { byKey[it] }
            RECENT -> recentKeys.mapNotNull { byKey[it] }
            else -> {
                val node = findNode(tree, key) ?: return emptyList()
                allRows.filter { rowMatches(it, node) }
            }
        }
    }

    fun findNode(tree: List<LiveModes.Node>, key: String): LiveModes.Node? {
        for (n in tree) {
            if (n.key == key) return n
            n.children.firstOrNull { it.key == key }?.let { return it }
        }
        return null
    }

    fun rowMatches(row: LiveModes.Row, node: LiveModes.Node): Boolean {
        if (row.type != node.type) return false
        if (node.region) return row.region == node.label
        if (node.key == Taxonomy.typeKey(node.type)) return true
        return row.sub == node.label || row.sub.startsWith(node.label + " · ") ||
            row.region == node.label
    }

    fun pageIndex(index: Int, down: Boolean, size: Int, page: Int = PAGE): Int {
        if (size <= 0) return 0
        val i = index.coerceIn(0, size - 1)
        return (if (down) i + page else i - page).coerceIn(0, size - 1)
    }
}
