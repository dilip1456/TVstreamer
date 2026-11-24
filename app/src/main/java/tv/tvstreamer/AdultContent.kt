package tv.tvstreamer

/**
 * Sensitive / password-controlled content filter.
 *
 * [showAdult] = true  → "sensitive only" mode: lists show ONLY locked titles and channels.
 * [showAdult] = false → normal mode: locked titles and channels are hidden from lists.
 * Playing a locked item still goes through [Repo.gate].
 */
object AdultContent {
    /** Whether an item or category may appear in lists. */
    fun listOk(locked: Boolean, showAdult: Boolean): Boolean =
        if (showAdult) locked else !locked
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
