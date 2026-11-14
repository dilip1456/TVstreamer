package tv.tvstreamer

/**
 * What the hero banner rotates through, and how it steps. Plain functions (unit-tested).
 * The slides are new / recently added movies and series — never Continue watching / last played.
 */
object HeroOrder {
    /** Seconds between slides, and how long a key press in the banner pauses the rotation. */
    const val ROTATE_MS = 7_000L
    const val PAUSE_MS = 12_000L
    const val MAX = 8

    /**
     * Newest / recently added titles for the hero. Items with a portal added date lead (newest first);
     * those without a date come last. [excludeKeys] (watch history / continue watching) are skipped.
     * Each [key] once, at most [max].
     */
    fun <T> newest(
        items: List<T>,
        addedMs: (T) -> Long,
        key: (T) -> String,
        excludeKeys: Set<String> = emptySet(),
        ok: (T) -> Boolean = { true },
        max: Int = MAX
    ): List<T> {
        val seen = HashSet<String>()
        return items.asSequence()
            .filter { ok(it) && key(it) !in excludeKeys }
            .sortedWith(compareBy<T> { if (addedMs(it) > 0) 0 else 1 }.thenByDescending { addedMs(it) })
            .filter { seen.add(key(it)) }
            .take(max)
            .toList()
    }

    /** One key per title: episodes of a series collapse to the show. */
    fun showKey(i: Item): String =
        if (i.isEpisode() || i.isEpisodeLike()) "s:" + (i.pid.ifEmpty { i.parsed.title.lowercase() }) else i.key

    /** History + continue-watching keys to keep off the hero (item key, series key, parent id). */
    fun historyKeys(history: List<Item>): Set<String> = buildSet {
        for (i in history) {
            add(i.key)
            add(showKey(i))
            if (i.pid.isNotEmpty()) {
                add("vod:${i.pid}")
                add("s:${i.pid}")
            }
        }
    }

    /** The next slide, wrapping around. */
    fun next(index: Int, size: Int): Int = if (size <= 0) 0 else (index + 1).mod(size)

    /** Rotate now? Only with more than one slide, and not within [PAUSE_MS] of the last key press in the banner. */
    fun shouldRotate(size: Int, now: Long, lastInteraction: Long): Boolean = size > 1 && now - lastInteraction >= PAUSE_MS
}
