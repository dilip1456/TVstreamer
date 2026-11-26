package tv.tvstreamer

/**
 * Catalog Selector (1.92): pure helpers behind the three-step picker — languages, then Movies / Series / Live
 * categories, then a summary of only what is shown. Storage is unchanged: languages go to the per-tab language
 * choices (store.langs / liveLangs), categories to the mode-aware group visibility ([CatalogGroups]).
 */
object CatalogSelector {
    const val MOVIES = "Movies"
    const val SERIES = "Series"
    const val LIVE = "Live TV"
    val SECTIONS = listOf(MOVIES, SERIES, LIVE)

    /** One language row: channel / title counts per column. */
    data class Lang(val name: String, val movies: Int, val series: Int, val live: Int) {
        val total: Int get() = movies + series + live
    }

    /** Merge the three per-tab language lists into one, most content first, "Other" last. */
    fun mergeLangs(movies: List<Pair<String, Int>>, series: List<Pair<String, Int>>, live: List<Pair<String, Int>>): List<Lang> {
        val m = movies.toMap(); val s = series.toMap(); val l = live.toMap()
        val names = LinkedHashSet<String>().apply { addAll(m.keys); addAll(s.keys); addAll(l.keys) }
        return names.map { Lang(it, m[it] ?: 0, s[it] ?: 0, l[it] ?: 0) }
            .sortedWith(compareBy<Lang> { it.name == Tags.OTHER }.thenByDescending { it.total }.thenBy { it.name })
    }

    /** What step 1 starts with: every language picked on any tab (nothing picked anywhere = no filter). */
    fun initial(movies: Set<String>?, series: Set<String>?, live: Set<String>?): Set<String> =
        (movies ?: emptySet()) + (series ?: emptySet()) + (live ?: emptySet())

    /** Stored form for a tab: null = every language. */
    fun stored(sel: Set<String>): Set<String>? = sel.takeIf { it.isNotEmpty() }

    /** A category passes the language step when no language is picked or it has a picked language. */
    fun langOk(catLangs: Set<String>, sel: Set<String>): Boolean = sel.isEmpty() || catLangs.any { it in sel }

    /** Movie and series groups for the picked languages (portal order kept). */
    fun vodGroups(cats: List<Cat>, isSeries: (Cat) -> Boolean, langsOf: (Cat) -> Set<String>, sel: Set<String>,
                  count: (Cat) -> Int): Pair<List<CatalogGroups.Group>, List<CatalogGroups.Group>> {
        val picked = cats.filter { langOk(langsOf(it), sel) }
        val movies = picked.filter { !isSeries(it) }.map { CatalogGroups.Group("vod:${it.id}", GroupTitle.display(it.title, MOVIES), MOVIES, count(it)) }
        val series = picked.filter { isSeries(it) }.map { CatalogGroups.Group("vod:${it.id}", GroupTitle.display(it.title, SERIES), SERIES, count(it)) }
        return movies to series
    }

    /** "3 of 10" style tally for a column. */
    fun tally(groups: List<CatalogGroups.Group>, on: (String) -> Boolean): Pair<Int, Int> = groups.count { on(it.key) } to groups.size

    /** Step 3: only the groups that are shown, with Live sub-groups dropped when their parent is hidden. */
    fun shownOnly(groups: List<CatalogGroups.Group>, on: (String) -> Boolean): List<CatalogGroups.Group> {
        val byKey = groups.associateBy { it.key }
        fun chainOn(g: CatalogGroups.Group): Boolean {
            var cur: CatalogGroups.Group? = g
            while (cur != null) {
                if (!on(cur.key)) return false
                cur = cur.parent?.let { byKey[it] }
            }
            return true
        }
        return groups.filter(::chainOn)
    }

    /** Short language summary for headers. */
    fun langLabel(sel: Set<String>): String = when {
        sel.isEmpty() -> "All languages"
        sel.size <= 3 -> sel.sorted().joinToString(", ")
        else -> "${sel.size} languages"
    }
}
