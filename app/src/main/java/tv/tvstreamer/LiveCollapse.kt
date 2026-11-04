package tv.tvstreamer

/**
 * Collapsible Live TV sections (1.56). OK on a section header, or on a sub-category in the left tree, folds its
 * channels away or shows them again. The collapsed section keys are saved, so the list looks the same next time.
 */
object LiveCollapse {
    fun toggle(set: Set<String>, keys: Collection<String>): Set<String> =
        if (keys.isEmpty()) set else if (keys.all { it in set }) set - keys.toSet() else set + keys

    fun encode(set: Set<String>): String = set.sorted().joinToString("\n")
    fun decode(s: String?): Set<String> = s.orEmpty().split('\n').filter { it.isNotBlank() }.toSet()

    /** List index of each section header given which sections are collapsed (a collapsed section is just its header). */
    fun starts(sections: List<Pair<String, Int>>, collapsed: Set<String>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>(); var i = 0
        for ((k, n) in sections) { out[k] = i; i += 1 + if (k in collapsed) 0 else n }
        return out
    }

    /** Section to jump to with Channel / Page up-down: the next (or previous) one from [current]. */
    fun step(keys: List<String>, current: String, down: Boolean): String? {
        if (keys.isEmpty()) return null
        val i = keys.indexOf(current)
        val j = if (i < 0) (if (down) 0 else keys.lastIndex) else if (down) i + 1 else i - 1
        return keys.getOrNull(j.coerceIn(0, keys.lastIndex))
    }
}
