package tv.tvstreamer

/**
 * Infinite-scroll helpers (v1.52 fix for "Movies shows ~15 titles, then jumps back and repeats").
 *
 * Root cause: the poster grids keyed every card by "index|key" and re-sorted / re-grouped the whole list when a
 * page arrived. A new page (or a new version merged into an existing title) shifted indexes, so every card's key
 * changed: Compose disposed the focused card, focus fell back to the start of the grid and the grid scrolled up,
 * where the same first page re-triggered loading — a loop over the same titles. Portals that answer a page they
 * do not have with page 1 again made it worse (nothing new, but the request kept repeating).
 *
 * Fix: cards are keyed by their own key only, deduped, and appended in a stable order (titles already on screen
 * keep their place; new ones go after them); a page that brings nothing new ends paging for that category.
 */
object Paging {
    /**
     * [next] in a stable order: what is in [prev] (keys already on screen) keeps its position, new entries follow in
     * [next]'s order. Duplicate keys are dropped. Entries no longer present are removed.
     */
    fun <T> stable(prev: List<String>, next: List<T>, key: (T) -> String): List<T> {
        val byKey = LinkedHashMap<String, T>()
        for (x in next) byKey.putIfAbsent(key(x), x)
        val out = ArrayList<T>(byKey.size)
        val used = HashSet<String>()
        for (k in prev) { val x = byKey[k] ?: continue; if (used.add(k)) out += x }
        for ((k, x) in byKey) if (used.add(k)) out += x
        return out
    }

    /** [existing] plus the new entries of [page] (by key), in order. */
    fun <T> append(existing: List<T>, page: List<T>, key: (T) -> String): List<T> {
        val known = existing.mapTo(HashSet(), key)
        return existing + page.filter { known.add(key(it)) }
    }

    /** Should the next page be asked for? Near the end, more exists, not busy, and not already asked at this size. */
    fun shouldLoad(lastVisible: Int, total: Int, more: Boolean, busy: Boolean, askedAt: Int, ahead: Int = 6): Boolean =
        more && !busy && total > 0 && lastVisible >= total - ahead && askedAt != total

    /** A page that added nothing new (portal repeated a page): stop after [limit] of them in a row. */
    fun endAfterEmpty(emptyInRow: Int, limit: Int = 2): Boolean = emptyInRow >= limit
}
