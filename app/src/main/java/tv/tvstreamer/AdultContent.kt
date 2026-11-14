package tv.tvstreamer

/**
 * Whether locked / adult portal titles and channels appear in lists.
 * Default mode shows them unless the user turns this off; playing a locked item still uses [Repo.gate].
 */
object AdultContent {
    /** True when a locked item or category may be listed. [showAdult] defaults to on. */
    fun listOk(locked: Boolean, showAdult: Boolean): Boolean = !locked || showAdult
}

/**
 * Every portal live category must appear as a section: channels whose category id is missing from
 * the portal's category list still get a stub category so they are not dropped.
 */
object LiveCatalog {
    fun withMissingCats(cats: List<Cat>, channels: List<Item>): List<Cat> {
        val have = cats.mapTo(HashSet()) { it.id }
        val extra = LinkedHashSet<String>()
        for (ch in channels) if (ch.cat.isNotEmpty() && ch.cat !in have) extra += ch.cat
        if (extra.isEmpty()) return cats
        return cats + extra.map { id ->
            Cat(id, id, channels.any { it.cat == id && it.locked })
        }
    }
}
