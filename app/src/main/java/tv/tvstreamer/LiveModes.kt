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
    data class Row(val ch: Item, val on: Boolean, val type: String = "", val sub: String = "", val region: String = "")

    /** What the list shows: only active channels (default), only hidden ones, or all (hidden ones dimmed). */
    enum class View(val label: String) {
        ACTIVE("Only active"), HIDDEN("Only hidden"), ALL("All channels");
        fun next(): View = values()[(ordinal + 1) % values().size]
        fun shows(on: Boolean): Boolean = when (this) { ACTIVE -> on; HIDDEN -> !on; ALL -> true }
        val showsHidden get() = this != ACTIVE
    }

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
             langs: Set<String>?, info: Map<String, ChInfo>): List<Row> =
        rows(chans, mode, folders, hidden, if (browseAll) View.ALL else View.ACTIVE, langs, info, emptyMap())

    /**
     * Rows for a [view] of the selected languages, each with its type, sub-category and (Sports) region. A channel is
     * on when it is enabled in the mode and none of its groups (type, sub-category, region) is hidden.
     */
    fun rows(chans: List<Item>, mode: String, folders: FolderSet, hidden: Set<String>, view: View,
             langs: Set<String>?, info: Map<String, ChInfo>, catTitle: Map<String, String>): List<Row> {
        val inMode = if (mode != DEFAULT && mode in folders.folders) folders.items(mode).map { it.key }.toSet() else null
        val localOf = HashMap<String, Boolean>()
        return chans.asSequence().filter { langOk(it, langs, info) }.map { c ->
            val title = catTitle[c.cat].orEmpty()
            val local = localOf.getOrPut(c.cat) { LiveKind.isLocalNews(title) }
            val type = if (local) LiveKind.NEWS else info[c.key]?.type ?: LiveKind.OTHER
            val sub = Taxonomy.sub(type, title, c.name)
            val region = if (type == LiveKind.SPORTS) Taxonomy.region(title, c.name) else ""
            val chOn = if (inMode != null) c.key in inMode else "ch:${c.key}" !in hidden
            Row(c, chOn && !Taxonomy.groupHidden(type, sub, region, hidden), type, sub, region)
        }.filter { view.shows(it.on) }.toList()
    }

    /** A left-nav tree node: a type, or one of its sub-categories / regions, with its channel count. */
    data class Node(val key: String, val type: String, val label: String, val count: Int, val hidden: Boolean,
                    val children: List<Node> = emptyList(), val region: Boolean = false)

    /**
     * Left-nav tree: types in [LiveKind.ORDER] with their sub-categories (rule order, "Other" last) and, for Sports, the
     * regions. Hidden groups are only listed when the view shows hidden channels.
     */
    fun tree(rows: List<Row>, hidden: Set<String>, view: View): List<Node> {
        val rank = LiveKind.ORDER.withIndex().associate { it.value to it.index }
        return rows.filter { it.type.isNotEmpty() }.groupBy { it.type }.entries.sortedBy { rank[it.key] ?: 99 }.mapNotNull { (type, rs) ->
            val tHidden = Taxonomy.typeKey(type) in hidden
            if (tHidden && !view.showsHidden) return@mapNotNull null
            val order = Taxonomy.RULES[type].orEmpty().map { it.first }.withIndex().associate { it.value to it.index }
            val subs = rs.groupBy { it.sub }.entries.sortedBy { order[it.key] ?: 999 }.mapNotNull { (sub, l) ->
                val h = Taxonomy.subKey(type, sub) in hidden
                if (h && !view.showsHidden) null else Node(Taxonomy.subKey(type, sub), type, sub, l.size, h || tHidden)
            }
            val regions = if (type != LiveKind.SPORTS) emptyList() else rs.filter { it.region.isNotEmpty() }.groupBy { it.region }.entries
                .sortedByDescending { it.value.size }.mapNotNull { (r, l) ->
                    val h = Taxonomy.regionKey(type, r) in hidden
                    if (h && !view.showsHidden) null else Node(Taxonomy.regionKey(type, r), type, r, l.size, h || tHidden, region = true)
                }
            Node(Taxonomy.typeKey(type), type, type, rs.size, tHidden, if (subs.size > 1 || regions.isNotEmpty()) subs + regions else emptyList())
        }
    }

    /** A sub-category larger than this is split into sections by portal category. */
    const val SPLIT_AT = 60

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
        if (rows.isNotEmpty() && rows.all { it.sub.isNotEmpty() }) return subSections(rows, catTitle, catPos, modePos)
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

    private fun subSections(rows: List<Row>, catTitle: Map<String, String>, catPos: Map<String, Int>, modePos: Map<String, Int>?): List<Section> {
        val typeRank = LiveKind.ORDER.withIndex().associate { it.value to it.index }
        fun sorted(l: List<Row>) = if (modePos != null) l.sortedBy { modePos[it.ch.key] ?: Int.MAX_VALUE }
            else l.sortedWith(compareBy({ if (it.ch.num > 0) it.ch.num else Int.MAX_VALUE }, { it.ch.name.lowercase() }))
        val out = ArrayList<Section>()
        for ((type, rs) in rows.groupBy { it.type }.entries.sortedBy { typeRank[it.key] ?: 99 }) {
            val order = Taxonomy.RULES[type].orEmpty().map { it.first }.withIndex().associate { it.value to it.index }
            for ((sub, l) in rs.groupBy { it.sub }.entries.sortedBy { order[it.key] ?: 999 }) {
                val local = sub == "Local News"
                if (l.size <= SPLIT_AT) { out += Section(type, sub, sorted(l), local); continue }
                // Large: one section per region (Sports) or portal category (same label merged), in portal order
                l.groupBy { r -> r.region.ifEmpty { catTitle[r.ch.cat].orEmpty().let { if (it.isBlank()) "" else LiveKind.sectionLabel(it) } } }.entries
                    .sortedBy { e -> e.value.minOf { catPos[it.ch.cat] ?: Int.MAX_VALUE } }
                    .forEach { (lab, part) -> out += Section(type, if (lab.isEmpty() || lab == sub) sub else "$sub · $lab", sorted(part), local) }
            }
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
