package tv.tvstreamer

/** One sticky-header section of the Live TV channel grid. */
data class LiveSection(val title: String, val items: List<Item>)

/**
 * Live TV browsing model (pure, unit tested): the groups shown in the rail / chips (My Channels folders, Recently
 * watched, then by type), the language and country filters, and the sections a group's channels are shown in.
 */
object LiveGroups {
    val ICONS = mapOf(
        LiveKind.NEWS to "📰", LiveKind.SPORTS to "⚽", LiveKind.ENT to "🎭", LiveKind.MOVIES to "🎬", LiveKind.MUSIC to "🎵",
        LiveKind.KIDS to "🧸", LiveKind.DOCS to "🌍", LiveKind.RELIGIOUS to "🕊", LiveKind.REGIONAL to "📍", LiveKind.OTHER to "📺"
    )
    fun icon(type: String) = ICONS[type] ?: "📺"

    /** Group keys: "f:<folder>", "recent", "t:<type>", "orig" (the portal's categories). */
    fun folderKey(name: String) = "f:$name"
    fun typeKey(type: String) = "t:$type"
    const val RECENT = "recent"
    const val ORIG = "orig"

    /** Country tag of a channel: "UK" from "UK: BBC One", else from its category ("|UK| NEWS"), else "". */
    fun countryOf(name: String, catTitle: String): String {
        fun tag(s: String) = Regex("^([A-Z]{2,4}): ").find(s)?.groupValues?.get(1).orEmpty()
        return tag(name).ifEmpty { tag(ChannelTitle.clean(catTitle)) }
    }

    /** Channels of [type] (by the sorted channel info). */
    fun ofType(pool: List<Item>, info: Map<String, ChInfo>, type: String): List<Item> =
        pool.filter { (info[it.key]?.type ?: LiveKind.OTHER) == type }

    /** Count per type, in display order, only the types that have channels. */
    fun typeCounts(pool: List<Item>, info: Map<String, ChInfo>): List<Pair<String, Int>> {
        val c = pool.groupingBy { info[it.key]?.type ?: LiveKind.OTHER }.eachCount()
        return LiveKind.ORDER.filter { (c[it] ?: 0) > 0 }.map { it to c.getValue(it) }
    }

    /** Filter chips: values with counts, most channels first (only shown when there is a real choice). */
    fun langCounts(items: List<Item>, info: Map<String, ChInfo>): List<Pair<String, Int>> =
        items.groupingBy { info[it.key]?.lang ?: Tags.OTHER }.eachCount().toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    fun countryCounts(items: List<Item>, catTitle: Map<String, String>): List<Pair<String, Int>> =
        items.mapNotNull { countryOf(it.name, catTitle[it.cat].orEmpty()).ifEmpty { null } }.groupingBy { it }.eachCount()
            .toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    fun filter(items: List<Item>, info: Map<String, ChInfo>, catTitle: Map<String, String>, lang: String, country: String): List<Item> =
        items.filter { (lang.isEmpty() || (info[it.key]?.lang ?: Tags.OTHER) == lang) && (country.isEmpty() || countryOf(it.name, catTitle[it.cat].orEmpty()) == country) }

    /** Letter used for A-Z jumping ("#" for digits / symbols), ignoring a country tag. */
    fun letterOf(name: String): String {
        val c = name.substringAfter(": ", name).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return "#"
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    /**
     * Sections for a group: A-Z -> one section per letter (sorted by name); otherwise by language (most channels first)
     * when [byLanguage] and there is more than one, else one untitled section in the given order.
     */
    fun sections(items: List<Item>, info: Map<String, ChInfo>, sortAz: Boolean, byLanguage: Boolean): List<LiveSection> {
        if (items.isEmpty()) return emptyList()
        if (sortAz) {
            val sorted = items.sortedWith(compareBy<Item>({ letterOf(it.name).let { l -> if (l == "#") "~" else l } }, { it.name.substringAfter(": ", it.name).lowercase() }))
            return sorted.groupBy { letterOf(it.name) }.map { (l, v) -> LiveSection(l, v) }
        }
        if (byLanguage) {
            val g = items.groupBy { info[it.key]?.lang ?: Tags.OTHER }
            if (g.size > 1) return g.entries.sortedWith(compareByDescending<Map.Entry<String, List<Item>>> { it.value.size }.thenBy { it.key })
                .map { LiveSection(it.key, it.value) }
        }
        return listOf(LiveSection("", items))
    }

    /** Sections cut into grid rows of [cols] for a LazyColumn with sticky headers. */
    fun rows(items: List<Item>, cols: Int): List<List<Item>> = items.chunked(cols.coerceAtLeast(1))
}
