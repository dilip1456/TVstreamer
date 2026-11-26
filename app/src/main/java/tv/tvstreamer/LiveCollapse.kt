package tv.tvstreamer

/**
 * Collapsible Live TV sections. OK on a type header (Sports, News, Kids, …) folds every sub-category and its
 * channels away; OK on a sub-category header folds just that group. Expand all / Collapse all cover every type.
 * The collapsed keys are saved, so the list looks the same next time.
 */
object LiveCollapse {
    /** Parent-type key, distinct from a section key such as "Sports/Cricket". */
    fun typeKey(type: String) = "$type/*"

    fun isTypeCollapsed(type: String, collapsed: Set<String>) = typeKey(type) in collapsed

    /**
     * Whether a type shows a header for each of its [sections]. A lone section whose label is the type name
     * (e.g. a single "Religious" block) uses only the type header.
     */
    fun showSubs(sections: List<LiveModes.Section>): Boolean =
        sections.size > 1 || sections.firstOrNull()?.let { !it.label.equals(it.type, ignoreCase = true) } == true

    fun toggle(set: Set<String>, keys: Collection<String>): Set<String> =
        if (keys.isEmpty()) set else if (keys.all { it in set }) set - keys.toSet() else set + keys

    fun encode(set: Set<String>): String = set.sorted().joinToString("\n")
    fun decode(s: String?): Set<String> = s.orEmpty().split('\n').filter { it.isNotBlank() }.toSet()

    fun collapseAll(types: Collection<String>): Set<String> = types.map { typeKey(it) }.toSet()
    fun expandAll(): Set<String> = emptySet()

    /** List index of each section header given which sections are collapsed (a collapsed section is just its header). */
    fun starts(sections: List<Pair<String, Int>>, collapsed: Set<String>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>(); var i = 0
        for ((k, n) in sections) { out[k] = i; i += 1 + if (k in collapsed) 0 else n }
        return out
    }

    /**
     * Positions in the Live TV list when each type has a parent header, and collapsing that type hides every
     * sub-category header and their channels (Sports, News, Kids, and the rest use the same rule).
     */
    data class Layout(
        val typeStart: Map<String, Int>,
        val sectionStart: Map<String, Int>,
        val size: Int,
        val visibleHeaders: List<String>
    )

    fun layout(sections: List<LiveModes.Section>, collapsed: Set<String>): Layout {
        var i = 0
        val typeStart = LinkedHashMap<String, Int>()
        val sectionStart = LinkedHashMap<String, Int>()
        val headers = ArrayList<String>()
        for ((type, group) in sections.groupBy { it.type }) {
            val tk = typeKey(type)
            typeStart[type] = i
            headers += tk
            i += 1
            val subs = showSubs(group)
            if (tk in collapsed) {
                for (s in group) sectionStart[s.key] = typeStart.getValue(type)
                continue
            }
            if (!subs) {
                val s = group.first()
                sectionStart[s.key] = typeStart.getValue(type)
                i += s.rows.size
                continue
            }
            for (s in group) {
                sectionStart[s.key] = i
                headers += s.key
                i += 1 + if (s.key in collapsed) 0 else s.rows.size
            }
        }
        return Layout(typeStart, sectionStart, i, headers)
    }

    /** Section to jump to with Channel / Page up-down: the next (or previous) one from [current]. */
    fun step(keys: List<String>, current: String, down: Boolean): String? {
        if (keys.isEmpty()) return null
        val i = keys.indexOf(current)
        val j = if (i < 0) (if (down) 0 else keys.lastIndex) else if (down) i + 1 else i - 1
        return keys.getOrNull(j.coerceIn(0, keys.lastIndex))
    }

    /**
     * 1.99.2: the header a channel row folds under. Inside a type that lists sub-categories it is the
     * sub-category; a type with one plain block uses the type header.
     */
    fun headerOf(sections: List<LiveModes.Section>, sectionKey: String): String? {
        val s = sections.firstOrNull { it.key == sectionKey } ?: return null
        val group = sections.filter { it.type == s.type }
        return if (showSubs(group)) s.key else typeKey(s.type)
    }

    /** Fold the group that [sectionKey] is in (never unfolds). */
    fun foldGroup(sections: List<LiveModes.Section>, collapsed: Set<String>, sectionKey: String): Set<String> =
        headerOf(sections, sectionKey)?.let { collapsed + it } ?: collapsed

    /** List index of a header key (a type key such as "Sports/" + "*", or a section key) in [layout]. */
    fun headerIndex(layout: Layout, key: String): Int? =
        if (key.endsWith("/*")) layout.typeStart[key.removeSuffix("/*")] else layout.sectionStart[key]

    /**
     * Channel / Page up-down and Rewind / Fast forward. From a channel, Up first lands on the header of the group
     * it is in (so a long open group can be folded at once); from a header it steps to the previous / next one.
     */
    fun jump(headers: List<String>, current: String, onHeader: Boolean, down: Boolean): String? {
        if (!onHeader && !down && current in headers) return current
        return step(headers, current, down)
    }
}
