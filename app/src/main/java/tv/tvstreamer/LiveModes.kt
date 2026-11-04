package tv.tvstreamer

/**
 * Live TV modes (pure logic, unit tested). "Default" lists every channel that is not hidden. A custom mode (stored as a
 * named channel set in [FolderSet], so it syncs like the old My Channels folders, per entry with tombstones) lists ONLY
 * its enabled channels. Old folders simply become modes ("Favorites" stays "Favorites").
 *
 * Browse all: every channel, with the ones not enabled in the current mode marked (dimmed + "Hidden").
 * Languages: only channels of the selected languages are listed (null = all languages).
 */
object LiveModes {
    const val DEFAULT = ""

    /** One list row: the channel and whether it is enabled (shown) in the current mode. */
    data class Row(val ch: Item, val on: Boolean)

    /** A list section: its nav type (News, Sports, …), header label ("Local News", "Entertainment") and rows. */
    data class Section(val type: String, val label: String, val rows: List<Row>, val local: Boolean = false) {
        val key get() = "$type/$label"
    }

    fun isOn(ch: Item, mode: String, folders: FolderSet, hidden: Set<String>): Boolean =
        if (mode == DEFAULT || mode !in folders.folders) "ch:${ch.key}" !in hidden
        else folders.items(mode).any { it.key == ch.key }

    /** Channel language passes the selection (unsorted channels pass until their language is known). */
    fun langOk(ch: Item, langs: Set<String>?, info: Map<String, ChInfo>): Boolean {
        if (langs.isNullOrEmpty()) return true
        val l = info[ch.key]?.lang ?: return true
        return l in langs
    }

    /** The rows to list: enabled channels (or all with [browseAll]) of the selected languages. */
    fun rows(chans: List<Item>, mode: String, folders: FolderSet, hidden: Set<String>, browseAll: Boolean,
             langs: Set<String>?, info: Map<String, ChInfo>): List<Row> {
        val inMode = if (mode != DEFAULT && mode in folders.folders) folders.items(mode).map { it.key }.toSet() else null
        return chans.asSequence().filter { langOk(it, langs, info) }.map { c ->
            Row(c, if (inMode != null) c.key in inMode else "ch:${c.key}" !in hidden)
        }.filter { browseAll || it.on }.toList()
    }

    /**
     * Rows grouped by portal type and category only (never by name letters): types in [LiveKind.ORDER], categories in
     * the user's / portal order ([catOrder] ids), channels by number. In a custom mode, channels keep the mode's order.
     */
    fun sections(rows: List<Row>, info: Map<String, ChInfo>, catTitle: Map<String, String>, catOrder: List<String>,
                 modeOrder: List<String>? = null): List<Section> {
        val catPos = catOrder.withIndex().associate { it.value to it.index }
        val modePos = modeOrder?.withIndex()?.associate { it.value to it.index }
        val groups = LinkedHashMap<Pair<String, String>, MutableList<Row>>()
        val localOf = HashMap<String, Boolean>()
        for (r in rows) {
            val title = catTitle[r.ch.cat].orEmpty()
            val local = localOf.getOrPut(r.ch.cat) { LiveKind.isLocalNews(title) }
            val type = if (local) LiveKind.NEWS else info[r.ch.key]?.type ?: LiveKind.OTHER
            groups.getOrPut(type to r.ch.cat) { ArrayList() }.add(r)
        }
        val typeRank = LiveKind.ORDER.withIndex().associate { it.value to it.index }
        val out = ArrayList<Section>()
        // Same label in one type (e.g. two "Sports" categories from different countries) -> one section
        val merged = LinkedHashMap<Pair<String, String>, Triple<MutableList<Row>, Int, Boolean>>()
        for ((k, l) in groups.entries.sortedWith(compareBy({ typeRank[it.key.first] ?: 99 }, { catPos[it.key.second] ?: Int.MAX_VALUE }))) {
            val title = catTitle[k.second].orEmpty()
            val local = localOf[k.second] == true
            val label = if (title.isBlank()) k.first else LiveKind.sectionLabel(title)
            val e = merged.getOrPut(k.first to label) { Triple(ArrayList(), catPos[k.second] ?: Int.MAX_VALUE, local) }
            e.first.addAll(l)
        }
        for ((k, v) in merged) {
            val sorted = if (modePos != null) v.first.sortedBy { modePos[it.ch.key] ?: Int.MAX_VALUE }
            else v.first.sortedWith(compareBy({ if (it.ch.num > 0) it.ch.num else Int.MAX_VALUE }, { it.ch.name.lowercase() }))
            out += Section(k.first, k.second, sorted, v.third)
        }
        return out
    }

    /** Languages present in [chans] with their channel counts, most channels first. */
    fun languages(chans: List<Item>, info: Map<String, ChInfo>): List<Pair<String, Int>> =
        chans.mapNotNull { info[it.key]?.lang }.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ it.key == Tags.OTHER }, { -it.value })).map { it.key to it.value }

    /** New mode: empty ("start with everything hidden") or with [current] (the channels listed now). */
    fun create(folders: FolderSet, name: String, current: List<Item>?): FolderSet {
        val n = FolderSet.clean(name)
        if (n.isEmpty()) return folders
        val f = folders.create(n)
        return if (current.isNullOrEmpty()) f else f.add(n, current)
    }

    /** Long-press: enable / disable [ch] in [mode] (Default: unhide / hide). Returns the new folders and hidden set. */
    fun toggle(ch: Item, mode: String, folders: FolderSet, hidden: Set<String>): Pair<FolderSet, Set<String>> {
        if (mode == DEFAULT || mode !in folders.folders) {
            val k = "ch:${ch.key}"
            return folders to (if (k in hidden) hidden - k else hidden + k)
        }
        val on = folders.items(mode).any { it.key == ch.key }
        return (if (on) folders.remove(mode, setOf(ch.key)) else folders.add(mode, listOf(ch))) to hidden
    }

    /** Move [ch] to the top of a custom mode. */
    fun toTop(ch: Item, mode: String, folders: FolderSet): FolderSet {
        val i = folders.items(mode).indexOfFirst { it.key == ch.key }
        return if (i <= 0) folders else folders.moveItem(mode, ch.key, -i)
    }
}
