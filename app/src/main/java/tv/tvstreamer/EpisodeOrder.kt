package tv.tvstreamer

/**
 * Episode ordering used everywhere (details page, "See all episodes", next episode, synthetic shows):
 * seasons ascending, episodes ascending 1..N. Unknown numbers go last and keep the portal's order.
 */
object EpisodeOrder {
    private val DIGITS = Regex("\\d+")

    /** Season number: from the season label ("Season 2", "S02", "Show Season 3") or the name ("S02E05"). 0 = unknown. */
    fun seasonNo(e: Item): Int =
        Names.seasonNumber(e.season).takeIf { it > 0 }
            ?: DIGITS.find(e.season)?.value?.toIntOrNull()?.takeIf { it in 1..999 }
            ?: Tags.episodeOf(e.name).first

    /** Episode number: portal series=N, else from the name ("Episode 5", "S02E05"), else the first number. 0 = unknown. */
    fun episodeNo(e: Item): Int =
        e.series.takeIf { it > 0 } ?: Tags.episodeOf(e.name).second.takeIf { it > 0 }
            ?: DIGITS.find(e.name)?.value?.toIntOrNull() ?: 0

    /** Sorted copy: season ascending, then episode ascending; ties and unknowns keep their original order. */
    fun sort(eps: List<Item>): List<Item> {
        fun last(n: Int) = if (n > 0) n else Int.MAX_VALUE
        return eps.withIndex().sortedWith(
            compareBy<IndexedValue<Item>>({ last(seasonNo(it.value)) }, { last(episodeNo(it.value)) }, { it.index })
        ).map { it.value }
    }

    /** Season labels in ascending season order (unknown labels last, original order). */
    fun seasons(eps: List<Item>): List<String> = sort(eps).map { it.season }.distinct()
}
