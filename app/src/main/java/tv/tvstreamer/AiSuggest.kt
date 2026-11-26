package tv.tvstreamer

/** Pure helpers for the "Suggested for you" row (1.97): what is left to review, and what was suggested before. */
object AiSuggest {
    const val REFRESH = "Refresh Suggestions"
    const val WATCHED = "Already Watched"

    /** Suggestions still to review: not disliked, not in [skipKeys] (watchlists) and not in [seenKeys] (watched). */
    fun remaining(items: List<Item>, feedback: AiFeedbackState, skipKeys: Set<String>, seenKeys: Set<String>): List<Item> =
        items.distinctBy { it.key }.filter { it.key !in skipKeys && it.key !in seenKeys && !AiFeedback.isDisliked(feedback, it) }

    /** Everything that was suggested is reviewed (watched, added to a list or disliked). */
    fun allReviewed(had: Boolean, remaining: List<Item>): Boolean = had && remaining.isEmpty()

    /** Adds [items] to the shown history (newest last, at most [max] each). */
    fun remember(keys: List<String>, titles: List<String>, items: List<Item>, titleOf: (Item) -> String, max: Int): Pair<List<String>, List<String>> {
        val k = (keys.filter { key -> items.none { it.key == key } } + items.map { it.key }).distinct().takeLast(max)
        val newTitles = items.map(titleOf).filter { it.isNotBlank() }
        val t = (titles.filter { it !in newTitles } + newTitles).distinct().takeLast(max)
        return k to t
    }
}

/** Per-category Refresh pacing (1.97): one portal page per category at most every [GAP_MS]. */
object CatRefresh {
    const val GAP_MS = 10 * 60_000L
    fun wait(last: Long, now: Long): Long = if (last <= 0L) 0L else (last + GAP_MS - now).coerceAtLeast(0L)
}
