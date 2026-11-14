package tv.tvstreamer

/**
 * Which episode the details page should play, and the Netflix-style "Play S1:E3" label.
 * Plain functions (unit-tested).
 */
object ResumeEpisode {
    /**
     * Index in a season-then-episode sorted list.
     * Last episode still in progress (or finished but still in Continue watching) wins;
     * a finished episode advances to the next; otherwise the first Continue watching
     * episode; otherwise 0 (Play S1:E1). Empty list: -1.
     */
    fun index(episodes: List<Item>, last: Pair<String, Boolean>?, continueKeys: Set<String> = emptySet()): Int {
        if (episodes.isEmpty()) return -1
        if (last != null) {
            val i = episodes.indexOfFirst { it.key == last.first }
            if (i >= 0) {
                val finished = last.second && episodes[i].key !in continueKeys
                if (finished && i + 1 < episodes.size) return i + 1
                return i
            }
        }
        val c = episodes.indexOfFirst { it.key in continueKeys }
        return if (c >= 0) c else 0
    }

    /** "S1:E3" from portal season / episode numbers in the name. */
    fun tag(ep: Item): String {
        val s = EpisodeOrder.seasonNo(ep)
        val e = EpisodeOrder.episodeNo(ep)
        return when {
            s > 0 && e > 0 -> "S$s:E$e"
            e > 0 -> "E$e"
            else -> ""
        }
    }

    /** Primary button label: "Play S1:E3", or "Play" when the episode cannot be numbered. */
    fun playLabel(episodes: List<Item>, index: Int): String {
        val tag = episodes.getOrNull(index)?.let { tag(it) }.orEmpty()
        return if (tag.isNotEmpty()) "Play $tag" else "Play"
    }

    fun movieLabel(canResume: Boolean): String = if (canResume) "Resume" else "Play"
}
