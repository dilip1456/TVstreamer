package tv.tvstreamer

/** Which version (quality / language / dub copy) of a title the details page selects, and how the chips read. */
object VersionPick {
    /**
     * Default: the version played last for this title, else one with progress in Continue watching, else the best
     * ([versions] come best first).
     */
    fun default(versions: List<Item>, lastPlayed: String?, inProgress: Set<String>): Item? =
        versions.find { it.key == lastPlayed } ?: versions.find { it.key in inProgress } ?: versions.firstOrNull()

    /** Chip labels: two versions that read the same get " · 2", " · 3" so they can be told apart. */
    fun labels(raw: List<String>): List<String> {
        val seen = mutableMapOf<String, Int>()
        val total = raw.groupingBy { it }.eachCount()
        return raw.map { l -> if ((total[l] ?: 0) < 2) l else { val n = (seen[l] ?: 0) + 1; seen[l] = n; "$l · $n" } }
    }

    /** Where the chosen version is remembered (per title group; synced with the other prefs). */
    fun prefKey(versions: List<Item>, fallback: Item): String = "ver_" + (versions.minByOrNull { it.key }?.key ?: fallback.key)
}
